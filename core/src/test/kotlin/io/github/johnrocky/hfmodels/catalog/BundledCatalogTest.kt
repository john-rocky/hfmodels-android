package io.github.johnrocky.hfmodels.catalog

import io.github.johnrocky.hfmodels.InputKind
import io.github.johnrocky.hfmodels.descriptor.Descriptor
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The asset the AAR ships must parse with the strict reader, and its entries must be self-consistent. */
class BundledCatalogTest {
    private val asset = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "core/src/main/assets/hfmodels/catalog.json") }.first { it.isFile }

    @Test fun bundledCatalogParsesAndPinsCommits() {
        val c = Catalog.parse(asset.readText())
        assertEquals("hfmodels-bundled", c.id)
        assertTrue(c.entries.size >= 5)
        for (e in c.entries) {
            assertEquals(40, e.modelCommit.length)
            assertEquals(e.modelId, e.descriptor.modelId)
            assertEquals("john-rocky/hfmodels-android", e.origin.repo)
            val v = e.descriptor.variant(null)!!
            assertTrue(v.files.all { it.bytes > 0 && it.sha256.length == 64 })
            // Each entry is checked against the version its runtime is pinned to (gradle.properties).
            assertTrue(v.runtimeRange.contains(mapOf("litert_lm" to "0.16.1", "litert" to "2.2.0").getValue(v.runtime)))
            // A transcriber reads audio; every other task reads text.
            assertTrue(v.profile(v.defaultProfile)!!.enabledInputs.contains(if ("transcribe" in e.descriptor.tasks) InputKind.AUDIO else InputKind.TEXT))
        }
        assertTrue(c.find("litert-community/gemma-4-E2B-it-litert-lm", "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1") != null)
        assertTrue(c.defaultBinding("litert-community/Qwen2.5-1.5B-Instruct") != null)
        assertTrue(c.find("litert-community/LFM2.5-VL-1.6B", "a2178688655510ef6c3043ce4bdfc2ce9a917689")!!.descriptor.variant("int4")!!.inputs.contains(InputKind.IMAGE))
    }

    /** The speech repos carry hfmodels.json at the commits these entries pin, and the entries are those descriptors as published. */
    @Test fun speechEntriesPinTheCommitsTheirReposPublishHfmodelsJsonAt() {
        val c = Catalog.parse(asset.readText())
        val asr = c.defaultBinding("litert-community/Zipformer-medium-CR-CTC-LiteRT")!!
        assertEquals("fa063a88d8191b289e70686bf814f0b52f9144b2", asr.modelCommit)
        assertEquals(listOf("transcribe"), asr.descriptor.tasks)
        assertEquals(listOf("small_fp16", "medium_fp16"), asr.descriptor.variants.map { it.id })
        assertEquals("medium_fp16", asr.descriptor.defaultVariant)
        assertEquals("gpu", asr.descriptor.variant(null)!!.defaultProfile)
        val tts = c.defaultBinding("litert-community/kitten-tts-nano-0.8")!!
        assertEquals("2c5b198f36e0b40a098585da7601bd752ff432d5", tts.modelCommit)
        assertEquals(listOf("speak"), tts.descriptor.tasks)
        assertEquals(listOf("fp32", "fp16"), tts.descriptor.variants.map { it.id })
        assertEquals("fp32", tts.descriptor.defaultVariant)
        assertEquals("cpu", tts.descriptor.variant(null)!!.defaultProfile)
        // The G2P files are in the repo at this commit, so a load downloads them like the graphs.
        for (v in tts.descriptor.variants) assertTrue(v.files.map { it.path }.containsAll(listOf("g2p/dp_g2p_matcha_fp16.tflite", "g2p/g2p_dict.txt.gz", "g2p/g2p_meta.json", "g2p/symbols.json")))
        for (e in listOf(asr, tts)) {
            // No load of these commits has run on a device, so no profile carries a verification record.
            assertTrue(e.descriptor.variants.all { v -> v.profiles.all { it.verification.isEmpty() } })
            // The repos' descriptors omit fallback_profiles on the cpu profiles and verification everywhere; they read the same
            // as with the keys spelled at the defaults tools/hfmodels_descriptor.py compares with (PROFILE_DEFAULTS).
            val spelled = JSONObject(e.descriptorJson)
            val variants = spelled.getJSONArray("variants")
            for (i in 0 until variants.length()) {
                val profiles = variants.getJSONObject(i).getJSONArray("profiles")
                for (j in 0 until profiles.length()) profiles.getJSONObject(j).apply {
                    if (!has("priority")) put("priority", 0)
                    if (!has("fallback_profiles")) put("fallback_profiles", JSONArray())
                    if (!has("default_selectable")) put("default_selectable", true)
                    if (!has("verification")) put("verification", JSONArray())
                }
            }
            assertEquals(e.descriptor.variants.map { it.profiles }, Descriptor.parse(spelled.toString(), e.modelId).variants.map { it.profiles })
        }
    }
}
