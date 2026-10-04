package io.github.johnrocky.hfmodels.samples.promises

import io.github.johnrocky.hfmodels.BackendKind
import io.github.johnrocky.hfmodels.BackendPolicy
import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.decide.Json
import io.github.johnrocky.hfmodels.samples.promises.DecisionModels.Choice
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionModelsTest {
    @Test fun withoutTheRuntimeTheAppLoadsAsBefore() {
        // The published variant on the descriptor's default profile, no NPU attempt and no fallback of the app's own.
        assertEquals(Choice("s128_wfp16", BackendPolicy.Auto), DecisionModels.choose(npuRuntime = false, backend = null))
        assertEquals(Choice("s128_wfp16", BackendPolicy.Auto), DecisionModels.choose(npuRuntime = false, backend = "other"))
    }

    @Test fun withTheRuntimeTheNpuComesFirstThenTheSameVariantOnItsDefaultProfile() {
        val c = DecisionModels.choose(npuRuntime = true, backend = null)
        assertEquals(Choice("s128_npu_wfp16", BackendPolicy.Require(BackendKind.NPU), fallback = Choice("s128_npu_wfp16", BackendPolicy.Auto)), c)
        assertEquals(c, DecisionModels.choose(npuRuntime = true, backend = "other"))
    }

    @Test fun theBackendExtraFixesTheBackendWithoutAFallback() {
        for (runtime in listOf(false, true)) {
            assertEquals(Choice("s128_npu_wfp16", BackendPolicy.Require(BackendKind.NPU)), DecisionModels.choose(runtime, "npu"))
            assertEquals(Choice("s128_wfp16", BackendPolicy.Require(BackendKind.GPU)), DecisionModels.choose(runtime, "gpu"))
            assertEquals(Choice("s128_wfp16", BackendPolicy.Require(BackendKind.CPU)), DecisionModels.choose(runtime, "cpu"))
        }
    }

    @Test fun onlyBackendErrorsFallBack() {
        for (code in listOf(ErrorCode.NATIVE_MODULE_MISSING, ErrorCode.UNSUPPORTED_CONFIGURATION, ErrorCode.INITIALIZATION_FAILED)) {
            assertTrue(code.name, code in DecisionModels.BACKEND_ERRORS)
        }
        // A file that is missing, a transfer or a busy slot fails the same way on any backend: no second load.
        for (code in listOf(ErrorCode.OFFLINE_CACHE_MISS, ErrorCode.NETWORK_ERROR, ErrorCode.MODEL_NOT_FOUND_OR_INACCESSIBLE, ErrorCode.CHECKSUM_MISMATCH, ErrorCode.STORAGE_FULL, ErrorCode.MODEL_BUSY, ErrorCode.MODEL_CLOSED)) {
            assertFalse(code.name, code in DecisionModels.BACKEND_ERRORS)
        }
    }

    @Test fun theAssetDescriptorGivesTheFallbackTheSameFilesAndThePublishedVariantNoNpu() {
        val f = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "catalog/dev/${DecisionModels.DESCRIPTOR_ASSET}") }.first { it.isFile }
        @Suppress("UNCHECKED_CAST")
        val variants = (Json.parseObject(f.readText())["variants"] as List<Map<String, Any?>>).associateBy { it["id"] as String }
        @Suppress("UNCHECKED_CAST")
        fun profiles(v: Map<String, Any?>) = (v["profiles"] as List<Map<String, Any?>>).associateBy { it["id"] as String }
        @Suppress("UNCHECKED_CAST")
        fun files(v: Map<String, Any?>) = (v["files"] as List<Map<String, Any?>>).associateBy { it["id"] as String }

        // The published variant: GPU by default, then the CPU; no NPU profile.
        val published = variants.getValue(DecisionModels.VARIANT)
        assertEquals("gpu", published["default_profile"])
        assertEquals(setOf("gpu", "cpu"), profiles(published).keys)
        assertEquals(listOf("cpu"), profiles(published).getValue("gpu")["fallback_profiles"])

        // The NPU variant: an npu profile, and the GPU by default (what Auto opens after a failed NPU load).
        val npu = variants.getValue(DecisionModels.NPU_VARIANT)
        assertEquals("gpu", npu["default_profile"])
        assertEquals(setOf("npu", "gpu", "cpu"), profiles(npu).keys)
        assertEquals(listOf("cpu"), profiles(npu).getValue("gpu")["fallback_profiles"])
        assertEquals("gliner25_decide_s128_npu_wfp16.tflite", files(npu).getValue("main")["path"])
        // ... the file the bundled catalog pins for the same variant (path, bytes, sha256).
        val entry = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "catalog/entries/litert-community__GLiNER2.5-Decide-LiteRT.json") }.first { it.isFile }
        @Suppress("UNCHECKED_CAST")
        val pinned = ((Json.parseObject(entry.readText())["descriptor"] as Map<String, Any?>)["variants"] as List<Map<String, Any?>>).single { it["id"] == DecisionModels.NPU_VARIANT }
        assertEquals(files(pinned).getValue("main"), files(npu).getValue("main"))
        // Every profile of the NPU variant reads the same three files: the fallback downloads nothing more.
        for (p in profiles(npu).values) assertEquals(listOf("main", "table", "tokenizer"), p["files"])
        // Its token table and tokenizer are the published variant's files (same sha256: the store keeps one copy).
        for (id in listOf("table", "tokenizer")) assertEquals(files(published).getValue(id), files(npu).getValue(id))
    }
}
