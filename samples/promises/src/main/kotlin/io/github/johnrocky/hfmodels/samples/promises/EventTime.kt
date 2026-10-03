package io.github.johnrocky.hfmodels.samples.promises

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * When a calendar entry for a sentence starts. Fixed rules on the sentence's words, not the model: the model
 * only sorts the sentence into a bundle. The first rule that matches wins.
 *
 * - Day: `on the 12th` (any `<n>st|nd|rd|th`: this month, next month once the day is past), a weekday (the next
 *   one, today if its time is still ahead), `next <weekday>` (a week after that), `this weekend` (its Saturday
 *   or Sunday, whichever is still ahead), `next weekend` (its Saturday), `the end of the month`, `tomorrow`,
 *   `today`, `tonight`.
 * - Time: `6:30 pm`, `8 am`, `at 6:30`, `6:30`, `at 7`, `noon`. Without am / pm: a morning stays a morning,
 *   `afternoon`, `evening` and `tonight` move it past noon, and 1 to 7 o'clock read as the evening.
 * - Without a time: morning 9:00, afternoon 14:00, evening 19:00, tonight 20:00, a weekend 10:00, `today` the
 *   next full hour, else 9:00.
 * - A time without a day is today, or tomorrow once it is past. Nothing found: tomorrow at 9:00.
 */
object EventTime {
    class Found(val start: ZonedDateTime, val matched: List<String>)

    private enum class Part(val default: LocalTime) { MORNING(LocalTime.of(9, 0)), AFTERNOON(LocalTime.of(14, 0)), EVENING(LocalTime.of(19, 0)), NIGHT(LocalTime.of(20, 0)) }

    private val ORDINAL = Regex("""\b(?:on\s+)?(?:the\s+)?(\d{1,2})(?:st|nd|rd|th)\b""")
    private val WEEKDAY = Regex("""\b(next\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""")
    private val WEEKEND = Regex("""\b(next\s+)?weekend\b""")
    private val END_OF_MONTH = Regex("""\bend of (?:the|this) month\b""")
    private val TOMORROW = Regex("""\btomorrow\b""")
    private val TODAY = Regex("""\b(?:today|tonight|this (?:morning|afternoon|evening))\b""")

    private val CLOCK_AMPM = Regex("""\b(\d{1,2})(?::(\d{2}))?\s*([ap])\.?m\b\.?""")
    private val AT_CLOCK = Regex("""\bat\s+(\d{1,2}):(\d{2})\b""")
    private val CLOCK = Regex("""\b(\d{1,2}):(\d{2})\b""")
    private val AT_HOUR = Regex("""\bat\s+(\d{1,2})\b(?![:.]\d|\s*(?:%|percent\b|people\b|minutes?\b|mins?\b|hours?\b))""")
    private val NOON = Regex("""\bnoon\b""")

    fun find(sentence: String, now: ZonedDateTime): Found {
        val s = sentence.lowercase(Locale.ROOT)
        val matched = ArrayList<String>()
        fun hit(r: Regex): MatchResult? = r.find(s)?.also { matched += it.value.trim() }

        val part = when {
            Regex("""\bmorning\b|\bfirst thing\b""").containsMatchIn(s) -> Part.MORNING
            Regex("""\bafternoon\b""").containsMatchIn(s) -> Part.AFTERNOON
            Regex("""\bevening\b""").containsMatchIn(s) -> Part.EVENING
            Regex("""\btonight\b|\bnight\b""").containsMatchIn(s) -> Part.NIGHT
            else -> null
        }

        // The time of day, if the sentence says one.
        var time: LocalTime? = null
        hit(CLOCK_AMPM)?.let { m -> time = clock(m.groupValues[1].toInt(), m.groupValues[2].ifEmpty { "0" }.toInt(), m.groupValues[3], part) }
        if (time == null) (hit(AT_CLOCK) ?: hit(CLOCK))?.let { m -> time = clock(m.groupValues[1].toInt(), m.groupValues[2].toInt(), null, part) }
        if (time == null) hit(AT_HOUR)?.let { m -> time = clock(m.groupValues[1].toInt(), 0, null, part) }
        if (time == null && hit(NOON) != null) time = LocalTime.NOON

        val today = now.toLocalDate()
        fun at(d: LocalDate, t: LocalTime) = d.atTime(t).atZone(now.zone)
        fun firstAhead(days: Sequence<LocalDate>, t: LocalTime) = days.map { at(it, t) }.first { it.isAfter(now) }

        hit(ORDINAL)?.let { m ->
            val n = m.groupValues[1].toInt()
            if (n in 1..31) {
                val month = if (n >= today.dayOfMonth) YearMonth.from(today) else YearMonth.from(today).plusMonths(1)
                return Found(at(month.atDay(minOf(n, month.lengthOfMonth())), time ?: part?.default ?: NINE), matched)
            }
            matched.removeAt(matched.size - 1)
        }
        hit(WEEKDAY)?.let { m ->
            val day = DayOfWeek.valueOf(m.groupValues[2].uppercase(Locale.ROOT))
            val t = time ?: part?.default ?: NINE
            val first = firstAhead(generateSequence(today) { it.plusDays(1) }.filter { it.dayOfWeek == day }, t)
            return Found(if (m.groupValues[1].isNotEmpty()) first.plusWeeks(1) else first, matched)
        }
        hit(WEEKEND)?.let { m ->
            val t = time ?: part?.default ?: TEN
            // This weekend's Saturday: today on a Saturday, yesterday on a Sunday, else the coming one.
            val saturday = if (today.dayOfWeek == DayOfWeek.SUNDAY) today.minusDays(1) else today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
            if (m.groupValues[1].isNotEmpty()) return Found(at(saturday.plusWeeks(1), t), matched)
            return Found(firstAhead(sequenceOf(saturday, saturday.plusDays(1), saturday.plusWeeks(1)), t), matched)
        }
        if (hit(END_OF_MONTH) != null) return Found(at(YearMonth.from(today).atEndOfMonth(), time ?: part?.default ?: NINE), matched)
        if (hit(TOMORROW) != null) return Found(at(today.plusDays(1), time ?: part?.default ?: NINE), matched)
        // "today" with no time of day: the next full hour.
        if (hit(TODAY) != null) return Found(time?.let { at(today, it) } ?: part?.let { at(today, it.default) } ?: now.plusHours(1).truncatedTo(ChronoUnit.HOURS), matched)

        val t = time ?: part?.default ?: return Found(at(today.plusDays(1), NINE), matched)
        val start = at(today, t)
        return Found(if (start.isAfter(now)) start else start.plusDays(1), matched)
    }

    private val NINE = LocalTime.of(9, 0)
    private val TEN = LocalTime.of(10, 0)

    /** A clock reading in 24 hours: am / pm when given, else the part of the day, else 1 to 7 o'clock as the evening. */
    private fun clock(h: Int, m: Int, ampm: String?, part: Part?): LocalTime? {
        if (h > 23 || m > 59) return null
        val hour = when {
            h > 12 -> h
            ampm == "a" -> if (h == 12) 0 else h
            ampm == "p" -> if (h == 12) 12 else h + 12
            part == Part.MORNING -> h
            part != null && h < 12 -> h + 12
            h in 1..7 -> h + 12
            else -> h
        }
        return LocalTime.of(hour, m)
    }
}
