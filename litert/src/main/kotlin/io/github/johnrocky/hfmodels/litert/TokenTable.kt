package io.github.johnrocky.hfmodels.litert

import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.ModelException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ByteBuffer
import java.nio.ShortBuffer
import java.nio.channels.FileChannel

/**
 * The token embedding table of a host-lookup graph: `[rows, hidden]` little-endian float16 or
 * float32 values in a flat file, memory-mapped read-only. The graph takes `inputs_embeds`
 * instead of token ids, so the host copies one row per position (the PAD row for padding, as the
 * publisher's reference host does). float16 is widened through a 65,536-entry table built once.
 * All calls happen on the model thread.
 */
internal class TokenTable private constructor(
    private val channel: FileChannel,
    private val map: ByteBuffer,
    val rows: Int,
    val hidden: Int,
    val dtype: Dtype,
) : AutoCloseable {
    enum class Dtype(val id: String, val bytes: Int) {
        FLOAT16("float16", 2), FLOAT32("float32", 4);

        companion object {
            fun parse(id: String): Dtype = entries.firstOrNull { it.id == id }
                ?: throw ModelException(ErrorCode.MANIFEST_INVALID, "handler_config.table_dtype '$id' is not supported (${entries.joinToString { it.id }})")
        }
    }

    private val shorts: ShortBuffer? = if (dtype == Dtype.FLOAT16) map.asShortBuffer() else null
    private val floats: FloatBuffer? = if (dtype == Dtype.FLOAT32) map.asFloatBuffer() else null
    private val tmp = ShortArray(hidden)

    /** Copies the row of token `id` into `dst[off, off + hidden)`. */
    fun copyRow(id: Int, dst: FloatArray, off: Int) {
        if (id < 0 || id >= rows) throw ModelException(ErrorCode.INFERENCE_FAILED, "token id $id is outside the embedding table ($rows rows)", details = mapOf("token" to id.toString(), "rows" to rows.toString()))
        if (shorts != null) {
            shorts.position(id * hidden)
            shorts.get(tmp, 0, hidden)
            val lut = HALF_TO_FLOAT
            for (i in 0 until hidden) dst[off + i] = lut[tmp[i].toInt() and 0xFFFF]
        } else {
            floats!!.position(id * hidden)
            floats.get(dst, off, hidden)
        }
    }

    override fun close() { runCatching { channel.close() } }

    companion object {
        /** Maps `file` as a `[rows, hidden]` table of `dtype`; the file length must be an exact multiple of one row. */
        fun open(file: File, hidden: Int, dtype: Dtype): TokenTable {
            val rowBytes = hidden.toLong() * dtype.bytes
            val len = file.length()
            if (len <= 0 || len % rowBytes != 0L) throw ModelException(ErrorCode.MANIFEST_INVALID, "'${file.name}' has $len bytes, not a whole number of ${dtype.id} rows of width $hidden", details = mapOf("bytes" to len.toString()))
            if (len > Int.MAX_VALUE) throw ModelException(ErrorCode.MANIFEST_INVALID, "'${file.name}' is larger than 2 GiB; this release maps a table in one piece")
            val channel = RandomAccessFile(file, "r").channel
            val map = try { channel.map(FileChannel.MapMode.READ_ONLY, 0, len).order(ByteOrder.LITTLE_ENDIAN) } catch (t: Throwable) { runCatching { channel.close() }; throw t }
            return TokenTable(channel, map, (len / rowBytes).toInt(), hidden, dtype)
        }

        /** IEEE 754 binary16 -> binary32, exact (subnormals, infinities and NaN included). */
        fun halfToFloat(h: Int): Float {
            val sign = if (h and 0x8000 != 0) -1f else 1f
            val exp = (h ushr 10) and 0x1F
            val mant = h and 0x3FF
            return when (exp) {
                0 -> sign * (mant.toFloat() * 5.9604645E-8f)           // subnormal: mant * 2^-24 (exact in float32)
                0x1F -> if (mant == 0) sign * Float.POSITIVE_INFINITY else Float.NaN
                else -> Float.fromBits(((h and 0x8000) shl 16) or ((exp + 112) shl 23) or (mant shl 13))
            }
        }

        val HALF_TO_FLOAT: FloatArray by lazy { FloatArray(65536) { halfToFloat(it) } }
    }
}
