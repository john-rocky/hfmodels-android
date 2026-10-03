package io.github.johnrocky.hfmodels.litert

import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.ModelException
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The kitten family's host side: voices.npz, the G2P's normalization and symbol mapping, the speed the graph gets, the row repeat and the trim. */
class KittenHostTest {
    /** voices_small.npz: np.savez of voice-a = arange(12).reshape(3, 4) * 0.5 - 1 and voice-b = arange(12).reshape(3, 4) / -8 + 0.25, float32. */
    @Test fun npzVoicesReadsWhatNumpyWrote() {
        val f = File.createTempFile("voices_small", ".npz").apply { deleteOnExit() }
        f.writeBytes(javaClass.classLoader!!.getResourceAsStream("kitten/voices_small.npz")!!.use { it.readBytes() })
        val v = NpzVoices.read(f)
        assertEquals(listOf("voice-a", "voice-b"), v.names)
        val a = v["voice-a"]!!
        assertEquals(3, a.rows)
        assertEquals(4, a.dim)
        assertArrayEquals(FloatArray(12) { it * 0.5f - 1f }, a.data, 0f)
        assertArrayEquals(floatArrayOf(-0.25f, -0.375f, -0.5f, -0.625f), v["voice-b"]!!.row(1), 0f)
    }

    @Test fun npzVoicesRefusesAnotherDtype() {
        val header = "{'descr': '<f8', 'fortran_order': False, 'shape': (1, 2), }".padEnd(117) + "\n"
        val bytes = byteArrayOf(0x93.toByte()) + "NUMPY".toByteArray() + byteArrayOf(1, 0, header.length.toByte(), 0) + header.toByteArray() + ByteArray(16)
        try {
            NpzVoices.parseNpy(bytes, "x.npy")
            fail("expected a refusal")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!, e.message!!.contains("'<f8'"))
        }
    }

    @Test fun numbersAreReadAsWords() {
        assertEquals(listOf("four", "thousand", "ninety"), KittenG2P.numberToWords("4090"))
        assertEquals(listOf("one", "thousand", "two", "hundred", "thirty", "four", "point", "five"), KittenG2P.numberToWords("1,234.5"))
        assertEquals(listOf("seven", "point", "three", "zero"), KittenG2P.numberToWords("7.30"))
        assertEquals(listOf("zero"), KittenG2P.numberToWords("0"))
    }

    private val dictionary = mapOf(
        "alarm" to "ɐlˈɑːɹm", "set" to "sˈɛt", "for" to "fɔːɹ", "seven" to "sˈɛvən", "four" to "fˈoːɹ", "thousand" to "θˈaʊzənd", "ninety" to "nˈaɪnti",
    )

    @Test fun wordsNumbersAcronymsAndPunctuationBecomeSpaceSeparatedTokens() {
        val oov = ArrayList<String>()
        val g2p = KittenG2P(dictionary, emptyMap()) { oov += it; "" }
        // Capitals are looked up in lower case; a run of two or more capitals is spelled; punctuation stands alone.
        assertEquals("ɐlˈɑːɹm sˈɛt fɔːɹ sˈɛvən , dʒˈiːpˈiːjˈuː !", g2p.ipa("Alarm set for seven, GPU!"))
        // The sample's number token takes a comma right after the digits (thousands separators), so "7," loses its comma.
        assertEquals("sˈɛvən fˈoːɹ θˈaʊzənd nˈaɪnti", g2p.ipa("7, 4090"))
        assertEquals(emptyList<String>(), oov)
    }

    @Test fun wordsTheDictionaryLacksGoToTheGraphInLowerCase() {
        val oov = ArrayList<String>()
        val g2p = KittenG2P(dictionary, emptyMap()) { oov += it; "<$it>" }
        assertEquals("sˈɛt <zorblax> .", g2p.ipa("set Zorblax."))
        // A mixed-case word is a word, not an acronym (the sample's tokenizer): "LiteRT" -> "litert".
        g2p.ipa("LiteRT")
        assertEquals(listOf("zorblax", "litert"), oov)
    }

    @Test fun theWordIIsItsLetterNameNotAGraphLookup() {
        val oov = ArrayList<String>()
        // The dictionary has no "i" (g2p_dict.txt.gz of Matcha-TTS@8d650e79): without the rule it would go to the graph.
        val g2p = KittenG2P(dictionary, emptyMap()) { oov += it; "<$it>" }
        assertEquals("ˈaɪ sˈɛt , ˈaɪ .", g2p.ipa("I set, I."))
        assertEquals(emptyList<String>(), oov)
    }

    @Test fun speedTimesThePriorIsWhatTheGraphGets() {
        // say.py: speed * SPEED_PRIORS[voice] in double, float32 into the graph.
        assertEquals(1.0f, LiteRtSpeaker.graphSpeed(1.25f, 0.8), 0f)
        assertEquals(0.8f, LiteRtSpeaker.graphSpeed(1f, 0.8), 0f)
        assertEquals(0.9f, LiteRtSpeaker.graphSpeed(1f, 0.9), 0f)
        assertEquals(1.5f, LiteRtSpeaker.graphSpeed(1.5f, 1.0), 0f)
    }

    @Test fun symbolIdsKeepTheLastIndexOfADuplicateAndDropUnknownCharacters() {
        val symbols = KittenG2P.readSymbols("""{"symbols": ["_", ",", " ", "a", "'", "b", "'"], "${'$'}comment": "x"}""")
        assertEquals(mapOf('_' to 0, ',' to 1, ' ' to 2, 'a' to 3, '\'' to 6, 'b' to 5), symbols)
        val g2p = KittenG2P(mapOf("ab" to "ab'q"), symbols) { "" }
        // "ab'q" -> a b ' (q is not in the table), then " ", ","; the 0 at each end is the model's.
        assertArrayEquals(intArrayOf(0, 3, 5, 6, 2, 1, 0), g2p.ids("ab,"))
    }

    @Test fun rowsRepeatByTheirDurations() {
        val x = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f) // [1, 3, 2]
        assertArrayEquals(floatArrayOf(1f, 2f, 1f, 2f, 5f, 6f), KittenSynthesizer.repeatRows(x, intArrayOf(2, 0, 1), 2), 0f)
        assertEquals(0, KittenSynthesizer.repeatRows(x, intArrayOf(0, 0, 0), 2).size)
    }

    @Test fun theEndIsTrimmedLikePipAndClamped() {
        assertEquals(2000, KittenSynthesizer.trim(FloatArray(7000), 5000, 1200).size)
        assertEquals(1200, KittenSynthesizer.trim(FloatArray(3000), 5000, 1200).size)
        assertEquals(1000, KittenSynthesizer.trim(FloatArray(1000), 5000, 1200).size)
        assertArrayEquals(floatArrayOf(1f, -1f, 0.5f), KittenSynthesizer.trim(floatArrayOf(1.5f, -2f, 0.5f, 9f), 1, 0), 0f)
    }

    @Test fun inputNamesWithAndWithoutTheSignaturePrefixFeedTheSameWay() {
        assertEquals("input_ids", KittenSynthesizer.canonical("serving_default_input_ids:0"))
        assertEquals("asr", KittenSynthesizer.canonical("asr"))
    }

    @Test fun emptyOrTooLongTextIsInvalidInput() {
        for (text in listOf("", "  ", "a".repeat(401))) {
            try {
                LiteRtSpeaker.checkText(text, 400)
                fail("expected INVALID_INPUT for ${text.length} characters")
            } catch (e: ModelException) {
                assertEquals(ErrorCode.INVALID_INPUT, e.code)
            }
        }
        // Code points, as Python's len: one emoji is one character.
        assertEquals(400, LiteRtSpeaker.checkText("😀" + "a".repeat(399), 400))
    }
}
