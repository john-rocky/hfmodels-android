package io.github.johnrocky.hfmodels.samples.ask

import org.junit.Assert.assertEquals
import org.junit.Test

class AnswerTest {
    @Test
    fun firstNonWhitespaceLetterIsTheAnswer() {
        assertEquals(0, Answer.parse("A", 3))
        assertEquals(1, Answer.parse(" B", 3))
        assertEquals(2, Answer.parse("C)", 3))
        assertEquals(4, Answer.parse("E", 5))
        assertEquals(1, Answer.parse("\nB", 2))
    }

    @Test
    fun aLetterOutsideTheOptionsDoesNotParse() {
        assertEquals(Answer.UNPARSED, Answer.parse("", 3))
        assertEquals(Answer.UNPARSED, Answer.parse("  ", 3))
        assertEquals(Answer.UNPARSED, Answer.parse("D", 3))
        assertEquals(Answer.UNPARSED, Answer.parse("C", 2))
        assertEquals(Answer.UNPARSED, Answer.parse("a", 5))
        assertEquals(Answer.UNPARSED, Answer.parse("F", 5))
    }

    @Test
    fun lettersForTheRecord() {
        assertEquals("?", Answer.letter(Answer.parse("D", 3)))
        assertEquals("B", Answer.letter(Answer.parse(" B", 3)))
        assertEquals("E", Answer.letter(4))
    }
}
