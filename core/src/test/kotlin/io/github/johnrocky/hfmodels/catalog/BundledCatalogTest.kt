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

    /** The entry tools/hfmodels_descriptor.py wrote for [modelId] (catalog/entries/), which tools/build_catalog.py bundles. */
    private fun entry(modelId: String) = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "catalog/entries/${modelId.replace("/", "__")}.json") }.first { it.isFile }

    /** Every profile object of a descriptor's JSON. */
    private fun JSONObject.profiles(): List<JSONObject> {
        val variants = getJSONArray("variants")
        return (0 until variants.length()).flatMap { i -> variants.getJSONObject(i).getJSONArray("profiles").let { ps -> (0 until ps.length()).map { ps.getJSONObject(it) } } }
    }

    /** A JSON value as maps, lists and scalars, so two documents compare with == whatever their key order. */
    private fun plain(x: Any?): Any? = when (x) {
        is JSONObject -> x.keys().asSequence().associateWith { plain(x.get(it)) }
        is JSONArray -> (0 until x.length()).map { plain(x.get(it)) }
        else -> x
    }

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

    /** The speech repos carry hfmodels.json at the commits these entries pin; the entries are those descriptors as published, plus the device gate's records. */
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
            // The 2026-10-05 gate passed every profile at these commits on a Galaxy S26 (verification/2026-10-05-…-voice.json),
            // and tools/build_catalog.py merges those runs into the asset: each profile carries its PASS record.
            for (v in e.descriptor.variants) for (p in v.profiles) assertTrue(
                "${e.modelId} ${v.id}/${p.id}: no PASS record of the 2026-10-05 gate",
                p.verification.any { it.result == "PASS" && it.date == "2026-10-05" && it.device == "SM-S942Q (Galaxy S26)" && it.runtime == "litert 2.2.0" },
            )
            // The repos' descriptors carry no record. Without the records the asset's descriptor is the entry's, the repo's
            // hfmodels.json as published (tools/check_catalog_pins.py compares the entry with the Hub's file).
            val published = JSONObject(e.descriptorJson).apply { profiles().forEach { it.remove("verification") } }
            assertEquals(e.modelId, plain(JSONObject(entry(e.modelId).readText()).getJSONObject("descriptor")), plain(published))
            // They omit fallback_profiles on the cpu profiles and verification everywhere; they read the same as with the keys
            // spelled at the defaults tools/hfmodels_descriptor.py compares with (PROFILE_DEFAULTS).
            val spelled = JSONObject(published.toString()).apply {
                for (p in profiles()) p.apply {
                    if (!has("priority")) put("priority", 0)
                    if (!has("fallback_profiles")) put("fallback_profiles", JSONArray())
                    if (!has("default_selectable")) put("default_selectable", true)
                    if (!has("verification")) put("verification", JSONArray())
                }
            }
            assertEquals(Descriptor.parse(published.toString(), e.modelId).variants.map { it.profiles }, Descriptor.parse(spelled.toString(), e.modelId).variants.map { it.profiles })
        }
    }
}
