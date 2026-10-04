package io.github.johnrocky.hfmodels.litert

import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel

/**
 * The host embedding lookup of one request, two ways, on the phone, with the same ids and the same float16 table:
 *  - the way the litert-samples Model Zoo's vendored card hosts do it (models/typed_decisions/{gliner,gliclass,deberta}/,
 *    from each card's Android host): a new FloatArray per request and one absolute `ShortBuffer.get` per value, widened
 *    through a 65,536-entry table (GLiNER2.5-Decide, GLiClass: `lut`) or by bit arithmetic per value (Open-Decision: `arith`);
 *  - this module's [TokenTable]: one bulk get per row into a reused array, widened through the same kind of table.
 * The Zoo rows of the same graphs measured slower than the SDK rows (r8 of the typed-decisions lane); this times the
 * host stage whose code differs. The two outputs are compared bit for bit.
 */
internal object HostLookupBench {
    /** One RESULT detail string: medians of `repeats` timed lookups after `warmups`, both ways, and whether they agree. */
    fun run(table: File, hidden: Int, ids: IntArray, zooStyle: String, warmups: Int = 3, repeats: Int = 20): String {
        val values: ShortBuffer = RandomAccessFile(table, "r").channel.use { ch -> ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer() }
        val rows = (table.length() / (2L * hidden)).toInt()
        val zoo: (IntArray) -> FloatArray = when (zooStyle) {
            "lut" -> { x -> zooLut(values, rows, hidden, x) }
            "arith" -> { x -> zooArith(values, rows, hidden, x) }
            else -> throw IllegalArgumentException("zoo style '$zooStyle' (lut | arith)")
        }
        TokenTable.open(table, hidden, TokenTable.Dtype.FLOAT16).use { t ->
            val reused = FloatArray(ids.size * hidden)
            val sdk: (IntArray) -> FloatArray = { x -> for (p in x.indices) t.copyRow(x[p], reused, p * hidden); reused }
            val identical = zoo(ids).contentEquals(sdk(ids).copyOf())
            fun time(f: (IntArray) -> FloatArray): List<Double> {
                repeat(warmups) { f(ids) }
                return List(repeats) { val t0 = SystemClock.elapsedRealtimeNanos(); f(ids); (SystemClock.elapsedRealtimeNanos() - t0) / 1e6 }.sorted()
            }
            val z = time(zoo)
            val s = time(sdk)
            return "rows=${ids.size} hidden=$hidden zoo_style=$zooStyle zoo_ms_median=${z[z.size / 2]} zoo_ms_min=${z.first()} sdk_ms_median=${s[s.size / 2]} sdk_ms_min=${s.first()} " +
                "repeats=$repeats outputs_identical=$identical"
        }
    }

    /** The Zoo's GLiNER2.5-Decide and GLiClass `EmbeddingTable.lookup`: per value, an absolute get and the widening table. */
    private fun zooLut(values: ShortBuffer, rows: Int, hidden: Int, ids: IntArray): FloatArray {
        val lut = TokenTable.HALF_TO_FLOAT
        val out = FloatArray(ids.size * hidden)
        for ((row, id) in ids.withIndex()) {
            require(id in 0 until rows) { "Token ID outside the embedding table: $id" }
            val source = id * hidden
            val target = row * hidden
            for (column in 0 until hidden) out[target + column] = lut[values.get(source + column).toInt() and 0xffff]
        }
        return out
    }

    /** The Zoo's Open-Decision `EmbeddingTable.lookup`: per value, an absolute get and the bit arithmetic of its `halfToFloat`. */
    private fun zooArith(values: ShortBuffer, rows: Int, hidden: Int, ids: IntArray): FloatArray {
        val out = FloatArray(ids.size * hidden)
        for ((position, id) in ids.withIndex()) {
            require(id in 0 until rows) { "Token id $id is outside the table" }
            val base = id * hidden
            val offset = position * hidden
            for (c in 0 until hidden) out[offset + c] = halfToFloat(values.get(base + c))
        }
        return out
    }

    private fun halfToFloat(half: Short): Float {
        val h = half.toInt() and 0xffff
        val sign = (h and 0x8000) shl 16
        val exponent = (h shr 10) and 0x1f
        val mantissa = h and 0x3ff
        val bits = when {
            exponent == 0 -> if (mantissa == 0) sign else {
                var m = mantissa
                var e = 127 - 15 + 1
                while (m and 0x400 == 0) { m = m shl 1; e-- }
                sign or (e shl 23) or ((m and 0x3ff) shl 13)
            }
            exponent == 0x1f -> sign or 0x7f800000 or (mantissa shl 13)
            else -> sign or ((exponent + 127 - 15) shl 23) or (mantissa shl 13)
        }
        return java.lang.Float.intBitsToFloat(bits)
    }
}
