package io.github.johnrocky.hfmodels.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic 16 kHz signals: near-silence (LCG noise at 0.001) and a 440 Hz sine at 0.1 (RMS 0.07, above the 0.02 start level). */
class EndpointerTest {
    private val sr = 16000
    private var seed = 7L

    private fun silence(seconds: Double) = FloatArray((seconds * sr).toInt()) {
        seed = (seed * 1103515245L + 12345L) % (1L shl 31)
        ((seed / (1L shl 31).toDouble() - 0.5) * 0.002).toFloat()
    }

    private fun tone(seconds: Double) = FloatArray((seconds * sr).toInt()) { i -> (0.1 * sin(2.0 * PI * 440.0 * i / sr)).toFloat() }

    private fun concat(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) { System.arraycopy(p, 0, out, o, p.size); o += p.size }
        return out
    }

    /** Feeds [pcm] in chunks of [chunk] samples (not a multiple of the 320-sample frame by default), then flushes. */
    private fun stream(e: Endpointer, pcm: FloatArray, chunk: Int = 437): List<Endpointer.Event> {
        val events = ArrayList<Endpointer.Event>()
        var i = 0
        while (i < pcm.size) {
            events += e.feed(pcm.copyOfRange(i, minOf(pcm.size, i + chunk)))
            i += chunk
        }
        e.flush()?.let { events += it }
        return events
    }

    private fun utterances(events: List<Endpointer.Event>) = events.filterIsInstance<Endpointer.Event.Utterance>()

    @Test fun silenceGivesNoEvent() {
        val e = Endpointer()
        assertEquals(emptyList<Endpointer.Event>(), stream(e, silence(2.0)))
        assertNull(e.flush())
    }

    @Test fun oneUtteranceIsSpeechPlusHangover() {
        val e = Endpointer()
        val events = stream(e, concat(silence(0.5), tone(1.0), silence(1.5)))
        assertEquals(2, events.size)
        assertEquals(Endpointer.Event.SpeechStart, events[0])
        val u = utterances(events).single()
        val expected = sr * (1000 + e.hangoverMs) / 1000
        assertTrue("utterance ${u.pcm.size} samples, expected $expected +- one 320-sample frame", abs(u.pcm.size - expected) <= 320)
        // The utterance starts at the tone, not in the silence before it.
        assertTrue(abs(u.pcm[sr / 1000 * 5]) > 0.005f || abs(u.pcm[sr / 1000 * 5 + 1]) > 0.005f)
    }

    @Test fun twoUtterancesWithAGap() {
        val events = stream(Endpointer(), concat(silence(0.5), tone(1.0), silence(1.5), tone(0.7), silence(1.5)), chunk = 320)
        assertEquals(listOf("SpeechStart", "Utterance", "SpeechStart", "Utterance"), events.map { it.toString().substringBefore('(') })
        val sizes = utterances(events).map { it.pcm.size }
        assertTrue(sizes.toString(), abs(sizes[0] - 1.8 * sr) <= 320 && abs(sizes[1] - 1.5 * sr) <= 320)
    }

    @Test fun longSoundIsCutAtTheMaximum() {
        val e = Endpointer()
        val events = stream(e, tone(20.0))
        val u = utterances(events)
        val max = sr * e.maxUtteranceMs / 1000
        assertEquals(max, u.first().pcm.size)
        assertTrue(u.all { it.pcm.size <= max })
        // The remaining 4 s opens a second utterance (the start rule's 100 ms included), returned by flush().
        assertEquals(2, u.size)
        assertEquals(20 * sr - max, u[1].pcm.size)
        assertEquals(2, events.count { it == Endpointer.Event.SpeechStart })
    }
}
