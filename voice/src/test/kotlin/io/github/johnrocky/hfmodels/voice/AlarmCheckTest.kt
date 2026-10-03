package io.github.johnrocky.hfmodels.voice

import java.text.DecimalFormatSymbols
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** AlarmTool's reading of Android's next alarm clock; the tool itself needs a phone (the voice sample's scripted turn). */
class AlarmCheckTest {
    private val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0): Long =
        Calendar.getInstance(tokyo).apply { clear(); set(y, mo - 1, d, h, mi, s) }.timeInMillis

    @Test fun theRequestedTimeIsItsNextOccurrence() {
        // 17:12 on Saturday: 07:30 is tomorrow morning, 21:30 tonight; the minute in progress is tomorrow's.
        val now = at(2026, 10, 3, 17, 12, 20)
        assertEquals(at(2026, 10, 4, 7, 30), AlarmCheck.nextOccurrence(now, 7, 30, tokyo))
        assertEquals(at(2026, 10, 3, 21, 30), AlarmCheck.nextOccurrence(now, 21, 30, tokyo))
        assertEquals(at(2026, 10, 4, 17, 12), AlarmCheck.nextOccurrence(now, 17, 12, tokyo))
    }

    @Test fun androidsNextAlarmConfirmsItOrHidesIt() {
        val expected = at(2026, 10, 4, 7, 30)
        // What dumpsys alarm reported after the S26's turn: 2026-10-04 07:30:00.000.
        assertTrue(AlarmCheck.matches(expected, expected))
        assertFalse(AlarmCheck.matches(null, expected))
        assertFalse(AlarmCheck.matches(at(2026, 10, 4, 7, 31), expected))
        // An earlier alarm (06:00) stays next whatever the Clock app did; a later one or none does not.
        assertTrue(AlarmCheck.hidden(at(2026, 10, 4, 6, 0), expected))
        assertFalse(AlarmCheck.hidden(at(2026, 10, 4, 8, 0), expected))
        assertFalse(AlarmCheck.hidden(null, expected))
    }

    @Test fun anAlarmAlreadyAtThatMinuteIsNotTakenForTheNewOne() {
        // Android reports one next alarm: the request cannot be told apart from it.
        val expected = at(2026, 10, 4, 6, 45)
        assertEquals(
            "Alarm requested for 06:45 (Wake Up). Android's next alarm was already Sun 06:45, so this one could not be confirmed.",
            AlarmCheck.unconfirmed(expected, expected, 6, 45, "Wake Up", tokyo),
        )
    }

    @Test fun anEarlierAlarmIsNamedAndALaterOneHidesNothing() {
        val expected = at(2026, 10, 4, 7, 30)
        assertEquals(
            "Alarm requested for 07:30 (Wake Up). Android's next alarm is Sun 06:45, so this one could not be confirmed.",
            AlarmCheck.unconfirmed(at(2026, 10, 4, 6, 45), expected, 7, 30, "Wake Up", tokyo),
        )
        assertNull(AlarmCheck.unconfirmed(at(2026, 10, 4, 8, 0), expected, 7, 30, "Wake Up", tokyo))
        assertNull(AlarmCheck.unconfirmed(null, expected, 7, 30, "Wake Up", tokyo))
    }

    @Test fun theSpokenTimeHasAsciiDigitsWhateverTheLocale() {
        val saved = Locale.getDefault()
        val arabic = Locale.forLanguageTag("ar-EG")
        try {
            Locale.setDefault(arabic)
            // Only meaningful where the locale's own digits differ: there a default-locale format would write them.
            assumeTrue("ar-EG has ASCII digits on this JVM", DecimalFormatSymbols.getInstance(arabic).zeroDigit != '0')
            assertEquals("07:30 (Wake Up)", AlarmCheck.requested(7, 30, "Wake Up"))
            assertEquals(
                "Alarm requested for 07:30 (Wake Up). Android's next alarm is Sun 06:45, so this one could not be confirmed.",
                AlarmCheck.unconfirmed(at(2026, 10, 4, 6, 45), at(2026, 10, 4, 7, 30), 7, 30, "Wake Up", tokyo),
            )
        } finally {
            Locale.setDefault(saved)
        }
    }
}
