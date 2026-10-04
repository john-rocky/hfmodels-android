package io.github.johnrocky.hfmodels.voice

import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.PreparedModelInfo
import io.github.johnrocky.hfmodels.speech.Speaker
import io.github.johnrocky.hfmodels.speech.SpeechAudio
import io.github.johnrocky.hfmodels.speech.SpeechTiming
import io.github.johnrocky.hfmodels.speech.Transcriber
import io.github.johnrocky.hfmodels.speech.TranscriberLimits
import io.github.johnrocky.hfmodels.speech.Transcript
import io.github.johnrocky.hfmodels.speech.TranscriptTiming
import io.github.johnrocky.hfmodels.voice.VoiceLoop.Event
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The loop's sentences on a scripted reply (the events a ToolRunner streams), a recording speaker and player: no
 * model and no LiteRT-LM class, so these run on any JVM. The real ToolRunner's markup handling under the loop is
 * VoiceLoopScenarios' (Java 21).
 */
class VoiceLoopTest {
    private val runnerTiming = TurnTiming(firstTokenMs = 5.0, replyMs = 9.0, chunks = 3, chars = 40, toolCalls = 0, turns = 1, decodeMs = 4.0)

    @Test fun sentencesBecomeFinalAsTheTextStreamsAndAreTheSplitterChunksOfTheWholeReply() {
        val deltas = listOf("OK", ". I set", " the alarm for 7", ".30 tomor", "row. Is there anything", " else?! Have a", " nice day")
        // A chunk is final once a character follows its run of marks; the rest at the end.
        val stream = SentenceStream(400)
        assertEquals(
            listOf(emptyList(), listOf("OK,"), emptyList(), listOf("I set the alarm for 7,"), listOf("30 tomorrow,"), listOf("Is there anything else,"), emptyList()),
            deltas.map { stream.add(it) },
        )
        assertEquals(listOf("Have a nice day,"), stream.flush())
        val whole = deltas.joinToString("")
        val expected = SentenceSplitter.split(whole)

        val loop = Loop(listOf(ToolEvent.Text("")) + deltas.map { ToolEvent.Text(it) } + ToolEvent.Done(whole, runnerTiming))
        val events = loop.typed("Set an alarm for seven thirty tomorrow.")
        // Each chunk is synthesized once, in order, and played in that order.
        assertEquals(expected, loop.synthesized)
        assertEquals(expected, events.filterIsInstance<Event.Speaking>().map { it.sentence })
        assertEquals(expected.map { it.length }, loop.played.map { it.size })
        assertEquals(1, loop.drained)
        // Only the first sentence carries the first-audio time; Done is last with the reply as the model wrote it.
        val speaking = events.filterIsInstance<Event.Speaking>()
        assertNotNull(speaking.first().firstAudioMs)
        assertTrue(speaking.drop(1).all { it.firstAudioMs == null })
        assertEquals(listOf("Heard", "Thinking"), events.take(2).map { it.javaClass.simpleName })
        val done = events.last() as Event.Done
        assertEquals(whole, done.timing.reply)
        assertEquals("Set an alarm for seven thirty tomorrow.", done.timing.heard)
        assertEquals(0.0, done.timing.transcribeMs, 0.0)
        val t = done.timing
        assertTrue(t.toString(), t.firstSentenceMs!! <= t.firstAudioMs!! && t.firstAudioMs!! <= t.speakMs!! && t.speakMs!! <= t.totalMs && t.replyMs <= t.totalMs)
        assertTrue(t.toString(), t.firstTokenMs!! >= 5.0)
    }

