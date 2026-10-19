package io.github.johnrocky.hfmodels.samples.translate

import io.github.johnrocky.hfmodels.samples.translate.EarlyEndpointer.Event
import io.github.johnrocky.hfmodels.voice.Endpointer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The early start: [EarlyEndpointer]'s cuts on scripted audio (frames of 20 ms, the endpointer's defaults: 300 ms
 * pre-roll, 100 ms start run, 800 ms hangover), and [listen] at the microphone's pace with turns that only wait.
 */
class ListenTest {
    private fun tone(seconds: Double, amp: Double = 0.1) = FloatArray((seconds * 16000).toInt()) { (amp * sin(2.0 * PI * 300.0 * it / 16000.0)).toFloat() }
    private fun quiet(seconds: Double) = FloatArray((seconds * 16000).toInt())

    private fun events(pcm: FloatArray, ep: EarlyEndpointer = EarlyEndpointer(Endpointer(maxUtteranceMs = 30_000))): List<Event> {
        val out = ArrayList<Event>()
        var i = 0
        while (i < pcm.size) { out += ep.feed(pcm.copyOfRange(i, minOf(i + 320, pcm.size))); i += 320 }
        return out
    }

    @Test fun anEarlyCutAt300msOfSilenceThenTheEndpointersCutStands() {
        // 0.3 s quiet, 1 s voice, 2 s quiet: speech starts at frame 19 with the 20 frames of the idle ring; frames 65.. are
        // quiet, the 15th quiet frame (79) gives the early cut, the 40th (104) the endpointer's.
        val pcm = quiet(0.3) + tone(1.0) + quiet(2.0)
        val e = events(pcm)
        assertEquals(listOf("SpeechStart", "Early", "Cut"), e.map { it.javaClass.simpleName })
        val early = e[1] as Event.Early
        val cut = e[2] as Event.Cut
        assertEquals(80 * 320, early.pcm.size)
        assertEquals(105 * 320, cut.pcm.size)
        assertTrue(cut.earlyStands)
        // The early audio is the start of the cut's audio; the 25 frames (500 ms) after it are the rest of the hangover.
        assertArrayEquals(early.pcm, cut.pcm.copyOf(early.pcm.size), 0f)
        // The endpointer decides the utterance as it does alone.
        assertArrayEquals(utterancesOf(pcm, Endpointer(maxUtteranceMs = 30_000)).single(), cut.pcm, 0f)
    }

    @Test fun voiceBeforeTheHangoverEndsVoidsTheEarlyCutAndTheUtteranceGoesOn() {
        // 0.5 s of silence inside: the early cut at 300 ms, the voice back at 500 ms, one utterance with both parts.
        val pcm = quiet(0.3) + tone(1.0) + quiet(0.5) + tone(0.6) + quiet(2.0)
        val e = events(pcm)
        assertEquals(listOf("SpeechStart", "Early", "Resumed", "Early", "Cut"), e.map { it.javaClass.simpleName })
        val cut = e[4] as Event.Cut
        assertTrue(cut.earlyStands)
        assertEquals(160 * 320, cut.pcm.size)
        assertArrayEquals(utterancesOf(pcm, Endpointer(maxUtteranceMs = 30_000)).single(), cut.pcm, 0f)
        val turns = earlyTurnsOf(pcm, EarlyEndpointer(Endpointer(maxUtteranceMs = 30_000)))
        assertEquals(1, turns.size)
        assertTrue(turns[0].early)
        assertEquals(300, turns[0].hangoverMs)
        assertEquals(1, turns[0].voidedEarly)
        assertEquals(135 * 320, turns[0].pcm.size)
    }

    @Test fun silenceAsLongAsTheHangoverIsTwoUtterances() {
        val pcm = quiet(0.3) + tone(1.0) + quiet(1.0) + tone(0.6) + quiet(2.0)
        val e = events(pcm)
        assertEquals(listOf("SpeechStart", "Early", "Cut", "SpeechStart", "Early", "Cut"), e.map { it.javaClass.simpleName })
        assertEquals(utterancesOf(pcm, Endpointer(maxUtteranceMs = 30_000)).map { it.size }, e.filterIsInstance<Event.Cut>().map { it.pcm.size })
    }

    @Test fun aFlushAfterAnEarlyCutKeepsIt() {
        val ep = EarlyEndpointer(Endpointer(maxUtteranceMs = 30_000))
        val e = events(quiet(0.3) + tone(1.0) + quiet(0.4), ep)
        assertEquals(listOf("SpeechStart", "Early"), e.map { it.javaClass.simpleName })
        val cut = ep.flush()!!
        assertTrue(cut.earlyStands)
        assertEquals(85 * 320, cut.pcm.size)
        assertNull(ep.flush())
    }

    @Test fun theEarlyCutMustComeBeforeTheHangover() {
        val thrown = runCatching { EarlyEndpointer(Endpointer(), earlyMs = 800) }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
    }

