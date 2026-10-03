// Ported from john-rocky/edge-agent-lab 2d2f12c, android/phone-agent/.../PhoneTools.kt (Apache-2.0, the same author).
package io.github.johnrocky.hfmodels.voice

import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phone tools that do the real thing on this phone: the alarm and the timer land in the Clock app
 * (`AlarmClock` intents with `EXTRA_SKIP_UI`), events in the app's own local "Phone Agent" calendar
 * (created on first use; account calendars are never read or written). A tool that cannot do its job
 * says so in its result. The app declares what they need: `com.android.alarm.permission.SET_ALARM`,
 * `android.permission.READ_CALENDAR` and `WRITE_CALENDAR` (granted at run time).
 *
 * ```kotlin
 * val runner = ToolRunner(chat, PhoneTools.all(context), ToolFormat.Runtime)
 * ```
 */
object PhoneTools {
    const val CALENDAR_NAME = "Phone Agent"

    /** get_current_datetime, get_calendar_events, set_alarm, add_calendar_event, set_timer: phone-agent's set and order. */
    fun all(context: Context): List<VoiceTool> {
        val calendar = CalendarTool(context.applicationContext)
        return listOf(ClockTool(), calendar.read, AlarmTool(context.applicationContext), calendar.add, TimerTool(context.applicationContext))
    }

    /** What Android itself reports: the next alarm the OS will fire, and the "Phone Agent" calendar for tomorrow (for a screen after a run). */
    suspend fun phoneState(context: Context): String = withContext(Dispatchers.IO) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val next = am.nextAlarmClock?.let { SimpleDateFormat("EEE HH:mm", Locale.US).format(Date(it.triggerTime)) } ?: "none"
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(tomorrowNoon()))
        val events = try {
            val raw = CalendarTool(context.applicationContext).events(day)
            if (raw.startsWith("No events")) "none" else {
                val a = JSONArray(raw)
                (0 until a.length()).joinToString("\n") { i ->
                    val o = a.getJSONObject(i)
                    "   ${o.getString("start").takeLast(5)}–${o.getString("end").takeLast(5)}  ${o.getString("title")}"
                }
            }
        } catch (e: Exception) { "?" }
        "Next alarm (Android): $next\nCalendar, $day:\n$events"
    }

    internal fun tomorrowNoon(): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0)
    }.timeInMillis
}

/** `get_current_datetime`: the local date and time with the day of the week ("Saturday, 2026-10-03 15:04"). */
class ClockTool : VoiceTool {
    override val name = "get_current_datetime"
    override val description = "Returns the current local date and time, including the day of the week."
    override val parameters = emptyList<ToolParam>()
    override suspend fun call(args: Map<String, Any?>): String = SimpleDateFormat("EEEE, yyyy-MM-dd HH:mm", Locale.US).format(Date())
}

/**
 * `set_alarm(hour, minute, label)`: an alarm in the Clock app, without showing its UI. Call it from a process with a
 * visible activity: Android 10 and later drop an activity start from the background without an exception, and the
 * result text still says the alarm was set. [PhoneTools.phoneState]'s next alarm shows whether it was.
 */
class AlarmTool(private val context: Context) : VoiceTool {
    override val name = "set_alarm"
    override val description = "Sets an alarm on this phone."
    override val parameters = listOf(
        ToolParam("hour", "integer", "Hour in 24-hour time (0-23)."),
        ToolParam("minute", "integer", "Minute (0-59)."),
        ToolParam("label", "string", "Short label shown with the alarm."),
    )

    override suspend fun call(args: Map<String, Any?>): String {
        val hour = ToolArgs.int(args, "hour")
        val minute = ToolArgs.int(args, "minute")
        val label = ToolArgs.stringOrNull(args, "label") ?: ""
        require(hour in 0..23 && minute in 0..59) { "hour must be 0-23 and minute 0-59" }
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(i)
            "Alarm set for %02d:%02d (%s)".format(hour, minute, label)
        } catch (e: ActivityNotFoundException) {
            "Error: no clock app can set alarms on this phone"
        }
    }
}

/**
 * `set_timer(minutes, label)`: a countdown in the Clock app, without showing its UI. Call it from a process with a
 * visible activity: Android 10 and later drop an activity start from the background without an exception, and the
 * result text still says the timer started ([PhoneTools.phoneState] reports alarms, not timers).
 */
class TimerTool(private val context: Context) : VoiceTool {
    override val name = "set_timer"
    override val description = "Starts a countdown timer on this phone."
    override val parameters = listOf(
        ToolParam("minutes", "integer", "Length of the timer in minutes."),
        ToolParam("label", "string", "Short label shown with the timer."),
    )

