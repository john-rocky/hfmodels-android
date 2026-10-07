package com.mlboydaisuke.eg2demo

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal RIFF/WAVE helpers (from the Qwen3-ASR demo app): the PCM samples of a wav (walks the chunks), and a
 *  44-byte header in front of 16-bit mono PCM, which is the form InputData.Audio takes ("Supported format: WAV"). */
object Wav {
    class Pcm(val data: ByteArray, val sampleRate: Int, val channels: Int, val bits: Int) {
        val seconds: Double get() = data.size.toDouble() / (sampleRate * channels * (bits / 8))
    }

    fun read(file: File): Pcm {
        RandomAccessFile(file, "r").use { f ->
            val head = ByteArray(12).also { f.readFully(it) }
            require(String(head, 0, 4) == "RIFF" && String(head, 8, 4) == "WAVE") { "not a wav: $file" }
            var fmt: ByteBuffer? = null
            while (f.filePointer + 8 <= f.length()) {
                val hdr = ByteArray(8).also { f.readFully(it) }
                val id = String(hdr, 0, 4)
                val size = ByteBuffer.wrap(hdr, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
                when (id) {
                    "fmt " -> {
                        fmt = ByteBuffer.wrap(ByteArray(size.toInt()).also { f.readFully(it) }).order(ByteOrder.LITTLE_ENDIAN)
                        if (size % 2 == 1L) f.skipBytes(1)
                    }
                    "data" -> {
                        val m = requireNotNull(fmt) { "data before fmt in $file" }
                        val data = ByteArray(size.toInt()).also { f.readFully(it) }
                        return Pcm(data, sampleRate = m.getInt(4), channels = m.getShort(2).toInt(), bits = m.getShort(14).toInt())
                    }
                    else -> f.seek(f.filePointer + size + (size % 2))
                }
            }
        }
        error("no data chunk in $file")
    }

    /** A complete 16-bit mono WAV (44-byte header + [pcm]) in memory. */
    fun mono16(pcm: ByteArray, sampleRate: Int): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size)
        }
        return header.array() + pcm
    }

    /** RMS of the 16-bit little-endian samples in [from, to) (byte offsets), 0..1 of full scale. */
    fun rms(pcm: ByteArray, from: Int, to: Int): Double {
        val a = (from.coerceIn(0, pcm.size) / 2) * 2
        val b = (to.coerceIn(0, pcm.size) / 2) * 2
        if (b <= a) return 0.0
        var sum = 0.0
        var i = a
        while (i < b) {
            val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toDouble()
            sum += v * v
            i += 2
        }
        return Math.sqrt(sum / ((b - a) / 2)) / 32768.0
    }
}
