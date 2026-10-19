package io.github.johnrocky.hfmodels.samples.translate

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The parakeet front-end against the Python host it was checked with (NeMo's FilterbankFeatures in numpy with librosa's
 * filterbank; not in this repository). Resources under `parakeet/`: `mel128_slaney.dat` (librosa 1.0.0's Slaney filterbank,
 * per bin int32 first, int32 count, count float32) and `sweep_features.f32` (the Python features of [sweep] in a
 * 50-frame window, 128 x 50 float32). The fixture parity reads the host's dumps of the ten sentences from
 * `-Dtranslate.fixturesRoot` (or TRANSLATE_FIXTURES_ROOT): `fixtures/frontend/<id>.utt.f32` (the utterance the
 * endpointer cut) and `<id>.feat.f32` (its 128 x 3000 features), `mac_preview_data/asr_30s.json` (the stop frames);
 * without it that test is skipped and says so.
 */
class FrontEndTest {
    private fun bytes(name: String) = javaClass.classLoader!!.getResourceAsStream("parakeet/$name")!!.use { it.readBytes() }

    private fun floats(b: ByteArray): FloatArray = FloatArray(b.size / 4).also { ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }

    /** ZipformerFrontEndTest's signal: 0.32 s, a 200 -> 3200 Hz sweep plus LCG noise, float64 then float32. */
    private fun sweep(): FloatArray {
        var s = 1L
        return FloatArray(5120) { i ->
            s = (s * 1103515245L + 12345L) % (1L shl 31)
            val noise = s / (1L shl 31).toDouble() - 0.5
            val f = 200.0 + 3000.0 * i / 5120
            (0.3 * sin(2.0 * PI * f * i / 16000.0) + 0.05 * noise).toFloat()
        }
    }

    @Test fun slaneyFilterbankIsLibrosas() {
        val b = ByteBuffer.wrap(bytes("mel128_slaney.dat")).order(ByteOrder.LITTLE_ENDIAN)
        val mine = ParakeetFrontEnd.Slaney.filterbank().dense()
        var maxRel = 0.0
        var nonzero = 0
        for (m in 0 until ParakeetFrontEnd.NMEL) {
            val first = b.int
            val count = b.int
            val ref = DoubleArray(ParakeetFrontEnd.NBIN)
            for (j in 0 until count) ref[first + j] = b.float.toDouble()
            for (k in 0 until ParakeetFrontEnd.NBIN) {
                assertEquals("bin $m, fft bin $k: zero pattern", ref[k] == 0.0, mine[m][k] == 0.0)
                if (ref[k] != 0.0) { nonzero++; maxRel = maxOf(maxRel, abs(mine[m][k] - ref[k]) / abs(ref[k])) }
            }
        }
        println("Slaney filterbank: $nonzero non-zero weights, max relative difference to librosa $maxRel")
        assertEquals(504, nonzero)
        // Both round to float32 the same way (the triangle, then times the norm); one float32 ulp is 6e-8 relative.
        assertTrue("max relative difference $maxRel", maxRel <= 1e-6)
    }

    @Test fun sweepFeaturesMatchThePythonFrontEnd() {
        val ref = floats(bytes("sweep_features.f32"))
        val fe = ParakeetFrontEnd(50)
        val out = FloatArray(ParakeetFrontEnd.NMEL * 50)
        fe.features(sweep(), out)
        val d = ref.indices.maxOf { abs(ref[it] - out[it]).toDouble() }
        println("sweep features (128 x 50) max|diff| vs Python = $d")
        // Both compute in float64 from the same float32 samples (pre-emphasis in float32 on both sides); what is left is
        // the FFTs' rounding (numpy's pocketfft vs this radix-2) and one float32 rounding of values within +-5. Measured
        // 2026-10-19: 0.0 here, at most 2.4e-7 (one float32 step) on the twelve fixture utterances. The bar, 1e-4, leaves
        // room for another JVM's floating point and still catches a wrong window, filterbank or normalization (those move
        // features by 1e-2 and more).
        assertTrue("max|diff| $d", d <= 1e-4)
    }

