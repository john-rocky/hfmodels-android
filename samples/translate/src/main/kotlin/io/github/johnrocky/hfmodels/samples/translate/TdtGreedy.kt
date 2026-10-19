// The decode loop follows google-ai-edge/litert-samples, samples/litert/speech_recognition/AndroidApp/.../TdtDecoder.kt
// (Apache-2.0) with the two stateful-decode fixes of 8a32fa03, plus NeMo's max_symbols_per_step and the valid-length stop.
package io.github.johnrocky.hfmodels.samples.translate

/**
 * The parakeet graph's decoder signatures as the greedy loop uses them. Both compute the joint for every encoder frame
 * and the loop reads one row of it ([t], [slot]): `TOKENS` vocabulary logits, the blank last, then `DURATIONS`.
 */
internal interface TdtJoint {
    /** Encoder frames of the window. */
    val frames: Int

    /**
     * `decode`: the [tokens] array of STATELESS_SLOTS from a zero LSTM state; writes the logits row of ([t], [slot]) into
     * [row] and keeps the LSTM state after the whole array for [adoptStateless].
     */
    fun stateless(tokens: IntArray, t: Int, slot: Int, row: FloatArray)

    /** `decode_1`: one [token] from the current state; writes the row of [t] and keeps the state after the token for [adopt]. */
    fun stateful(token: Int, t: Int, row: FloatArray)

    /** The current state becomes the one the last [stateless] call ended with. */
    fun adoptStateless()

    /** The current state becomes the one the last [stateful] call ended with. */
    fun adopt()
}

/** Token-and-duration transducer greedy decoding over [TdtJoint]. */
internal object TdtGreedy {
    const val BLANK = 8192
    const val TOKENS = BLANK + 1
    const val DURATIONS = 5
    const val ROW = TOKENS + DURATIONS
    const val STATELESS_SLOTS = 4
    /** The model's `max_symbols_per_step` (nvidia/parakeet-tdt-0.6b-v3 config.json): after 10 tokens at one frame, the next frame. */
    const val MAX_SYMBOLS_PER_STEP = 10

    class Token(val id: Int, val frame: Int)
    class Result(val tokens: List<Token>, val steps: Int)

    /**
     * Greedy decoding up to encoder frame [limit] (the valid-length stop). The first STATELESS_SLOTS - 1 tokens come from
     * the stateless `decode` over [blank, t1, t2, t3]; once its four slots hold real tokens, the state after them goes to
     * `decode_1` with the next token (an unfilled zero slot would skew it). On `decode_1` the LSTM state advances only when
     * a token is emitted: taking it on a blank would feed the last token in again at every blank.
     */
    fun decode(joint: TdtJoint, limit: Int = joint.frames): Result {
        val end = limit.coerceIn(0, joint.frames)
        val row = FloatArray(ROW)
        val slots = IntArray(STATELESS_SLOTS).also { it[0] = BLANK }
        var stateless = true
        var slot = 0
        var last = BLANK
        val out = ArrayList<Token>()
        var t = 0
        var steps = 0
        var atFrame = -1
        var symbols = 0
        while (t < end) {
            if (t != atFrame) { atFrame = t; symbols = 0 }
            if (symbols >= MAX_SYMBOLS_PER_STEP) { t++; continue }
            if (stateless) joint.stateless(slots, t, slot, row) else joint.stateful(last, t, row)
            steps++
            val token = argmax(row, 0, TOKENS)
            val duration = argmax(row, TOKENS, ROW)
            if (token != BLANK) {
                symbols++
                out += Token(token, t)
                if (stateless) {
                    slot++
                    if (slot < STATELESS_SLOTS) {
                        slots[slot] = token
                    } else {
                        joint.adoptStateless()
                        stateless = false
                        last = token
                    }
                } else {
                    joint.adopt()
                    last = token
                }
            }
            t += if (duration == 0 && token == BLANK) 1 else duration
        }
        return Result(out, steps)
    }

    /** The index (from [from]) of the largest value in [from, until); the first on a tie. */
    fun argmax(a: FloatArray, from: Int, until: Int): Int {
        var best = from
        for (i in from + 1 until until) if (a[i] > a[best]) best = i
        return best - from
    }
}
