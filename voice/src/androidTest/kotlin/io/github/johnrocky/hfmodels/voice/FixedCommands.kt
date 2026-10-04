package io.github.johnrocky.hfmodels.voice

/**
 * The ten fixed commands' expected calls (`tomorrow` = the day after the run), shared by ToolsDeviceTest and
 * VoiceLoopDeviceTest: c01 set_alarm 7:30, c02 set_alarm 6:15, c03 set_timer 10, c04 set_timer 45, c05
 * get_current_datetime, c06 get_calendar_events tomorrow, c07 add_calendar_event title with "dentist" at tomorrow
 * 17:00, c08 add_calendar_event title with "standup" or "stand up" at tomorrow 09:00, c09 set_alarm 8:00 and
 * set_timer 20 (any order), c10 set_alarm 21:30. A command succeeds when every expected call is there with the
 * arguments listed (others are not checked); further calls are counted as extra and do not fail it.
 */
internal object FixedCommands {
    class Expect(val name: String, val label: String, val check: (Map<String, Any?>) -> Boolean)

    private fun num(a: Map<String, Any?>, k: String): Double? = when (val v = a[k]) {
        is Number -> v.toDouble()
        null -> null
        else -> v.toString().trim().toDoubleOrNull()
    }

    private fun minuteOf(a: Map<String, Any?>, k: String): String? = a[k]?.toString()?.trim()?.replace('T', ' ')?.take(16)

    private fun alarm(h: Int, m: Int) = Expect("set_alarm", "set_alarm{hour=$h,minute=$m}") { a -> num(a, "hour") == h.toDouble() && num(a, "minute") == m.toDouble() }
    private fun timer(min: Int) = Expect("set_timer", "set_timer{minutes=$min}") { a -> num(a, "minutes") == min.toDouble() }

    fun expected(tomorrow: String): Map<String, List<Expect>> = mapOf(
        "c01" to listOf(alarm(7, 30)),
        "c02" to listOf(alarm(6, 15)),
        "c03" to listOf(timer(10)),
        "c04" to listOf(timer(45)),
        "c05" to listOf(Expect("get_current_datetime", "get_current_datetime") { true }),
        "c06" to listOf(Expect("get_calendar_events", "get_calendar_events{date=$tomorrow}") { a -> a["date"]?.toString()?.trim()?.take(10) == tomorrow }),
        "c07" to listOf(Expect("add_calendar_event", "add_calendar_event{title~dentist,start=$tomorrow 17:00}") { a ->
            a["title"]?.toString()?.lowercase()?.contains("dentist") == true && minuteOf(a, "start") == "$tomorrow 17:00"
        }),
        "c08" to listOf(Expect("add_calendar_event", "add_calendar_event{title~standup,start=$tomorrow 09:00}") { a ->
            a["title"]?.toString()?.lowercase()?.let { it.contains("standup") || it.contains("stand up") || it.contains("stand-up") } == true && minuteOf(a, "start") == "$tomorrow 09:00"
        }),
        "c09" to listOf(alarm(8, 0), timer(20)),
        "c10" to listOf(alarm(21, 30)),
    )

    /** (every expected call found, each by its own call; the calls left over). */
    fun judge(expect: List<Expect>, calls: List<RecordingTools.Call>): Pair<Boolean, Int> {
        val used = BooleanArray(calls.size)
        var found = 0
        for (e in expect) {
            val i = calls.indices.firstOrNull { !used[it] && calls[it].name == e.name && runCatching { e.check(calls[it].args) }.getOrDefault(false) } ?: continue
            used[i] = true
            found++
        }
        return (found == expect.size) to (calls.size - found)
    }

    fun describe(c: RecordingTools.Call) = c.name + c.args.entries.joinToString(",", "{", "}") { "${it.key}=${it.value}" }
}