    /** A turn that only waits (as one still hearing), its start and job kept. */
    private fun CoroutineScope.waitingTurns(started: MutableList<Pair<TurnStart, Job>>): (TurnStart) -> Job = { s ->
        launch(Dispatchers.Default) { awaitCancellation() }.also { started += s to it }
    }

    @Test fun aPauseShorterThanTheHangoverCancelsTheEarlyTurnAndTheUtterancesTurnStartsAgain() = runBlocking {
        val started = CopyOnWriteArrayList<Pair<TurnStart, Job>>()
        val pcm = quiet(0.3) + tone(1.0) + quiet(0.5) + tone(0.6)
        val heard = listen(wavChunks(pcm, 1000, realTime = true), EarlyEndpointer(Endpointer(maxUtteranceMs = 30_000)), early = true, input = "file",
            stopRequested = { false }, noSpeechMs = null, startTurn = waitingTurns(started))
        try {
            assertEquals(2, started.size)
            assertTrue(started[0].second.isCancelled)
            assertFalse(started[0].first.gate.isOpen)
            val (s, job) = started[1]
            assertSame(job, heard.turn)
            assertSame(s, heard.start)
            assertTrue(s.early)
            assertEquals(300, s.hangoverMs)
            assertEquals(300, s.specMs)
            assertEquals(1, s.discardedEarly)
            assertEquals(1, heard.discardedEarly)
            assertEquals(135 * 320, s.pcm.size)
            // The gate opened at the endpointer's cut, about 500 ms (25 frames at the microphone's pace) after the early cut.
            assertTrue(s.gate.isOpen)
            val gateMs = (s.gate.openedAtNanos - s.cutNanos) / 1e6
            assertTrue("gate after $gateMs ms", gateMs in 440.0..700.0)
            assertFalse(job.isCancelled)
        } finally {
            started.forEach { it.second.cancel() }
        }
    }

    @Test fun withoutTheEarlyStartTheTurnStartsAtTheEndpointersCut() = runBlocking {
        val started = CopyOnWriteArrayList<Pair<TurnStart, Job>>()
        val heard = listen(wavChunks(quiet(0.3) + tone(1.0), 1000, realTime = false), EarlyEndpointer(Endpointer(maxUtteranceMs = 30_000)), early = false, input = "file",
            stopRequested = { false }, noSpeechMs = null, startTurn = waitingTurns(started))
        try {
            assertEquals(1, started.size)
            val s = heard.start!!
            assertFalse(s.early)
            assertEquals(800, s.hangoverMs)
            assertEquals(0, s.specMs)
            assertTrue(s.gate.isOpen)
            assertEquals(105 * 320, s.pcm.size)
        } finally {
            started.forEach { it.second.cancel() }
        }
    }

    @Test fun aPressAfterTheEarlyCutLetsTheEarlyTurnStand() = runBlocking {
        val started = CopyOnWriteArrayList<Pair<TurnStart, Job>>()
        var chunks = 0
        // The press comes 400 ms into the silence (frame 85): after the early cut, before the hangover's end.
        val heard = listen(wavChunks(quiet(0.3) + tone(1.0) + quiet(2.0), 0, realTime = true), EarlyEndpointer(Endpointer(maxUtteranceMs = 30_000)), early = true, input = "mic",
            stopRequested = { ++chunks > 85 }, noSpeechMs = null, startTurn = waitingTurns(started))
        try {
            assertTrue(heard.stoppedByPress)
            assertEquals(1, started.size)
            assertSame(started[0].second, heard.turn)
            assertTrue(heard.start!!.early)
            assertTrue(heard.start!!.gate.isOpen)
        } finally {
            started.forEach { it.second.cancel() }
        }
    }

    @Test fun silenceIsNothingHeardAndStartsNoTurn() = runBlocking {
        val started = CopyOnWriteArrayList<Pair<TurnStart, Job>>()
        val heard = listen(wavChunks(quiet(1.0), 0, realTime = false), EarlyEndpointer(Endpointer(maxUtteranceMs = 30_000)), early = true, input = "file",
            stopRequested = { false }, noSpeechMs = null, startTurn = waitingTurns(started))
        assertNull(heard.turn)
        assertEquals("the endpointer found no speech", heard.nothingHeard)
        assertTrue(started.isEmpty())
    }

    @Test fun aCancelledListeningCancelsItsEarlyTurn() = runBlocking {
        val started = CopyOnWriteArrayList<Pair<TurnStart, Job>>()
        val listening = launch(Dispatchers.Default) {
            listen(wavChunks(quiet(0.3) + tone(1.0) + quiet(2.0), 0, realTime = true), EarlyEndpointer(Endpointer(maxUtteranceMs = 30_000)), early = true, input = "mic",
                stopRequested = { false }, noSpeechMs = null, startTurn = waitingTurns(started))
        }
        while (started.isEmpty()) delay(5)
        listening.cancel()
        listening.join()
        assertTrue(started.single().second.isCancelled)
    }
}
