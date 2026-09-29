package io.github.johnrocky.hfmodels.samples.pong

import org.junit.Assert.assertEquals
import org.junit.Test

class AnswerTest {
    @Test
    fun firstNonWhitespaceLetterIsTheAnswer() {
        assertEquals(0, Answer.parse("A"))
        assertEquals(1, Answer.parse(" B"))
        assertEquals(2, Answer.parse("C)"))
        assertEquals(Answer.UNPARSED, Answer.parse(""))
        assertEquals(Answer.UNPARSED, Answer.parse("D"))
        assertEquals("?", Answer.letter(Answer.parse("D")))
        assertEquals("B", Answer.letter(Answer.parse(" B")))
    }
}
