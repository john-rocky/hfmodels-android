package io.github.johnrocky.hfmodels.store

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * One HTTP GET with an optional `Range` header and an optional bearer token; the seam the JVM
 * tests use to observe requests. The default implementation follows redirects by hand so the
 * Range header survives the Hugging Face -> CDN hop (HttpURLConnection may drop request headers
 * across hosts) and so the Authorization header is NOT forwarded off the original host.
 */
interface HttpSource {
    class Response(
        val status: Int,
        val contentLength: Long?,
        val contentRange: String?,
        val retryAfterSeconds: Long?,
        val body: InputStream,
        val close: () -> Unit,
    )

    @Throws(IOException::class)
    fun get(url: String, rangeStart: Long?, token: String?, timeoutMs: Int): Response
}

class HttpUrlConnectionSource(private val userAgent: String = "hfmodels-android/0.1") : HttpSource {
    override fun get(url: String, rangeStart: Long?, token: String?, timeoutMs: Int): HttpSource.Response {
        var current = url
        val originHost = URL(url).host
        repeat(MAX_REDIRECTS + 1) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", userAgent)
            // The token goes to the host the caller named and nowhere else (a CDN redirect drops it).
            if (token != null && URL(current).host == originHost) c.setRequestProperty("Authorization", "Bearer $token")
            if (rangeStart != null && rangeStart > 0) c.setRequestProperty("Range", "bytes=$rangeStart-")
            val status = c.responseCode
            if (status in REDIRECTS) {
                val loc = c.getHeaderField("Location") ?: throw IOException("HTTP $status without Location from $current")
                c.disconnect()
                current = URL(URL(current), loc).toString()
                return@repeat
            }
            val len = c.getHeaderField("Content-Length")?.toLongOrNull()
            val retry = c.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
            val stream = if (status >= 400) (c.errorStream ?: InputStream.nullInputStream()) else c.inputStream
            return HttpSource.Response(status, len, c.getHeaderField("Content-Range"), retry, stream) { c.disconnect() }
        }
        throw IOException("too many redirects from $url")
    }

    private companion object {
        const val MAX_REDIRECTS = 5
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}
