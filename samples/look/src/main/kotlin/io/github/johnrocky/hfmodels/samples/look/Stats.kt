package io.github.johnrocky.hfmodels.samples.look

import java.util.Locale

/** One streamed chunk: when it arrived (`SystemClock.elapsedRealtimeNanos`) and how many characters of answer text it carried. */
class Chunk(val atNanos: Long, val chars: Int)

/** The times of one answer, counted from the moment `stream` was called. */
data class Timing(
    /** From calling `stream` to the first chunk that carries text; null when no chunk did. */
    val firstTokenMs: Double?,
    /** From calling `stream` to the end of the stream. */
    val streamMs: Double,
    /** Chunks that carried text. */
    val chunks: Int,
    /** Every chunk the runtime delivered, with or without text. */
    val allChunks: Int,
    /** (chunks - 1) / (last text chunk - first text chunk) in seconds; null with fewer than two text chunks. */
    val chunksPerSecond: Double?,
    /** Characters of answer text. */
    val chars: Int,
    /** When each text chunk arrived, in ms after the call. */
    val chunkMs: List<Double>,
)

/** The numbers the screen and the record show for one answer. No Android types: the JVM tests run it. */
object Stats {
    fun of(startNanos: Long, endNanos: Long, chunks: List<Chunk>): Timing {
        val text = chunks.filter { it.chars > 0 }
        val first = text.firstOrNull()?.atNanos
        val last = text.lastOrNull()?.atNanos
        val spanS = if (first != null && last != null) (last - first) / 1e9 else 0.0
        return Timing(
            firstTokenMs = first?.let { (it - startNanos) / 1e6 },
            streamMs = (endNanos - startNanos) / 1e6,
            chunks = text.size,
            allChunks = chunks.size,
            chunksPerSecond = if (text.size >= 2 && spanS > 0) (text.size - 1) / spanS else null,
            chars = text.sumOf { it.chars },
            chunkMs = text.map { (it.atNanos - startNanos) / 1e6 },
        )
    }

    /**
     * The line under the answer: `first token 2.31 s · 21.8 chunks/s · CPU · on-device, offline`. [rateUnit] names
     * what the speed counts ("chunks/s" until a chunk is shown to be one token); [offline] when the load before the
     * answer made no network request.
     */
    fun line(t: Timing, backend: String, offline: Boolean, rateUnit: String = RATE_UNIT): String {
        val first = t.firstTokenMs?.let { String.format(Locale.US, "first token %.2f s", it / 1000.0) } ?: "no text"
        val rate = t.chunksPerSecond?.let { String.format(Locale.US, "%.1f %s", it, rateUnit) }
        val where = if (offline) "on-device, offline" else "on-device"
        return listOfNotNull(first, rate, backend, where).joinToString(" · ")
    }

    const val RATE_UNIT = "chunks/s"
}
