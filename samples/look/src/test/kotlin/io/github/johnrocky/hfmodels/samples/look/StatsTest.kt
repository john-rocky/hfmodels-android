package io.github.johnrocky.hfmodels.samples.look

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The numbers under the answer: first token, chunks, chunks per second, and the line the screen shows. */
class StatsTest {
    private val ms = 1_000_000L

    @Test
    fun firstTokenIsTheFirstChunkWithText() {
        // The call at 1,000 ms; an empty chunk at 1,500 ms does not count as the first token, the one at 3,310 ms does.
        val start = 1_000 * ms
        val t = Stats.of(start, start + 4_000 * ms, listOf(Chunk(start + 500 * ms, 0), Chunk(start + 2_310 * ms, 4), Chunk(start + 2_360 * ms, 6), Chunk(start + 2_410 * ms, 3)))
        assertEquals(2_310.0, t.firstTokenMs!!, 1e-9)
        assertEquals(4_000.0, t.streamMs, 1e-9)
        assertEquals(3, t.chunks)
        assertEquals(4, t.allChunks)
        assertEquals(13, t.chars)
        assertEquals(listOf(2_310.0, 2_360.0, 2_410.0), t.chunkMs)
        // Two intervals of 50 ms between three text chunks: 2 / 0.1 s.
        assertEquals(20.0, t.chunksPerSecond!!, 1e-9)
    }

    @Test
    fun noRateFromFewerThanTwoChunks() {
        val one = Stats.of(0, 900 * ms, listOf(Chunk(400 * ms, 5)))
        assertEquals(400.0, one.firstTokenMs!!, 1e-9)
        assertNull(one.chunksPerSecond)
        val none = Stats.of(0, 900 * ms, listOf(Chunk(400 * ms, 0)))
        assertNull(none.firstTokenMs)
        assertNull(none.chunksPerSecond)
        assertEquals(0, none.chunks)
        assertEquals(1, none.allChunks)
        // Two chunks in the same nanosecond: no time between them, no rate.
        assertNull(Stats.of(0, ms, listOf(Chunk(5, 1), Chunk(5, 1))).chunksPerSecond)
    }

    @Test
    fun theLineUnderTheAnswer() {
        val t = Stats.of(0, 9_000 * ms, listOf(Chunk(930 * ms, 3)) + (1..65).map { Chunk(930 * ms + it * 2_000 * ms / 65, 4) })
        assertEquals("first token 0.93 s · 32.5 chunks/s · GPU · on-device, offline", Stats.line(t, "GPU", offline = true))
        assertEquals("first token 0.93 s · 32.5 chunks/s · CPU · on-device", Stats.line(t, "CPU", offline = false))
        assertEquals("first token 0.93 s · 32.5 tok/s · CPU · on-device", Stats.line(t, "CPU", offline = false, rateUnit = "tok/s"))
        val silent = Stats.of(0, ms, emptyList())
        assertEquals("no text · CPU · on-device", Stats.line(silent, "CPU", offline = false))
    }
}
