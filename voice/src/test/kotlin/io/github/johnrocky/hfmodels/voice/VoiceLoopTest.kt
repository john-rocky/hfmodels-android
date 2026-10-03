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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

    @Test fun aToolCallEndsTheModelTurnsSentenceAndASilentLastTurnSaysTheLastRoundsResults() {
        // The deltas a ToolRunner gives for LFM2.5's turn "<|tool_call_start|>[...]<|tool_call_end|>I am setting ...":
        // the sentence after the call block, whole, before the call runs; markup never comes through.
        val loop = Loop(listOf(
            ToolEvent.Text("I am setting an alarm labeled 'Seven thirty' for 7:30 AM tomorrow."),
            ToolEvent.ToolCalled("get_current_datetime", emptyMap(), "Saturday, 2026-10-03 16:45", 0.4, turn = 1),
            ToolEvent.Text("Checking"),
            ToolEvent.ToolCalled("set_alarm", mapOf("hour" to 7, "minute" to 30), "Alarm set for 07:30 (Morning Alarm)", 0.5, turn = 2),
            ToolEvent.Done("", runnerTiming.copy(toolCalls = 2, turns = 3)),
        ))
        val events = loop.typed("Set an alarm for seven thirty tomorrow morning.")
        // A model turn's last sentence is final at its call ("Checking" has no mark); the empty last turn is followed by
        // the results of the last round of calls only (Gemma 4 E2B's c01: get_current_datetime, then set_alarm, then nothing).
        assertEquals(listOf("I am setting an alarm labeled 'Seven thirty' for 7:30 AM tomorrow,", "Checking,", "Alarm set for 07:30 (Morning Alarm),"), loop.synthesized)
        assertEquals(listOf("get_current_datetime", "set_alarm"), events.filterIsInstance<Event.ToolCalled>().map { it.name })
        val done = events.last() as Event.Done
        assertEquals("I am setting an alarm labeled 'Seven thirty' for 7:30 AM tomorrow. Checking Alarm set for 07:30 (Morning Alarm)", done.timing.reply)
        assertEquals(2, done.timing.toolCalls)
        assertEquals(3, done.timing.llmTurns)
        assertTrue(events.none { it is Event.Error })

        // No tool and no text: the empty-reply text.
        val silent = Loop(listOf(ToolEvent.Done("", runnerTiming)))
        silent.typed("Hmm.")
        assertEquals(listOf("Sorry, I did not get that,"), silent.synthesized)
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
        assertEquals("Done. ,. Setting the alarm Sorry, I could not finish that.", (events.last() as Event.Done).timing.reply)

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
        val loop = Loop(listOf(ToolEvent.Text("Timer started."), ToolEvent.Done("Timer started.", runnerTiming)), transcript = { "START A TIMER" }, slowTranscriber = true)
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

    /** The engine with a scripted reply, a speaker and a player that record, and an optional transcriber. */
    private class Loop(
        private val script: List<ToolEvent>,
        private val transcript: ((FloatArray) -> String)? = null,
        private val slowTranscriber: Boolean = false,
        /** Set: after the script the reply hangs until cancelled, then completes this. */
        private val hangAfterScript: CompletableDeferred<Unit>? = null,
    ) {
        val synthesized = ArrayList<String>()
        val played = ArrayList<FloatArray>()
        val heardLengths = ArrayList<Int>()
        var drained = 0
        var stopped = 0
        var requests = 0
        private var firstWrite = 0L

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
            for (e in script) emit(e)
            hangAfterScript?.let { done -> try { awaitCancellation() } finally { done.complete(Unit) } }
        }

        val engine = LoopEngine(transcriber, speaker, ::reply, VoiceLoopConfig(), PlayerPort(
            play = { pcm -> if (firstWrite == 0L) firstWrite = System.nanoTime(); played += pcm },
            drain = { drained++ },
            stop = { stopped++ },
            firstWriteAtNanos = { firstWrite },
        ))

        fun typed(text: String): List<Event> = runBlocking { engine.turn(null, text).toList() }
        fun heard(pcm: FloatArray): List<Event> = runBlocking { engine.turn(pcm, null).toList() }
    }
}
