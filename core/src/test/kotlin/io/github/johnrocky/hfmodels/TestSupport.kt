package io.github.johnrocky.hfmodels

import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest

class RecordingLog : HfLog {
    val lines = mutableListOf<String>()
    override fun i(msg: String) { lines += "I $msg" }
    override fun w(msg: String, t: Throwable?) { lines += "W $msg" }
    override fun e(msg: String, t: Throwable?) { lines += "E $msg" }
}

fun sha256Hex(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
fun sha256Hex(s: String): String = sha256Hex(s.toByteArray(Charsets.UTF_8))

/**
 * Local HTTP/1.1 fixture on a ServerSocket (Android unit tests compile against android.jar, so the
 * JDK's com.sun httpserver is not available) that speaks Range and plays a tiny Hugging Face Hub:
 * `/api/models/<repo>/revision/<rev>`, `/api/models/<repo>`, `/<repo>/resolve/<commit>/<path>`.
 * Knobs reproduce the failure shapes the store must survive: a cut connection (resume), a server
 * that ignores Range (restart), a redirect (Range must survive the hop, Authorization must not),
 * wrong bytes (checksum rollback), 401 / 403 / 404, 503 with Retry-After.
 */
class FakeHub {
    class Repo(val id: String, val commit: String, val files: MutableMap<String, ByteArray> = LinkedHashMap(), var gated: Boolean = false, var status: Int = 200, val branches: MutableMap<String, String> = mutableMapOf())
    val repos = LinkedHashMap<String, Repo>()
    @Volatile var truncateAfter: Int? = null
    @Volatile var ignoreRange = false
    @Volatile var redirectFilesTo: String? = null      // e.g. "/cdn" : file requests 302 to a different host-path
    @Volatile var failNextWith: Int? = null
    @Volatile var retryAfter: Long? = null
    @Volatile var serveWrongBytesFor: String? = null
    /** Sleep this long per 16 KiB written, so a cancel can land mid-transfer on loopback. */
    @Volatile var delayPerChunkMs: Long = 0
    val requests = mutableListOf<Request>()
    data class Request(val path: String, val range: String?, val auth: String?)

    private val socket = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
    val endpoint: String get() = "http://127.0.0.1:${socket.localPort}"

    fun repo(id: String, commit: String = "a".repeat(40), block: Repo.() -> Unit = {}): Repo =
        Repo(id, commit).apply { branches["main"] = commit; block() }.also { repos[id] = it }

    private val thread = Thread {
        while (!socket.isClosed) {
            val client = try { socket.accept() } catch (_: IOException) { break }
            Thread { client.use { handle(it) } }.start()
        }
    }.apply { isDaemon = true; start() }

    private fun handle(client: Socket) {
        val input = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
        val out = BufferedOutputStream(client.getOutputStream())
        val requestLine = input.readLine() ?: return
        val rawPath = requestLine.split(" ").getOrNull(1) ?: "/"
        val path = java.net.URLDecoder.decode(rawPath, "UTF-8")
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val range = headers["range"]
        synchronized(requests) { requests.add(Request(path, range, headers["authorization"])) }

        fun respond(status: Int, extra: List<String>, body: ByteArray, writeUpTo: Int = body.size) {
            val reason = mapOf(200 to "OK", 206 to "Partial Content", 302 to "Found", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found", 416 to "Range Not Satisfiable", 429 to "Too Many", 503 to "Unavailable")[status] ?: "X"
            val head = buildString {
                append("HTTP/1.1 $status $reason\r\n")
                extra.forEach { append(it).append("\r\n") }
                append("Content-Length: ${body.size}\r\nConnection: close\r\n\r\n")
            }
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            if (delayPerChunkMs > 0) {
                var off = 0
                while (off < writeUpTo) {
                    val n = minOf(16 * 1024, writeUpTo - off)
                    out.write(body, off, n); out.flush(); off += n
                    try { Thread.sleep(delayPerChunkMs) } catch (_: InterruptedException) { return }
                }
            } else out.write(body, 0, writeUpTo)
            out.flush()
        }

        failNextWith?.let { s -> failNextWith = null; respond(s, retryAfter?.let { listOf("Retry-After: $it") } ?: emptyList(), ByteArray(0)); return }

        // --- Hub API ---
        Regex("^/api/models/([^/]+/[^/]+)(?:/revision/([^/]+))?$").find(path)?.let { m ->
            val repo = repos[m.groupValues[1]]
            val rev = m.groupValues[2].ifEmpty { "main" }
            if (repo == null) { respond(404, emptyList(), "{}".toByteArray()); return }
            if (repo.status != 200) { respond(repo.status, emptyList(), "{}".toByteArray()); return }
            if (repo.gated && headers["authorization"] == null) { respond(401, emptyList(), "{}".toByteArray()); return }
            val commit = if (rev.length == 40) rev.takeIf { it == repo.commit } else repo.branches[rev]
            if (commit == null) { respond(404, emptyList(), "{\"error\":\"Invalid rev id\"}".toByteArray()); return }
            val siblings = repo.files.entries.joinToString(",") { (n, b) -> "{\"rfilename\":\"$n\",\"size\":${b.size},\"lfs\":{\"sha256\":\"${sha256Hex(b)}\",\"size\":${b.size}}}" }
            val json = "{\"id\":\"${repo.id}\",\"sha\":\"$commit\",\"gated\":${repo.gated},\"private\":false,\"cardData\":{\"license\":\"apache-2.0\"},\"siblings\":[$siblings]}"
            respond(200, listOf("Content-Type: application/json"), json.toByteArray()); return
        }
        // --- files ---
        val m = Regex("^(?:/cdn)?/([^/]+/[^/]+)/resolve/([^/]+)/(.+)$").find(path)
        if (m == null) { respond(404, emptyList(), ByteArray(0)); return }
        val repo = repos[m.groupValues[1]]
        val commit = m.groupValues[2]; val file = m.groupValues[3]
        if (repo == null || repo.status != 200) { respond(repo?.status ?: 404, emptyList(), ByteArray(0)); return }
        if (repo.gated && headers["authorization"] == null && !path.startsWith("/cdn")) { respond(401, emptyList(), ByteArray(0)); return }
        if (commit != repo.commit) { respond(404, emptyList(), ByteArray(0)); return }
        val data0 = repo.files[file]
        if (data0 == null) { respond(404, emptyList(), ByteArray(0)); return }
        val cdn = redirectFilesTo
        if (cdn != null && !path.startsWith("/cdn")) { respond(302, listOf("Location: $cdn$rawPath"), ByteArray(0)); return }
        val data = if (serveWrongBytesFor == file) ByteArray(data0.size) else data0
        var body = data
        var status = 200
        val extra = mutableListOf<String>()
        if (range != null && !ignoreRange) {
            val start = range.removePrefix("bytes=").substringBefore("-").toInt()
            if (start >= data.size) { respond(416, listOf("Content-Range: bytes */${data.size}"), ByteArray(0)); return }
            body = data.copyOfRange(start, data.size)
            extra.add("Content-Range: bytes $start-${data.size - 1}/${data.size}")
            status = 206
        }
        val cut = truncateAfter
        if (cut != null && cut < body.size) {
            truncateAfter = null
            respond(status, extra, body, writeUpTo = cut) // declared length > written: the client sees a cut connection
            return
        }
        respond(status, extra, body)
    }

    fun stop() { socket.close() }
}