    @Test fun aToolCallEndsTheModelTurnsSentenceAndASilentLastTurnSaysTheLastRoundsActions() {
        // The deltas a ToolRunner gives for LFM2.5's turn "<|tool_call_start|>[...]<|tool_call_end|>I am setting ...":
        // the sentence after the call block, whole, before the call runs; markup never comes through. The model's words
        // are said here (speakActionResults off), so the silent last turn is what says what was done.
        val loop = Loop(listOf(
            ToolEvent.Text("I am setting an alarm labeled 'Seven thirty' for 7:30 AM tomorrow."),
            ToolEvent.ToolCalled("get_current_datetime", emptyMap(), "Saturday, 2026-10-03 16:45", 0.4, turn = 1),
            ToolEvent.Text("Checking"),
            ToolEvent.ToolCalled("set_alarm", mapOf("hour" to 7, "minute" to 30), "Alarm set for 07:30 (Morning Alarm)", 0.5, turn = 2),
            ToolEvent.Done("", runnerTiming.copy(toolCalls = 2, turns = 3)),
        ), config = VoiceLoopConfig(speakActionResults = false), actions = PHONE_ACTIONS)
        val events = loop.typed("Set an alarm for seven thirty tomorrow morning.")
        // A model turn's last sentence is final at its call ("Checking" has no mark); the empty last turn is followed by
        // the results of the last round's actions only (Gemma 4 E2B's c01: get_current_datetime, then set_alarm, then nothing).
        assertEquals(listOf("I am setting an alarm labeled 'Seven thirty' for 7:30 AM tomorrow,", "Checking,", "Alarm set for 07:30 (Morning Alarm),"), loop.synthesized)
        assertEquals(listOf("get_current_datetime", "set_alarm"), events.filterIsInstance<Event.ToolCalled>().map { it.name })
        val done = events.last() as Event.Done
        assertEquals("I am setting an alarm labeled 'Seven thirty' for 7:30 AM tomorrow. Checking", done.timing.reply)
        assertEquals("I am setting an alarm labeled 'Seven thirty' for 7:30 AM tomorrow. Checking Alarm set for 07:30 (Morning Alarm)", done.timing.spoken)
        assertEquals(2, done.timing.toolCalls)
        assertEquals(3, done.timing.llmTurns)
        assertTrue(events.none { it is Event.Error })

        // No tool and no text: the empty-reply text.
        val silent = Loop(listOf(ToolEvent.Done("", runnerTiming)))
        silent.typed("Hmm.")
        assertEquals(listOf("Sorry, I did not get that,"), silent.synthesized)

        // A read and then nothing: its result is data (a JSON array), not a sentence; the empty-reply text instead.
        val read = Loop(listOf(
            ToolEvent.ToolCalled("get_calendar_events", mapOf("date" to "2026-10-04"), "[{\"title\":\"Dentist\",\"start\":\"2026-10-04 17:00\"}]", 0.3, turn = 1),
            ToolEvent.Done("", runnerTiming.copy(toolCalls = 1, turns = 2)),
        ), actions = PHONE_ACTIONS)
        read.typed("What is on my calendar tomorrow?")
        assertEquals(listOf("Sorry, I did not get that,"), read.synthesized)
    }

