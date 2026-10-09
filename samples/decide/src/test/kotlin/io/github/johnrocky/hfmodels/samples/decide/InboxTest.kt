package io.github.johnrocky.hfmodels.samples.decide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The inbox screen's import and numbers on the JVM, no model and no phone: paste, the panel, the agreement, median and p90. */
class InboxTest {
    @Test fun pasteGivesOneTextPerLine() {
        val r = Inbox.paste("  first text \n\nsecond text\r\n   \nthird text\n")
        assertTrue(r is Inbox.Import.Texts)
        val texts = (r as Inbox.Import.Texts).texts
        assertEquals(listOf("first text", "second text", "third text"), texts.map { it.text })
        assertEquals(listOf(0L, 1L, 2L), texts.map { it.id })
        assertTrue(texts.all { it.sender == Inbox.PASTED && it.label == null })
    }

    @Test fun emptyPasteIsASentence() {
        for (clip in listOf<CharSequence?>(null, "", " \n\t\r\n  ")) {
            val r = Inbox.paste(clip)
            assertTrue("$clip", r is Inbox.Import.Empty)
            assertEquals(Inbox.EMPTY_PASTE, (r as Inbox.Import.Empty).message)
        }
    }

    @Test fun panelIsTheLabelledTexts() {
        val panel = Inbox.panel()
        assertEquals(24, panel.size)
        assertEquals(InboxPanel.ROWS.map { it.second }, panel.map { it.text })
        assertEquals(3, panel.count { it.label == "scam" })
        assertEquals("#01 · label nothing", panel.first().sender)
    }

    @Test fun agreementLeavesTheScamsOut() {
        val panel = Inbox.panel()
        // Every text answered as its label says, scams included: 21 of 21.
        val right = panel.map { t -> sorted(t, t.label?.let { InboxPanel.LABEL_OPTION[it] } ?: InboxPanel.OPTIONS[0]) }
        assertEquals(21 to 21, Inbox.agreement(right))
        // Two non-scam texts answered otherwise: 19 of 21; a scam's answer never counts.
        val two = right.mapIndexed { i, s -> if (i == 0 || i == 4) sorted(s.text, InboxPanel.OPTIONS[3]) else s }
        assertEquals(19 to 21, Inbox.agreement(two))
        val scamsWrong = right.map { s -> if (s.text.label == "scam") sorted(s.text, InboxPanel.OPTIONS[4]) else s }
        assertEquals(21 to 21, Inbox.agreement(scamsWrong))
        assertEquals(0 to 0, Inbox.agreement(emptyList()))
    }

    @Test fun medianAndP90() {
        assertEquals(2.0, Inbox.median(listOf(3.0, 1.0, 2.0)), 0.0)
        assertEquals(2.5, Inbox.median(listOf(4.0, 1.0, 3.0, 2.0)), 0.0)
        assertEquals(9.0, Inbox.p90((1..10).map { it.toDouble() }), 0.0)
        assertEquals(5.0, Inbox.p90(listOf(5.0)), 0.0)
        assertTrue(Inbox.median(emptyList()).isNaN() && Inbox.p90(emptyList()).isNaN())
    }

    private fun sorted(t: Inbox.Text, choice: String) = Inbox.Sorted(t, choice, InboxPanel.OPTIONS.indexOf(choice), mapOf(choice to 1.0), 1.0)
}
