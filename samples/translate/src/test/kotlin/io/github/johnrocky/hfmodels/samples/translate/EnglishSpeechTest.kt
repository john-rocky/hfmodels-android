package io.github.johnrocky.hfmodels.samples.translate

import io.github.johnrocky.hfmodels.voice.SentenceSplitter
import org.junit.Assert.assertEquals
import org.junit.Test

/** The English as the speaker gets it: the rewrite, the number words, and the cut of the streamed translation. */
class EnglishSpeechTest {
    @Test fun rewritesWhatTheSpeakerCannotSay() {
        val cases = listOf(
            // Hy-MT2's own output on 2026-10-19 (Mac, e03): curly apostrophes.
            "I’m sorry, we don’t accept credit cards here." to "I'm sorry, we don't accept credit cards here.",
            "The train leaves at 3:15 p.m." to "The train leaves at three fifteen PM.",
            "Meet me at 9 a.m. tomorrow." to "Meet me at nine AM tomorrow.",
            "It opens at 7am and closes at 11 PM." to "It opens at seven AM and closes at eleven PM.",
            "The train to Toledo leaves Platform 4 at 3:15, so you have time for a coffee." to "The train to Toledo leaves Platform 4 at three fifteen, so you have time for a coffee.",
            "We have a reservation for 9:00." to "We have a reservation for nine o'clock.",
            "It leaves at 10:05." to "It leaves at ten oh five.",
            "That's €23.50 in total." to "That's 23 euros 50 in total.",
            "Son 23,50 € en total." to "Son 23 euros 50 en total.",
            "It costs €1,500." to "It costs 1500 euros.",
            "Only €1." to "Only 1 euro.",
            "That is 2.000 € or $5 or £3.20." to "That is 2000 euros or 5 dollars or 3 pounds 20.",
            "A 20% discount." to "A 20 percent discount.",
            "On the 21st, the 2nd floor, the 12th day, the 40th time." to "On the twenty first, the second floor, the twelfth day, the fortieth time.",
            "It weighs 2.5 kilos." to "It weighs 2 point 5 kilos.",
            "Mr. Smith met Dr. Jones on St. Mary's Road near Main St." to "Mister Smith met Doctor Jones on Saint Mary's Road near Main Street.",
            "Go to platform No. 4, e.g. the one on the left." to "Go to platform number 4, for example the one on the left.",
            "The Andrés hotel in España." to "The Andres hotel in Espana.",
            "Fish & chips" to "Fish and chips",
            "Do you want a bag?" to "Do you want a bag?",
        )
        for ((input, want) in cases) assertEquals(input, want, EnglishSpeech.rewrite(input))
    }

    @Test fun aRewrittenTimeStaysOneChunkForTheSpeaker() {
        // Without the rewrite SentenceSplitter cuts "p.m." into "p," and "m,".
        assertEquals(listOf("The train leaves at 3:15 p,", "m,"), SentenceSplitter.split("The train leaves at 3:15 p.m."))
        assertEquals(listOf("The train leaves at three fifteen PM,"), SentenceSplitter.split(EnglishSpeech.rewrite("The train leaves at 3:15 p.m.")))
        assertEquals(listOf("It weighs 2 point 5 kilos,"), SentenceSplitter.split(EnglishSpeech.rewrite("It weighs 2.5 kilos.")))
    }

    @Test fun numberWords() {
        assertEquals("zero", NumberWords.cardinal(0))
        assertEquals("fifteen", NumberWords.cardinal(15))
        assertEquals("twenty three", NumberWords.cardinal(23))
        assertEquals("one hundred five", NumberWords.cardinal(105))
        assertEquals("one thousand five hundred", NumberWords.cardinal(1500))
        assertEquals("10000", NumberWords.cardinal(10000))
        val ordinals = mapOf(1 to "first", 2 to "second", 3 to "third", 4 to "fourth", 5 to "fifth", 8 to "eighth", 9 to "ninth", 11 to "eleventh",
            12 to "twelfth", 20 to "twentieth", 21 to "twenty first", 22 to "twenty second", 100 to "one hundredth")
        for ((n, w) in ordinals) assertEquals("$n", w, NumberWords.ordinal(n))
    }

