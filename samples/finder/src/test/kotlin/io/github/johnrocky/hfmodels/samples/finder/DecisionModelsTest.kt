package io.github.johnrocky.hfmodels.samples.finder

import io.github.johnrocky.hfmodels.BackendKind
import io.github.johnrocky.hfmodels.BackendPolicy
import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.decide.Json
import io.github.johnrocky.hfmodels.samples.finder.DecisionModels.Choice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionModelsTest {
    @Test fun withoutTheRuntimeTheAppLoadsTheDefaultVariant() {
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
        for (code in listOf(ErrorCode.OFFLINE_CACHE_MISS, ErrorCode.NETWORK_ERROR, ErrorCode.MODEL_NOT_FOUND_OR_INACCESSIBLE, ErrorCode.CHECKSUM_MISMATCH, ErrorCode.STORAGE_FULL, ErrorCode.MODEL_BUSY, ErrorCode.MODEL_CLOSED)) {
            assertFalse(code.name, code in DecisionModels.BACKEND_ERRORS)
        }
    }

    /**
     * The id resolves to the repo's own hfmodels.json (catalog/proposals holds the bytes the repo carries from f6f6e9c9)
     * or, when a branch head carries none, to the bundled catalog's copy (catalog/entries, pinned at 310090c3). Both name
     * the two variants the app asks for, with the profiles [DecisionModels.choose] relies on.
     */
    @Test fun bothDescriptorsTheIdCanResolveToCarryTheVariantsTheAppNames() {
        val repo = Json.parseObject(Fixtures.file("catalog/proposals/litert-community__GLiNER2.5-Decide-LiteRT.hfmodels.json").readText())
        @Suppress("UNCHECKED_CAST")
        val catalog = Json.parseObject(Fixtures.file("catalog/entries/litert-community__GLiNER2.5-Decide-LiteRT.json").readText())["descriptor"] as Map<String, Any?>
        for (d in listOf(repo, catalog)) {
            assertEquals(DecisionModels.REPO, d["model_id"])
            assertEquals(DecisionModels.VARIANT, d["default_variant"])
            @Suppress("UNCHECKED_CAST")
            val variants = (d["variants"] as List<Map<String, Any?>>).associateBy { it["id"] as String }
            @Suppress("UNCHECKED_CAST")
            fun profiles(v: Map<String, Any?>) = (v["profiles"] as List<Map<String, Any?>>).map { it["id"] as String }
            assertEquals(listOf("gpu", "cpu"), profiles(variants.getValue(DecisionModels.VARIANT)))
            assertEquals("gpu", variants.getValue(DecisionModels.VARIANT)["default_profile"])
            // The NPU variant: an npu profile, and the GPU by default (what Auto opens after a failed NPU load).
            assertEquals(listOf("npu", "gpu", "cpu"), profiles(variants.getValue(DecisionModels.NPU_VARIANT)))
            assertEquals("gpu", variants.getValue(DecisionModels.NPU_VARIANT)["default_profile"])
            @Suppress("UNCHECKED_CAST")
            val hc = variants.getValue(DecisionModels.NPU_VARIANT)["handler_config"] as Map<String, Any?>
            // One question per forward, the sieve's form: no packing.
            assertFalse(hc["pack_questions"] == true)
            assertEquals(128L, hc["window"])
        }
    }
}
