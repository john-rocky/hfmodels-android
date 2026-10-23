package io.github.johnrocky.hfmodels.samples.loops

import java.util.Locale

/**
 * The times of one answer, from the stream's own clock (nanoseconds of `SystemClock.elapsedRealtimeNanos()`).
 *
 * A chunk is one streamed message that carried answer text or reasoning. The line calls chunks tokens, as
 * samples/chat does; README.md says how the count compared with the tokenizer's on the measured phone.
 */
data class Timing(
    /** From calling `stream` to the first chunk: the prompt's prefill and the first token. Null when no chunk came. */
    val firstTokenMs: Double?,
    /** (chunks - 1) / (last chunk - first chunk): the decode rate after the first token. Null below two chunks. */
    val tokensPerSecond: Double?,
    /** From calling `stream` to its end. */
    val totalMs: Double,
    val chunks: Int,
) {
    /** `first token 0.42 s · 17.1 tok/s · 9.6 s`, the line under an answer. */
    fun line(): String {
        val first = firstTokenMs?.let { String.format(Locale.US, "first token %.2f s", it / 1000.0) } ?: "no token"
        val rate = tokensPerSecond?.let { String.format(Locale.US, " · %.1f tok/s", it) } ?: ""
        return first + rate + String.format(Locale.US, " · %.1f s", totalMs / 1000.0)
    }

    companion object {
        /** [chunkNs]: the time each chunk reached the collector, in order. */
        fun of(startNs: Long, chunkNs: List<Long>, endNs: Long): Timing {
            val first = chunkNs.firstOrNull()
            val last = chunkNs.lastOrNull()
            val rate = if (chunkNs.size >= 2 && first != null && last != null && last > first) (chunkNs.size - 1) / ((last - first) / 1e9) else null
            return Timing(
                firstTokenMs = first?.let { (it - startNs) / 1e6 },
                tokensPerSecond = rate,
                totalMs = (endNs - startNs) / 1e6,
                chunks = chunkNs.size,
            )
        }
    }
}