    override suspend fun call(args: Map<String, Any?>): String {
        val minutes = ToolArgs.int(args, "minutes")
        val label = ToolArgs.stringOrNull(args, "label") ?: ""
        require(minutes in 1..1440) { "minutes must be 1-1440" }
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(i)
            "Timer started: $minutes min ($label)"
        } catch (e: ActivityNotFoundException) {
            "Error: no clock app can start timers on this phone"
        }
    }
}

/**
 * `get_calendar_events(date)` ([read]) and `add_calendar_event(title, start, end, location)` ([add])
 * on one calendar: the app's own local "Phone Agent" calendar, created on first use. An account
 * calendar is never touched, so a run cannot leak or alter someone's real schedule.
 */
class CalendarTool(private val context: Context) {
    private val fmtMin = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    val read: VoiceTool = object : VoiceTool {
        override val name = "get_calendar_events"
        override val description = "Lists the events already on the phone calendar for one day."
        override val parameters = listOf(ToolParam("date", "string", "The day to list, as YYYY-MM-DD."))
        override suspend fun call(args: Map<String, Any?>): String = withContext(Dispatchers.IO) { events(ToolArgs.string(args, "date")) }
    }

    val add: VoiceTool = object : VoiceTool {
        override val name = "add_calendar_event"
        override val description = "Adds an event to the phone calendar."
        override val parameters = listOf(
            ToolParam("title", "string", "Event title."),
            ToolParam("start", "string", "Start time as YYYY-MM-DD HH:MM."),
            ToolParam("end", "string", "End time as YYYY-MM-DD HH:MM."),
            ToolParam("location", "string", "Where the event takes place."),
        )
        override suspend fun call(args: Map<String, Any?>): String = withContext(Dispatchers.IO) {
            addEvent(ToolArgs.string(args, "title"), ToolArgs.string(args, "start"), ToolArgs.string(args, "end"), ToolArgs.stringOrNull(args, "location") ?: "")
        }
    }

    internal fun events(date: String): String {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date.trim().take(10)) ?: throw IllegalArgumentException("bad date '$date' (use YYYY-MM-DD)")
        val begin = day.time
        val end = begin + 24L * 3600 * 1000
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().appendPath(begin.toString()).appendPath(end.toString()).build()
        val proj = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.EVENT_LOCATION)
        val rows = JSONArray()
        context.contentResolver.query(uri, proj, CalendarContract.Instances.CALENDAR_ID + "=?", arrayOf(calendarId().toString()), CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
            while (c.moveToNext()) {
                rows.put(JSONObject().put("title", c.getString(0) ?: "").put("start", fmtMin.format(Date(c.getLong(1)))).put("end", fmtMin.format(Date(c.getLong(2)))).put("location", c.getString(3) ?: ""))
            }
        }
        return if (rows.length() == 0) "No events on ${date.trim().take(10)}" else rows.toString()
    }

    private fun parseMinute(s: String): Long {
        val t = s.trim().replace('T', ' ').take(16)
        return fmtMin.parse(t)?.time ?: throw IllegalArgumentException("bad time '$s' (use YYYY-MM-DD HH:MM)")
    }

    private fun addEvent(title: String, start: String, end: String, location: String): String {
        val s = parseMinute(start)
        val e = parseMinute(end)
        require(e > s) { "end must be after start" }
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId())
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.EVENT_LOCATION, location)
            put(CalendarContract.Events.DTSTART, s)
            put(CalendarContract.Events.DTEND, e)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) ?: throw IllegalStateException("calendar refused the event")
        return "Event '$title' added: ${fmtMin.format(Date(s))} to ${fmtMin.format(Date(e))} at $location"
    }

    /** The local "Phone Agent" calendar's id, created on first use. */
    private fun calendarId(): Long {
        val name = PhoneTools.CALENDAR_NAME
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID),
            CalendarContract.Calendars.ACCOUNT_TYPE + "=? AND " + CalendarContract.Calendars.ACCOUNT_NAME + "=? AND " + CalendarContract.Calendars.NAME + "=?",
            arrayOf(CalendarContract.ACCOUNT_TYPE_LOCAL, name, name), null,
        )?.use { c -> if (c.moveToFirst()) return c.getLong(0) }
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, name)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
        val v = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, name)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, name)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, name)
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF58A6FF.toInt())
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, name)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        }
        val row = context.contentResolver.insert(uri, v) ?: throw IllegalStateException("could not create a calendar")
        return ContentUris.parseId(row)
    }
}
