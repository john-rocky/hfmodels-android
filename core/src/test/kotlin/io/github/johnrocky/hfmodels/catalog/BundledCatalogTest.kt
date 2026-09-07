package io.github.johnrocky.hfmodels.catalog

import io.github.johnrocky.hfmodels.InputKind
import java.io.File
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
            assertTrue(v.runtimeRange.contains("0.16.1"))
            assertTrue(v.profile(v.defaultProfile)!!.enabledInputs.contains(InputKind.TEXT))
        }
        assertTrue(c.find("litert-community/gemma-4-E2B-it-litert-lm", "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1") != null)
        assertTrue(c.defaultBinding("litert-community/Qwen2.5-1.5B-Instruct") != null)
        assertTrue(c.find("litert-community/LFM2.5-VL-1.6B", "a2178688655510ef6c3043ce4bdfc2ce9a917689")!!.descriptor.variant("int4")!!.inputs.contains(InputKind.IMAGE))
    }
}
