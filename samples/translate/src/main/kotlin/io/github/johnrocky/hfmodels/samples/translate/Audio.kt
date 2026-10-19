package io.github.johnrocky.hfmodels.samples.translate

import io.github.johnrocky.hfmodels.voice.Endpointer
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The microphone's and the speech recognizer's rate: 16 kHz mono. */
const val SAMPLE_RATE = 16000

/** 16-bit mono PCM WAV files: the fixtures in, the utterance the speech recognizer reads, a recording's tracks out. */
object Wav {
    /** The samples (in [-1, 1]) and the rate of a 16-bit PCM WAV; the first channel of a stereo file. Walks the RIFF chunks. */
    fun read(f: File): Pair<FloatArray, Int> {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        require(b.limit() >= 12 && String(b.array(), 0, 4, Charsets.US_ASCII) == "RIFF" && String(b.array(), 8, 4, Charsets.US_ASCII) == "WAVE") { "${f.name}: not a RIFF/WAVE file" }
        var p = 12
        var rate = 0
        var channels = 1
        var bits = 0
        while (p + 8 <= b.limit()) {
            val id = String(b.array(), p, 4, Charsets.US_ASCII)
            val n = b.getInt(p + 4)
            when (id) {
                "fmt " -> {
                    require(b.getShort(p + 8).toInt() == 1) { "${f.name}: not PCM" }
                    channels = b.getShort(p + 10).toInt()
                    rate = b.getInt(p + 12)
                    bits = b.getShort(p + 22).toInt()
                    require(bits == 16) { "${f.name}: $bits-bit samples, expected 16" }
                }
                "data" -> {
                    require(rate > 0) { "${f.name}: data before fmt" }
                    val frames = minOf(n, b.limit() - p - 8) / (2 * channels)
                    return FloatArray(frames) { i -> b.getShort(p + 8 + 2 * channels * i) / 32768f } to rate
                }
            }
            p += 8 + n + (n and 1)
        }
        error("${f.name}: no data chunk")
    }

    /** Mono 16-bit PCM. */
    fun write(f: File, pcm: FloatArray, rate: Int) {
        val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in pcm) data.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(pcm.size * 2)
        RandomAccessFile(f, "rw").use { it.setLength(0); it.write(h.array()); it.write(data.array()) }
    }
}

/**
 * Every utterance an [Endpointer] cuts from [pcm] (a file, as if spoken), in order: the audio and [trailMs] of silence go
 * through it in 20 ms chunks as the microphone delivers them, then the open utterance at the end ([Endpointer.flush]).
 * [onCut] sees the index of the chunk each utterance was cut at (a real-time player waits there).
 */
fun utterancesOf(pcm: FloatArray, endpointer: Endpointer, trailMs: Int = endpointer.hangoverMs + 200, onCut: (Int) -> Unit = {}): List<FloatArray> {
    endpointer.reset()
    val chunk = endpointer.sampleRate * 20 / 1000
    val all = pcm + FloatArray(endpointer.sampleRate * trailMs / 1000)
    val out = ArrayList<FloatArray>()
    var i = 0
    while (i < all.size) {
        for (e in endpointer.feed(all.copyOfRange(i, minOf(i + chunk, all.size)))) if (e is Endpointer.Event.Utterance) { out += e.pcm; onCut(i / chunk) }
        i += chunk
    }
    endpointer.flush()?.let { out += it.pcm; onCut(all.size / chunk) }
    return out
}