    @Test fun theStreamIsCutAtSentenceEndsNotAtAbbreviations() {
        val c = SentenceCutter()
        assertEquals(emptyList<String>(), c.add("The train leaves at 3:15 p.m. so you"))
        assertEquals(listOf("The train leaves at 3:15 p.m. so you have time."), c.add(" have time. Do you want"))
        assertEquals(emptyList<String>(), c.add(" a bag?"))
        assertEquals(listOf("Do you want a bag?"), c.flush())
        assertEquals(emptyList<String>(), c.flush())
        val d = SentenceCutter()
        assertEquals(listOf("Mr. Smith is here.", "U.S. citizens can enter.", "Really?!", "He said \"go.\""), d.add("Mr. Smith is here. U.S. citizens can enter. Really?! He said \"go.\" It costs 2.5 euros"))
        assertEquals(listOf("It costs 2.5 euros"), d.flush())
    }

    @Test fun theFirstPieceIsAClauseAfterFourWords() {
        val c = SpeechCutter()
        // The comma has nothing after it yet.
        assertEquals(emptyList<String>(), c.add("To get to the Louvre Museum,"))
        assertEquals(listOf("To get to the Louvre Museum,"), c.add(" continue straight"))
        assertEquals(0, c.sentencesEnded)
        // After the first piece, whole sentences: a later comma is not a cut.
        assertEquals(listOf("continue straight for two blocks, then turn right."), c.add(" for two blocks, then turn right. Then"))
        assertEquals(1, c.sentencesEnded)
        assertEquals(listOf("Then ask again."), c.add(" ask again.") + c.flush())
        assertEquals(2, c.sentencesEnded)
        // A semicolon or a colon counts as a comma does; the words before it count, not the marks.
        val d = SpeechCutter()
        assertEquals(listOf("Sorry, we don't accept cards here;"), d.add("Sorry, we don't accept cards here; only cash."))
        assertEquals(listOf("only cash."), d.flush())
        assertEquals(listOf("The menu has three parts:"), SpeechCutter().add("The menu has three parts: starters"))
    }

    @Test fun aShortClauseOrAnEarlierSentenceEndGivesTheWholeFirstSentence() {
        // "Excuse me," has two words: the first piece is the whole sentence.
        val a = SpeechCutter()
        assertEquals(emptyList<String>(), a.add("Excuse me, what time is the next bus"))
        assertEquals(listOf("Excuse me, what time is the next bus to the airport?"), a.add(" to the airport? Thanks"))
        assertEquals(1, a.sentencesEnded)
        // The sentence ends before the clause mark.
        val b = SpeechCutter()
        assertEquals(listOf("That's 18 euros in total."), b.add("That's 18 euros in total. Would you like a bag, sir?"))
        assertEquals(listOf("Would you like a bag, sir?"), b.flush())
        // A time or a number is not a clause mark (no white space after the colon or the comma).
        val c = SpeechCutter()
        assertEquals(emptyList<String>(), c.add("The train to Lyon leaves at 9:30 and costs 23,50"))
        assertEquals(listOf("The train to Lyon leaves at 9:30 and costs 23,50 euros;"), c.add(" euros; so hurry"))
        // No mark at all: the whole translation at the end.
        val e = SpeechCutter()
        assertEquals(emptyList<String>(), e.add("Thank you very much"))
        assertEquals(listOf("Thank you very much"), e.flush())
        assertEquals(1, e.sentencesEnded)
        assertEquals(emptyList<String>(), SpeechCutter().flush())
    }

    @Test fun thePiecesOfAStreamDoNotDependOnHowItArrives() {
        val text = "To get to the Louvre Museum, continue straight for two blocks. Then turn right at 3 p.m. Thanks!"
        val whole = SpeechCutter().let { it.add(text) + it.flush() }
        val pieces = SpeechCutter().let { c -> text.chunked(3).flatMap { c.add(it) } + c.flush() }
        assertEquals(listOf("To get to the Louvre Museum,", "continue straight for two blocks.", "Then turn right at 3 p.m. Thanks!"), whole)
        assertEquals(whole, pieces)
    }

    @Test fun theCutsOfAStreamDoNotDependOnHowItArrives() {
        val text = "To get to Plaza Mayor, keep going straight for two streets. Then turn left at 3 p.m. Thanks!"
        val whole = SentenceCutter().let { it.add(text) + it.flush() }
        val pieces = SentenceCutter().let { c -> text.chunked(3).flatMap { c.add(it) } + c.flush() }
        assertEquals(listOf("To get to Plaza Mayor, keep going straight for two streets.", "Then turn left at 3 p.m. Thanks!"), whole)
        assertEquals(whole, pieces)
    }
}
