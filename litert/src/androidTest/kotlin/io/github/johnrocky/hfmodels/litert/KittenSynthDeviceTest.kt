package io.github.johnrocky.hfmodels.litert

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The kitten synthesizer alone on a named device, below the public API: the publisher's bench inputs
 * (bench_inputs.npz: the espeak ids and the style row of make_bench_inputs.py's three sentences)
 * straight into [KittenSynthesizer], so the graphs are compared with the publisher's output where this
 * SDK's G2P gives other ids (SpeakDeviceTest, step g2p). Per sentence: the durations against
 * `ref_dur_i`, and the waveform against `ref_wav_i` (the publisher's fp32 graphs on a Mac, untrimmed;
 * compared over the trimmed length, clamped as the synthesizer clamps); the samples go to
 * `<external files>/kitten/<variant>/synth_i.f32` for a comparison on the Mac. Then VmRSS / VmHWM and
 * the Java heap after each piece the speak handler loads (dictionary, g2p graph, each of the three
 * graphs) and after each sentence. RESULT lines under tag `hfmodels-kitten`. Arguments: variant (fp32 | fp16), dir
 * (default /data/local/tmp/hfmodels/kitten, laid out like the repo).
 *
 *   GATE_MODULE=litert GATE_TEST=KittenSynthDeviceTest GATE_TAG=kitten-synth tools/speak_gate.sh fp32
 */
@RunWith(AndroidJUnit4::class)
class KittenSynthDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val variant = args.getString("variant") ?: "fp32"
    private val base = File(args.getString("dir") ?: "/data/local/tmp/hfmodels/kitten")
    private val failures = ArrayList<String>()

    private fun result(step: String, ok: Boolean, detail: String) {
        Log.i(TAG, "RESULT step=$step ok=$ok variant=$variant $detail")
        if (!ok) failures += "$step: $detail"
    }

    @Test fun benchIdsThroughTheSynthesizer() {
        val suffix = if (variant == "fp16") "_fp16" else ""
        val out = File(ctx.getExternalFilesDir(null), "kitten/$variant").apply { mkdirs() }
        Log.i(TAG, "device=${Build.MODEL} build=${Build.DISPLAY} android=${Build.VERSION.RELEASE} litert=${BuildConfig.LITERT_VERSION} variant=$variant dir=$base out=${out.path}")
        val memory = ArrayList<String>()
        fun mem(step: String) {
            val status = File("/proc/self/status").readLines()
            fun kb(key: String) = status.first { it.startsWith("$key:") }.substringAfter(':').trim().substringBefore(' ')
            val rt = Runtime.getRuntime()
            memory += "$step=rss:${kb("VmRSS")},hwm:${kb("VmHWM")},heap:${(rt.totalMemory() - rt.freeMemory()) / 1024}"
        }
        mem("start")
        val dictionary = KittenG2P.readDictionary(File(base, "g2p/g2p_dict.txt.gz"))
        mem("dictionary")
        val meta = KittenNeuralG2P.Meta.parse(File(base, "g2p/g2p_meta.json").readText())
        val neural = LiteRtDecisionModel.Runtime.call {
            KittenNeuralG2P.open(File(base, "g2p/dp_g2p_matcha_fp16.tflite"), meta, CompiledModel.Options(Accelerator.CPU).apply { cpuOptions = CompiledModel.CpuOptions(numThreads = 4) }, LiteRtDecisionModel.Runtime.environment(ctx))
        }
        mem("g2p_graph")
        val loadMs = LinkedHashMap<String, Long>()
        val synth = LiteRtDecisionModel.Runtime.call {
            KittenSynthesizer.open(File(base, "kitten_predictor$suffix.tflite"), File(base, "kitten_prosody$suffix.tflite"), File(base, "kitten_vocoder$suffix.tflite"), 4, 5000, 1200, loadMs) { mem(it) }
        }
        try {
            val npz = Npz(File(base, "bench_inputs.npz"))
            for (i in 0 until 3) {
                val ids = npz.ints("ids_$i")
                val style = npz.floats("style_$i")
                val refDur = npz.ints("ref_dur_$i")
                val refWav = npz.floats("ref_wav_$i")
                val t0 = System.nanoTime()
                val r = LiteRtDecisionModel.Runtime.call { synth.synthesize(ids, style, 1f) }
                val ms = (System.nanoTime() - t0) / 1e6
                val n = minOf(r.samples.size, refWav.size)
                var maxAbs = 0.0
                for (k in 0 until n) maxAbs = maxOf(maxAbs, abs((r.samples[k] - refWav[k].coerceIn(-1f, 1f)).toDouble()))
                val durDiff = refDur.indices.count { it >= r.durations.size || r.durations[it] != refDur[it] }
                File(out, "synth_$i.f32").writeBytes(ByteBuffer.allocate(r.samples.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { asFloatBuffer().put(r.samples) }.array())
                mem("bench$i")
                result("synth", true, "i=$i n_ids=${ids.size} durations_equal=${r.durations.contentEquals(refDur)} durations_differing=$durDiff frames=${r.frames}/${refDur.sum()} samples=${r.samples.size} ref_samples=${refWav.size} maxabs_vs_ref=${"%.3g".format(maxAbs)} ms=${"%.1f".format(ms)}")
            }
            result("memory", true, memory.joinToString(" ") + " (kB) graph_load_ms=$loadMs dictionary_words=${dictionary.size}")
        } catch (t: Throwable) {
            Log.e(TAG, "failed", t)
            failures += "exception: ${t.javaClass.simpleName}: ${t.message}"
        } finally {
            LiteRtDecisionModel.Runtime.call { synth.close(); neural.close() }
        }
        Log.i(TAG, "RESULT ok=${failures.isEmpty()} variant=$variant device=${Build.MODEL} failures=${failures.joinToString(" | ")}")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
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
        const val TAG = "hfmodels-kitten"
    }
}
