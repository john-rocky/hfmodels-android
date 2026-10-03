package io.github.johnrocky.hfmodels.samples.voice

import io.github.johnrocky.hfmodels.voice.VoiceLoop.Event
import io.github.johnrocky.hfmodels.voice.VoiceLoop.TurnTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The screen's state from the loop's events, and the milliseconds as the screen writes them. */
class VoiceUiTest {
    private fun timing(heard: String, firstSentence: Double?, firstAudio: Double?, transcribe: Double, reply: String, spoken: String) = TurnTiming(
        transcribeMs = transcribe, firstTokenMs = 900.0, firstSentenceMs = firstSentence, firstAudioMs = firstAudio, replyMs = 1900.0,
        speakMs = 2100.0, totalMs = 7000.0, toolCalls = 1, llmTurns = 2, heard = heard, reply = reply, spoken = spoken,
    )

    @Test fun aMicrophoneTurnShowsWhatWasHeardDoneAndSaidAndTheTimeFromTheEndOfSpeech() {
        var ui = VoiceUi(ready = true, listening = true)
        val events = listOf(
            Event.Listening,
            Event.Heard("Wake me up at six fifteen.", 2380.0, 63.0),
            Event.Thinking,
            Event.ToolCalled("set_alarm", mapOf("hour" to 16.0, "minute" to 15.0, "label" to "Wake Up"), "Alarm set for 16:15 (Wake Up)", 0.5),
            Event.Speaking("OK,", 120.0, 1300.0),
        )
        for (e in events) ui = ui.on(e, hangoverMs = 800)
        assertEquals("Wake me up at six fifteen.", ui.heard)
        assertEquals("⏰ set_alarm(hour=16, minute=15, label=\"Wake Up\")", "${ui.tools.single().icon} ${ui.tools.single().call}")
        assertEquals("OK", ui.reply)
        assertEquals("Speaking…", ui.status)
        ui = ui.on(Event.Speaking("Alarm set for 16:15 (Wake Up),", 300.0, null), 800)
        assertEquals("OK Alarm set for 16:15 (Wake Up)", ui.reply)
        ui = ui.on(Event.Done(timing("Wake me up at six fifteen.", 1040.0, 1224.0, 63.0, "OK. I have set an alarm for 6:15.", "OK. Alarm set for 16:15 (Wake Up)")), 800)
        // At the end: what was said as one text, the model's own words beside it (they say 6:15; the phone did 16:15).
        assertEquals("OK. Alarm set for 16:15 (Wake Up)", ui.reply)
        assertEquals("OK. I have set an alarm for 6:15.", ui.modelReply)
        assertEquals("Reply in 2.0 s", ui.replyIn)
        assertEquals("(800 ms end of speech + 63 ms hearing + 977 ms thinking + 184 ms voice)", ui.breakdown)
        assertEquals("Listening…", ui.status)
        // The next utterance clears the turn.
        ui = ui.on(Event.Heard("What time is it.", 1500.0, 50.0), 800)
        assertEquals(emptyList<ToolLine>(), ui.tools)
        assertEquals("", ui.reply)
        assertNull(ui.modelReply)
        assertEquals("", ui.replyIn)
    }

    @Test fun aTypedTurnHasNoEndOfSpeechAndASilentTurnNoReplyTime() {
        var ui = VoiceUi(ready = true)
        ui = ui.on(Event.Heard("Set an alarm for seven thirty tomorrow morning.", 0.0, 0.0), 0)
        ui = ui.on(Event.Done(timing("Set an alarm for seven thirty tomorrow morning.", 1420.0, 1920.0, 0.0, "", "Alarm set for 07:30 (Morning Alarm)")), 0)
        assertEquals("Reply in 1.9 s", ui.replyIn)
        assertEquals("(1.4 s thinking + 500 ms voice)", ui.breakdown)
        // The model said nothing of its own: nothing to show beside what was said.
        assertNull(ui.modelReply)
        assertEquals("Ready", ui.status)
        // A blank transcript: no reply and no time.
        ui = ui.on(Event.Heard("", 900.0, 40.0), 800)
        ui = ui.on(Event.Done(timing("", null, null, 40.0, "", "")), 800)
        assertEquals("Heard nothing", VoiceUi().on(Event.Heard("", 900.0, 40.0), 800).status)
        assertEquals("", ui.replyIn)
        assertEquals("", ui.breakdown)
    }

    @Test fun millisecondsUnderASecondAreMillisecondsAndAbove() {
        assertEquals("640 ms", ms(640.0))
        assertEquals("1.2 s", ms(1234.0))
        assertEquals("No sound", replyIn(timing("Hello.", 300.0, null, 0.0, "Hi.", "Hi."), 0))
        assertEquals("get_current_datetime()", ToolLine("get_current_datetime", emptyMap(), "Saturday, 2026-10-03 17:05").call)
        // The runtime's numbers are a Number type of its own (they printed as 7.0 on the S26): whole ones as whole.
        assertEquals("set_timer(minutes=10, label=\"Tea\")", ToolLine("set_timer", mapOf("minutes" to java.math.BigDecimal("10.0"), "label" to "Tea"), "Timer started: 10 min (Tea)").call)
        assertEquals(7.5, wholeOrNot(java.math.BigDecimal("7.5")))
    }
}