    @Test fun framesThatSeeOnlyPaddingAreTheLogFloor() {
        val fe = ParakeetFrontEnd(100)
        val out = FloatArray(ParakeetFrontEnd.NMEL * 100)
        val pcm = FloatArray(1600) { (0.2 * sin(2.0 * PI * 440.0 * it / 16000.0)).toFloat() }
        fe.features(pcm, out)
        // 1,600 samples + the pre-emphasis tail: frames from (1600 + 256) / 160 + 1 = 12 on see only zeros, so every bin
        // has the same value there (the floor, normalized), and nothing is NaN.
        for (m in 0 until ParakeetFrontEnd.NMEL) {
            val floor = out[m * 100 + 12]
            for (t in 12 until 100) assertEquals(floor, out[m * 100 + t], 0f)
        }
        assertTrue(out.all { it.isFinite() })
        assertEquals(ParakeetFrontEnd.LOG_FLOOR, kotlin.math.ln(Math.pow(2.0, -24.0)), 0.0)
    }

    @Test fun fftOfAnImpulseIsFlatAndOfACosineIsTwoLines() {
        val re = DoubleArray(8).also { it[0] = 1.0 }
        val im = DoubleArray(8)
        ParakeetFrontEnd.fft(re, im)
        for (k in 0 until 8) { assertEquals(1.0, re[k], 1e-12); assertEquals(0.0, im[k], 1e-12) }
        val n = 16
        val c = DoubleArray(n) { kotlin.math.cos(2.0 * PI * 3 * it / n) }
        val ci = DoubleArray(n)
        ParakeetFrontEnd.fft(c, ci)
        for (k in 0 until n) assertEquals(if (k == 3 || k == n - 3) n / 2.0 else 0.0, kotlin.math.hypot(c[k], ci[k]), 1e-9)
    }

    @Test fun decodingStopsAtTheFramesThatCarryAudio() {
        // edge-llm-bench patch 06: ceil(valid x encoder / mel) + 4, all frames once that reaches the end.
        assertEquals(68, ParakeetFrontEnd.validEncoderFrames(81_280, 3000, 375))   // 5.08 s: 508 frames -> 63.5 -> 64, + 4
        assertEquals(375, ParakeetFrontEnd.validEncoderFrames(480_000, 3000, 375))
        assertEquals(372, ParakeetFrontEnd.validEncoderFrames(470_000, 3000, 375))  // 2938 frames -> 367.25 -> 368, + 4
        assertEquals(375, ParakeetFrontEnd.validEncoderFrames(477_000, 3000, 375))  // 2982 frames -> 373 + 4 reaches the end
        assertEquals(5, ParakeetFrontEnd.validEncoderFrames(1, 3000, 375))
        assertEquals(1, ParakeetFrontEnd.validFrames(1))
        assertEquals(508, ParakeetFrontEnd.validFrames(81_280))
    }

    @Test fun fixturesMatchThePythonDumps() {
        val root = System.getProperty("translate.fixturesRoot").orEmpty()
        assumeTrue("translate.fixturesRoot / TRANSLATE_FIXTURES_ROOT not set: the fixture parity is skipped", root.isNotEmpty())
        val dir = File(root, "fixtures/frontend")
        val json = JSONObject(File(root, "mac_preview_data/asr_30s.json").readText()).getJSONObject("files")
        val ids = dir.list()!!.filter { it.endsWith(".utt.f32") }.map { it.removeSuffix(".utt.f32") }.sorted()
        assertTrue("dumps in $dir", ids.size >= 2)
        val fe = ParakeetFrontEnd(3000)
        val out = FloatArray(ParakeetFrontEnd.NMEL * 3000)
        var worst = 0.0
        for (id in ids) {
            val utt = floats(File(dir, "$id.utt.f32").readBytes())
            val ref = floats(File(dir, "$id.feat.f32").readBytes())
            fe.features(utt, out)
            val d = ref.indices.maxOf { abs(ref[it] - out[it]).toDouble() }
            val stop = ParakeetFrontEnd.validEncoderFrames(utt.size, 3000, 375)
            val pyStop = json.getJSONObject(id).getJSONArray("pieces").getJSONObject(0).getInt("valid_enc")
            println("$id: ${utt.size} samples, features max|diff| = $d, stop $stop (Python $pyStop)")
            assertEquals("$id stop frame", pyStop, stop)
            worst = maxOf(worst, d)
        }
        println("fixtures: ${ids.size} utterances, worst max|diff| = $worst")
        assertTrue("worst max|diff| $worst", worst <= 1e-4)
    }
}
