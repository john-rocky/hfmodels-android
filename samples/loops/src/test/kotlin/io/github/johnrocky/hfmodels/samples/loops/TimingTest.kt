package io.github.johnrocky.hfmodels.samples.loops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The line under an answer, from the chunks' arrival times. */
class TimingTest {
    private val ms = 1_000_000L

    @Test
    fun firstTokenRateAndTotal() {
        // Stream called at 0; the first chunk at 400 ms, then one every 50 ms (20 per second); the stream ends 30 ms after the last.
        val chunks = (0 until 101).map { (400 + 50L * it) * ms }
        val t = Timing.of(0, chunks, chunks.last() + 30 * ms)
        assertEquals(400.0, t.firstTokenMs!!, 1e-9)
        assertEquals(20.0, t.tokensPerSecond!!, 1e-9)
        assertEquals(5430.0, t.totalMs, 1e-9)
        assertEquals(101, t.chunks)
        assertEquals("first token 0.40 s · 20.0 tok/s · 5.4 s", t.line())
    }

    @Test
    fun oneOrNoChunkHasNoRate() {
        val one = Timing.of(0, listOf(300 * ms), 310 * ms)
        assertEquals(300.0, one.firstTokenMs!!, 1e-9)
        assertNull(one.tokensPerSecond)
        assertEquals("first token 0.30 s · 0.3 s", one.line())
        val none = Timing.of(0, emptyList(), 60 * ms)
        assertNull(none.firstTokenMs)
        assertEquals("no token · 0.1 s", none.line())
    }
}