    @Test fun anActionsResultIsSaidInsteadOfTheModelsWordsAboutIt() {
        // Gemma 4 E2B's c02 from the WAV (r4): "OK." with the call, set_alarm at 16:15, then "I have set an alarm for 6:15."
        val script = listOf(
            ToolEvent.Text("OK."),
            ToolEvent.ToolCalled("set_alarm", mapOf("hour" to 16, "minute" to 15, "label" to "Wake Up"), "Alarm set for 16:15 (Wake Up)", 0.5, turn = 1),
            ToolEvent.Text("I have set an alarm for 6:15."),
            ToolEvent.Done("I have set an alarm for 6:15.", runnerTiming.copy(toolCalls = 1, turns = 2)),
        )
        val loop = Loop(script, actions = PHONE_ACTIONS)
        val events = loop.typed("Wake me up at six fifteen.")
        // What came before the call is said as it streamed; after the call, what the phone did.
        assertEquals(listOf("OK,", "Alarm set for 16:15 (Wake Up),"), loop.synthesized)
        assertEquals(listOf("OK,", "Alarm set for 16:15 (Wake Up),"), events.filterIsInstance<Event.Speaking>().map { it.sentence })
        val done = events.last() as Event.Done
        assertEquals("OK. I have set an alarm for 6:15.", done.timing.reply)
        assertEquals("OK. Alarm set for 16:15 (Wake Up)", done.timing.spoken)
        // speakActionResults off: the model's words, as in r4.
        val asR4 = Loop(script, config = VoiceLoopConfig(speakActionResults = false), actions = PHONE_ACTIONS)
        asR4.typed("Wake me up at six fifteen.")
        assertEquals(listOf("OK,", "I have set an alarm for 6:15,"), asR4.synthesized)

        // A failed action is said as "Sorry, " and the reason; the next try's result after it; a read is never said.
        val retry = Loop(listOf(
            ToolEvent.ToolCalled("get_current_datetime", emptyMap(), "Saturday, 2026-10-03 16:35", 0.4, turn = 1),
            ToolEvent.ToolCalled("set_alarm", mapOf("hour" to 73, "minute" to 0), "Error: hour must be 0-23 and minute 0-59", 0.2, turn = 2),
            ToolEvent.ToolCalled("set_alarm", mapOf("hour" to 7, "minute" to 30), "Alarm set for 07:30 (Reminder)", 0.5, turn = 3),
            ToolEvent.Text("I have set an alarm for 7:30 AM tomorrow morning."),
            ToolEvent.Done("I have set an alarm for 7:30 AM tomorrow morning.", runnerTiming.copy(toolCalls = 3, turns = 4)),
        ), actions = PHONE_ACTIONS)
        retry.typed("Set an alarm for seven thirty tomorrow morning.")
        assertEquals(listOf("Sorry, hour must be 0-23 and minute 0-59,", "Alarm set for 07:30 (Reminder),"), retry.synthesized)

        // A turn with reads only: the model's words.
        val time = Loop(listOf(
            ToolEvent.ToolCalled("get_current_datetime", emptyMap(), "Saturday, 2026-10-03 16:45", 0.4, turn = 1),
            ToolEvent.Text("It is 4:45 PM."),
            ToolEvent.Done("It is 4:45 PM.", runnerTiming.copy(toolCalls = 1, turns = 2)),
        ), actions = PHONE_ACTIONS)
        time.typed("What time is it?")
        assertEquals(listOf("It is 4:45 PM,"), time.synthesized)
    }

    @Test fun markdownIsNotSaidAndStaysInTheReply() {
        assertEquals("Bold, italic and code.", stripMarkdown("**Bold**, _italic_ and `code`."))
        assertEquals("Tomorrow\nTeam standup at 9:00\nDentist at 17:00", stripMarkdown("### Tomorrow\n- Team standup at 9:00\n  - Dentist at 17:00"))
        // A hyphen inside a line is not a bullet.
        assertEquals("Six - fifteen, 0-23 and a well-known word.", stripMarkdown("Six - fifteen, 0-23 and a well-known word."))
        // Gemma 4 E2B's c06 shape (r4): a list in bold.
        val deltas = listOf("You have two events tomorrow:\n\n* **Team", " standup** at 9:00 AM.\n* **Dentist**", " at 5:00 PM.")
        val whole = deltas.joinToString("")
        val loop = Loop(deltas.map { ToolEvent.Text(it) } + ToolEvent.Done(whole, runnerTiming))
        val events = loop.typed("What is on my calendar tomorrow?")
        assertEquals(listOf("You have two events tomorrow:\n\nTeam standup at 9:00 AM,", "Dentist at 5:00 PM,"), loop.synthesized)
        val done = events.last() as Event.Done
        assertEquals(whole, done.timing.reply)
        assertEquals(stripMarkdown(whole).trim(), done.timing.spoken)
    }

