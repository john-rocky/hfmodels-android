// Ported from google-ai-edge/litert-samples c18346a8, samples/litert_model_zoo/android/.../models/zipformer/ZipformerFbank.kt (Apache-2.0).
package io.github.johnrocky.hfmodels.litert

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * Kaldi-compatible 80-mel log filterbank matching `torchaudio.compliance.kaldi.fbank` with
 * dither=0, snip_edges=false (reflect padding), povey window, low_freq=20, high_freq=-400: the
 * icefall Zipformer front-end. Input PCM stays in [-1, 1] (not scaled to the int16 range) and there
 * is no CMN. The mel bank and the povey window are computed here ([melBanks], [poveyWindow]) where
 * the zoo app reads them from assets; the JVM test checks both against the zoo's files.
 */
internal class ZipformerFbank(
    private val mel: FloatArray = melBanks(),
    private val win: FloatArray = poveyWindow(),
) {
    companion object {
        const val SR = 16000
        const val WIN = 400
        const val HOP = 160
        const val NFFT = 512
        const val NBIN = 257
        const val NMEL = 80
        const val LOW_FREQ = 20.0
        const val HIGH_FREQ = -400.0
        const val LOG_PAD = -23.025851f // ln(1e-10), the icefall padding value
        private const val EPS = 1.1920928955078125e-07f
        private const val PREEMPH = 0.97f
        private const val PAD = WIN / 2 - HOP / 2 // 120, snip_edges=false reflect margin

        /**
         * Kaldi's triangular mel filters as `torchaudio.compliance.kaldi.get_mel_banks` builds them (no VTLN warp),
         * with the zero Nyquist column `fbank()` appends: [numBins][paddedWindow / 2 + 1], row-major.
         */
        fun melBanks(numBins: Int = NMEL, paddedWindow: Int = NFFT, sampleRate: Double = SR.toDouble(), lowFreq: Double = LOW_FREQ, highFreq: Double = HIGH_FREQ): FloatArray {
            val nyquist = 0.5 * sampleRate
            val high = if (highFreq <= 0.0) highFreq + nyquist else highFreq
            val fftBins = paddedWindow / 2
            val binWidth = sampleRate / paddedWindow
            val melLow = melScale(lowFreq)
            val delta = (melScale(high) - melLow) / (numBins + 1)
            val out = FloatArray(numBins * (fftBins + 1))
            for (b in 0 until numBins) {
                val left = melLow + b * delta
                val center = melLow + (b + 1) * delta
                val right = melLow + (b + 2) * delta
                for (k in 0 until fftBins) {
                    val m = melScale(binWidth * k)
                    val up = (m - left) / (center - left)
                    val down = (right - m) / (right - center)
                    out[b * (fftBins + 1) + k] = max(0.0, minOf(up, down)).toFloat()
                }
            }
            return out
        }

        /** The povey window: a symmetric Hann window to the power 0.85 (`torch.hann_window(n, periodic=False).pow(0.85)`). */
        fun poveyWindow(n: Int = WIN): FloatArray = FloatArray(n) { i -> (0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))).pow(0.85).toFloat() }

        private fun melScale(f: Double): Double = 1127.0 * ln(1.0 + f / 700.0)
    }

    private val cosT = FloatArray(NFFT / 2) { cos(2.0 * PI * it / NFFT).toFloat() }
    private val sinT = FloatArray(NFFT / 2) { kotlin.math.sin(2.0 * PI * it / NFFT).toFloat() }

    /** Frame count for n samples (snip_edges=false). */
    fun frames(n: Int): Int = (n + HOP / 2) / HOP

    /** pcm [-1,1] -> log-mel, [frames][80] row-major, written into [out] from offset 0. Needs at least [WIN] samples. */
    fun compute(pcm: FloatArray, out: FloatArray) {
        val n = pcm.size
        val nFrames = frames(n)
        val re = FloatArray(NFFT)
        val im = FloatArray(NFFT)
        val frame = FloatArray(WIN)
        val power = FloatArray(NBIN)

        // reflect-padded sample fetch: q<0 -> pcm[-q-1], q>=n -> pcm[2n-1-q]
        fun sample(p: Int): Float {
            val q = p - PAD
            return when {
                q < 0 -> pcm[-q - 1]
                q < n -> pcm[q]
                else -> pcm[2 * n - 1 - q]
            }
        }

        for (t in 0 until nFrames) {
            val base = t * HOP
            var mean = 0f
            for (i in 0 until WIN) {
                frame[i] = sample(base + i)
                mean += frame[i]
            }
            mean /= WIN
            // remove DC, pre-emphasis (replicate pad), povey window, zero-pad to NFFT
            var prev = frame[0] - mean
            for (i in 0 until WIN) {
                val cur = frame[i] - mean
                re[i] = (cur - PREEMPH * prev) * win[i]
                im[i] = 0f
                prev = cur
            }
            for (i in WIN until NFFT) {
                re[i] = 0f
                im[i] = 0f
            }
            fft(re, im)
            for (k in 0 until NBIN) power[k] = re[k] * re[k] + im[k] * im[k]
            val row = t * NMEL
            for (m in 0 until NMEL) {
                var acc = 0f
                val off = m * NBIN
                for (k in 0 until NBIN) acc += mel[off + k] * power[k]
                out[row + m] = ln(max(acc, EPS))
            }
        }
    }

    /** Iterative in-place radix-2 complex FFT of size NFFT (512). */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = NFFT
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
            var m = n shr 1
            while (m in 1..j) {
                j -= m
                m = m shr 1
            }
            j += m
        }
        var len = 2
        while (len <= n) {
            val half = len shr 1
            val step = n / len
            var i = 0
            while (i < n) {
                var k = 0
                for (jj in i until i + half) {
                    val wr = cosT[k]
                    val wi = -sinT[k]
                    val xr = re[jj + half] * wr - im[jj + half] * wi
                    val xi = re[jj + half] * wi + im[jj + half] * wr
                    re[jj + half] = re[jj] - xr
                    im[jj + half] = im[jj] - xi
                    re[jj] += xr
                    im[jj] += xi
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
    }
}
