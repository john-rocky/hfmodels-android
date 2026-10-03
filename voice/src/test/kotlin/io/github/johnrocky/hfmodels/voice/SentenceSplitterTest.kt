package io.github.johnrocky.hfmodels.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Expected values are what `chunk_text` of the KittenTTS 0.8.1 wheel (kittentts/onnx_model.py) returns for the same input. */
class SentenceSplitterTest {
    @Test fun cutsLikePipAtEveryPeriodIncludingAbbreviationsAndDecimals() {
        assertEquals(listOf("Dr,", "Smith arrived at 7,", "30 p,", "m,", "It rained,"), SentenceSplitter.split("Dr. Smith arrived at 7.30 p.m. It rained."))
    }

    @Test fun dropsTheMarksAndAddsACommaUnlessTheChunkEndsInPunctuation() {
        assertEquals(listOf("Hello,", "How are you,", "Fine; thanks,"), SentenceSplitter.split("Hello! How are you? Fine; thanks"))
        assertEquals(listOf("Wait,", "what,", "OK,"), SentenceSplitter.split("Wait... what?! OK"))
        assertEquals(listOf("Alarm set for seven thirty tomorrow morning,"), SentenceSplitter.split("Alarm set for seven thirty tomorrow morning."))
        assertEquals(listOf("Fine;"), SentenceSplitter.split("Fine;"))
    }

    @Test fun emptyTextGivesNoChunk() {
        assertEquals(emptyList<String>(), SentenceSplitter.split(""))
        assertEquals(emptyList<String>(), SentenceSplitter.split("   "))
        assertEquals(emptyList<String>(), SentenceSplitter.split("..."))
    }

    @Test fun japaneseSentenceEndsCutToo() {
        assertEquals(listOf("こんにちは,", "元気ですか,", "はい,"), SentenceSplitter.split("こんにちは。元気ですか？はい！"))
    }

    @Test fun aLongSentenceIsCutAtSpacesLikePip() {
        val text = (0 until 60).joinToString(" ") { "word%03d".format(it) }
        assertEquals(479, text.length)
        val chunks = SentenceSplitter.split(text)
        // pip: [400, 80] characters, the first ending "word049,"; here the same.
        assertEquals(listOf(400, 80), chunks.map { it.length })
        assertTrue(chunks[0].endsWith("word049,"))
        assertTrue(chunks[1].startsWith("word050 "))
        assertEquals(text, chunks.joinToString(" ") { it.removeSuffix(",") })
    }

    @Test fun theAddedCommaCountsAgainstTheLimit() {
        // pip 0.8.1 returns one chunk of 401 characters here, which Speaker.maxChars (400) refuses.
        val text = ("a".repeat(9) + " ").repeat(40).trim() + "b"
        assertEquals(400, text.length)
        val chunks = SentenceSplitter.split(text)
        assertTrue(chunks.toString(), chunks.all { it.length <= 400 && it.endsWith(",") })
        assertEquals(text, chunks.joinToString(" ") { it.removeSuffix(",") })
    }

    @Test fun aSentenceThatEndsInPunctuationMayUseTheWholeLimit() {
        // No comma is added, so 400 characters fit Speaker.maxChars as they are (pip: the same one chunk).
        val text = ("a".repeat(9) + " ").repeat(39) + "b".repeat(9) + ";"
        assertEquals(400, text.length)
        assertEquals(listOf(text), SentenceSplitter.split(text))
        // One more character and it is cut at a space, each piece within the limit.
        val longer = "c$text"
        assertTrue(SentenceSplitter.split(longer).let { c -> c.size == 2 && c.all { it.length <= 400 } })
    }

    @Test fun aRunWithoutSpacesIsCutAtTheLimit() {
        val chunks = SentenceSplitter.split("x".repeat(450), maxChars = 200)
        assertEquals(listOf(200, 200, 53), chunks.map { it.length })
        assertEquals("x".repeat(450), chunks.joinToString("") { it.removeSuffix(",") })
    }
}
