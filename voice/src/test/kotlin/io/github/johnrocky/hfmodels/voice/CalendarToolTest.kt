package io.github.johnrocky.hfmodels.voice

import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** What the calendar tools send back for dates they cannot read; the model only sees the message ("Error: <message>"). */
class CalendarToolTest {
    // The dates are read before the Context is touched, so a bare ContextWrapper of the stub android.jar does.
    private val calendar = CalendarTool(ContextWrapper(null))

    @Test fun aDateTheToolCannotReadTellsTheModelTheFormat() {
        val e = assertThrows(IllegalArgumentException::class.java) { runBlocking { calendar.read.call(mapOf("date" to "tomorrow")) } }
        assertEquals("bad date 'tomorrow' (use YYYY-MM-DD)", e.message)
    }

    @Test fun aTimeTheToolCannotReadTellsTheModelTheFormat() {
        val args = mapOf("title" to "Lunch", "start" to "noon tomorrow", "end" to "2026-10-04 13:00", "location" to "")
        val e = assertThrows(IllegalArgumentException::class.java) { runBlocking { calendar.add.call(args) } }
        assertEquals("bad time 'noon tomorrow' (use YYYY-MM-DD HH:MM)", e.message)
    }
}
