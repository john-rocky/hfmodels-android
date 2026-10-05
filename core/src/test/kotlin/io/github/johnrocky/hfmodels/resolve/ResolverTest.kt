package io.github.johnrocky.hfmodels.resolve

import io.github.johnrocky.hfmodels.BackendKind
import io.github.johnrocky.hfmodels.BackendPolicy
import io.github.johnrocky.hfmodels.BindingSource
import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.FakeHub
import io.github.johnrocky.hfmodels.Handler
import io.github.johnrocky.hfmodels.InputKind
import io.github.johnrocky.hfmodels.LoadEvent
import io.github.johnrocky.hfmodels.LoadOptions
import io.github.johnrocky.hfmodels.LocalModel
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.ModelRef
import io.github.johnrocky.hfmodels.NetworkPolicy
import io.github.johnrocky.hfmodels.PrepareHost
import io.github.johnrocky.hfmodels.PreparedModel
import io.github.johnrocky.hfmodels.PreparedModelInfo
import io.github.johnrocky.hfmodels.RecordingLog
import io.github.johnrocky.hfmodels.Task
import io.github.johnrocky.hfmodels.VerificationLevel
import io.github.johnrocky.hfmodels.catalog.Catalog
import io.github.johnrocky.hfmodels.descriptor.Fixtures
import io.github.johnrocky.hfmodels.hub.HfHub
import io.github.johnrocky.hfmodels.sha256Hex
import io.github.johnrocky.hfmodels.store.HttpUrlConnectionSource
import io.github.johnrocky.hfmodels.store.ModelStore
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A chat model that holds no native resources: enough for resolver / client tests. */
class FakePrepared(override val info: PreparedModelInfo, val files: Map<String, File>, private val host: PrepareHost) : PreparedModel {
    var closed = false
    override fun close() { if (!closed) { closed = true; host.onModelClosed(this) } }
    override suspend fun closeAndJoin() = close()
}

object FakeChat : Task<FakePrepared> {
    override val id = "chat"
    override val handler = object : Handler<FakePrepared> {
        override val id = "litertlm.conversation"
        override val abi = 1
        override val runtime = "litert_lm"
        override val runtimeVersion = "0.16.1"
        override fun prepare(local: LocalModel<FakePrepared>, host: PrepareHost, onProgress: (LoadEvent) -> Unit): FakePrepared {
            val p = local.plan
            onProgress(LoadEvent.Initializing(p.profile.id))
            return FakePrepared(
                PreparedModelInfo(p.ref.repoId, p.modelOrigin.commit, p.descriptorSha256, p.descriptorOrigin, p.bindingSource, p.variant.id, p.profile.id, host.sdkVersion, id, abi, runtime, runtimeVersion,
                    p.variant.inputs, p.enabledInputs, p.options.backendPolicy, emptyMap(), p.excludedProfiles, emptyList(), p.verification, emptyList(), local.files.mapValues { it.value.path }),
                local.files, host,
            )
        }
    }
}

class ResolverTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var hub: FakeHub
    private lateinit var resolver: Resolver
    private lateinit var store: ModelStore
    private lateinit var bindings: Bindings
    private val log = RecordingLog()
    private val weights = ByteArray(1 shl 16) { (it % 13).toByte() }
    private val commit = "a".repeat(40)
    private val catalogCommit = "c".repeat(40)

    private fun descriptorFor(modelId: String) = Fixtures.chat(modelId, fileSha = sha256Hex(weights), bytes = weights.size.toLong())

    private fun catalog(vararg entries: Pair<String, String>, descriptor: (String) -> String = { descriptorFor(it) }): Catalog = Catalog.parse(
        """{"schema_version":1,"catalog_id":"test","origin":{"repo":"john-rocky/hfmodels-android","commit":"${"b".repeat(40)}"},"entries":[""" +
            entries.joinToString(",") { (id, c) -> """{"model_id":"$id","model_commit":"$c","path":"catalog/$id.json","descriptor":${descriptor(id)}}""" } + "]}",
    )

    @Before fun setUp() {
        hub = FakeHub()
        val root = File(tmp.root, "hfmodels")
        store = ModelStore(root, timeoutMs = 5_000, bufferBytes = 8192)
        bindings = Bindings(root)
        resolver = Resolver(HfHub(HttpUrlConnectionSource(), hub.endpoint), store, bindings, DescriptorCache(root), catalog("org/catalogued" to catalogCommit), DeviceFacts(36, listOf("arm64-v8a")), log)
    }

    @After fun tearDown() = hub.stop()

    @Test fun repoLocalDescriptorAtBranchHead() {
        hub.repo("org/model", commit) { files["model.litertlm"] = weights; files["hfmodels.json"] = descriptorFor("org/model").toByteArray() }
        val plan = resolver.inspect(ModelRef("org/model"), FakeChat, LoadOptions())
        assertEquals(commit, plan.modelOrigin.commit)
        assertEquals(BindingSource.RESOLVED_BRANCH, plan.bindingSource)
        assertEquals("org/model", plan.descriptorOrigin.repo)
        assertEquals(commit, plan.descriptorOrigin.commit)
        assertEquals("cpu", plan.profile.id)   // the descriptor's default profile
        assertEquals(1, plan.files.size)
        assertEquals("${hub.endpoint}/org/model/resolve/$commit/model.litertlm", plan.files[0].url)
        assertFalse(plan.files[0].cached)
        assertEquals(weights.size.toLong(), plan.bytesToDownload)
        assertEquals(VerificationLevel.MAINTAINER_TESTED, plan.verification)
        // No weight was fetched: only the API and the descriptor.
        assertTrue(hub.requests.none { it.path.endsWith("model.litertlm") })
        assertTrue(plan.excludedProfiles.any { it.first == "gpu" && it.second == "not selected" })
    }

    @Test fun explicitCommitPinsAndCrossChecksHubMetadata() {
        hub.repo("org/model", commit) { files["model.litertlm"] = weights; files["hfmodels.json"] = descriptorFor("org/model").toByteArray() }
        val plan = resolver.inspect(ModelRef("org/model", revision = commit), FakeChat, LoadOptions())
        assertEquals(BindingSource.EXPLICIT_REVISION, plan.bindingSource)
        // A descriptor that disagrees with the Hub's LFS metadata is a METADATA_MISMATCH, not a silent pick (new commit: a commit's descriptor is immutable and cached).
        hub.repo("org/model", "b".repeat(40)) { files["model.litertlm"] = weights; files["hfmodels.json"] = Fixtures.chat("org/model", fileSha = "f".repeat(64), bytes = weights.size.toLong()).toByteArray() }
        try { resolver.inspect(ModelRef("org/model", revision = "main"), FakeChat, LoadOptions()); fail() } catch (e: ModelException) { assertEquals(ErrorCode.METADATA_MISMATCH, e.code) }
    }

    @Test fun catalogDefaultBindingWhenBranchHeadHasNoDescriptor() {
        // The owner's repo moved on (branch head = commit) and never had hfmodels.json; the catalog pins catalogCommit.
        hub.repo("org/catalogued", catalogCommit) { files["model.litertlm"] = weights; branches["main"] = this@ResolverTest.commit }
        val plan = resolver.inspect(ModelRef("org/catalogued"), FakeChat, LoadOptions())
        assertEquals(log.lines.joinToString("\n") + "\n" + hub.requests.joinToString("\n"), BindingSource.CATALOG_DEFAULT_BINDING, plan.bindingSource)
        assertEquals(catalogCommit, plan.modelOrigin.commit)
        assertEquals("john-rocky/hfmodels-android", plan.descriptorOrigin.repo)
        assertEquals("${hub.endpoint}/org/catalogued/resolve/$catalogCommit/model.litertlm", plan.files[0].url)
        // With an explicit revision the exception does not apply.
        try { resolver.inspect(ModelRef("org/catalogued", revision = "main"), FakeChat, LoadOptions()); fail() } catch (e: ModelException) { assertEquals(ErrorCode.MODEL_NOT_REGISTERED, e.code) }
    }

    /**
     * At one commit the commit's own hfmodels.json is read before the catalog's entry for that commit (spec §6.3); the
     * catalog stands in only for a commit that carries none. An entry pinned at a commit whose hfmodels.json is older
     * than the entry is therefore never read online: the bundled catalog pinned GLiNER2.5-Decide's four variants at
     * 310090c3, whose own hfmodels.json lists three (s128_npu_wfp16 came with f6f6e9c9), so an explicit-revision load
     * of s128_npu_wfp16 was VARIANT_NOT_FOUND online. The order stays; the pin is what changes: an entry pins a commit
     * that carries no hfmodels.json or the same descriptor (tools/check_catalog_pins.py).
     */
    @Test fun aCommitsOwnDescriptorIsReadBeforeTheCatalogEntryForThatCommit() {
        val pinned = "3".repeat(40)
        val own = descriptorFor("org/pinned")   // variant q8: what the commit itself carries
        val catalogued = JSONObject(own).apply {   // q8 and q8_npu: what the catalog pins at the same commit
            val variants = getJSONArray("variants")
            variants.put(JSONObject(variants.getJSONObject(0).toString()).put("id", "q8_npu"))
        }.toString()
        hub.repo("org/pinned", pinned) { files["model.litertlm"] = weights; files["hfmodels.json"] = own.toByteArray() }
        val r = Resolver(HfHub(HttpUrlConnectionSource(), hub.endpoint), store, bindings, DescriptorCache(File(tmp.root, "hfmodels")), catalog("org/pinned" to pinned, descriptor = { catalogued }), DeviceFacts(36, listOf("arm64-v8a")), log)
        val ref = ModelRef("org/pinned", revision = pinned, variant = "q8_npu")
        // Offline, before anything was read from the repo, the catalog is the only source and has the variant.
        val offline = r.inspect(ref, FakeChat, LoadOptions(networkPolicy = NetworkPolicy.Offline))
        assertEquals("john-rocky/hfmodels-android", offline.descriptorOrigin.repo)
        assertEquals("q8_npu", offline.variant.id)
        // Online, the commit's own hfmodels.json answers first and has no such variant.
        try { r.inspect(ref, FakeChat, LoadOptions()); fail() } catch (e: ModelException) {
            assertEquals(ErrorCode.VARIANT_NOT_FOUND, e.code)
            assertTrue(e.reason, e.reason.contains("(have: [q8])"))
        }
        assertTrue(hub.requests.any { it.path == "/org/pinned/resolve/$pinned/hfmodels.json" })
        // That read is cached for the commit, so the catalog's variant is now out of reach offline as well.
        try { r.inspect(ref, FakeChat, LoadOptions(networkPolicy = NetworkPolicy.Offline)); fail() } catch (e: ModelException) { assertEquals(ErrorCode.VARIANT_NOT_FOUND, e.code) }
    }

    @Test fun unregisteredRepoIsModelNotRegistered() {
        hub.repo("org/plain", commit) { files["model.litertlm"] = weights }
        try { resolver.inspect(ModelRef("org/plain"), FakeChat, LoadOptions()); fail() } catch (e: ModelException) {
            assertEquals(ErrorCode.MODEL_NOT_REGISTERED, e.code)
            assertTrue(e.reason.contains("hfmodels.json"))
        }
    }

    @Test fun hubErrorsAreTyped() {
        try { resolver.inspect(ModelRef("org/missing"), FakeChat, LoadOptions()); fail() } catch (e: ModelException) { assertEquals(ErrorCode.MODEL_NOT_FOUND_OR_INACCESSIBLE, e.code) }
        hub.repo("org/gated", commit) { gated = true; files["hfmodels.json"] = descriptorFor("org/gated").toByteArray(); files["model.litertlm"] = weights }
        try { resolver.inspect(ModelRef("org/gated"), FakeChat, LoadOptions()); fail() } catch (e: ModelException) { assertEquals(ErrorCode.AUTH_REQUIRED, e.code); assertTrue(e.details["url"]!!.endsWith("org/gated")) }
        val withToken = resolver.inspect(ModelRef("org/gated"), FakeChat, LoadOptions(credentials = { "hf_tok" }))
        assertEquals(commit, withToken.modelOrigin.commit)
        assertTrue(hub.requests.last().auth == "Bearer hf_tok")
        hub.repo("org/model", commit) { files["hfmodels.json"] = descriptorFor("org/model").toByteArray(); files["model.litertlm"] = weights }
        try { resolver.inspect(ModelRef("org/model", revision = "v9"), FakeChat, LoadOptions()); fail() } catch (e: ModelException) { assertEquals(ErrorCode.REVISION_NOT_FOUND, e.code) }
        hub.repos["org/model"]!!.status = 403
        try { resolver.inspect(ModelRef("org/model"), FakeChat, LoadOptions()); fail() } catch (e: ModelException) { assertEquals(ErrorCode.ACCESS_DENIED, e.code) }
    }

    @Test fun descriptorProblemsAreTyped() {
        // One commit per case: a commit's descriptor is immutable, so the resolver caches it.
        fun at(c: Char, text: String, ref: ModelRef = ModelRef("org/model"), code: ErrorCode) {
            hub.repo("org/model", c.toString().repeat(40)) { files["hfmodels.json"] = text.toByteArray(); files["model.litertlm"] = weights }
            try { resolver.inspect(ref, FakeChat, LoadOptions()); fail("expected $code") } catch (e: ModelException) { assertEquals(e.reason, code, e.code) }
        }
        at('1', "{\"schema_version\": 7}", code = ErrorCode.UNSUPPORTED_SCHEMA)
        at('2', "{\"schema_version\": 1, \"model_id\": \"org/model\"}", code = ErrorCode.MANIFEST_INVALID)
        at('3', descriptorFor("org/model"), ModelRef("org/model", variant = "int4"), ErrorCode.VARIANT_NOT_FOUND)
        at('4', Fixtures.chat("org/model", fileSha = sha256Hex(weights), bytes = weights.size.toLong(), runtimeMax = "0.16.1"), code = ErrorCode.RUNTIME_VERSION_MISMATCH)
        at('5', descriptorFor("org/model").replace("\"id\": \"litertlm.conversation\"", "\"id\": \"vision.yolox\"").replace("\"runtime\": \"litert_lm\"", "\"runtime\": \"litert\""), code = ErrorCode.HANDLER_NOT_INSTALLED)
    }

    @Test fun profileSelectionFollowsThePolicy() {
        hub.repo("org/model", commit) { files["hfmodels.json"] = descriptorFor("org/model").toByteArray(); files["model.litertlm"] = weights }
        assertEquals("gpu", resolver.inspect(ModelRef("org/model"), FakeChat, LoadOptions(backendPolicy = BackendPolicy.Require(BackendKind.GPU))).profile.id)
        assertEquals("gpu", resolver.inspect(ModelRef("org/model"), FakeChat, LoadOptions(backendPolicy = BackendPolicy.RequireProfile("gpu"))).profile.id)
        assertEquals(VerificationLevel.UNVERIFIED, resolver.inspect(ModelRef("org/model"), FakeChat, LoadOptions(backendPolicy = BackendPolicy.RequireProfile("gpu"))).verification)
        try { resolver.inspect(ModelRef("org/model"), FakeChat, LoadOptions(backendPolicy = BackendPolicy.Require(BackendKind.NPU))); fail() } catch (e: ModelException) { assertEquals(ErrorCode.NO_COMPATIBLE_PROFILE, e.code) }
        try { resolver.inspect(ModelRef("org/model"), FakeChat, LoadOptions(requiredInputs = setOf(InputKind.TEXT, InputKind.IMAGE))); fail() } catch (e: ModelException) { assertEquals(ErrorCode.NO_COMPATIBLE_PROFILE, e.code) }
        try { resolver.inspect(ModelRef("org/model"), FakeChat, LoadOptions(backendPolicy = BackendPolicy.RequireProfile("gpu"), allowUnverified = false)); fail() } catch (e: ModelException) { assertEquals(ErrorCode.NO_COMPATIBLE_PROFILE, e.code) }
        // An NPU profile without a PASS record is never auto-selected, however high its priority.
        val npu = """,{"id": "npu", "priority": 1000, "files": ["weights"], "enabled_inputs": ["text"], "components": {"language": "npu"}, "requirements": {"min_android_api": 31, "abis": ["arm64-v8a"]}, "fallback_profiles": [], "default_selectable": true}"""
        hub.repo("org/model", "d".repeat(40)) { files["model.litertlm"] = weights; files["hfmodels.json"] = Fixtures.chat("org/model", extraProfile = npu, fileSha = sha256Hex(weights), bytes = weights.size.toLong()).replace("\"default_profile\": \"cpu\"", "\"default_profile\": \"npu\"").toByteArray() }
        val plan = resolver.inspect(ModelRef("org/model"), FakeChat, LoadOptions())
        assertEquals("gpu", plan.profile.id)
        assertTrue(plan.excludedProfiles.any { it.first == "npu" && it.second.contains("NPU") })
        // Named explicitly it is loadable (the first device gate of an NPU profile runs before any PASS exists), reported UNVERIFIED.
        val named = resolver.inspect(ModelRef("org/model"), FakeChat, LoadOptions(backendPolicy = BackendPolicy.Require(BackendKind.NPU)))
        assertEquals("npu", named.profile.id)
        assertEquals(VerificationLevel.UNVERIFIED, named.verification)
        assertEquals("npu", resolver.inspect(ModelRef("org/model"), FakeChat, LoadOptions(backendPolicy = BackendPolicy.RequireProfile("npu"))).profile.id)
        // A GPU profile whose only record is a FAIL (the process died on the reference phone) is skipped by Auto even as the
        // default profile, and still available to Require(GPU) / RequireProfile.
        val gpuFail = """,{"id": "gpu_fail", "priority": 200, "files": ["weights"], "enabled_inputs": ["text"], "components": {"language": "gpu"}, "requirements": {"min_android_api": 31, "abis": ["arm64-v8a"]}, "fallback_profiles": ["cpu"], "default_selectable": true,
           "verification": [{"level": "MAINTAINER_TESTED", "device": "Pixel 8a", "os_build": "CP1A.260505.005", "runtime": "litert_lm 0.16.1", "result": "FAIL", "date": "2026-09-08"}]}"""
        hub.repo("org/model", "e".repeat(40)) { files["model.litertlm"] = weights; files["hfmodels.json"] = Fixtures.chat("org/model", extraProfile = gpuFail, fileSha = sha256Hex(weights), bytes = weights.size.toLong()).replace("\"default_profile\": \"cpu\"", "\"default_profile\": \"gpu_fail\"").toByteArray() }
        val auto = resolver.inspect(ModelRef("org/model"), FakeChat, LoadOptions())
        assertEquals("gpu", auto.profile.id)
        assertTrue(auto.excludedProfiles.any { it.first == "gpu_fail" && it.second.contains("FAIL") })
        assertEquals("gpu_fail", resolver.inspect(ModelRef("org/model"), FakeChat, LoadOptions(backendPolicy = BackendPolicy.RequireProfile("gpu_fail"))).profile.id)
        assertEquals("gpu_fail", resolver.inspect(ModelRef("org/model"), FakeChat, LoadOptions(backendPolicy = BackendPolicy.Require(BackendKind.GPU))).profile.id)
    }

    @Test fun offlineNeedsABindingAndUsesTheCachedDescriptorWithZeroRequests() {
        hub.repo("org/model", commit) { files["hfmodels.json"] = descriptorFor("org/model").toByteArray(); files["model.litertlm"] = weights }
        try { resolver.inspect(ModelRef("org/model"), FakeChat, LoadOptions(networkPolicy = NetworkPolicy.Offline)); fail() } catch (e: ModelException) { assertEquals(ErrorCode.OFFLINE_CACHE_MISS, e.code) }
        assertTrue(hub.requests.isEmpty())
        val online = resolver.inspect(ModelRef("org/model"), FakeChat, LoadOptions())
        bindings.write(Bindings.Binding("org/model", online.modelOrigin.commit, online.descriptorSha256, "org/model", commit, "hfmodels.json", "now"))
        val n = hub.requests.size
        val offline = resolver.inspect(ModelRef("org/model"), FakeChat, LoadOptions(networkPolicy = NetworkPolicy.Offline))
        assertEquals(n, hub.requests.size)
        assertEquals(BindingSource.SAVED_BINDING, offline.bindingSource)
        assertEquals(online.descriptorSha256, offline.descriptorSha256)
        // An explicit commit also works offline through the descriptor cache.
        assertEquals(n, hub.requests.size.also { resolver.inspect(ModelRef("org/model", revision = commit), FakeChat, LoadOptions(networkPolicy = NetworkPolicy.Offline)) })
    }
}
