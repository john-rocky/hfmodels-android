package io.github.johnrocky.hfmodels.probe.coexist

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A0: both runtimes in one release + R8 process on one named device, driven from the shell so no
 * test APK shares (and un-shrinks) the app's classpath:
 *
 *   adb shell am start -n io.github.johnrocky.hfmodels.probe.coexist/.ProbeActivity \
 *       --es lmPath /data/local/tmp/hfmodels/qwen25_1_5b_q8.litertlm \
 *       --es yoloxPath /data/local/tmp/hfmodels/yolox_nano.tflite \
 *       --es imagePath /data/local/tmp/hfmodels/sample.png
 *   adb logcat -s hfmodels-a0       # one RESULT line per check, then DONE
 *
 * Each check is independent (its own engine / model, closed before the next). Elapsed times are
 * one-shot wall clock, not a benchmark. Failures are caught and logged as FAIL so later checks run.
 */
@OptIn(ExperimentalApi::class)
class ProbeActivity : Activity() {
    private lateinit var lmPath: String
    private lateinit var yoloxPath: String
    private lateinit var imagePath: String
    private var status: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).also { it.text = "hfmodels coexist probe running; see logcat -s hfmodels-a0"; setContentView(it) }
        lmPath = intent.getStringExtra("lmPath") ?: "/data/local/tmp/hfmodels/qwen25_1_5b_q8.litertlm"
        yoloxPath = intent.getStringExtra("yoloxPath") ?: "/data/local/tmp/hfmodels/yolox_nano.tflite"
        imagePath = intent.getStringExtra("imagePath") ?: "/data/local/tmp/hfmodels/sample.png"
        val only = intent.getStringExtra("only")?.split(',')?.toSet()
        ExperimentalFlags.enableBenchmark = true
        Thread { runAll(only) }.start()
    }

    private fun runAll(only: Set<String>?) {
        Log.i(TAG, "START device=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT} build=${Build.DISPLAY} " +
            "litertlm=${BuildConfig.LITERTLM_VERSION} litert=${BuildConfig.LITERT_VERSION} keepJni=${BuildConfig.KEEP_JNI} keepWork=${BuildConfig.KEEP_WORK} " +
            "nativeLibDir=${applicationInfo.nativeLibraryDir} libs=${File(applicationInfo.nativeLibraryDir).list()?.sorted()} " +
            "files=${listOf(lmPath, yoloxPath, imagePath).map { "${it}:${File(it).length()}" }}")
        val checks = linkedMapOf<String, () -> String>(
            "litertlm_cpu" to { lmTurn(Backend.CPU()) },
            "litert_gpu" to { detect(Accelerator.GPU) },
            "litertlm_gpu" to { lmTurn(Backend.GPU()) },
            "litert_cpu" to { detect(Accelerator.CPU) },
            "both_open_gpu" to { bothOpenOnGpu() },
            "litertlm_stream_cpu" to { streamingCallback() },
        )
        var ok = 0; var fail = 0
        for ((name, body) in checks) {
            if (only != null && name !in only) continue
            val t0 = SystemClock.elapsedRealtime()
            try {
                val v = body()
                ok++
                result(name, "ok=true total_ms=${SystemClock.elapsedRealtime() - t0} $v")
            } catch (t: Throwable) {
                fail++
                Log.e(TAG, "check $name threw", t)
                result(name, "ok=false total_ms=${SystemClock.elapsedRealtime() - t0} error=${q("${t.javaClass.name}: ${t.message}")}")
            }
        }
        Log.i(TAG, "DONE ok=$ok fail=$fail")
        runOnUiThread { status?.text = "DONE ok=$ok fail=$fail"; finish() }
    }

    private fun lmTurn(backend: Backend): String {
        val t0 = SystemClock.elapsedRealtime()
        val engine = Engine(EngineConfig(modelPath = lmPath, backend = backend, cacheDir = cacheDir.path))
        engine.initialize()
        val loadMs = SystemClock.elapsedRealtime() - t0
        val conv = engine.createConversation(ConversationConfig(systemInstruction = Contents.of(SYSTEM)))
        val t1 = SystemClock.elapsedRealtime()
        val reply = text(conv.sendMessage(PROMPT))
        val genMs = SystemClock.elapsedRealtime() - t1
        val b = runCatching { conv.getBenchmarkInfo() }.getOrNull()
        val mapped = mappedLibs()
        conv.close()
        engine.close()
        check(reply.isNotBlank()) { "blank reply" }
        return "load_ms=$loadMs gen_ms=$genMs prefill_tok_s=${b?.lastPrefillTokensPerSecond} decode_tok_s=${b?.lastDecodeTokensPerSecond} " +
            "decode_tokens=${b?.lastDecodeTokenCount} reply=${q(reply)} mapped=$mapped"
    }

    private fun detect(acc: Accelerator): String {
        val t0 = SystemClock.elapsedRealtime()
        val yolox = Yolox(yoloxPath, acc)
        val loadMs = SystemClock.elapsedRealtime() - t0
        val bmp = BitmapFactory.decodeFile(imagePath)
        val t1 = SystemClock.elapsedRealtime()
        val dets = yolox.detect(bmp)
        val runMs = SystemClock.elapsedRealtime() - t1
        val dets2 = yolox.detect(bmp)
        val mapped = mappedLibs()
        yolox.close()
        check(dets.isNotEmpty()) { "no detections" }
        check(dets.size == dets2.size) { "second run differs: ${dets.size} vs ${dets2.size}" }
        val top = dets.take(5).joinToString(";") { "${it.classId}:%.2f".format(it.score) }
        return "load_ms=$loadMs run_ms=$runMs image=${bmp.width}x${bmp.height} detections=${dets.size} top=$top mapped=$mapped"
    }

    /** Both runtimes open at the same time, both on the GPU, used alternately, closed in reverse order. */
    private fun bothOpenOnGpu(): String {
        val engine = Engine(EngineConfig(modelPath = lmPath, backend = Backend.GPU(), cacheDir = cacheDir.path))
        engine.initialize()
        val conv = engine.createConversation(ConversationConfig(systemInstruction = Contents.of(SYSTEM)))
        val yolox = Yolox(yoloxPath, Accelerator.GPU)
        val bmp = BitmapFactory.decodeFile(imagePath)
        val d1 = yolox.detect(bmp)
        val r1 = text(conv.sendMessage(PROMPT))
        val d2 = yolox.detect(bmp)
        val r2 = text(conv.sendMessage("And 17 + 26? Answer briefly."))
        val mapped = mappedLibs()
        yolox.close()
        conv.close()
        engine.close()
        check(r1.isNotBlank() && r2.isNotBlank()) { "blank reply" }
        check(d1.isNotEmpty() && d1.size == d2.size) { "detections ${d1.size}/${d2.size}" }
        return "detections=${d1.size}/${d2.size} reply1=${q(r1)} reply2=${q(r2)} mapped=$mapped"
    }

    /** The streaming callback path (InputData$Text / BenchmarkInfo are JNI FindClass targets). */
    private fun streamingCallback(): String {
        val engine = Engine(EngineConfig(modelPath = lmPath, backend = Backend.CPU(), cacheDir = cacheDir.path))
        engine.initialize()
        val conv = engine.createConversation(ConversationConfig(systemInstruction = Contents.of(SYSTEM)))
        val chunks = StringBuilder(); var n = 0; var err: Throwable? = null
        val done = CountDownLatch(1)
        conv.sendMessageAsync(Message.user(PROMPT), object : MessageCallback {
            override fun onMessage(message: Message) { chunks.append(text(message)); n++ }
            override fun onDone() { done.countDown() }
            override fun onError(throwable: Throwable) { err = throwable; done.countDown() }
        })
        check(done.await(120, TimeUnit.SECONDS)) { "no onDone within 120 s" }
        val b = runCatching { conv.getBenchmarkInfo() }.getOrNull()
        conv.close(); engine.close()
        err?.let { throw it }
        check(n > 1) { "expected >1 chunk, got $n" }
        return "chunks=$n chars=${chunks.length} decode_tok_s=${b?.lastDecodeTokensPerSecond} reply=${q(chunks.toString())}"
    }

    private fun text(m: Message): String = m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    /** Runtime / accelerator .so files mapped into this process right now. */
    private fun mappedLibs(): String =
        File("/proc/self/maps").readLines().mapNotNull { l -> Regex("/[^ ]*\\.so$").find(l)?.value }
            .map { it.substringAfterLast('/') }
            .filter { it.contains("LiteRt", true) || it.contains("litert", true) || it.contains("OpenCL") || it.contains("vndk") }
            .toSortedSet().joinToString(",")

    private fun q(s: String) = "\"" + s.take(160).replace("\n", " ").replace("\"", "'") + "\""

    private fun result(check: String, values: String) {
        Log.i(TAG, "RESULT check=$check device=${Build.MODEL} sdk=${Build.VERSION.SDK_INT} litertlm=${BuildConfig.LITERTLM_VERSION} " +
            "litert=${BuildConfig.LITERT_VERSION} keepJni=${BuildConfig.KEEP_JNI} keepWork=${BuildConfig.KEEP_WORK} $values")
    }

    private companion object {
        const val TAG = "hfmodels-a0"
        const val SYSTEM = "You are a helpful assistant."
        const val PROMPT = "What is 17 + 25? Answer briefly."
    }
}