    @Test fun anAllCapitalsTranscriptGoesToTheModelAsASentence() {
        assertEquals("Set an alarm for seven thirty to morrow morning.", normalizeTranscript("SET AN ALARM FOR SEVEN THIRTY TO MORROW MORNING"))
        assertEquals("I had a meeting I think I'm late.", normalizeTranscript(" I HAD A MEETING I THINK I'M LATE "))
        // A transcript with lower-case letters keeps its case; a sentence mark at the end is kept.
        assertEquals("What time is it?", normalizeTranscript("What time is it?"))
        assertEquals("what time is it.", normalizeTranscript("what time is it"))
        assertEquals("", normalizeTranscript("  "))
        // Through the loop: the model gets the sentence, and Heard shows it; off, the transcript as it came.
        val loop = Loop(listOf(ToolEvent.Done("OK.", runnerTiming)), transcript = { "WAKE ME UP AT SIX FIFTEEN" })
        assertEquals("Wake me up at six fifteen.", (loop.heard(FloatArray(1600)).first { it is Event.Heard } as Event.Heard).text)
        assertEquals(listOf("Wake me up at six fifteen."), loop.requestTexts)
        val raw = Loop(listOf(ToolEvent.Done("OK.", runnerTiming)), transcript = { "WAKE ME UP AT SIX FIFTEEN" }, config = VoiceLoopConfig(normalizeTranscript = false))
        raw.heard(FloatArray(1600))
        assertEquals(listOf("WAKE ME UP AT SIX FIFTEEN"), raw.requestTexts)
        // Typed text is never changed.
        val typed = Loop(listOf(ToolEvent.Done("OK.", runnerTiming)))
        typed.typed("WAKE ME UP")
        assertEquals(listOf("WAKE ME UP"), typed.requestTexts)
    }

    @Test fun aTurnsClockStartsWhenItHasTheLoopNotWhileItWaitsForTheTurnBefore() {
        // The first request takes 300 ms; the second turn is asked for meanwhile and waits for the loop.
        val loop = Loop(listOf(ToolEvent.Text("The timer is running."), ToolEvent.Done("The timer is running.", runnerTiming)), firstReplyDelayMs = 300)
        runBlocking {
            val first = async { loop.engine.turn(null, "Start a timer.").toList() }
            delay(20)
            val second = async { loop.engine.turn(null, "Start a timer.").toList() }
            val a = (first.await().last() as Event.Done).timing
            val b = (second.await().last() as Event.Done).timing
            assertTrue(a.toString(), a.totalMs >= 300)
            // The second turn's own work takes a few ms; its first sound is its own write, not the first turn's.
            assertTrue(b.toString(), b.totalMs < 150 && b.firstAudioMs!! < 150)
        }
    }

    @Test fun aFailedRequestSaysWhatCameBeforeThenTheFailureTextWithAnError() {
        val loop = Loop(listOf(
            ToolEvent.Text("Done. ,. Setting the alarm"),
            ToolEvent.Failed("the model still calls tools after 4 rounds: set_timer", runnerTiming.copy(toolCalls = 4, turns = 5)),
        ))
        val events = loop.typed("Set an alarm.")
        // The chunk "," has nothing to say: the speaker refuses it (INVALID_INPUT) and it is skipped without an Error.
        assertEquals(listOf("Done,", ",", "Setting the alarm,", "Sorry, I could not finish that,"), loop.synthesized)
        assertEquals(listOf("Done,", "Setting the alarm,", "Sorry, I could not finish that,"), events.filterIsInstance<Event.Speaking>().map { it.sentence })
        val error = events.single { it is Event.Error } as Event.Error
        assertNull(error.code)
        assertEquals("the model still calls tools after 4 rounds: set_timer", error.message)
        assertTrue(events.indexOf(error) < events.indexOfLast { it is Event.Speaking })
        assertTrue(events.last() is Event.Done)
        assertEquals("Done. ,. Setting the alarm", (events.last() as Event.Done).timing.reply)
        assertEquals("Done. ,. Setting the alarm Sorry, I could not finish that.", (events.last() as Event.Done).timing.spoken)

        // The transcriber's error: the failure text, an Error with its code, and no request to the model.
        val asrFails = Loop(emptyList(), transcript = { throw ModelException(ErrorCode.INVALID_INPUT, "longer than the window") })
        val e2 = asrFails.heard(FloatArray(1600))
        assertEquals(ErrorCode.INVALID_INPUT, (e2.single { it is Event.Error } as Event.Error).code)
        assertEquals(listOf("Sorry, I could not finish that,"), asrFails.synthesized)
        assertEquals(0, asrFails.requests)
        // A blank transcript: Heard with no text, Done, nothing said and no request.
        val blank = Loop(emptyList(), transcript = { "  " })
        assertEquals(listOf("Heard", "Done"), blank.heard(FloatArray(1600)).map { it.javaClass.simpleName })
        assertEquals(0, blank.requests)
    }

