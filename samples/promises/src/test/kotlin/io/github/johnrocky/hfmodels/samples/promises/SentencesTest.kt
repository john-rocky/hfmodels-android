package io.github.johnrocky.hfmodels.samples.promises

import org.junit.Assert.assertEquals
import org.junit.Test

class SentencesTest {
    @Test fun theSampleIsSixteenSentencesWithTheirSenders() {
        val s = Sentences.split(SampleChat.TEXT)
        assertEquals(SampleChat.LINES.map { Sentence(it.sender, it.text) }, s)
        assertEquals(mapOf("nothing" to 5, "promise" to 4, "request" to 4, "plan" to 3), SampleChat.LINES.groupingBy { it.label }.eachCount())
        // The bundle titles read right: every promise is the owner's, every request the other side's.
        assertEquals(setOf("You"), SampleChat.LINES.filter { it.label == "promise" }.map { it.sender }.toSet())
        assertEquals(setOf("Them"), SampleChat.LINES.filter { it.label == "request" }.map { it.sender }.toSet())
    }

    @Test fun linesNamesAndSentenceEnds() {
        val s = Sentences.split("Them: Sure. Can you come? Great!\nLet's meet at the station at 6:30 tonight.\n\n  [10:01, 03/10/2026] My sister: ok  ")
        assertEquals(
            listOf(
                Sentence("Them", "Sure."), Sentence("Them", "Can you come?"), Sentence("Them", "Great!"),
                Sentence(null, "Let's meet at the station at 6:30 tonight."), Sentence("My sister", "ok"),
            ),
            s,
        )
    }

    @Test fun aColonAfterMoreThanThreeWordsIsPartOfTheSentence() {
        assertEquals(listOf(Sentence(null, "Here is what I think: fine.")), Sentences.split("Here is what I think: fine."))
        assertEquals(listOf(Sentence(null, "The link is https://example.com/x today.")), Sentences.split("The link is https://example.com/x today."))
    }
}
