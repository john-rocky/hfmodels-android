package io.github.johnrocky.hfmodels.hub

import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.store.HttpSource
import java.io.IOException
import java.net.URLEncoder
import org.json.JSONException
import org.json.JSONObject

/** What the Hub API says about one repo at one revision. */
data class RepoInfo(
    val repoId: String,
    /** The immutable commit the revision resolved to. */
    val sha: String,
    val gated: Boolean,
    val private: Boolean,
    val licenseId: String?,
    /** rfilename -> (sha256, size) for LFS files; non-LFS files have null sha256 and their blob size. */
    val siblings: Map<String, Sibling>,
)

data class Sibling(val path: String, val sha256: String?, val size: Long?)

/**
 * The Hugging Face Hub, reduced to what the resolver needs: resolve a revision to a commit
 * (`GET /api/models/{repo}/revision/{rev}`), fetch one small file at a commit
 * (`resolve/{commit}/{path}`), and build pinned file URLs. Every request carries the caller's
 * token to huggingface.co only (see [HttpSource]). Nothing here downloads weights.
 */
class HfHub(
    private val http: HttpSource,
    val endpoint: String = "https://huggingface.co",
    private val timeoutMs: Int = 20_000,
) {
    fun fileUrl(repoId: String, commit: String, path: String): String =
        "$endpoint/$repoId/resolve/$commit/" + path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    fun modelPageUrl(repoId: String): String = "$endpoint/$repoId"

    @Throws(ModelException::class)
    fun repoInfo(repoId: String, revision: String?, token: String?): RepoInfo {
        val rev = revision ?: "main"
        val url = "$endpoint/api/models/$repoId/revision/" + URLEncoder.encode(rev, "UTF-8").replace("+", "%20")
        val body = getText(url, token, what = "repo $repoId@$rev") { status ->
            when (status) {
                401 -> ModelException(ErrorCode.AUTH_REQUIRED, "$repoId needs a Hugging Face token (HTTP 401)", details = mapOf("repo" to repoId, "url" to modelPageUrl(repoId)))
                403 -> ModelException(ErrorCode.ACCESS_DENIED, "$repoId is gated or access is denied (HTTP 403); accept the terms on the model page", details = mapOf("repo" to repoId, "url" to modelPageUrl(repoId)))
                404 -> if (revision != null && repoExists(repoId, token)) {
                    ModelException(ErrorCode.REVISION_NOT_FOUND, "$repoId has no revision '$revision'", details = mapOf("repo" to repoId, "revision" to revision))
                } else {
                    ModelException(ErrorCode.MODEL_NOT_FOUND_OR_INACCESSIBLE, "$repoId is not available from the Hub as $rev (HTTP 404: missing, private, or not visible to this token)", details = mapOf("repo" to repoId, "url" to modelPageUrl(repoId)))
                }
                else -> null
            }
        }
        val o = try { JSONObject(body) } catch (e: JSONException) { throw ModelException(ErrorCode.NETWORK_ERROR, "Hub API returned a non-JSON body for $repoId", retryable = true, cause = e) }
        val sha = o.optString("sha", "")
        if (sha.length != 40) throw ModelException(ErrorCode.NETWORK_ERROR, "Hub API returned no commit sha for $repoId@$rev", retryable = true)
        val siblings = LinkedHashMap<String, Sibling>()
        o.optJSONArray("siblings")?.let { a ->
            for (i in 0 until a.length()) {
                val s = a.optJSONObject(i) ?: continue
                val name = s.optString("rfilename", "")
                if (name.isEmpty()) continue
                val lfs = s.optJSONObject("lfs")
                val sha256 = lfs?.optString("sha256")?.takeIf { it.length == 64 }
                val size = when {
                    lfs != null && lfs.has("size") -> lfs.optLong("size")
                    s.has("size") -> s.optLong("size")
                    else -> null
                }
                siblings[name] = Sibling(name, sha256, size)
            }
        }
        val license = o.optJSONObject("cardData")?.optString("license")?.takeIf { it.isNotEmpty() }
        return RepoInfo(repoId, sha, o.optBoolean("gated", false) || o.optString("gated") in setOf("auto", "manual"), o.optBoolean("private", false), license, siblings)
    }

    /** True when `GET /api/models/{repo}` succeeds (used only to tell a missing revision from a missing repo). */
    private fun repoExists(repoId: String, token: String?): Boolean = try {
        val r = http.get("$endpoint/api/models/$repoId", null, token, timeoutMs)
        try { r.status == 200 } finally { r.close() }
    } catch (_: IOException) { false }

    /**
     * The text of a small file at a commit, or null when the repo answers 404 for that path.
     * 401 / 403 are raised, never turned into "absent" (spec §6.3).
     */
    @Throws(ModelException::class)
    fun smallFile(repoId: String, commit: String, path: String, token: String?, maxBytes: Int = 1 shl 20): String? {
        val url = fileUrl(repoId, commit, path)
        var absent = false
        val text = getText(url, token, what = "$repoId@${commit.take(8)}/$path", maxBytes = maxBytes) { status ->
            when (status) {
                401 -> ModelException(ErrorCode.AUTH_REQUIRED, "$repoId needs a Hugging Face token (HTTP 401)", details = mapOf("repo" to repoId, "url" to modelPageUrl(repoId)))
                403 -> ModelException(ErrorCode.ACCESS_DENIED, "$repoId is gated or access is denied (HTTP 403)", details = mapOf("repo" to repoId, "url" to modelPageUrl(repoId)))
                404 -> { absent = true; null }
                else -> null
            }
        }
        return if (absent) null else text
    }

    /** GET a small text body. [onStatus] maps a non-200 status to a typed error, or returns null to let a 404 read as "absent" (empty string). */
    private fun getText(url: String, token: String?, what: String, maxBytes: Int = 1 shl 20, onStatus: (Int) -> ModelException?): String {
        val r = try { http.get(url, null, token, timeoutMs) } catch (e: IOException) {
            throw ModelException(ErrorCode.NETWORK_ERROR, "cannot reach the Hub for $what: ${e.javaClass.simpleName}: ${e.message}", retryable = true, cause = e)
        }
        try {
            if (r.status != 200) {
                onStatus(r.status)?.let { throw it }
                if (r.status == 404) return ""
                throw ModelException(ErrorCode.NETWORK_ERROR, "HTTP ${r.status} for $what", retryable = r.status == 429 || r.status >= 500, details = mapOf("status" to r.status.toString()))
            }
            val bytes = r.body.readNBytes(maxBytes + 1)
            if (bytes.size > maxBytes) throw ModelException(ErrorCode.MANIFEST_INVALID, "$what is larger than $maxBytes bytes")
            return String(bytes, Charsets.UTF_8)
        } finally {
            r.close()
        }
    }
}
