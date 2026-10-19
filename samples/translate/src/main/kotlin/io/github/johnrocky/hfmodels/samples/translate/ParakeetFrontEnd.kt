package io.github.johnrocky.hfmodels.samples.translate

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The log-mel features parakeet-tdt-0.6b-v3 was trained on: NeMo's `FilterbankFeatures` in eval mode (NVIDIA NeMo main,
 * `nemo/collections/asr/parts/preprocessing/features.py`, and the model's `processor_config.json`): pre-emphasis 0.97;
 * a 400-sample Hann window (`periodic=False`) centred in a 512-point FFT; hop 160; `torch.stft(center=True)` with zero
 * padding; the power spectrum; librosa's Slaney mel filterbank (128 bins, 0 to 8 kHz, `norm="slaney"`); log(x + 2^-24);
 * each bin normalized by its mean and unbiased standard deviation (+ 1e-5). Written here instead of jLibrosa, whose
 * window is the whole 512 points.
 *
 * The graph takes a fixed window of [frames] mel frames ([frames] x 160 samples). The utterance is padded with exact
 * zeros to the window, and every frame of the window enters the normalization, padding included: the LiteRT exports
 * know the normalized floor of that padding as silence (normalizing the valid frames only and writing the padding as
 * zeros took short clips from 2.40 to 11.42 % WER, edge-llm-bench 2026-09-25, patch 06). Frames that see only padding
 * have a zero spectrum and are not transformed. Output layout: `out[m * frames + f]` ([1, 128, frames]).
 */
internal class ParakeetFrontEnd(val frames: Int) {
    private val mel = Slaney.filterbank()
    private val window = hannInFft()
    private val re = DoubleArray(NFFT)
    private val im = DoubleArray(NFFT)
    private val power = DoubleArray(NBIN)
    private val logMel = DoubleArray(NMEL * frames)

    init {
        require(frames > 1) { "the window needs at least two frames" }
    }

    /** Samples one call takes: the window. */
    val windowSamples: Int get() = frames * HOP

    /** [pcm]: mono 16 kHz in [-1, 1], at most [windowSamples]; [out]: NMEL x [frames] floats. */
    fun features(pcm: FloatArray, out: FloatArray) {
        require(pcm.size <= windowSamples) { "${pcm.size} samples is longer than the ${frames}-frame window" }
        require(out.size == NMEL * frames) { "out holds ${out.size} floats, the features are ${NMEL * frames}" }
        // Pre-emphasis in float32 as torch does it; the sample after the last one is -0.97 x the last (the padding is 0).
        val n = windowSamples
        val y = FloatArray(min(n, pcm.size + 1))
        if (y.isNotEmpty()) {
            y[0] = if (pcm.isNotEmpty()) pcm[0] else 0f
            for (i in 1 until y.size) y[i] = (if (i < pcm.size) pcm[i] else 0f) - PREEMPH * pcm[i - 1]
        }
        // Frame t covers samples [t * 160 - 256, t * 160 + 256) of the window; past the last of y it sees only zeros.
        val lastSeen = if (y.isEmpty()) -1 else minOf(frames - 1, (y.size - 1 + NFFT / 2) / HOP)
        for (t in 0 until frames) {
            if (t > lastSeen) {
                for (m in 0 until NMEL) logMel[m * frames + t] = LOG_FLOOR
                continue
            }
            val start = t * HOP - NFFT / 2
            for (k in 0 until NFFT) {
                val i = start + k
                re[k] = if (i in y.indices) y[i] * window[k] else 0.0
                im[k] = 0.0
            }
            fft(re, im)
            for (k in 0 until NBIN) power[k] = re[k] * re[k] + im[k] * im[k]
            for (m in 0 until NMEL) {
                var acc = 0.0
                val w = mel.weights[m]
                val k0 = mel.first[m]
                for (j in w.indices) acc += w[j] * power[k0 + j]
                logMel[m * frames + t] = ln(acc + LOG_GUARD)
            }
        }
        for (m in 0 until NMEL) {
            val base = m * frames
            var sum = 0.0
            for (t in 0 until frames) sum += logMel[base + t]
            val mean = sum / frames
            var ss = 0.0
            for (t in 0 until frames) { val d = logMel[base + t] - mean; ss += d * d }
            val std = sqrt(ss / (frames - 1)) + STD_EPS
            for (t in 0 until frames) out[base + t] = ((logMel[base + t] - mean) / std).toFloat()
        }
    }

    /** librosa's Slaney mel filterbank (`librosa.filters.mel(sr=16000, n_fft=512, n_mels=128, norm="slaney")`), stored by its non-zero span per bin. */
    class Slaney(val first: IntArray, val weights: Array<DoubleArray>) {
        /** The dense [NMEL][NBIN] matrix, for the tests. */
        fun dense(): Array<DoubleArray> = Array(NMEL) { m -> DoubleArray(NBIN).also { row -> weights[m].copyInto(row, first[m]) } }

