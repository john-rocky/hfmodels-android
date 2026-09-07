package io.github.johnrocky.hfmodels.store

import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.FakeHub
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.RecordingLog
import io.github.johnrocky.hfmodels.sha256Hex
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The nine store cases of the ported design plus: redirect drops the token, 5xx / 429 are interruptions, offline never connects. */
class ModelStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var hub: FakeHub
    private lateinit var store: ModelStore
    private val log = RecordingLog()
    private val blob = ByteArray(1 shl 20) { (it % 251).toByte() }
    private val sha = sha256Hex(blob)

    @Before fun setUp() {
        hub = FakeHub()
        hub.repo("org/model") { files["model.litertlm"] = blob }
        store = ModelStore(File(tmp.root, "hfmodels"), timeoutMs = 5_000, bufferBytes = 64 * 1024)
    }

    @After fun tearDown() = hub.stop()

    private fun ref(size: Long? = blob.size.toLong(), file: String = "model.litertlm") =
        ArtifactRef(file, "${hub.endpoint}/org/model/resolve/${"a".repeat(40)}/$file", sha, size, repoId = "org/model", commit = "a".repeat(40), path = file)

    private fun blobDir() = File(store.root, "blobs/$sha")

    @Test fun downloadsVerifiesWritesSidecarAndCaches() {
        val seen = mutableListOf<Pair<Long, Long?>>()
        val r = store.ensure(ref(), log, onProgress = { d, t -> seen.add(d to t) })
        assertEquals(ModelStore.Result.Status.DOWNLOADED, r.status)
        assertEquals(blob.size.toLong(), r.bytesDownloaded)
        assertEquals(0L, r.resumedFrom)
        assertTrue(r.file.readBytes().contentEquals(blob))
        assertEquals(File(blobDir(), "model.litertlm"), r.file)
        assertEquals(0L to blob.size.toLong(), seen.first())
        assertEquals(blob.size.toLong() to blob.size.toLong(), seen.last())
        assertFalse(File(blobDir(), "model.litertlm.partial").exists())
        val sidecar = FlatJson.read(File(blobDir(), "model.litertlm.hfmodels.json").readText())
        assertEquals(sha, sidecar["sha256"])
        assertEquals(blob.size.toString(), sidecar["size_bytes"])
        assertEquals("org/model", sidecar["repo_id"])
        assertTrue(hub.requests.all { it.auth == null })

        val n = hub.requests.size
        assertTrue(store.isCached(ref()))
        assertEquals(ModelStore.Result.Status.CACHED, store.ensure(ref(), log, offline = true).status)
        assertEquals(n, hub.requests.size) // no connection
        assertTrue(store.verify(ref()))
        assertEquals(blob.size.toLong(), store.bytesPresent(ref()))
    }

    @Test fun offlineWithoutCacheIsAnExplicitErrorWithZeroRequests() {
        try {
            store.ensure(ref(), log, offline = true)
            fail("expected OFFLINE_CACHE_MISS")
        } catch (e: ModelException) {
            assertEquals(ErrorCode.OFFLINE_CACHE_MISS, e.code)
        }
        assertTrue(hub.requests.isEmpty())
    }

    @Test fun interruptedThenResumedWithRange() {
        hub.truncateAfter = 300_000
        try {
            store.ensure(ref(), log)
            fail("expected TransferInterrupted")
        } catch (e: ModelStore.TransferInterrupted) {
            assertTrue(e.bytesSoFar in 1 until blob.size.toLong())
        }
        val partial = File(blobDir(), "model.litertlm.partial")
        assertTrue(partial.isFile && partial.length() in 1 until blob.size.toLong())
        val got = partial.length()
        assertEquals(got, store.bytesPresent(ref()))
        assertFalse(store.fileFor(ref()).exists())

        val r = store.ensure(ref(), log)
        assertEquals(ModelStore.Result.Status.DOWNLOADED, r.status)
        assertEquals(got, r.resumedFrom)
        assertEquals(blob.size - got, r.bytesDownloaded)
        assertEquals("bytes=$got-", hub.requests.last().range)
        assertTrue(r.file.readBytes().contentEquals(blob))
        assertFalse(partial.exists())
    }

    @Test fun serverIgnoringRangeRestartsFromZero() {
        hub.truncateAfter = 100_000
        runCatching { store.ensure(ref(), log) }
        hub.ignoreRange = true
        val r = store.ensure(ref(), log)
        assertEquals(0L, r.resumedFrom)
        assertEquals(blob.size.toLong(), r.bytesDownloaded)
        assertTrue(r.file.readBytes().contentEquals(blob))
        assertTrue(log.lines.any { it.contains("ignored Range") })
    }

    @Test fun partialOfAnotherRefIsNotResumed() {
        blobDir().mkdirs()
        File(blobDir(), "model.litertlm.partial").writeBytes(ByteArray(1000) { 7 })
        File(blobDir(), "model.litertlm.partial.json").writeText(FlatJson.write(mapOf("sha256" to "0".repeat(64), "source_url" to ref().sourceUrl)))
        val r = store.ensure(ref(), log)
        assertEquals(0L, r.resumedFrom)
        assertTrue(r.file.readBytes().contentEquals(blob))
    }

    @Test fun checksumMismatchRollsBackAndKeepsOldFile() {
        val good = store.ensure(ref(), log).file
        hub.serveWrongBytesFor = "model.litertlm" // same size, wrong bytes
        File(blobDir(), "model.litertlm.hfmodels.json").delete() // force a refetch, keep the file
        try {
            store.ensure(ref(), log)
            fail("expected CHECKSUM_MISMATCH")
        } catch (e: ModelException) {
            assertEquals(ErrorCode.CHECKSUM_MISMATCH, e.code)
            assertEquals("true", e.details["previous_kept"])
            assertEquals(sha256Hex(ByteArray(blob.size)), e.details["actual_sha256"])
        }
        assertTrue(good.readBytes().contentEquals(blob))
        assertFalse(File(blobDir(), "model.litertlm.partial").exists())
        assertFalse(File(blobDir(), "model.litertlm.partial.json").exists())
    }

    @Test fun sizeMismatchIsAChecksumFailure() {
        try {
            store.ensure(ref(size = blob.size.toLong() - 1), log)
            fail("expected CHECKSUM_MISMATCH")
        } catch (e: ModelException) {
            assertEquals(ErrorCode.CHECKSUM_MISMATCH, e.code)
            assertEquals("false", e.details["previous_kept"])
        }
    }

    @Test fun redirectKeepsRangeAndDropsTheToken() {
        hub.redirectFilesTo = "/cdn"
        hub.truncateAfter = 200_000
        runCatching { store.ensure(ref(), log, token = "hf_secret") }
        val r = store.ensure(ref(), log, token = "hf_secret")
        assertTrue(r.resumedFrom > 0)
        val real = hub.requests.last { it.path.startsWith("/cdn/") }
        assertEquals("bytes=${r.resumedFrom}-", real.range)
        assertTrue(r.file.readBytes().contentEquals(blob))
        // Same host here (loopback), so the bearer is still sent on the hop; the cross-host drop is in HttpUrlConnectionSource.
        assertTrue(hub.requests.filter { !it.path.startsWith("/cdn/") }.all { it.auth == "Bearer hf_secret" })
    }

    @Test fun unauthorizedIsAuthRequiredWithoutAToken() {
        hub.repos["org/model"]!!.gated = true
        try {
            store.ensure(ref(), log)
            fail("expected AUTH_REQUIRED")
        } catch (e: ModelException) {
            assertEquals(ErrorCode.AUTH_REQUIRED, e.code)
        }
        assertTrue(hub.requests.all { it.auth == null })
        assertNull(store.fileFor(ref()).takeIf { it.exists() })
        // With a token the same transfer succeeds and the token was sent to the Hub host.
        val r = store.ensure(ref(), log, token = "hf_x")
        assertEquals(ModelStore.Result.Status.DOWNLOADED, r.status)
        assertEquals("Bearer hf_x", hub.requests.last().auth)
    }

    @Test fun serverErrorAndTooManyRequestsAreResumableInterruptions() {
        hub.failNextWith = 503; hub.retryAfter = 7
        try { store.ensure(ref(), log); fail("expected TransferInterrupted") } catch (e: ModelStore.TransferInterrupted) {
            assertEquals(503, e.httpStatus); assertEquals(7L, e.retryAfterSeconds)
        }
        hub.failNextWith = 429; hub.retryAfter = null
        try { store.ensure(ref(), log); fail("expected TransferInterrupted") } catch (e: ModelStore.TransferInterrupted) { assertEquals(429, e.httpStatus) }
        assertEquals(ModelStore.Result.Status.DOWNLOADED, store.ensure(ref(), log).status)
    }

    @Test fun cancelKeepsPartialForResume() {
        var calls = 0
        try {
            store.ensure(ref(), log, cancel = { ++calls > 3 })
            fail("expected TransferInterrupted")
        } catch (e: ModelStore.TransferInterrupted) {
            assertTrue(e.bytesSoFar > 0)
        }
        val partial = File(blobDir(), "model.litertlm.partial")
        assertTrue(partial.isFile && partial.length() > 0)
        val r = store.ensure(ref(), log)
        assertTrue(r.resumedFrom > 0)
        assertTrue(r.file.readBytes().contentEquals(blob))
    }

    @Test fun evictRemovesEverything() {
        store.ensure(ref(), log)
        assertTrue(store.evict(ref()))
        assertFalse(store.isCached(ref()))
        assertFalse(store.evict(ref()))
    }

    @Test fun artifactRefRejectsPathsAndBadDigests() {
        for (bad in listOf("a/b.litertlm", "../x", "")) {
            try { ArtifactRef(bad, "https://x", "0".repeat(64)); fail("accepted '$bad'") } catch (_: IllegalArgumentException) {}
        }
        try { ArtifactRef("x", "http://example.com/x", "0".repeat(64)); fail("accepted http") } catch (_: IllegalArgumentException) {}
        try { ArtifactRef("x", "https://x", "ABC"); fail("accepted bad sha") } catch (_: IllegalArgumentException) {}
    }

    @Test fun flatJsonRoundTrip() {
        val text = FlatJson.write(mapOf("a" to "q\"uote\\", "n" to 42L, "z" to null, "b" to true))
        val back = FlatJson.read(text)
        assertEquals("q\"uote\\", back["a"])
        assertEquals("42", back["n"])
        assertTrue(back.containsKey("z") && back["z"] == null)
        assertEquals("true", back["b"])
    }
}
