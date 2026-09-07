package io.github.johnrocky.hfmodels.litertlm

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The header reader against the first bytes of four published bundles (everything up to the end of
 * the LlmMetadata section, 16-20 KB each; the weights are not needed because the section table
 * puts the metadata before them). Expected values were read with the LiteRT-LM builder's own
 * FlatBuffer / protobuf classes on 2026-09-08.
 */
class LitertlmBundleTest {
    private fun fixture(name: String): File {
        val f = File(javaClass.classLoader!!.getResource(name)!!.toURI())
        assertTrue(f.isFile)
        return f
    }

    @Test fun lfm25ThinkingDeclaresAChannelOnTheJinjaPath() {
        val b = LitertlmBundle.read(fixture("lfm25-1.2b-thinking_int4_gpu.litertlm.head"))
        assertEquals(listOf("LlmMetadataProto", "ExecutorMetadataProto", "HF_Tokenizer_Zlib", "TFLiteModel"), b.sections.map { it.typeName })
        assertEquals(16384L, b.sections[0].begin); assertEquals(18240L, b.sections[0].end)
        assertEquals(736220768L, b.sections[3].end)
        assertEquals(1, b.channels.size)
        assertEquals(LitertlmBundle.BundleChannel("thought", "<think>", "</think>", null), b.channels[0])
        assertEquals("", b.modelPrefix); assertEquals("", b.modelSuffix)
        assertTrue(b.jinjaTemplate!!.contains("<|im_start|>assistant"))
    }

    @Test fun deepSeekStructuredPrefixPreOpensThinkWithoutAChannel() {
        val b = LitertlmBundle.read(fixture("deepseek-r1-distill-qwen-1.5b_q8.litertlm.head"))
        assertEquals(listOf("LlmMetadataProto", "HF_Tokenizer_Zlib", "TFLiteModel"), b.sections.map { it.typeName })
        assertTrue(b.channels.isEmpty())
        assertEquals("<｜Assistant｜><think>\n", b.modelPrefix)
        assertEquals("", b.modelSuffix)
        assertNull(b.jinjaTemplate)
    }

    @Test fun qwen3ChannelKeepsItsNewlines() {
        val b = LitertlmBundle.read(fixture("qwen3-0.6b_dynamic_wi4b32_afp32.litertlm.head"))
        assertEquals(listOf(LitertlmBundle.BundleChannel("thought", "<think>\n", "\n</think>", null)), b.channels)
        assertEquals(344437808L, b.sections.last().end)
    }

    @Test fun qwen35DeclaresNothingAndItsTemplateClosesThink() {
        val b = LitertlmBundle.read(fixture("qwen3.5-0.8b_int8.litertlm.head"))
        assertTrue(b.channels.isEmpty())
        assertEquals("", b.modelPrefix)
        assertTrue(b.jinjaTemplate!!.trimEnd().endsWith("<think>\n\n</think>\n\n{% endif -%}"))
    }

    @Test fun notABundleIsAnIOException() {
        val f = File.createTempFile("not-a-bundle", ".litertlm").apply { writeBytes(ByteArray(64) { 1 }); deleteOnExit() }
        try { LitertlmBundle.read(f); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("bad magic")) }
    }

    @Test fun openChannelFollowsTheRuntimeRule() {
        val think = listOf(ChannelMarkers("thought", "<think>\n", "\n</think>"))
        assertEquals("thought", openChannelName("<｜User｜>hi<｜Assistant｜><think>\n", think))
        assertNull(openChannelName("<|im_start|>assistant\n<think>\n\n</think>\n\n", think))
        assertNull(openChannelName("<|im_start|>assistant\n", think))
        assertNull(openChannelName("anything", emptyList()))
    }
}
