package io.github.johnrocky.hfmodels.samples.finder

import io.github.johnrocky.hfmodels.decide.Answer
import io.github.johnrocky.hfmodels.decide.Json
import io.github.johnrocky.hfmodels.decide.Question
import org.junit.Assert.assertEquals
import org.junit.Test

class QuestionsTest {
    @Test fun theFiveQuestionsAreTheSievesWordForWord() {
        // The sieve's own file (round 18, PREREG §10 pins its sha256), copied as it is.
        val f = Fixtures.file("src/test/resources/questions_v1.json")
        assertEquals("f3857fba336e94cca5656f5c605fd2ce9cb39811b25c7bb85d485e6120759d88", Fixtures.sha256(f))
        val v1 = Json.parseObject(f.readText())
        @Suppress("UNCHECKED_CAST")
        val f1 = v1["f1"] as Map<String, Map<String, Any?>>
        // Same ids in the same order, each question as its JSON object (type, instructions, criteria with their order).
        assertEquals(f1.keys.toList(), Finder.QUESTIONS.keys.toList())
        for ((id, q) in Finder.QUESTIONS) assertEquals(id, Json.dumps(f1.getValue(id)), Json.dumps(q.toMap()))
        assertEquals(v1["f1_spice_keys"], Finder.SPICE_KEYS)
        // The filters are the questions: one per question id, unset = the question's first key.
        assertEquals(Finder.QUESTIONS.keys.toList(), Facet.values().map { it.id })
        for (facet in Facet.values()) {
            val first = when (val q = Finder.QUESTIONS.getValue(facet.id)) {
                is Question.Choice -> q.criteria.keys.first()
                else -> Finder.SPICE_KEYS.first()
            }
            assertEquals(facet.id, first, facet.unset)
        }
    }

    @Test fun theSieveFilesAreTheOnesItScored() {
        assertEquals("d0f1bb009a2e67fc9438ea52b9495598adc8e9a33d0eb8690de2da1d432cf00f", Fixtures.sha256(Fixtures.file("src/androidTest/assets/f1_filter.jsonl")))
        assertEquals("c5c0f7b68b1befbdf0de407511abb202bc0f482308cc99a1452e6e20cb4fe0a3", Fixtures.sha256(Fixtures.file("src/androidTest/assets/gliner_f1_v1.jsonl")))
        assertEquals(40, Fixtures.sieve.size)
        assertEquals(Fixtures.sieve.keys, Fixtures.mac.keys)
    }

    @Test fun aChoiceIsItsChoiceAndSpiceIsTheMostLikelyLevel() {
        val choice = Answer.Choice("dinner", linkedMapOf("any" to 0.1, "breakfast" to 0.0, "lunch" to 0.1, "dinner" to 0.7, "dessert" to 0.1), 0.5)
        assertEquals("dinner", Finder.key(choice))
        // Levels 0..3 = any, mild, medium, hot; the expected level (1.6 here) is not what the filter reads.
        val spice = Answer.Score(1.6, linkedMapOf("0" to "not mentioned", "1" to "mild", "2" to "medium", "3" to "hot"), linkedMapOf("0" to 0.3, "1" to 0.0, "2" to 0.4, "3" to 0.3), 0.5)
        assertEquals("medium", Finder.key(spice))
        assertEquals(linkedMapOf("any" to 0.3, "mild" to 0.0, "medium" to 0.4, "hot" to 0.3), Finder.probabilities(spice))
        // A tie goes to the first level, as torch.argmax does.
        val tie = Answer.Score(1.5, emptyMap(), linkedMapOf("0" to 0.4, "1" to 0.1, "2" to 0.1, "3" to 0.4), 0.5)
        assertEquals("any", Finder.key(tie))
    }

    @Test fun theMacHostsAnswersReadAsFilters() {
        // The Mac host's spice answer is the most likely level, its other answers the choices: what Finder.key gives.
        for ((id, row) in Fixtures.mac) {
            @Suppress("UNCHECKED_CAST")
            val p = (row["probabilities"] as Map<String, Map<String, Double>>).getValue("spice")
            val score = Answer.Score(0.0, emptyMap(), p, 0.0)
            assertEquals(id, Fixtures.macAnswers(id)[Facet.SPICE], Finder.key(score))
        }
    }

    @Test fun chipsShowTheWordsTheModelRead() {
        assertEquals("under an hour", Finder.chipText(Facet.TIME, "under_60"))
        assertEquals("under 15 minutes", Finder.chipText(Facet.TIME, "under_15"))
        assertEquals("any", Finder.chipText(Facet.TIME, "any"))
        assertEquals("nuts", Finder.chipText(Facet.WITHOUT, "nuts"))
        assertEquals("nothing", Finder.chipText(Facet.WITHOUT, "nothing"))
        assertEquals("medium", Finder.chipText(Facet.SPICE, "medium"))
    }
}
