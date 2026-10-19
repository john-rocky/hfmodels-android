package io.github.johnrocky.hfmodels.samples.translate

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Token ids to text. The parity test reads the model's tokenizer.json from `-Dtranslate.parakeetRoot` (or
 * TRANSLATE_PARAKEET_ROOT: the dir holding `parakeet_tokenizer.json`, nvidia/parakeet-tdt-0.6b-v3 @ 541d1f99) and the
 * Mac host's ids and texts from `-Dtranslate.fixturesRoot` (`fixtures/frontend/<id>.ids.txt`,
 * `mac_preview_data/asr_30s.tsv`); without them it is skipped and says so.
 */
class VocabTest {
    private val tiny = """
        {"added_tokens": [
           {"id": 0, "content": "<unk>", "special": true}, {"id": 1, "content": "<|nospeech|>", "special": false},
           {"id": 2, "content": "<pad>", "special": true}, {"id": 5, "content": "2", "special": false},
           {"id": 6, "content": "3", "special": false}, {"id": 12, "content": "<blank>", "special": true}],
         "model": {"type": "BPE", "vocab": {"<unk>": 0, "<|nospeech|>": 1, "<pad>": 2, "▁S": 3, "on": 4, "2": 5, "3": 6, "▁": 7,
           "▁e": 8, "ur": 9, "os": 10, ".": 11}}}
    """.trimIndent()

    @Test fun piecesJoinWithTheMetaspaceAsASpaceAndDigitsStay() {
        val v = ParakeetVocab.parse(tiny)
        assertEquals(13, v.size)
        assertEquals("Son 23 euros.", v.text(intArrayOf(3, 4, 7, 5, 6, 8, 9, 10, 11)))
    }

    @Test fun specialAndControlTokensAreSkipped() {
        val v = ParakeetVocab.parse(tiny)
        assertEquals("Son 23 euros.", v.text(intArrayOf(0, 1, 3, 4, 2, 7, 5, 6, 12, 8, 9, 10, 11, 99, -1)))
        assertEquals("", v.text(intArrayOf(0, 1, 2)))
    }

    @Test fun theModelsVocabularyGivesThePythonHostsTexts() {
        val models = System.getProperty("translate.parakeetRoot").orEmpty()
        val fixtures = System.getProperty("translate.fixturesRoot").orEmpty()
        assumeTrue("translate.parakeetRoot / translate.fixturesRoot not set: the vocabulary parity is skipped", models.isNotEmpty() && fixtures.isNotEmpty())
        val v = ParakeetVocab.parse(File(models, "parakeet_tokenizer.json").readText())
        assertEquals(8193, v.size)
        val expected = File(fixtures, "mac_preview_data/asr_30s.tsv").readLines().filter { it.isNotBlank() }.associate { it.substringBefore('\t') to it.substringAfter('\t') }
        val dir = File(fixtures, "fixtures/frontend")
        var n = 0
        for (f in dir.listFiles()!!.filter { it.name.endsWith(".ids.txt") }.sortedBy { it.name }) {
            val id = f.name.removeSuffix(".ids.txt")
            val ids = f.readText().trim().split(' ').filter { it.isNotEmpty() }.map { it.toInt() }.toIntArray()
            val want = expected[id] ?: continue
            // The host's text is its pieces joined; the long file's is the first piece of one 30 s utterance.
            assertEquals(id, want, v.text(ids))
            n++
        }
        println("vocabulary parity: $n utterances equal to the Python host's text")
        assertEquals(12, n)
    }
}
