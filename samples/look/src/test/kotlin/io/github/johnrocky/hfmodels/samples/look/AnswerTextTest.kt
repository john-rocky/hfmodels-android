package io.github.johnrocky.hfmodels.samples.look

import org.junit.Assert.assertEquals
import org.junit.Test

/** The answer as the screen shows it: Markdown's marks dropped, bold kept as bold, while it streams and at the end. */
class AnswerTextTest {
    private fun bold(s: AnswerText.Shown) = s.bold.map { s.text.substring(it.first, it.last + 1) }

    @Test
    fun theSignAnswer() {
        // The Mac CPU run of the shipped VL_int8 file on the sign photo (litert-lm 0.18.0, greedy), byte for byte as the model wrote it.
        val raw = "The sign says:  \n**Please  \nKEEP  \nOFF THE GRASS**\n\nIt’s a white rectangular sign with black text, placed in a grassy area, reminding people to stay off the grass."
        val s = AnswerText.render(raw)
        assertEquals("The sign says:\nPlease\nKEEP\nOFF THE GRASS\n\nIt’s a white rectangular sign with black text, placed in a grassy area, reminding people to stay off the grass.", s.text)
        assertEquals(listOf("Please\nKEEP\nOFF THE GRASS"), bold(s))
    }

    @Test
    fun boldWordsHeadingsBulletsAndQuotes() {
        val s = AnswerText.render("This is a photo of a **red bicycle** — parked.\n\n### How to use it:\n- Stand it **upright**.\n* Read it.\n> **Please KEEP OFF THE GRASS**")
        assertEquals("This is a photo of a red bicycle — parked.\n\nHow to use it:\n• Stand it upright.\n• Read it.\nPlease KEEP OFF THE GRASS", s.text)
        assertEquals(listOf("red bicycle", "How to use it:", "upright", "Please KEEP OFF THE GRASS"), bold(s))
    }

    @Test
    fun whileItStreams() {
        // A ** that has not closed yet: bold to the end.
        val open = AnswerText.render("The sign says:   \n**Ple")
        assertEquals("The sign says:\nPle", open.text)
        assertEquals(listOf("Ple"), bold(open))
        // A lone * at the very end is half of a ** on its way: held back, not shown and then taken away.
        val half = AnswerText.render("The sign says:   \n*")
        assertEquals("The sign says:\n", half.text)
        assertEquals(emptyList<String>(), bold(half))
        // Trailing spaces on the last line stay until the line ends (the next piece may follow them).
        assertEquals("The sign ", AnswerText.render("The sign ").text)
        assertEquals("", AnswerText.render("").text)
    }

    @Test
    fun textWithoutMarksIsUnchanged() {
        val plain = "A white and red lighthouse stands on a rocky coastal cliff, surrounded by dry grasses and the calm blue ocean under a clear sky."
        assertEquals(AnswerText.Shown(plain, emptyList()), AnswerText.render(plain))
        // A single star inside a line is text, and an emoji stays as it is.
        assertEquals("2 * 3 = 6 🌿", AnswerText.render("2 * 3 = 6 🌿").text)
    }
}
