package io.github.johnrocky.hfmodels.voice

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.johnrocky.hfmodels.BackendPolicy
import io.github.johnrocky.hfmodels.BundledCatalog
import io.github.johnrocky.hfmodels.HfModels
import io.github.johnrocky.hfmodels.LoadEvent
import io.github.johnrocky.hfmodels.LoadOptions
import io.github.johnrocky.hfmodels.ModelRef
import io.github.johnrocky.hfmodels.NetworkPolicy
import io.github.johnrocky.hfmodels.litert.Speak
import io.github.johnrocky.hfmodels.speech.Speaker
import io.github.johnrocky.hfmodels.speech.SpeechAudio
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Text to speech with `litert-community/kitten-tts-nano-0.8` on a named device, through the public
 * API at the commit the bundled catalog pins, offline: the descriptor is the catalog's entry (the repo's
 * own hfmodels.json at that commit), and every file is imported from `dir`, laid out like the repo, and
 * sha256-checked against it. In one process and one load:
 *   1. load: import, lexicon, graphs; the profile and each graph's load ms (from the notes);
 *   2. g2p: the publisher's three bench sentences (make_bench_inputs.py) through `phonemeIds`, compared
 *      with `ids_i` of bench_inputs.npz (espeak through the pip package's tokenizer);
 *   3. parity: the same sentences through `synthesize` against `ref_wav_i` / `ref_dur_i` (the publisher's
 *      fp32 graphs at graph speed 1.0, untrimmed), at the speed that puts 1.0 into the graph (1.25 for the
 *      bench voice, whose prior is 0.8): frames, lengths and two spectral correlations, `comparable=false`
 *      where the ids differed;
 *   4. speak: the ten fixed replies at the default speed (say.py's pace) once (16-bit WAVs to
 *      `<external files>/tts/<variant>/`), then again warm, median;
 *   5. memory: VmHWM (peak RSS) before the load, after it and after the replies;
 *   6. release.
 * RESULT lines under tag `hfmodels-speak`. Arguments: variant (fp32 | fp16), dir (default
 * /data/local/tmp/hfmodels/kitten), fixtures (default /data/local/tmp/hfmodels-voice/replies: replies.tsv,
 * id<TAB>text; tools/voice_fixtures.sh), voice (default expr-voice-2-m, the bench's), bench (default
 * <dir>/bench_inputs.npz), xnnpack_off (graphs to run without XNNPACK, comma-separated, e.g. `predictor`, or `none`;
 * the other graphs run with it; written as every variant's whole handler_config.xnnpack into a copy of the catalog's
 * descriptor, which then goes in explicitly; the WAVs go to `tts/<variant>-xnnpackoff-<graphs>/`; without it the
 * descriptor's setting applies, predictor off). Loading as an app does: by_id and network as in TranscribeDeviceTest.
 *
 *   tools/speak_gate.sh fp32
 *   GATE_TAG=speak-kitten-xnnpack-all tools/speak_gate.sh fp32 -Pandroid.testInstrumentationRunnerArguments.xnnpack_off=none
 */
@RunWith(AndroidJUnit4::class)
class SpeakDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val variant = args.getString("variant") ?: "fp32"
    private val base = File(args.getString("dir") ?: "/data/local/tmp/hfmodels/kitten")
    private val fixtures = File(args.getString("fixtures") ?: "/data/local/tmp/hfmodels-voice/replies")
    private val voice = args.getString("voice") ?: "expr-voice-2-m"
    private val bench = File(args.getString("bench") ?: File(base, "bench_inputs.npz").path)
    private val xnnpackOff = args.getString("xnnpack_off")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
    private val byId = args.getString("by_id")?.toBoolean() ?: false
    private val network = when (val n = args.getString("network") ?: "offline") {
        "offline" -> NetworkPolicy.Offline; "unmetered" -> NetworkPolicy.Unmetered; "any" -> NetworkPolicy.Any
        else -> error("network must be offline, unmetered or any, not '$n'")
    }
    private val failures = ArrayList<String>()

    private fun result(step: String, ok: Boolean, detail: String) {
        Log.i(TAG, "RESULT step=$step ok=$ok variant=$variant $detail")
        if (!ok) failures += "$step: $detail"
    }

    @Test fun loadG2pParitySpeakMemoryRelease(): Unit = runBlocking {
        val hwmBefore = status("VmHWM")
        val models = HfModels(ctx)
        val entry = BundledCatalog.load(ctx).defaultBinding(REPO) ?: error("$REPO is not in the bundled catalog")
        // Without the switch no descriptor goes in: the SDK finds the commit's as an app's load does (its descriptor cache,
        // the repo's hfmodels.json when the policy allows the network, the bundled catalog's entry).
        val descriptor = if (xnnpackOff == null) null else JSONObject(entry.descriptorJson).apply {
            val vs = getJSONArray("variants")
            for (i in 0 until vs.length()) vs.getJSONObject(i).getJSONObject("handler_config").put("xnnpack", JSONObject().apply { for (g in GRAPHS) put(g, g !in xnnpackOff) })
        }.toString()
        val prior = JSONObject(entry.descriptorJson).getJSONArray("variants").getJSONObject(0).getJSONObject("handler_config").optJSONObject("speed_priors")?.optDouble(voice, 1.0) ?: 1.0
        // The speed that puts 1.0 into the graph, the bench's: 1.25 for a prior of 0.8 (1.25 x 0.8 is 1.0 in float32).
        val benchSpeed = (1.0 / prior).toFloat()
        val opts = LoadOptions(backendPolicy = BackendPolicy.Auto, networkPolicy = network, descriptorJson = descriptor)
        val out = File(ctx.getExternalFilesDir(null), "tts/$variant" + if (xnnpackOff == null) "" else "-xnnpackoff-${xnnpackOff.joinToString("+")}").apply { mkdirs() }
        Log.i(TAG, "device=${Build.MODEL} soc=${Build.SOC_MANUFACTURER}/${Build.SOC_MODEL} build=${Build.DISPLAY} android=${Build.VERSION.RELEASE} model=$REPO@${if (byId) "id" else entry.modelCommit.take(8)} variant=$variant network=$network voice=$voice prior=$prior bench_speed=$benchSpeed xnnpack_off=${xnnpackOff?.joinToString("+") ?: "descriptor"} wav_dir=${out.path}")
        var model: Speaker? = null
        try {
            // 1. load
            val ref = if (byId) ModelRef(REPO, variant = variant) else ModelRef(REPO, revision = entry.modelCommit, variant = variant)
            val plan = models.inspect(ref, Speak, opts)
            val t0 = SystemClock.elapsedRealtime()
            for (f in plan.files) if (!f.cached) models.importFile(plan, f.id, File(base, f.path))
            val importMs = SystemClock.elapsedRealtime() - t0
            val events = ArrayList<LoadEvent>()
            val t1 = SystemClock.elapsedRealtime()
            val m = models.fromPretrained(ref, Speak, opts) { events += it }
            model = m
            val loadMs = SystemClock.elapsedRealtime() - t1
            val hwmLoad = status("VmHWM")
            result("load", events.last() is LoadEvent.Ready && events.none { it is LoadEvent.DownloadStarted },
                "commit=${m.info.commit.take(8)} binding=${m.info.bindingSource} descriptor=${m.info.descriptorOrigin.repo}@${m.info.descriptorOrigin.commit.take(8)}/${m.info.descriptorOrigin.path} " +
                    "files=${plan.files.size} import_ms=$importMs load_ms=$loadMs profile=${m.info.profileId} runtime=litert ${m.info.runtimeVersion} voices=${m.voices.size} default_voice=${m.voices[0]} sample_rate=${m.sampleRate} max_chars=${m.maxChars} notes=${m.info.notes.joinToString(" | ")}")

            // 2. g2p: the bench sentences' symbol ids against the publisher's
            val npz = Npz(bench)
            val idsEqual = BooleanArray(BENCH.size)
            for ((i, text) in BENCH.withIndex()) {
                val got = m.phonemeIds(text)
                val want = npz.ints("ids_$i")
                val diff = (0 until minOf(got.size, want.size)).firstOrNull { got[it] != want[it] } ?: if (got.size == want.size) null else minOf(got.size, want.size)
                idsEqual[i] = diff == null
                val around = diff?.let { k -> " got[${maxOf(0, k - 3)}..]=${got.drop(maxOf(0, k - 3)).take(8)} ref[${maxOf(0, k - 3)}..]=${want.drop(maxOf(0, k - 3)).take(8)}" } ?: ""
                result("g2p", true, "i=$i equal=${diff == null} n_got=${got.size} n_ref=${want.size} first_diff=${diff ?: "none"}$around")
            }

            // 3. parity: the same sentences synthesized, against the publisher's fp32 output
            var firstCallMs = 0.0
            for ((i, text) in BENCH.withIndex()) {
                val a = m.synthesize(text, voice, benchSpeed)
                if (i == 0) firstCallMs = a.timing.totalMs
                val refWav = npz.floats("ref_wav_$i")
                val refFrames = npz.ints("ref_dur_$i").sum()
                val n = minOf(a.samples.size, refWav.size)
                var maxAbs = 0.0
                for (k in 0 until n) maxAbs = maxOf(maxAbs, abs((a.samples[k] - refWav[k]).toDouble()))
                result("parity", true, "i=$i speed=$benchSpeed graph_speed=${(benchSpeed.toDouble() * prior).toFloat()} comparable=${idsEqual[i]} frames_equal=${a.timing.frames == refFrames} frames=${a.timing.frames}/$refFrames " +
                    "samples=${a.samples.size}/${refWav.size} untrimmed=${600 * a.timing.frames}/${refWav.size} logmel_corr=${"%.4f".format(logMelCorr(a.samples, refWav))} " +
                    "logspec_corr=${"%.4f".format(logSpecCorr(a.samples, refWav))} maxabs=${"%.3g".format(maxAbs)} ms_total=${"%.1f".format(a.timing.totalMs)}")
                writeWav(File(out, "bench$i.wav"), a)
                // The float samples too: the correlations above are computed on them, a 16-bit WAV moves them by up to 0.01.
                File(out, "bench$i.f32").writeBytes(ByteBuffer.allocate(a.samples.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { asFloatBuffer().put(a.samples) }.array())
            }

            // 4. speak: the ten replies, then again warm
            val replies = File(fixtures, "replies.tsv").readLines(Charsets.UTF_8).filter { it.isNotBlank() }.map { it.substringBefore('\t') to it.substringAfter('\t') }
            val first = HashMap<String, FloatArray>()
            for ((id, text) in replies) {
                val a = m.synthesize(text, voice)
                first[id] = a.samples
                val audioS = a.samples.size.toDouble() / a.sampleRate
                result("speak", a.samples.isNotEmpty(), "file=$id chars=${text.codePointCount(0, text.length)} frames=${a.timing.frames} audio_s=${"%.2f".format(audioS)} ms_g2p=${"%.1f".format(a.timing.g2pMs)} ms_synth=${"%.1f".format(a.timing.synthMs)} ms_total=${"%.1f".format(a.timing.totalMs)} rtf=${"%.3f".format(a.timing.totalMs / 1000 / audioS)}")
                writeWav(File(out, "$id.wav"), a)
            }
            val g2p = ArrayList<Double>(); val synth = ArrayList<Double>(); val total = ArrayList<Double>(); val rtf = ArrayList<Double>()
            var same = 0
            for ((id, text) in replies) {
                val a = m.synthesize(text, voice)
                g2p += a.timing.g2pMs; synth += a.timing.synthMs; total += a.timing.totalMs
                rtf += a.timing.totalMs / 1000 / (a.samples.size.toDouble() / a.sampleRate)
                if (a.samples.contentEquals(first[id])) same++
            }
            result("timing", same == replies.size, "warm_calls=${replies.size} ms_g2p_median=${"%.1f".format(median(g2p))} ms_synth_median=${"%.1f".format(median(synth))} ms_total_median=${"%.1f".format(median(total))} ms_total_min=${"%.1f".format(total.min())} ms_total_max=${"%.1f".format(total.max())} rtf_median=${"%.3f".format(median(rtf))} first_call_after_load_ms_total=${"%.1f".format(firstCallMs)} same_pcm_as_first=$same/${replies.size}")

            // 5. memory
            result("memory", true, "vmhwm_kb_before=$hwmBefore after_load=$hwmLoad after_speak=${status("VmHWM")} vmrss_kb_after_speak=${status("VmRSS")}")

            // 6. release
            val t2 = SystemClock.elapsedRealtime()
            m.closeAndJoin()
            result("release", true, "close_ms=${SystemClock.elapsedRealtime() - t2}")
            model = null
        } catch (t: Throwable) {
            Log.e(TAG, "failed", t)
            failures += "exception: ${t.javaClass.simpleName}: ${t.message}"
        } finally {
            runCatching { model?.closeAndJoin() }
            models.closeAndJoin()
        }
        Log.i(TAG, "RESULT ok=${failures.isEmpty()} model=$REPO variant=$variant device=${Build.MODEL} build=${Build.DISPLAY} failures=${failures.joinToString(" | ")}")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /** A field of /proc/self/status in kB (VmHWM: the peak resident set). */
    private fun status(key: String): Long =
        File("/proc/self/status").readLines().first { it.startsWith("$key:") }.substringAfter(':').trim().substringBefore(' ').toLong()

    private fun median(v: List<Double>) = v.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

    private fun writeWav(f: File, a: SpeechAudio) {
        val b = ByteBuffer.allocate(44 + a.samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + a.samples.size * 2).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(a.sampleRate).putInt(a.sampleRate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(a.samples.size * 2)
        for (x in a.samples) b.putShort((x.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        f.writeBytes(b.array())
    }

    /** The arrays of an `np.savez` file (little-endian int32 / float32, C order), by name. */
    private class Npz(file: File) {
        private val arrays = ZipFile(file).use { z -> z.entries().toList().associate { e -> e.name.removeSuffix(".npy") to z.getInputStream(e).use { it.readBytes() } } }

        private fun body(name: String, descr: String): ByteBuffer {
            val b = arrays[name] ?: error("bench_inputs.npz has no '$name'")
            val le = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            val (len, start) = if (b[6].toInt() == 1) (le.getShort(8).toInt() and 0xffff) to 10 else le.getInt(8) to 12
            val header = String(b, start, len, Charsets.ISO_8859_1)
            check(header.contains("'descr': '$descr'") && header.contains("'fortran_order': False")) { "$name: $header" }
            return ByteBuffer.wrap(b, start + len, b.size - start - len).slice().order(ByteOrder.LITTLE_ENDIAN)
        }

        fun ints(name: String): IntArray = body(name, "<i4").asIntBuffer().let { IntArray(it.remaining()).apply { it.get(this) } }
        fun floats(name: String): FloatArray = body(name, "<f4").asFloatBuffer().let { FloatArray(it.remaining()).apply { it.get(this) } }
    }

    companion object {
        const val TAG = "hfmodels-speak"
        const val REPO = "litert-community/kitten-tts-nano-0.8"
        /** The kitten graphs by their handler_config.xnnpack keys. */
        val GRAPHS = listOf("predictor", "prosody", "vocoder")

        /** make_bench_inputs.py SENTENCES, the order of ids_i / ref_wav_i in bench_inputs.npz. */
        val BENCH = listOf(
            "Hello! How can I help you today?",
            "The weather looks great for a walk in the park this afternoon.",
            "Streaming text to speech now runs entirely on the LiteRT runtime, with dynamic sequence lengths and no fixed buckets.",
        )

        private const val NFFT = 1024
        private const val HOP = 256
        private const val SR = 24000

        private val WIN = DoubleArray(NFFT) { 0.5 - 0.5 * cos(2 * PI * it / (NFFT - 1)) }

        /** 80 triangular mel filters (HTK scale, 0 to 12 kHz) over the 513 rfft bins. */
        private val MEL: Array<DoubleArray> = run {
            val mel = { f: Double -> 2595.0 * log10(1.0 + f / 700.0) }
            val hz = { m: Double -> 700.0 * (10.0.pow(m / 2595.0) - 1.0) }
            val edges = DoubleArray(82) { hz(mel(SR / 2.0) * it / 81.0) }
            Array(80) { j ->
                DoubleArray(NFFT / 2 + 1) { k ->
                    val f = k.toDouble() * SR / NFFT
                    when {
                        f < edges[j] || f > edges[j + 2] -> 0.0
                        f <= edges[j + 1] -> (f - edges[j]) / (edges[j + 1] - edges[j])
                        else -> (edges[j + 2] - f) / (edges[j + 2] - edges[j + 1])
                    }
                }
            }
        }

        /** bench.py's log_spec_corr: np.hanning(1024) frames at hop 256, log(|rfft| + 1e-5), Pearson over every bin of the common length. */
        fun logSpecCorr(a: FloatArray, b: FloatArray): Double {
            val m = minOf(a.size, b.size)
            fun logMag(p: DoubleArray) = DoubleArray(p.size) { ln(sqrt(p[it]) + 1e-5) }
            return pearson(logMag(power(a, m)), logMag(power(b, m)))
        }

        /** The same frames' power through [MEL], log(x + 1e-10), Pearson. */
        fun logMelCorr(a: FloatArray, b: FloatArray): Double {
            val m = minOf(a.size, b.size)
            fun mels(p: DoubleArray): DoubleArray {
                val bins = NFFT / 2 + 1
                val frames = p.size / bins
                return DoubleArray(frames * MEL.size) { i ->
                    val t = i / MEL.size; val w = MEL[i % MEL.size]
                    var s = 0.0
                    for (k in 0 until bins) s += w[k] * p[t * bins + k]
                    ln(s + 1e-10)
                }
            }
            return pearson(mels(power(a, m)), mels(power(b, m)))
        }

        /** |rfft|^2 of the Hann-windowed frames of the first [m] samples, frame-major. */
        private fun power(x: FloatArray, m: Int): DoubleArray {
            val frames = if (m < NFFT) 0 else 1 + (m - NFFT) / HOP
            val bins = NFFT / 2 + 1
            val out = DoubleArray(frames * bins)
            val re = DoubleArray(NFFT); val im = DoubleArray(NFFT)
            for (t in 0 until frames) {
                for (k in 0 until NFFT) { re[k] = x[t * HOP + k] * WIN[k]; im[k] = 0.0 }
                fft(re, im)
                for (k in 0 until bins) out[t * bins + k] = re[k] * re[k] + im[k] * im[k]
            }
            return out
        }

        /** In-place radix-2 FFT. */
        private fun fft(re: DoubleArray, im: DoubleArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
            }
            var len = 2
            while (len <= n) {
                val ang = -2 * PI / len
                for (i in 0 until n step len) for (k in 0 until len / 2) {
                    val c = cos(ang * k); val s = sin(ang * k)
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * c - im[i + k + len / 2] * s
                    val vi = re[i + k + len / 2] * s + im[i + k + len / 2] * c
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                }
                len = len shl 1
            }
        }

        private fun pearson(x: DoubleArray, y: DoubleArray): Double {
            if (x.isEmpty()) return Double.NaN
            val mx = x.average(); val my = y.average()
            var sxy = 0.0; var sxx = 0.0; var syy = 0.0
            for (i in x.indices) { val dx = x[i] - mx; val dy = y[i] - my; sxy += dx * dy; sxx += dx * dx; syy += dy * dy }
            return sxy / sqrt(sxx * syy)
        }
    }
}
