package io.github.johnrocky.hfmodels.samples.translate

import io.github.johnrocky.hfmodels.samples.translate.TdtGreedy.BLANK
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The greedy TDT loop without a graph: a scripted joint that answers (token, duration) per call and keeps the tokens its
 * LSTM state has consumed, so the test sees which calls ran on which state.
 */
class TdtGreedyTest {
    private class Scripted(override val frames: Int, val answer: (FakeCall) -> Pair<Int, Int>) : TdtJoint {
        val calls = ArrayList<FakeCall>()
        /** The tokens the adopted state has consumed, and those of the last call's output state. */
        var state: List<Int> = emptyList()
        private var pending: List<Int> = emptyList()

        override fun stateless(tokens: IntArray, t: Int, slot: Int, row: FloatArray) {
            val c = FakeCall(true, t, slot, tokens.toList(), -1, state)
            calls += c
            pending = tokens.toList()
            fill(row, answer(c))
        }

        override fun stateful(token: Int, t: Int, row: FloatArray) {
            val c = FakeCall(false, t, 0, emptyList(), token, state)
            calls += c
            pending = state + token
            fill(row, answer(c))
        }

        override fun adoptStateless() { state = pending }
        override fun adopt() { state = pending }

        private fun fill(row: FloatArray, a: Pair<Int, Int>) {
            row.fill(-10f)
            row[a.first] = 5f
            row[TdtGreedy.TOKENS + a.second] = 5f
        }
    }

    private data class FakeCall(val stateless: Boolean, val t: Int, val slot: Int, val tokens: List<Int>, val token: Int, val state: List<Int>)

    @Test fun theFourStatelessSlotsFillBeforeDecode1AndTheStateMovesOnlyOnATokenEmitted() {
        // A token at frames 0..4 and 6 (duration 1), a blank at frame 5 and 7.
        val j = Scripted(frames = 8) { c ->
            when (c.t) {
                in 0..4 -> (10 + c.t) to 1
                6 -> 15 to 1
                else -> BLANK to 1
            }
        }
        val r = TdtGreedy.decode(j)
        assertEquals(listOf(10, 11, 12, 13, 14, 15), r.tokens.map { it.id })
        assertEquals(listOf(0, 1, 2, 3, 4, 6), r.tokens.map { it.frame })
        // Stateless calls read slot 0..3 of [blank, t1, t2, t3] from a zero state.
        val stateless = j.calls.filter { it.stateless }
        assertEquals(listOf(listOf(BLANK, 0, 0, 0), listOf(BLANK, 10, 0, 0), listOf(BLANK, 10, 11, 0), listOf(BLANK, 10, 11, 12)), stateless.map { it.tokens })
        assertEquals(listOf(0, 1, 2, 3), stateless.map { it.slot })
        // decode_1 starts from the state after the four slots, with the fourth token emitted.
        val stateful = j.calls.filter { !it.stateless }
        assertEquals(13, stateful[0].token)
        assertEquals(listOf(BLANK, 10, 11, 12), stateful[0].state)
        assertEquals(14, stateful[1].token)
        assertEquals(listOf(BLANK, 10, 11, 12, 13), stateful[1].state)
        // stateful[1] (frame 5) answered a blank: it left the state and the token as they were, so frame 6 repeats them.
        assertEquals(listOf(5, 6), listOf(stateful[1].t, stateful[2].t))
        assertEquals(stateful[1].state, stateful[2].state)
        assertEquals(stateful[1].token, stateful[2].token)
        assertEquals(listOf(BLANK, 10, 11, 12, 13, 14), stateful[3].state)
        assertEquals(j.calls.size, r.steps)
    }

    @Test fun tenSymbolsAtOneFrameMoveToTheNext() {
        // A token with duration 0 at every frame: NeMo's max_symbols_per_step ends each frame after 10.
        val j = Scripted(frames = 2) { 7 to 0 }
        val r = TdtGreedy.decode(j)
        assertEquals(20, r.tokens.size)
        assertEquals(List(10) { 0 } + List(10) { 1 }, r.tokens.map { it.frame })
    }

    @Test fun decodingStopsAtTheLimit() {
        // Blanks everywhere but a token at frame 8; a stop at frame 6 never reaches it (the padded tail of a short clip).
        val j = Scripted(frames = 10) { c -> if (c.t == 8) 42 to 1 else BLANK to 1 }
        assertEquals(emptyList<Int>(), TdtGreedy.decode(j, limit = 6).tokens.map { it.id })
        assertEquals(listOf(42), TdtGreedy.decode(Scripted(frames = 10) { c -> if (c.t == 8) 42 to 1 else BLANK to 1 }).tokens.map { it.id })
    }

    @Test fun aBlankSkipsItsDurationAndAZeroDurationBlankOneFrame() {
        val j = Scripted(frames = 9) { BLANK to 3 }
        TdtGreedy.decode(j)
        assertEquals(listOf(0, 3, 6), j.calls.map { it.t })
        val k = Scripted(frames = 3) { BLANK to 0 }
        TdtGreedy.decode(k)
        assertEquals(listOf(0, 1, 2), k.calls.map { it.t })
    }

    @Test fun argmaxTakesTheFirstOnATie() {
        val a = floatArrayOf(1f, 3f, 3f, 2f)
        assertEquals(1, TdtGreedy.argmax(a, 0, 4))
        assertEquals(0, TdtGreedy.argmax(a, 2, 4))
    }
}