    @Test fun listenTurnsEachUtteranceAndDropsTheAudioThatComesDuringATurn() {
        // 16 kHz: 400 ms of silence, 500 ms of voice, 1,000 ms of silence (the 800 ms hangover ends it), then 300 ms of
        // voice that arrives while the turn runs (dropped), then 2 s of silence.
        fun chunks(ms: Int, level: Float) = List(ms / 20) { FloatArray(320) { i -> if (i % 2 == 0) level else -level } }
        val audio = chunks(400, 0f) + chunks(500, 0.1f) + chunks(1000, 0f) + chunks(300, 0.1f) + chunks(2000, 0f)
        val loop = Loop(listOf(ToolEvent.Text("The timer is running."), ToolEvent.Done("The timer is running.", runnerTiming)), transcript = { "START A TIMER" }, slowTranscriber = true)
        val events = runBlocking { loop.engine.listen(flow { for (c in audio) emit(c) }).toList() }
        assertEquals(listOf("Listening", "Heard", "Thinking", "Speaking", "Done", "Listening"), events.map { it.javaClass.simpleName })
        // The utterance: 300 ms of pre-roll, the voice, the 800 ms hangover.
        assertEquals(listOf((300 + 500 + 800) * 16), loop.heardLengths)
        assertEquals(1, loop.requests)
    }

    @Test fun cancellingATurnStopsTheReplyAndTheSoundAndCloseAndJoinEndsAListen() {
        // The reply says one sentence and then hangs, as a model still generating.
        val replyCancelled = CompletableDeferred<Unit>()
        val loop = Loop(listOf(ToolEvent.Text("Setting the alarm now. ")), hangAfterScript = replyCancelled)
        runBlocking {
            val events = ArrayList<Event>()
            val job = launch { loop.engine.turn(null, "Set an alarm.").collect { e -> events += e } }
            while (events.none { it is Event.Speaking }) kotlinx.coroutines.delay(1)
            job.cancel()
            job.join()
            assertTrue(replyCancelled.isCompleted)
            assertTrue(loop.stopped >= 1)
            assertTrue(events.none { it is Event.Done })
            assertEquals(listOf("Setting the alarm now,"), loop.synthesized)
        }
        // closeAndJoin ends a listen in progress and stops the sound; the loop then refuses new work.
        val idle = Loop(emptyList())
        runBlocking {
            val ended = CompletableDeferred<Unit>()
            launch { runCatching { idle.engine.listen(flow { awaitCancellation() }).collect {} }; ended.complete(Unit) }
            kotlinx.coroutines.delay(10)
            idle.engine.closeAndJoin()
            ended.await()
            assertTrue(idle.stopped >= 1)
            assertTrue(runCatching { idle.engine.turn(null, "Hello.").toList() }.exceptionOrNull() is IllegalStateException)
        }
    }

    @Test fun aStoppedListenEndsOnlyOnceItsTurnHasUnwoundAndTheLoopTakesANewListenBefore() {
        // What an app's microphone button has to count (samples/voice: stillRunning): the job of a stopped listen
        // completes only once its turn has unwound, and the loop takes a new listen before that, a second microphone.
        val replyCancelled = CompletableDeferred<Unit>()
        val stopTakes = CompletableDeferred<Unit>()
        val loop = Loop(listOf(ToolEvent.Text("Setting it now. ")), transcript = { "SET AN ALARM" }, hangAfterScript = replyCancelled, stopTakes = stopTakes)
        var opened = 0
        // 20 ms chunks: silence, half a second of voice, silence; then nothing until cancelled.
        fun mic() = flow {
            opened++
            for (i in 0 until 100) {
                val level = if (i in 25 until 50) 0.1f else 0f
                emit(FloatArray(320) { k -> if (k % 2 == 0) level else -level })
            }
            awaitCancellation()
        }
        runBlocking {
            val events = ArrayList<Event>()
            val first = launch { loop.engine.listen(mic()).collect { e -> events += e } }
            while (events.none { it is Event.Speaking }) delay(1)
            first.cancel()
            try {
                assertFalse(first.isActive)
                assertFalse(first.isCompleted)
                withTimeout(5_000) { while (runCatching { loop.engine.listen(mic()).first() }.isFailure) delay(1) }
                assertEquals(2, opened)
                assertFalse(first.isCompleted)
            } finally {
                stopTakes.complete(Unit)
            }
            first.join()
            assertTrue(replyCancelled.isCompleted)
        }
    }

