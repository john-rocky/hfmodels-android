package io.github.johnrocky.hfmodels.litert

import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.descriptor.Descriptor
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * The head budget of every variant this SDK loads, through the function the handler's prepare() calls:
 * laya-LiteRT as published (its hfmodels.json is generated from catalog/specs with the same
 * handler_config), the development descriptor the device gate side-loads (catalog/dev) and the bundled
 * Julia-1 entry. On fe96742 julia's rule sat in the shared path and refused laya's multilingual
 * 256-token variants (head 256 in a 256 window) at load time; the parity tests build sequences without
 * prepare() and stayed green. No model files needed.
 */
class HeadBudgetTest {
    private fun repoFile(path: String) = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, path) }.first { it.isFile }

    /** The publisher's config for a laya variant that declares no head_tokens (`rl_agent_config.json`, convaiinnovations/laya@1c5edc17). */
    private val layaConfig = LayaCalibration.parse("""{"max_len": 512, "head_max_len": 192}""")

    /** variant id -> (window, head budget) */
    private fun budgets(variants: List<Pair<String, JSONObject>>): Map<String, Pair<Int, Int>> = variants.associate { (id, hc) ->
        val family = DecisionFamily.parse(hc.optString("family", "laya"))
        val window = hc.getInt("window")
        id to (window to LiteRtDecisionHandler.headTokens(hc, family, window, if (family == DecisionFamily.LAYA) layaConfig else null))
    }

    @Test fun publishedLayaVariantsKeepTheirHeadBudget() {
        val variants = JSONObject(repoFile("catalog/specs/litert-community__laya-LiteRT.json").readText()).getJSONArray("variants")
        val got = budgets(List(variants.length()) { variants.getJSONObject(it) }.map { it.getString("id") to it.getJSONObject("handler_config") })
        assertEquals(
            mapOf("ml_s256_fp32" to (256 to 256), "ml_s512_fp32" to (512 to 256), "ml_s256_wfp16" to (256 to 256),
                "en_s256_fp32" to (256 to 192), "en_s512_fp32" to (512 to 192), "en_s512_wfp16" to (512 to 192)),
            got,
        )
    }

    @Test fun developmentLayaVariantsKeepTheirHeadBudget() {
        val d = Descriptor.parse(repoFile("catalog/dev/convaiinnovations__laya-litert-dev.hfmodels.json").readText())
        assertEquals(
            mapOf("en_s512_wfp16" to (512 to 192), "en_s256_wfp16" to (256 to 192), "ml_s256_fp32" to (256 to 256), "en_s512_fp32" to (512 to 192),
                "en_s256_fp32" to (256 to 192), "ml_s256_wfp16" to (256 to 256), "ml_s512_fp32" to (512 to 256)),
            budgets(d.variants.map { it.id to it.handlerConfig }),
        )
    }

    @Test fun juliaVariantsTakeTheReferenceHostsBudget() {
        val entry = JSONObject(repoFile("catalog/entries/litert-community__Julia-1-LiteRT.json").readText())
        val d = Descriptor.parse(entry.getJSONObject("descriptor").toString())
        assertEquals(mapOf("s512_fp32" to (512 to 507), "s1024_fp32" to (1024 to 512)), budgets(d.variants.map { it.id to it.handlerConfig }))
    }

    @Test fun aJuliaHeadThatReachesIntoTheFrameIsRefused() {
        fun julia(head: Int) = LiteRtDecisionHandler.headTokens(JSONObject("""{"family": "julia", "window": 512, "head_tokens": $head}"""), DecisionFamily.JULIA, 512, null)
        assertEquals(507, julia(507))
        try {
            julia(508)
            fail("head 508 in a 512 window was accepted")
        } catch (e: ModelException) {
            assertEquals(ErrorCode.MANIFEST_INVALID, e.code)
        }
    }
}
