package io.github.johnrocky.hfmodels.litert

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.johnrocky.hfmodels.HfLog
import io.github.johnrocky.hfmodels.store.ArtifactRef
import io.github.johnrocky.hfmodels.store.ModelStore
import java.io.File
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device probe for a large download through the SDK's own transfer path ([ModelStore.ensure] over
 * [io.github.johnrocky.hfmodels.store.HttpUrlConnectionSource]) with the diagnostics the shipped
 * retry loop does not print: the cause chain of every interruption, the download thread's stack
 * whenever no byte arrives for `stall` seconds, the resolved addresses and the active network.
 *
 * Arguments (instrumentation): url, sha256, size (bytes), file (name), attempts (default 3),
 * stall (seconds without progress before a stack dump, default 20), budget (seconds, default 360).
 * One RESULT line per attempt and one final RESULT line under tag `hfmodels-dlprobe`.
 */
@RunWith(AndroidJUnit4::class)
class DownloadProbeTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val url = args.getString("url") ?: error("-e url <https://...> is required")
    private val sha = args.getString("sha256") ?: error("-e sha256 <hex> is required")
    private val size = args.getString("size")?.toLong() ?: error("-e size <bytes> is required")
    private val fileName = args.getString("file") ?: url.substringBefore('?').substringAfterLast('/')
    private val attempts = args.getString("attempts")?.toInt() ?: 3
    private val stallSeconds = args.getString("stall")?.toLong() ?: 20
    private val budgetSeconds = args.getString("budget")?.toLong() ?: 360

    private val log = object : HfLog {
        override fun i(msg: String) { Log.i(TAG, msg) }
        override fun w(msg: String, t: Throwable?) { Log.w(TAG, msg + chain(t)) }
        override fun e(msg: String, t: Throwable?) { Log.e(TAG, msg + chain(t)) }
    }

    @Test
    fun download() {
        val root = File(ctx.cacheDir, "dlprobe").apply { mkdirs() }
        val ref = ArtifactRef(file = fileName, sourceUrl = url, sha256 = sha, sizeBytes = size)
        val store = ModelStore(root)
        // A fresh transfer every run: the point is the first megabytes and the resume, not the cache.
        File(root, "blobs").deleteRecursively()
        logNetwork()
        val t0 = System.nanoTime()
        val bytes = AtomicLong(0)
        val lastProgressNs = AtomicLong(t0)
        val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
        var outcome = "unknown"
        val worker = thread(name = "dlprobe-download") {
            var attempt = 0
            while (true) {
                attempt++
                val a0 = System.nanoTime()
                try {
                    val r = store.ensure(ref, log, onProgress = { done, _ ->
                        bytes.set(done); lastProgressNs.set(System.nanoTime())
                    }, cancel = { cancel.get() })
                    val ms = (System.nanoTime() - a0) / 1_000_000
                    result("attempt", true, "n=$attempt status=${r.status} bytes=${r.bytesDownloaded} resumedFrom=${r.resumedFrom} ms=$ms")
                    outcome = "done"
                    return@thread
                } catch (e: ModelStore.TransferInterrupted) {
                    val ms = (System.nanoTime() - a0) / 1_000_000
                    result("attempt", false, "n=$attempt bytesSoFar=${e.bytesSoFar} http=${e.httpStatus} ms=$ms cause=${chain(e.cause).trim()}")
                    Log.w(TAG, "attempt $attempt stack", e)
                    if (cancel.get()) { outcome = "cancelled after $attempt attempts"; return@thread }
                    if (attempt >= attempts) { outcome = "gave up after $attempt attempts at ${e.bytesSoFar} bytes"; return@thread }
                    Thread.sleep(1000L shl (attempt - 1))
                } catch (e: Throwable) {
                    val ms = (System.nanoTime() - a0) / 1_000_000
                    result("attempt", false, "n=$attempt ms=$ms fatal=${e.javaClass.name}: ${e.message}")
                    Log.e(TAG, "attempt $attempt fatal", e)
                    outcome = "fatal ${e.javaClass.name}"
                    return@thread
                }
            }
        }
        // Watchdog: a stack dump of the download thread every `stall` seconds without a new byte.
        var lastDumpAt = t0
        while (worker.isAlive) {
            worker.join(1000)
            val now = System.nanoTime()
            val sinceProgressMs = (now - lastProgressNs.get()) / 1_000_000
            if (sinceProgressMs >= stallSeconds * 1000 && (now - lastDumpAt) / 1_000_000 >= stallSeconds * 1000) {
                lastDumpAt = now
                val st = worker.stackTrace.joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                Log.w(TAG, "STALL bytes=${bytes.get()} noProgressMs=$sinceProgressMs state=${worker.state} at=$st")
            }
            if ((now - t0) / 1_000_000_000 >= budgetSeconds) {
                cancel.set(true)
                worker.interrupt()
                worker.join(15_000)
                if (outcome == "unknown") outcome = "budget exhausted (${budgetSeconds}s) at ${bytes.get()} bytes"
                break
            }
        }
        val totalMs = (System.nanoTime() - t0) / 1_000_000
        val ok = outcome == "done"
        result("final", ok, "outcome='$outcome' bytes=${bytes.get()} of $size ms=$totalMs url=$url")
        File(root, "blobs").deleteRecursively()
        assertTrue(outcome, ok)
    }

    private fun logNetwork() {
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork
            val caps = net?.let { cm.getNetworkCapabilities(it) }
            val transports = listOf(
                NetworkCapabilities.TRANSPORT_WIFI to "wifi", NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
                NetworkCapabilities.TRANSPORT_VPN to "vpn", NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",
            ).filter { caps?.hasTransport(it.first) == true }.joinToString("+") { it.second }
            val lp = net?.let { cm.getLinkProperties(it) }
            Log.i(TAG, "NETWORK active=$net transports=$transports validated=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)} " +
                "iface=${lp?.interfaceName} dns=${lp?.dnsServers} privateDns=${lp?.isPrivateDnsActive} mtu=${lp?.mtu}")
        } catch (e: Throwable) {
            Log.w(TAG, "NETWORK unavailable: ${e.javaClass.name}: ${e.message}")
        }
        for (host in listOf(URL(url).host)) {
            try {
                Log.i(TAG, "DNS $host -> ${InetAddress.getAllByName(host).joinToString(",") { it.hostAddress ?: "?" }}")
            } catch (e: Throwable) {
                Log.w(TAG, "DNS $host failed: ${e.javaClass.name}: ${e.message}")
            }
        }
    }

    private fun result(step: String, ok: Boolean, detail: String) {
        Log.i(TAG, "RESULT step=$step ok=$ok file=$fileName $detail")
    }

    private fun chain(t: Throwable?): String {
        if (t == null) return ""
        val sb = StringBuilder(" | cause:")
        var c: Throwable? = t
        var depth = 0
        while (c != null && depth < 6) {
            sb.append(' ').append(c.javaClass.name).append(": ").append(c.message)
            c = c.cause?.takeIf { it !== c }
            depth++
            if (c != null) sb.append(" <-")
        }
        return sb.toString()
    }

    private companion object {
        const val TAG = "hfmodels-dlprobe"
    }
}
