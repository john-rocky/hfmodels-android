package io.github.johnrocky.hfmodels.samples.voice

import io.github.johnrocky.hfmodels.voice.VoiceLoop.Event
import io.github.johnrocky.hfmodels.voice.VoiceLoop.TurnTiming
import java.util.Locale

/** One tool call on the screen: what the model asked for and what the phone answered. */
data class ToolLine(val name: String, val args: Map<String, Any?>, val result: String) {
    val icon: String
        get() = when (name) {
            "set_alarm" -> "⏰"
            "set_timer" -> "⏲️"
            "add_calendar_event", "get_calendar_events" -> "📅"
            "get_current_datetime" -> "🕒"
            else -> "🔧"
        }

    /** `set_alarm(hour=7, minute=30)`: the call as the model made it. */
    val call: String get() = name + args.entries.joinToString(", ", "(", ")") { "${it.key}=${plain(it.value)}" }

    private fun plain(v: Any?): String = when (v) {
        is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
        is Float -> if (v == Math.floor(v.toDouble()).toFloat()) v.toLong().toString() else v.toString()
        is String -> "\"$v\""
        else -> v.toString()
    }
}

/**
 * What the screen shows. [on] folds the loop's events into it, one turn at a time: [heard] the text the model got,
 * [tools] the calls, [reply] what was said (sentence by sentence while it is said, the whole of it at the end),
 * [modelReply] the model's own words when they are not what was said (an action's result was said instead).
 */
data class VoiceUi(
    val status: String = "Not loaded",
    val loading: Boolean = false,
    val ready: Boolean = false,
    val listening: Boolean = false,
    val busy: Boolean = false,
    val heard: String = "",
    val tools: List<ToolLine> = emptyList(),
    val reply: String = "",
    val modelReply: String? = null,
    val replyIn: String = "",
    val breakdown: String = "",
    val totalMs: Double? = null,
    val phoneState: String = "",
    val network: String = "",
    val error: String? = null,
)

/**
 * The state after [e]. [hangoverMs]: the silence the endpointer waited for before it cut the utterance (the
 * microphone's turns; 0 for typed text), part of the time from the end of speech to the first sound.
 */
fun VoiceUi.on(e: Event, hangoverMs: Int): VoiceUi = when (e) {
    Event.Listening -> copy(busy = false, status = "Listening…")
    is Event.Heard -> copy(
        busy = true, heard = e.text, tools = emptyList(), reply = "", modelReply = null, replyIn = "", breakdown = "",
        totalMs = null, error = null, status = if (e.text.isBlank()) "Heard nothing" else "Thinking…",
    )
    Event.Thinking -> copy(status = "Thinking…")
    is Event.ToolCalled -> copy(tools = tools + ToolLine(e.name, e.args, e.result))
    is Event.Speaking -> copy(status = "Speaking…", reply = (reply + " " + e.sentence.removeSuffix(",")).trim())
    is Event.Error -> copy(error = (e.code?.let { "$it: " } ?: "") + e.message)
    is Event.Done -> {
        val t = e.timing
        copy(
            busy = false,
            status = if (listening) "Listening…" else "Ready",
            reply = t.spoken.ifBlank { reply },
            modelReply = t.reply.takeIf { it.isNotBlank() && words(it) != words(t.spoken) },
            replyIn = replyIn(t, hangoverMs),
            breakdown = breakdown(t, hangoverMs),
            totalMs = t.totalMs,
        )
    }
}

/** "Reply in 2.6 s": from the end of speech (the hangover before the cut, then the turn) to the first sound. */
fun replyIn(t: TurnTiming, hangoverMs: Int): String =
    t.firstAudioMs?.let { "Reply in " + String.format(Locale.US, "%.1f s", (hangoverMs + it) / 1000) } ?: if (t.heard.isBlank()) "" else "No sound"

/**
 * The parts of [replyIn]: the end-of-speech wait (microphone only), hearing (the transcriber), thinking (to the first
 * sentence to say: the model's, or an action's result) and the voice (that sentence synthesized, to the first write).
 */
fun breakdown(t: TurnTiming, hangoverMs: Int): String {
    val audio = t.firstAudioMs ?: return ""
    val parts = ArrayList<String>()
    if (hangoverMs > 0) parts += "${ms(hangoverMs.toDouble())} end of speech"
    if (t.transcribeMs > 0) parts += "${ms(t.transcribeMs)} hearing"
    val sentence = t.firstSentenceMs
    if (sentence != null) {
        parts += "${ms(sentence - t.transcribeMs)} thinking"
        parts += "${ms(audio - sentence)} voice"
    } else {
        parts += "${ms(audio - t.transcribeMs)} thinking and voice"
    }
    return parts.joinToString(" + ", "(", ")")
}

/** 640 -> "640 ms", 1234 -> "1.2 s". */
fun ms(v: Double): String = if (v < 1000) String.format(Locale.US, "%.0f ms", v) else String.format(Locale.US, "%.1f s", v / 1000)

private fun words(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