        companion object {
            private const val F_SP = 200.0 / 3.0
            private const val MIN_LOG_HZ = 1000.0
            private const val MIN_LOG_MEL = MIN_LOG_HZ / F_SP
            private val LOGSTEP = ln(6.4) / 27.0

            fun hzToMel(hz: Double): Double = if (hz >= MIN_LOG_HZ) MIN_LOG_MEL + ln(hz / MIN_LOG_HZ) / LOGSTEP else hz / F_SP
            fun melToHz(mel: Double): Double = if (mel >= MIN_LOG_MEL) MIN_LOG_HZ * exp(LOGSTEP * (mel - MIN_LOG_MEL)) else F_SP * mel

            fun filterbank(fMin: Double = 0.0, fMax: Double = RATE / 2.0): Slaney {
                val lo = hzToMel(fMin)
                val hi = hzToMel(fMax)
                // np.linspace(lo, hi, 130): i * step + lo, the last one exactly hi.
                val step = (hi - lo) / (NMEL + 1)
                val hz = DoubleArray(NMEL + 2) { melToHz(if (it == NMEL + 1) hi else it * step + lo) }
                val first = IntArray(NMEL)
                val weights = Array(NMEL) { m ->
                    val enorm = 2.0 / (hz[m + 2] - hz[m])
                    val row = DoubleArray(NBIN) { k ->
                        val f = k * RATE.toDouble() / NFFT
                        val lower = (f - hz[m]) / (hz[m + 1] - hz[m])
                        val upper = (hz[m + 2] - f) / (hz[m + 2] - hz[m + 1])
                        // librosa stores the triangle in its float32 matrix, then scales it by the norm in place: two roundings.
                        (max(0.0, min(lower, upper)).toFloat().toDouble() * enorm).toFloat().toDouble()
                    }
                    val a = row.indexOfFirst { it != 0.0 }.coerceAtLeast(0)
                    val b = row.indexOfLast { it != 0.0 }.coerceAtLeast(a)
                    first[m] = a
                    row.copyOfRange(a, b + 1)
                }
                return Slaney(first, weights)
            }
        }
    }

    companion object {
        const val RATE = 16000
        const val HOP = 160
        const val WIN = 400
        const val NFFT = 512
        const val NBIN = NFFT / 2 + 1
        const val NMEL = 128
        const val PREEMPH = 0.97f
        val LOG_GUARD = 2.0.pow(-24)
        const val STD_EPS = 1e-5
        /** log(0 + 2^-24): a frame that sees only padding. */
        val LOG_FLOOR = ln(LOG_GUARD)

        /** `torch.hann_window(400, periodic=False)` centred in 512 points (56 zeros each side). */
        fun hannInFft(): DoubleArray {
            val w = DoubleArray(NFFT)
            val left = (NFFT - WIN) / 2
            for (n in 0 until WIN) w[left + n] = 0.5 - 0.5 * cos(2.0 * PI * n / (WIN - 1))
            return w
        }

        /** Frames that carry audio: ceil(samples / hop), as the omni engine's patch 06 counts them. */
        fun validFrames(samples: Int): Int = (samples + HOP - 1) / HOP

        /**
         * Where greedy decoding stops: the encoder frames that carry audio, ceil(valid x encoder / mel), plus [margin]
         * (the word that ends the audio; patch 06's 4), or every frame when that reaches the end. NeMo bounds decoding
         * by `length` the same way; the 30 s graph otherwise decodes the padded tail and can write words there.
         */
        fun validEncoderFrames(samples: Int, melFrames: Int, encoderFrames: Int, margin: Int = 4): Int {
            val valid = validFrames(samples)
            if (valid >= melFrames) return encoderFrames
            val v = (valid.toLong() * encoderFrames + melFrames - 1).div(melFrames).toInt() + margin
            return if (v >= encoderFrames) encoderFrames else v
        }

        /** In-place iterative radix-2 FFT (size a power of two). */
        fun fft(re: DoubleArray, im: DoubleArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            var len = 2
            while (len <= n) {
                val ang = -2.0 * PI / len
                val wr = cos(ang)
                val wi = kotlin.math.sin(ang)
                var i = 0
                while (i < n) {
                    var cr = 1.0
                    var ci = 0.0
                    for (k in 0 until len / 2) {
                        val a = i + k
                        val b = a + len / 2
                        val xr = re[b] * cr - im[b] * ci
                        val xi = re[b] * ci + im[b] * cr
                        re[b] = re[a] - xr; im[b] = im[a] - xi
                        re[a] += xr; im[a] += xi
                        val nr = cr * wr - ci * wi
                        ci = cr * wi + ci * wr
                        cr = nr
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }
}
