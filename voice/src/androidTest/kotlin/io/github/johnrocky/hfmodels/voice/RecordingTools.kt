package io.github.johnrocky.hfmodels.voice

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PhoneTools' five tools by name and parameters, without touching the phone: each call is recorded and
 * answered with a fixed text (an alarm "set", a timer "started", an event "added", and a calendar that
 * holds a standup at 09:00 and the dentist at 17:00 on any day asked). The clock is the real one.
 */
class RecordingTools {
    data class Call(val name: String, val args: Map<String, Any?>)

    val calls = ArrayList<Call>()

    private fun tool(name: String, description: String, parameters: List<ToolParam>, answer: (Map<String, Any?>) -> String) = object : VoiceTool {
        override val name = name
        override val description = description
        override val parameters = parameters
        override suspend fun call(args: Map<String, Any?>): String {
            synchronized(calls) { calls += Call(name, args) }
            return answer(args)
        }
    }

    /** The same names, descriptions and parameters as [PhoneTools.all], in its order. */
    val all: List<VoiceTool> = listOf(
        tool("get_current_datetime", "Returns the current local date and time, including the day of the week.", emptyList()) {
            SimpleDateFormat("EEEE, yyyy-MM-dd HH:mm", Locale.US).format(Date())
        },
        tool("get_calendar_events", "Lists the events already on the phone calendar for one day.", listOf(ToolParam("date", "string", "The day to list, as YYYY-MM-DD."))) { a ->
            val day = ToolArgs.string(a, "date").trim().take(10)
            """[{"title":"Team standup","start":"$day 09:00","end":"$day 09:15","location":"Office, room 4"},{"title":"Dentist","start":"$day 17:00","end":"$day 17:45","location":"Smile Clinic"}]"""
        },
        tool("set_alarm", "Sets an alarm on this phone.", listOf(
            ToolParam("hour", "integer", "Hour in 24-hour time (0-23)."),
            ToolParam("minute", "integer", "Minute (0-59)."),
            ToolParam("label", "string", "Short label shown with the alarm."),
        )) { a -> "Alarm set for %02d:%02d (%s)".format(ToolArgs.int(a, "hour"), ToolArgs.int(a, "minute"), ToolArgs.stringOrNull(a, "label") ?: "") },
        tool("add_calendar_event", "Adds an event to the phone calendar.", listOf(
            ToolParam("title", "string", "Event title."),
            ToolParam("start", "string", "Start time as YYYY-MM-DD HH:MM."),
            ToolParam("end", "string", "End time as YYYY-MM-DD HH:MM."),
            ToolParam("location", "string", "Where the event takes place."),
        )) { a -> "Event '${ToolArgs.string(a, "title")}' added: ${ToolArgs.string(a, "start")} to ${ToolArgs.stringOrNull(a, "end") ?: "?"} at ${ToolArgs.stringOrNull(a, "location") ?: ""}" },
        tool("set_timer", "Starts a countdown timer on this phone.", listOf(
            ToolParam("minutes", "integer", "Length of the timer in minutes."),
            ToolParam("label", "string", "Short label shown with the timer."),
        )) { a -> "Timer started: ${ToolArgs.int(a, "minutes")} min (${ToolArgs.stringOrNull(a, "label") ?: ""})" },
    )
}
