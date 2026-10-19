package io.github.johnrocky.hfmodels.samples.translate

import io.github.johnrocky.hfmodels.voice.Endpointer
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cutting audio: the endpointer over a file, the input gate, the WAV reader and writer. */
class AudioTest {
    private fun tone(seconds: Double, amp: Double, hz: Double = 300.0) = FloatArray((seconds * 16000).toInt()) { (amp * sin(2.0 * PI * hz * it / 16000.0)).toFloat() }

    @Test fun aFileGivesTheUtterancesTheMicrophoneWould() {
        // 0.3 s quiet, 1 s voice, 1 s quiet, 0.6 s voice: two utterances, each from its pre-roll to the end of its hangover.
        val pcm = FloatArray(4800) + tone(1.0, 0.1) + FloatArray(16000) + tone(0.6, 0.1)
        val u = utterancesOf(pcm, Endpointer())
        assertEquals(2, u.size)
        // 300 ms pre-roll + 1 s + 800 ms hangover = 2.1 s (frames of 320 samples).
        assertEquals(33600, u[0].size)
        assertTrue(u[1].size in 22400..27200)
    }

    @Test fun silenceAndQuietNoiseGiveNoUtterance() {
        assertEquals(0, utterancesOf(FloatArray(80000), Endpointer()).size)
        val rng = java.util.Random(7)
        val noise = FloatArray(80000) { (rng.nextGaussian() * 0.003).toFloat() }
        assertEquals(0, utterancesOf(noise, Endpointer()).size)
    }

    @Test fun speechLongerThanTheWindowIsCutAtTheWindow() {
        // 35 s of voice through the 15 s window (Qwen3-ASR's file): 15 + 15 + 5 s, the last one with its hangover.
        val u = utterancesOf(tone(35.0, 0.1), Endpointer(maxUtteranceMs = 15_000))
        assertEquals(listOf(240_000, 240_000), u.take(2).map { it.size })
        assertEquals(3, u.size)
    }

    @Test fun theInputGateNeedsVoiceAndLevel() {
        assertFalse(InputGate.check(FloatArray(16000), 16000, 0.02f).heard)
        assertEquals("too short (0 ms of voice)", InputGate.check(FloatArray(16000), 16000, 0.02f).reason)
        assertFalse(InputGate.check(tone(0.2, 0.1), 16000, 0.02f).heard)
        assertTrue(InputGate.check(tone(1.0, 0.1), 16000, 0.02f).heard)
        // 300 ms of voice in 20 s of silence: long enough, too quiet overall (RMS 0.0035 < 0.005).
        val sparse = FloatArray(320000).also { tone(0.3, 0.04).copyInto(it, 1000) }
        val v = InputGate.check(sparse, 16000, 0.02f)
        assertFalse(v.heard)
        assertTrue(v.reason, v.reason.startsWith("too quiet"))
        assertEquals(300, v.voicedMs)
    }

    @Test fun wavRoundTrip() {
        val f = File.createTempFile("translate", ".wav").apply { deleteOnExit() }
        val pcm = floatArrayOf(0f, 0.5f, -0.5f, 0.25f)
        Wav.write(f, pcm, 16000)
        val (back, rate) = Wav.read(f)
        assertEquals(16000, rate)
        assertArrayEquals(floatArrayOf(0f, 16383 / 32768f, -16383 / 32768f, 8191 / 32768f), back, 0f)
    }
}
