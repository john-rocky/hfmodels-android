package io.github.johnrocky.hfmodels.litert

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The zipformer front-end and decoder without a graph. Resources under `zipformer/`: the LiteRT model
 * zoo app's `mel80_257.bin` (80 x 257 float32 LE) and `povey400.bin` (400 float32 LE), copied
 * unchanged from google-ai-edge/litert-samples c18346a8, and `fbank_ref.bin`: torchaudio 2.11.0's
 * `compliance.kaldi.fbank(x, num_mel_bins=80, sample_frequency=16000, dither=0.0, snip_edges=False,
 * high_freq=-400.0)` of [signal] (32 x 80 float32 LE).
 */
class ZipformerFrontEndTest {
    private fun floats(name: String, n: Int): FloatArray {
        val b = javaClass.classLoader!!.getResourceAsStream("zipformer/$name")!!.use { it.readBytes() }
        assertEquals("$name size", n * 4, b.size)
        return FloatArray(n).also { ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
    }

    private fun maxAbsDiff(a: FloatArray, b: FloatArray): Double = a.indices.maxOf { abs(a[it].toDouble() - b[it].toDouble()) }

    @Test fun melBanksMatchTheZooFile() {
        val ref = floats("mel80_257.bin", ZipformerFbank.NMEL * ZipformerFbank.NBIN)
        val d = maxAbsDiff(ZipformerFbank.melBanks(), ref)
        println("mel80_257 max|diff| = $d")
        assertTrue("mel bank max|diff| $d", d <= 1e-4)
    }

    @Test fun poveyWindowMatchesTheZooFile() {
        val ref = floats("povey400.bin", ZipformerFbank.WIN)
        val d = maxAbsDiff(ZipformerFbank.poveyWindow(), ref)
        println("povey400 max|diff| = $d")
        assertTrue("povey window max|diff| $d", d <= 1e-4)
    }

    /** 0.32 s: a 200 -> 3200 Hz sweep plus LCG noise, float64 then float32 (the generator of fbank_ref.bin, line for line). */
    private fun signal(): FloatArray {
        var s = 1L
        return FloatArray(5120) { i ->
            s = (s * 1103515245L + 12345L) % (1L shl 31)
            val noise = s / (1L shl 31).toDouble() - 0.5
            val f = 200.0 + 3000.0 * i / 5120
            (0.3 * sin(2.0 * PI * f * i / 16000.0) + 0.05 * noise).toFloat()
        }
    }

    @Test fun fbankMatchesTorchaudio() {
        val x = signal()
        val fb = ZipformerFbank()
        assertEquals(32, fb.frames(x.size))
        val ref = floats("fbank_ref.bin", 32 * ZipformerFbank.NMEL)
        val got = FloatArray(32 * ZipformerFbank.NMEL)
        fb.compute(x, got)
        val d = maxAbsDiff(got, ref)
        println("fbank vs torchaudio (32 x 80, log domain) max|diff| = $d")
        assertTrue("fbank max|diff| $d", d <= 1e-2)
    }

    @Test fun contractOfTheSixteenSecondWindow() {
        val c = ZipformerCtc(frames = 1600, blank = 0)
        assertEquals(796, c.t50)
        assertEquals(listOf(796, 398, 199, 100), c.biasLengths)
        assertEquals(398, c.tOut)
        // 2.73 s of audio (c01, 43700 samples): 273 fbank frames -> 133 frames at 50 Hz -> 67 output frames.
        val valid50 = c.valid50(ZipformerFbank().frames(43700))
        assertEquals(133, valid50)
        assertEquals(67, c.validOut(valid50))
        // The card's slices: b[:, ::2], b[:, ::4], b[:, ::8] of the 50 Hz bias.
        val b50 = c.bias(0, valid50)
        for (r in 1..3) {
            val ds = 1 shl r
            val sliced = FloatArray(c.biasLengths[r]) { b50[it * ds] }
            assertTrue("rate $r", sliced.contentEquals(c.bias(r, valid50)))
        }
        assertEquals(0f, b50[132]); assertEquals(-1000f, b50[133])
    }

    @Test fun greedyCtcDropsBlanksAndRepeats() {
        val pieces = mapOf(0 to "<blk>", 1 to "▁SET", 2 to "▁AN", 3 to "▁A", 4 to "LARM")
        val path = intArrayOf(0, 1, 1, 0, 2, 2, 2, 0, 0, 3, 4, 4, 0, 4, 0)
        val classes = 5
        val logits = FloatArray(path.size * classes) { -5f }
        for ((t, c) in path.withIndex()) logits[t * classes + c] = 3f
        assertEquals("SET AN ALARMLARM", ZipformerCtc.decode(logits, path.size, classes, blank = 0, pieces = pieces))
        assertEquals("SET", ZipformerCtc.decode(logits, 3, classes, blank = 0, pieces = pieces))
    }

    @Test fun tokensAreReadLikeTheZooApp() {
        val f = File.createTempFile("tokens", ".txt").apply { deleteOnExit() }
        f.writeText("<blk> 0\n<sos/eos> 1\n▁THE 4\n#0 500\n", Charsets.UTF_8)
        assertEquals(mapOf(0 to "<blk>", 1 to "<sos/eos>", 4 to "▁THE", 500 to "#0"), ZipformerCtc.readTokens(f))
    }
}
