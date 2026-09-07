package io.github.johnrocky.hfmodels

import io.github.johnrocky.hfmodels.catalog.Catalog
import io.github.johnrocky.hfmodels.descriptor.Fixtures
import io.github.johnrocky.hfmodels.hub.HfHub
import io.github.johnrocky.hfmodels.resolve.DeviceFacts
import io.github.johnrocky.hfmodels.resolve.FakeChat
import io.github.johnrocky.hfmodels.store.HttpUrlConnectionSource
import io.github.johnrocky.hfmodels.store.ModelStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The client end to end against the loopback Hub: inspect without weights, download, prepare, offline, busy, shared downloads. */
class HfModelsTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var hub: FakeHub
    private lateinit var models: HfModels
    private val log = RecordingLog()
    private val weights = ByteArray(1 shl 20) { (it % 17).toByte() }
    private val commit = "a".repeat(40)

    private fun client(bufferBytes: Int = 1 shl 16): HfModels {
        val root = File(tmp.root, "hfmodels")
        val http = HttpUrlConnectionSource()
        return HfModels(null, root, File(tmp.root, "cache").apply { mkdirs() }, HfHub(http, hub.endpoint), ModelStore(root, http, 5_000, bufferBytes),
            Catalog("none", emptyList()), DeviceFacts(36, listOf("arm64-v8a")), log)
    }

    @Before fun setUp() {
        hub = FakeHub()
        hub.repo("org/model", commit) { files["hfmodels.json"] = Fixtures.chat("org/model", fileSha = sha256Hex(weights), bytes = weights.size.toLong()).toByteArray(); files["model.litertlm"] = weights }
        models = client()
    }

    @After fun tearDown() { hub.stop(); models.close() }

    @Test fun inspectDownloadPrepareThenOfflineReload() = runBlocking {
        val events = mutableListOf<LoadEvent>()
        val plan = models.inspect(ModelRef("org/model"), FakeChat)
        assertTrue("inspect must not fetch weights", hub.requests.none { it.path.endsWith("model.litertlm") })
        assertEquals(weights.size.toLong(), plan.bytesToDownload)
        val local = models.download(plan) { events += it }
        assertTrue(local.files.getValue("weights").readBytes().contentEquals(weights))
        assertTrue(events.first() is LoadEvent.DownloadStarted)
        assertTrue(events.any { it is LoadEvent.Downloading })
        assertTrue(events.last() is LoadEvent.Verifying)
        val model = models.prepare(local) { events += it }
        assertTrue(events.any { it is LoadEvent.Initializing })
        assertTrue(events.last() is LoadEvent.Ready)
        assertEquals(commit, model.info.commit)
        assertEquals(commit, models.boundCommit("org/model"))
        model.closeAndJoin()

        // Second load: binding saved, descriptor cached, file cached -> zero HTTP requests offline.
        val n = hub.requests.size
        val again = models.fromPretrained(ModelRef("org/model"), FakeChat, LoadOptions(networkPolicy = NetworkPolicy.Offline))
        assertEquals(n, hub.requests.size)
        assertEquals(BindingSource.SAVED_BINDING, again.info.bindingSource)
        again.closeAndJoin()
        // The repo moving its branch does not move the app: the saved binding wins.
        hub.repos["org/model"]!!.branches["main"] = "e".repeat(40)
        val third = models.inspect(ModelRef("org/model"), FakeChat)
        assertEquals(commit, third.modelOrigin.commit)
        assertTrue(models.unbind("org/model"))
    }

    @Test fun secondPrepareWhileOneIsOpenIsModelBusy() = runBlocking {
        val first = models.fromPretrained(ModelRef("org/model"), FakeChat)
        try { models.fromPretrained(ModelRef("org/model"), FakeChat); fail() } catch (e: ModelException) { assertEquals(ErrorCode.MODEL_BUSY, e.code) }
        first.closeAndJoin()
        models.fromPretrained(ModelRef("org/model"), FakeChat).closeAndJoin()
    }

    @Test fun downloadPolicyLimitsAreEnforcedBeforeAnyTransfer() = runBlocking {
        val plan = models.inspect(ModelRef("org/model"), FakeChat, LoadOptions(maxDownloadBytes = 10))
        try { models.download(plan); fail() } catch (e: ModelException) { assertEquals(ErrorCode.DOWNLOAD_POLICY_BLOCKED, e.code) }
        assertTrue(hub.requests.none { it.path.endsWith("model.litertlm") })
        val off = models.inspect(ModelRef("org/model", revision = commit), FakeChat, LoadOptions(networkPolicy = NetworkPolicy.Offline))
        try { models.download(off); fail() } catch (e: ModelException) { assertEquals(ErrorCode.OFFLINE_CACHE_MISS, e.code) }
    }

    @Test fun transientFailuresAreRetriedAndResumed() = runBlocking {
        hub.truncateAfter = 200_000
        val plan = models.inspect(ModelRef("org/model"), FakeChat)
        val local = models.download(plan)
        assertTrue(local.files.getValue("weights").readBytes().contentEquals(weights))
        assertTrue(log.lines.any { it.contains("retrying") })
        assertTrue(hub.requests.any { it.range != null })
    }

    @Test fun checksumMismatchIsNotRetried() = runBlocking {
        hub.serveWrongBytesFor = "model.litertlm"
        val plan = models.inspect(ModelRef("org/model"), FakeChat)
        val before = hub.requests.size
        try { models.download(plan); fail() } catch (e: ModelException) { assertEquals(ErrorCode.CHECKSUM_MISMATCH, e.code) }
        assertEquals(1, hub.requests.size - before)
    }

    @Test fun concurrentDownloadsShareOneTransferAndOneCancelDoesNotHurtTheOther() = runBlocking {
        hub.delayPerChunkMs = 5
        val slow = client(bufferBytes = 4096)
        val plan = slow.inspect(ModelRef("org/model"), FakeChat)
        val a = async { slow.download(plan) }
        var seen = 0L
        val b = launch { slow.download(plan) { if (it is LoadEvent.Downloading) seen = it.bytes } }
        withTimeout(10_000) { while (seen < 64 * 1024) delay(5) }
        b.cancel()                                   // one waiter leaves ...
        val local = withTimeout(20_000) { a.await() } // ... the other still gets the file
        assertTrue(local.files.getValue("weights").readBytes().contentEquals(weights))
        assertEquals(1, hub.requests.count { it.path.endsWith("model.litertlm") })
        slow.close()
    }

    @Test fun lastWaiterCancellingStopsTheTransferAndKeepsThePartial() = runBlocking {
        hub.delayPerChunkMs = 5
        val slow = client(bufferBytes = 4096)
        val plan = slow.inspect(ModelRef("org/model"), FakeChat)
        var seen = 0L
        val j = launch { slow.download(plan) { if (it is LoadEvent.Downloading) seen = it.bytes } }
        withTimeout(10_000) { while (seen < 64 * 1024) delay(5) }
        j.cancel(); j.join()
        assertTrue(j.isCancelled)
        delay(300)
        hub.delayPerChunkMs = 0
        val partial = File(tmp.root, "hfmodels/blobs/${sha256Hex(weights)}/model.litertlm.partial")
        assertTrue("partial kept for a resume", partial.isFile && partial.length() > 0)
        assertFalse(File(tmp.root, "hfmodels/blobs/${sha256Hex(weights)}/model.litertlm").exists())
        val local = slow.download(plan)
        assertTrue(local.files.getValue("weights").readBytes().contentEquals(weights))
        assertTrue(hub.requests.last { it.path.endsWith("model.litertlm") }.range != null)
        slow.close()
    }

    @Test fun closedClientRefuses() = runBlocking {
        models.close()
        try { models.inspect(ModelRef("org/model"), FakeChat); fail() } catch (e: ModelException) { assertEquals(ErrorCode.MODEL_CLOSED, e.code) }
    }
}
