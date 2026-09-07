package io.github.johnrocky.hfmodels.store

import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.HfLog
import io.github.johnrocky.hfmodels.ModelException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * What the store needs to fetch one artifact. The values come from the plan (descriptor + the
 * resolved commit); nothing here guesses a URL. [sourceUrl] is a `resolve/<commit>/<path>` URL.
 */
data class ArtifactRef(
    /** The publisher's file name (the last path segment); used inside the blob directory. */
    val file: String,
    val sourceUrl: String,
    /** Lower-case hex sha256 of the whole file. This is the cache key. */
    val sha256: String,
    /** Expected size; null = trust Content-Length. */
    val sizeBytes: Long? = null,
    val repoId: String? = null,
    val commit: String? = null,
    val path: String? = null,
) {
    init {
        require(file.isNotBlank() && !file.contains('/') && !file.contains("..")) { "artifact file must be a bare file name: '$file'" }
        require(sourceUrl.startsWith("https://") || sourceUrl.startsWith("http://127.0.0.1") || sourceUrl.startsWith("http://localhost")) {
            "artifact source_url must be https (loopback http is allowed for tests): $sourceUrl"
        }
        require(sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' }) { "sha256 must be 64 lower-case hex chars" }
    }
}

/** Progress of one download; [totalBytes] is null until the server or the ref says. */
fun interface DownloadProgress {
    fun onProgress(bytesDone: Long, totalBytes: Long?)
}

/**
 * Content-addressed weight store (ported from a verified design: single connection, Range resume
 * from the partial's length, sha256 streamed while downloading, atomic rename, rollback).
 *
 * Layout under [root]: `blobs/<sha256>/<file>` is a verified file; next to it
 * `<file>.hfmodels.json` records sha256 / size / source / when. A download in progress is
 * `<file>.partial` plus `<file>.partial.json` (which ref it belongs to).
 *
 * Rules:
 * - resumable: a partial whose note matches the ref is continued with `Range: bytes=<offset>-`
 *   (expects 206 with a matching Content-Range; a 200 restarts from zero; a 416 restarts from zero);
 * - verified before use: sha256 over the whole file (streamed while downloading, the already-present
 *   prefix rehashed on resume) and size when known. A mismatch deletes the partial and throws
 *   [ErrorCode.CHECKSUM_MISMATCH]; a previously verified file, if any, is untouched;
 * - offline: [ensure] with `offline = true` never opens a connection; a missing or unverified file
 *   is [ErrorCode.OFFLINE_CACHE_MISS], not a download;
 * - 401 / 403 / checksum are never retried here; transient I/O, 5xx and 429 are retried by the caller
 *   (see [io.github.johnrocky.hfmodels.HfModels]) with the partial kept between attempts.
 */
class ModelStore(
    val root: File,
    private val http: HttpSource = HttpUrlConnectionSource(),
    private val timeoutMs: Int = 30_000,
    private val bufferBytes: Int = 1 shl 20,
) {
    data class Result(val file: File, val status: Status, val bytesDownloaded: Long, val resumedFrom: Long) {
        enum class Status { CACHED, DOWNLOADED }
    }

    /** Raised by [ensure] for a transient transfer failure; the partial is on disk for a resume. */
    class TransferInterrupted(val ref: ArtifactRef, val bytesSoFar: Long, val httpStatus: Int?, val retryAfterSeconds: Long?, cause: Throwable?) :
        IOException("transfer of '${ref.file}' interrupted after $bytesSoFar bytes" + (httpStatus?.let { " (HTTP $it)" } ?: ""), cause)

    fun blobDir(ref: ArtifactRef): File = File(File(root, "blobs"), ref.sha256)
    fun fileFor(ref: ArtifactRef): File = File(blobDir(ref), ref.file)
    private fun sidecar(ref: ArtifactRef) = File(blobDir(ref), ref.file + SIDECAR_SUFFIX)
    private fun partial(ref: ArtifactRef) = File(blobDir(ref), ref.file + ".partial")
    private fun partialNote(ref: ArtifactRef) = File(blobDir(ref), ref.file + ".partial.json")

    /** True when the file is present and its sidecar names this ref's sha256 (no rehash). */
    fun isCached(ref: ArtifactRef): Boolean {
        val f = fileFor(ref)
        val sc = sidecar(ref)
        if (!f.isFile || !sc.isFile) return false
        val meta = FlatJson.read(sc.readText())
        if (meta["sha256"] != ref.sha256) return false
        val size = ref.sizeBytes
        return size == null || f.length() == size
    }

    /** Rehash the file on disk; true only when it matches [ref]. */
    fun verify(ref: ArtifactRef): Boolean {
        val f = fileFor(ref)
        if (!f.isFile) return false
        val size = ref.sizeBytes
        if (size != null && f.length() != size) return false
        return hashFile(f, f.length()).hex() == ref.sha256
    }

    /** Bytes already on disk for [ref]: the full file when cached, else the partial's length. */
    fun bytesPresent(ref: ArtifactRef): Long = when {
        isCached(ref) -> fileFor(ref).length()
        partial(ref).isFile && partialNote(ref).isFile && FlatJson.read(partialNote(ref).readText())["sha256"] == ref.sha256 -> partial(ref).length()
        else -> 0L
    }

    /**
     * Make sure [ref] is on disk and verified; download (or resume) if not.
     *
     * @param offline never open a connection.
     * @param cancel polled between buffers; true = stop now, keep the partial, throw [TransferInterrupted].
     * @throws ModelException for 401 / 403 / checksum / offline miss (never retried)
     * @throws TransferInterrupted for a cut connection, timeout, 5xx, 429, cancel (resumable)
     */
    @Throws(ModelException::class, TransferInterrupted::class)
    fun ensure(
        ref: ArtifactRef,
        log: HfLog,
        offline: Boolean = false,
        token: String? = null,
        onProgress: DownloadProgress? = null,
        cancel: () -> Boolean = { false },
    ): Result {
        val final = fileFor(ref)
        if (isCached(ref)) return Result(final, Result.Status.CACHED, 0, 0)
        if (offline) throw ModelException(ErrorCode.OFFLINE_CACHE_MISS, "'${ref.file}' is not in the cache and the network policy is Offline", details = mapOf("file" to ref.file, "sha256" to ref.sha256))
        val dir = blobDir(ref)
        if (!dir.isDirectory && !dir.mkdirs()) throw ModelException(ErrorCode.STORAGE_FULL, "cannot create ${dir.path}")

        val part = partial(ref)
        val note = partialNote(ref)
        var offset = 0L
        var digest = MessageDigest.getInstance("SHA-256")
        if (part.isFile && note.isFile) {
            val n = FlatJson.read(note.readText())
            val sameRef = n["sha256"] == ref.sha256 && n["source_url"] == ref.sourceUrl
            val size = ref.sizeBytes
            if (sameRef && (size == null || part.length() <= size)) {
                offset = part.length()
                digest = try { hashFile(part, offset) } catch (e: IOException) { offset = 0; MessageDigest.getInstance("SHA-256") }
            }
        }
        if (offset == 0L) {
            RandomAccessFile(part, "rw").use { it.setLength(0) }
            digest = MessageDigest.getInstance("SHA-256")
        }
        note.writeText(FlatJson.write(mapOf("sha256" to ref.sha256, "source_url" to ref.sourceUrl, "size_bytes" to ref.sizeBytes)))
        var resumedFrom = offset
        log.i("download ${ref.file}" + (if (offset > 0) " resume from $offset" else "") + " <- ${ref.sourceUrl}")

        var total = ref.sizeBytes
        var downloaded = 0L
        try {
            var resp = http.get(ref.sourceUrl, if (offset > 0) offset else null, token, timeoutMs)
            if (resp.status == 416 && offset > 0) {
                resp.close()
                RandomAccessFile(part, "rw").use { it.setLength(0) }
                offset = 0; resumedFrom = 0; digest = MessageDigest.getInstance("SHA-256")
                resp = http.get(ref.sourceUrl, null, token, timeoutMs)
            }
            try {
                when {
                    resp.status == 401 -> throw ModelException(ErrorCode.AUTH_REQUIRED, "HTTP 401 for ${ref.file}: the repo needs a Hugging Face token", details = mapOf("file" to ref.file))
                    resp.status == 403 -> throw ModelException(ErrorCode.ACCESS_DENIED, "HTTP 403 for ${ref.file}: access to the repo is gated or denied", details = mapOf("file" to ref.file))
                    resp.status == 404 -> throw ModelException(ErrorCode.MODEL_NOT_FOUND_OR_INACCESSIBLE, "HTTP 404 for ${ref.sourceUrl}", details = mapOf("file" to ref.file))
                    resp.status == 429 || resp.status >= 500 -> throw TransferInterrupted(ref, offset, resp.status, resp.retryAfterSeconds, null)
                    offset > 0 && resp.status == 200 -> {
                        // Server ignored Range: start over instead of appending a whole file to a prefix.
                        log.w("download ${ref.file}: server ignored Range, restarting from 0")
                        RandomAccessFile(part, "rw").use { it.setLength(0) }
                        offset = 0; resumedFrom = 0; digest = MessageDigest.getInstance("SHA-256")
                    }
                    offset > 0 && resp.status == 206 -> {
                        val cr = resp.contentRange ?: ""
                        if (!cr.startsWith("bytes $offset-")) throw ModelException(ErrorCode.NETWORK_ERROR, "asked bytes=$offset-, got Content-Range '$cr'", retryable = true)
                    }
                    resp.status != 200 && resp.status != 206 -> throw ModelException(ErrorCode.NETWORK_ERROR, "HTTP ${resp.status} for ${ref.sourceUrl}", retryable = resp.status >= 500)
                }
                if (total == null) resp.contentLength?.let { total = it + offset }
                onProgress?.onProgress(offset, total)
                val buf = ByteArray(bufferBytes)
                RandomAccessFile(part, "rw").use { raf ->
                    raf.seek(offset)
                    while (true) {
                        if (cancel() || Thread.currentThread().isInterrupted) {
                            throw TransferInterrupted(ref, offset + downloaded, null, null, null)
                        }
                        val n = resp.body.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        downloaded += n
                        onProgress?.onProgress(offset + downloaded, total)
                    }
                    raf.fd.sync()
                }
            } finally {
                resp.close()
            }
        } catch (e: TransferInterrupted) {
            throw e
        } catch (e: IOException) {
            if (e.message?.contains("ENOSPC") == true || e.message?.contains("No space left") == true) {
                throw ModelException(ErrorCode.STORAGE_FULL, "no space left while writing ${ref.file}; partial kept", cause = e)
            }
            // Connection dropped, timeout: the partial stays for a resume.
            throw TransferInterrupted(ref, offset + downloaded, null, null, e)
        }
        val t = total
        if (t != null && offset + downloaded < t) {
            throw TransferInterrupted(ref, offset + downloaded, null, null, null)
        }

        val size = offset + downloaded
        val hex = digest.hex()
        val expectedSize = ref.sizeBytes
        if (hex != ref.sha256 || (expectedSize != null && size != expectedSize)) {
            part.delete(); note.delete()
            log.e("download ${ref.file}: sha256/size mismatch (got $hex / $size, expected ${ref.sha256} / $expectedSize); partial deleted" +
                if (final.isFile) ", previous verified file kept" else "")
            throw ModelException(
                ErrorCode.CHECKSUM_MISMATCH,
                "'${ref.file}': expected sha256 ${ref.sha256} / ${expectedSize ?: "?"} bytes, got $hex / $size bytes; partial deleted" + if (final.isFile) ", previous verified file kept" else "",
                details = mapOf("file" to ref.file, "expected_sha256" to ref.sha256, "actual_sha256" to hex, "actual_bytes" to size.toString(), "previous_kept" to final.isFile.toString()),
            )
        }
        Files.move(part.toPath(), final.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        note.delete()
        sidecar(ref).writeText(
            FlatJson.write(
                mapOf(
                    "sha256" to ref.sha256, "size_bytes" to size, "source_url" to ref.sourceUrl,
                    "repo_id" to ref.repoId, "commit" to ref.commit, "path" to ref.path,
                    "verified_at" to java.time.Instant.now().toString(),
                ),
            ),
        )
        log.i("downloaded ${ref.file}: $size bytes, sha256 ok" + (if (resumedFrom > 0) " (resumed from $resumedFrom)" else ""))
        return Result(final, Result.Status.DOWNLOADED, downloaded, resumedFrom)
    }

    /**
     * Side-load: hash [source] and, when it matches [ref], copy it into the blob directory with a
     * sidecar (atomic rename). Nothing is written when the hash differs.
     */
    @Throws(ModelException::class)
    fun importVerified(ref: ArtifactRef, source: File, log: HfLog): File {
        if (isCached(ref)) return fileFor(ref)
        if (!source.isFile) throw ModelException(ErrorCode.INVALID_INPUT, "not a file: ${source.path}")
        val size = source.length()
        val hex = hashFile(source, size).hex()
        if (hex != ref.sha256 || (ref.sizeBytes != null && size != ref.sizeBytes)) throw ModelException(
            ErrorCode.CHECKSUM_MISMATCH, "'${source.name}': expected sha256 ${ref.sha256} / ${ref.sizeBytes ?: "?"} bytes, got $hex / $size bytes; nothing imported",
            details = mapOf("expected_sha256" to ref.sha256, "actual_sha256" to hex, "actual_bytes" to size.toString()),
        )
        val dir = blobDir(ref)
        if (!dir.isDirectory && !dir.mkdirs()) throw ModelException(ErrorCode.STORAGE_FULL, "cannot create ${dir.path}")
        val tmp = File(dir, ref.file + ".import")
        try {
            source.inputStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o, bufferBytes) } }
        } catch (e: IOException) {
            tmp.delete()
            throw ModelException(ErrorCode.STORAGE_FULL, "copy failed: ${e.message}", cause = e)
        }
        Files.move(tmp.toPath(), fileFor(ref).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        sidecar(ref).writeText(FlatJson.write(mapOf("sha256" to ref.sha256, "size_bytes" to size, "source_url" to ref.sourceUrl, "repo_id" to ref.repoId, "commit" to ref.commit, "path" to ref.path, "imported_from" to source.path, "verified_at" to java.time.Instant.now().toString())))
        log.i("imported ${ref.file}: $size bytes, sha256 ok, from ${source.path}")
        return fileFor(ref)
    }

    /** Remove the blob directory (file, sidecar, partial). Returns true if anything was deleted. */
    fun evict(ref: ArtifactRef): Boolean {
        val dir = blobDir(ref)
        if (!dir.exists()) return false
        return dir.deleteRecursively()
    }

    private fun hashFile(f: File, upto: Long): MessageDigest {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(bufferBytes)
        var left = upto
        f.inputStream().use { s ->
            while (left > 0) {
                val n = s.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                md.update(buf, 0, n)
                left -= n
            }
        }
        if (left != 0L) throw IOException("${f.name}: expected $upto bytes, file is shorter")
        return md
    }

    private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }

    companion object {
        const val SIDECAR_SUFFIX = ".hfmodels.json"
    }
}

/**
 * Flat JSON (string / integer / null values, one level) for the store's own sidecar files.
 * Hand-rolled so the JVM tests do not depend on Android's org.json for this hot path.
 */
internal object FlatJson {
    fun write(fields: Map<String, Any?>): String = fields.entries.joinToString(",\n", "{\n", "\n}\n") { (k, v) ->
        val value = when (v) {
            null -> "null"
            is Number -> v.toString()
            is Boolean -> v.toString()
            else -> "\"" + v.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
        "  \"$k\": $value"
    }

    private val entry = Regex("\"([^\"]+)\"\\s*:\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"|(-?\\d+)|true|false|null)")

    fun read(text: String): Map<String, String?> = entry.findAll(text).associate { m ->
        m.groupValues[1] to when {
            m.groups[3] != null -> m.groupValues[3].replace("\\\"", "\"").replace("\\\\", "\\")
            m.groups[4] != null -> m.groupValues[4]
            m.groupValues[2] == "true" || m.groupValues[2] == "false" -> m.groupValues[2]
            else -> null
        }
    }
}