    /** The engine with a scripted reply, a speaker and a player that record, and an optional transcriber. */
    private class Loop(
        private val script: List<ToolEvent>,
        private val transcript: ((FloatArray) -> String)? = null,
        private val slowTranscriber: Boolean = false,
        /** Set: after the script the reply hangs until cancelled, then completes this. */
        private val hangAfterScript: CompletableDeferred<Unit>? = null,
        /** Set: a cancelled reply stops only once this completes, as a model whose stop the runtime confirms later. */
        private val stopTakes: CompletableDeferred<Unit>? = null,
        config: VoiceLoopConfig = VoiceLoopConfig(),
        actions: Set<String> = emptySet(),
        /** The first request waits this long before its script. */
        private val firstReplyDelayMs: Long = 0,
    ) {
        val synthesized = ArrayList<String>()
        val played = ArrayList<FloatArray>()
        val heardLengths = ArrayList<Int>()
        val requestTexts = ArrayList<String>()
        var drained = 0
        var stopped = 0
        var requests = 0
        private var firstWrite = 0L
        // As SpeechPlayer: a run opens at the first play after a drain or a stop, and its first write is the time.
        private var runOpen = false

        private val speaker = object : Speaker {
            override val info: PreparedModelInfo get() = throw UnsupportedOperationException()
            override val voices = listOf("v")
            override val sampleRate = 24000
            override val maxChars = 400
            override fun close() {}
            override suspend fun closeAndJoin() {}
            override fun phonemeIds(text: String) = IntArray(0)
            override suspend fun synthesize(text: String, voice: String?, speed: Float): SpeechAudio {
                synthesized += text
                if (text.none { it.isLetterOrDigit() }) throw ModelException(ErrorCode.INVALID_INPUT, "the text has nothing to say")
                // As many samples as characters: the player's arrays tell the sentences apart.
                return SpeechAudio(FloatArray(text.length), 24000, SpeechTiming(0.1, 1.0, 1.1, 1))
            }
        }

        private val transcriber = transcript?.let { f ->
            object : Transcriber {
                override val info: PreparedModelInfo get() = throw UnsupportedOperationException()
                override val limits = TranscriberLimits(16000, 16.0, listOf("en"))
                override fun close() {}
                override suspend fun closeAndJoin() {}
                override suspend fun transcribe(pcm: FloatArray): Transcript {
                    heardLengths += pcm.size
                    if (slowTranscriber) kotlinx.coroutines.delay(5)
                    return Transcript(f(pcm), TranscriptTiming(1.0, 2.0, 3.0))
                }
            }
        }

        private fun reply(text: String): Flow<ToolEvent> = flow {
            requests++
            requestTexts += text
            if (requests == 1 && firstReplyDelayMs > 0) delay(firstReplyDelayMs)
            for (e in script) emit(e)
            hangAfterScript?.let { done ->
                try {
                    awaitCancellation()
                } finally {
                    stopTakes?.let { withContext(NonCancellable) { it.await() } }
                    done.complete(Unit)
                }
            }
        }

        val engine = LoopEngine(transcriber, speaker, ::reply, config, PlayerPort(
            play = { pcm -> if (!runOpen) { firstWrite = System.nanoTime(); runOpen = true }; played += pcm },
            drain = { drained++; runOpen = false },
            stop = { stopped++; runOpen = false },
            firstWriteAtNanos = { firstWrite },
        ), actions)

        fun typed(text: String): List<Event> = runBlocking { engine.turn(null, text).toList() }
        fun heard(pcm: FloatArray): List<Event> = runBlocking { engine.turn(pcm, null).toList() }
    }

    private companion object {
        /** PhoneTools' actions ([VoiceTool.isAction]). */
        val PHONE_ACTIONS = setOf("set_alarm", "set_timer", "add_calendar_event")
    }
}
