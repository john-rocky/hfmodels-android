package io.github.johnrocky.hfmodels.samples.promises

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class EventTimeTest {
    private val zone = ZoneId.of("Asia/Tokyo")
    /** A Saturday afternoon. */
    private val now = ZonedDateTime.of(2026, 10, 3, 17, 30, 0, 0, zone)

    private fun start(sentence: String, at: ZonedDateTime = now) = EventTime.find(sentence, at).start.toLocalDateTime().toString()

    @Test fun theSampleSentences() {
        assertEquals("2026-10-03T18:30", start("Let's meet at the station at 6:30 tonight."))
        assertEquals("2026-10-12T19:00", start("So it's dinner at your place on the 12th at 7, right?"))
        // Saturday's noon has passed: the weekend's next noon is Sunday's.
        assertEquals("2026-10-04T12:00", start("Great, the park at noon this weekend it is."))
        assertEquals("2026-10-03T20:00", start("I'll send you the photos tonight."))
        assertEquals("2026-10-03T20:00", start("Could you feed the cat tonight?"))
        assertEquals("2026-10-31T09:00", start("I promise I'll pay you back at the end of the month."))
        // Nothing to go by: tomorrow at 9.
        assertEquals("2026-10-04T09:00", start("Text me when you get home, please."))
        assertEquals("2026-10-04T09:00", start("I'll bring your charger back next time I see you."))
    }

    @Test fun theOtherFixtureSentences() {
        assertEquals("2026-10-04T09:00", start("I'll call the plumber first thing tomorrow."))
        assertEquals("2026-10-04T12:00", start("Lunch at noon tomorrow works for me, see you at the usual place."))
        // Today is the 3rd and 8 am has passed: the 3rd of next month.
        assertEquals("2026-11-03T08:00", start("Done, the movers are booked for 8 am on the 3rd."))
        assertEquals("2026-10-04T10:00", start("Okay, the team call is moved to 10 tomorrow morning."))
        assertEquals("2026-10-03T18:00", start("Please send me the contract before you leave today."))
    }

    @Test fun weekdaysDatesAndClocks() {
        assertEquals("2026-10-09T09:00", start("Can we talk on friday?"))
        assertEquals("2026-10-13T15:00", start("Let's do it next tuesday at 3."))
        assertEquals("2026-10-10T10:00", start("Next weekend works."))
        assertEquals("2026-11-01T09:00", start("Rent is due on the 1st."))
        assertEquals("2026-10-03T20:00", start("See you at 8pm"))
        assertEquals("2026-10-03T19:00", start("Call me at 7"))
        assertEquals("2026-10-04T19:00", start("Call me at 7", now.withHour(20)))
        assertEquals("2026-10-03T21:15", start("The movie starts at 9:15 tonight"))
        assertEquals("2026-10-04T09:00", start("We are 10 people at 10 percent"))
        assertEquals("2026-10-04T15:00", start("The shop is open from 3 to 5"))
    }

    @Test fun aStartThatHasPassedMovesOn() {
        val evening = now.withHour(19)
        val late = now.withHour(21)
        assertEquals("2026-10-04T18:30", start("Let's meet at the station at 6:30 tonight.", evening))
        assertEquals("2026-10-04T20:00", start("I'll send you the photos tonight.", late))
        assertEquals("2026-10-31T09:00", start("I promise I'll pay you back at the end of the month.", late))
        assertEquals("2026-11-30T09:00", start("I promise I'll pay you back at the end of the month.", ZonedDateTime.of(2026, 10, 31, 12, 0, 0, 0, zone)))
        assertEquals("2026-10-03T22:00", start("Please send me the contract before you leave today.", late))
    }
}
