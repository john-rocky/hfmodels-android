package io.github.johnrocky.hfmodels.samples.loops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The final `####` line against a GSM8K question's known answer. */
class MarksTest {
    @Test
    fun theLastLineCounts() {
        assertEquals("60", Marks.finalValue("Start = 100 - 40\n#### 50\nWait, again.\n#### 60"))
        assertEquals(Marks.Mark.Match("60"), Marks.mark("so she started with \$60.\n\n#### 60", "60"))
        assertEquals(Marks.Mark.Mismatch("25", "60"), Marks.mark("#### 25", "60"))
        assertEquals(Marks.Mark.NoLine, Marks.mark("The answer is 60.", "60"))
        assertNull(Marks.finalValue("#### no number here"))
    }

    @Test
    fun numbersAreNormalized() {
        assertEquals("8000", Marks.finalValue("#### 8,000"))
        assertEquals("60", Marks.finalValue("#### \$60.00"))
        assertEquals("2.5", Marks.finalValue("####2.50 cars"))
        // A whole number keeps its zeros (a strip of ".0" characters once turned 20.00 into 2 elsewhere).
        assertEquals("20", Marks.normalize("20.00"))
        assertEquals("100", Marks.normalize("100"))
        assertEquals(Marks.Mark.Match("8000"), Marks.mark("#### 8000", "8,000"))
    }
}
