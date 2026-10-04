package io.github.johnrocky.hfmodels.voice

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.johnrocky.hfmodels.BackendKind
import io.github.johnrocky.hfmodels.BackendPolicy
import io.github.johnrocky.hfmodels.HfModels
import io.github.johnrocky.hfmodels.LoadEvent
import io.github.johnrocky.hfmodels.LoadOptions
import io.github.johnrocky.hfmodels.ModelRef
import io.github.johnrocky.hfmodels.NetworkPolicy
import io.github.johnrocky.hfmodels.litert.Transcribe
import io.github.johnrocky.hfmodels.speech.Transcriber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Speech to text with `litert-community/Zipformer-medium-CR-CTC-LiteRT` on a named device, through the
 * public API and the development descriptor (catalog/dev, a test-APK asset, passed explicitly so the run
 * is offline; every file is imported from `dir`, laid out like the repo, and sha256-checked against the
 * descriptor). In one process and one load:
 *   1. load (import, tokens, compile) and the profile that came up;
 *   2. transcribe: each fixture WAV once, one RESULT line per file (exact and normalized match with the
 *      expected text; normalized = lower case, every run of characters outside [a-z0-9] -> one space, trimmed);
 *   3. timing: the ten files again (warm), median feature / inference / total ms;
 *   4. endpointer: the same WAVs through [Endpointer] in 20 ms chunks, then flush; each utterance, its
 *      pre-roll included, through transcribe (one line per file), and one line with the normalized matches,
 *      the pre-roll each file got and each utterance's start and end;
 *   5. release.
 * RESULT lines under tag `hfmodels-transcribe`. Arguments: variant (small_fp16 | medium_fp16), backend
 * (gpu | cpu | auto), dir (default /data/local/tmp/hfmodels/zipformer: the model files and tokens.txt),
 * fixtures (default /data/local/tmp/hfmodels-voice/commands: c01..c10.wav, 16 kHz mono s16, and
 * commands.tsv, id<TAB>expected text; tools/voice_fixtures.sh makes them). Two measurement switches, off by
 * default: preroll_ms (zeros put before each WAV for steps 2 and 3) and gpu_precision (default | fp32, written
 * into every variant's handler_config of the descriptor before the load).
 *
 *   tools/transcribe_gate.sh small_fp16 gpu
 */
@RunWith(AndroidJUnit4::class)
class TranscribeDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val testCtx = InstrumentationRegistry.getInstrumentation().context
    private val args = InstrumentationRegistry.getArguments()
    private val variant = args.getString("variant") ?: "small_fp16"
    private val backend = args.getString("backend") ?: "gpu"
    private val base = File(args.getString("dir") ?: "/data/local/tmp/hfmodels/zipformer")
    private val fixtures = File(args.getString("fixtures") ?: "/data/local/tmp/hfmodels-voice/commands")
    private val prerollMs = args.getString("preroll_ms")?.toInt() ?: 0
    private val gpuPrecision = args.getString("gpu_precision")
    private val failures = ArrayList<String>()

    private fun result(step: String, ok: Boolean, detail: String) {
        Log.i(TAG, "RESULT step=$step ok=$ok variant=$variant backend=$backend $detail")
        if (!ok) failures += "$step: $detail"
    }

    @Test fun loadTranscribeTimingEndpointRelease(): Unit = runBlocking {
        val models = HfModels(ctx)
        val policy = when (backend) { "cpu" -> BackendPolicy.Require(BackendKind.CPU); "gpu" -> BackendPolicy.Require(BackendKind.GPU); else -> BackendPolicy.Auto }
        val asset = testCtx.assets.open(DESCRIPTOR_ASSET).bufferedReader().use { it.readText() }
        val commit = JSONObject(asset).getString("revision")
        val descriptor = if (gpuPrecision == null) asset else JSONObject(asset).apply {
            val vs = getJSONArray("variants")
            for (i in 0 until vs.length()) vs.getJSONObject(i).getJSONObject("handler_config").put("gpu_precision", gpuPrecision)
        }.toString()
        val opts = LoadOptions(backendPolicy = policy, networkPolicy = NetworkPolicy.Offline, descriptorJson = descriptor)
        Log.i(TAG, "device=${Build.MODEL} soc=${Build.SOC_MANUFACTURER}/${Build.SOC_MODEL} build=${Build.DISPLAY} android=${Build.VERSION.RELEASE} model=$REPO@${commit.take(8)} variant=$variant backend=$backend preroll_ms=$prerollMs gpu_precision=${gpuPrecision ?: "descriptor"}")
        var model: Transcriber? = null
        try {
            // 1. load
            val ref = ModelRef(REPO, revision = commit, variant = variant)
            val plan = models.inspect(ref, Transcribe, opts)
            val t0 = SystemClock.elapsedRealtime()
            for (f in plan.files) if (!f.cached) models.importFile(plan, f.id, File(base, f.path))
            val importMs = SystemClock.elapsedRealtime() - t0
            val events = ArrayList<LoadEvent>()
            val t1 = SystemClock.elapsedRealtime()
            val m = models.fromPretrained(ref, Transcribe, opts) { events += it }
            model = m
            val loadMs = SystemClock.elapsedRealtime() - t1
            result("load", events.last() is LoadEvent.Ready && events.none { it is LoadEvent.DownloadStarted },
                "import_ms=$importMs load_ms=$loadMs profile=${m.info.profileId} runtime=litert ${m.info.runtimeVersion} window_s=${m.limits.windowSeconds} sample_rate=${m.limits.sampleRate} notes=${m.info.notes.joinToString(" | ")}")

            // 2. one transcription per fixture
            val expected = File(fixtures, "commands.tsv").readLines(Charsets.UTF_8).filter { it.isNotBlank() }.map { it.substringBefore('\t') to it.substringAfter('\t') }
            val wavs = expected.associate { (id, _) -> id to readWav(File(fixtures, "$id.wav")) }
            val audio = if (prerollMs == 0) wavs else wavs.mapValues { (_, pcm) -> FloatArray(prerollMs * 16) + pcm }
            var exact = 0; var norm = 0
            val first = HashMap<String, String>()
            for ((id, expect) in expected) {
                val t = m.transcribe(audio.getValue(id))
                first[id] = t.text
                val e = t.text == expect
                val n = normalize(t.text) == normalize(expect)
                if (e) exact++
                if (n) norm++
                result("transcribe", true, "file=$id exact=$e norm=$n ms_feature=${"%.1f".format(t.timing.featureMs)} ms_infer=${"%.1f".format(t.timing.inferenceMs)} ms_total=${"%.1f".format(t.timing.totalMs)} audio_s=${"%.2f".format(audio.getValue(id).size / 16000.0)} got=\"${t.text}\" expect=\"$expect\"")
            }
            result("summary", true, "files=${expected.size} exact=$exact norm=$norm preroll_ms=$prerollMs gpu_precision=${gpuPrecision ?: "descriptor"}")

            // 3. warm: the ten files again
            val feature = ArrayList<Double>(); val infer = ArrayList<Double>(); val total = ArrayList<Double>()
            var same = 0
            for ((id, _) in expected) {
                val t = m.transcribe(audio.getValue(id))
                feature += t.timing.featureMs; infer += t.timing.inferenceMs; total += t.timing.totalMs
                if (t.text == first[id]) same++
            }
            result("timing", same == expected.size, "warm_calls=${expected.size} ms_feature_median=${"%.1f".format(median(feature))} ms_infer_median=${"%.1f".format(median(infer))} ms_total_median=${"%.1f".format(median(total))} ms_infer_min=${"%.1f".format(infer.min())} ms_infer_max=${"%.1f".format(infer.max())} same_text_as_first=$same/${expected.size}")

            // 4. endpointer: 20 ms chunks, then flush; each utterance, its pre-roll included, through transcribe
            val spans = ArrayList<String>()
            val prerolls = ArrayList<String>()
            var utterances = 0
            var epNorm = 0
            for ((id, expect) in expected) {
                val pcm = wavs.getValue(id)
                val cut = endpoint(pcm, Endpointer())
                // The pre-roll this file got: the first utterance with it, less the same utterance without it.
                val bare = endpoint(pcm, Endpointer(preRollMs = 0))
                utterances += cut.size
                spans += "$id=${if (cut.isEmpty()) "none" else cut.joinToString("+") { it.span }}/${pcm.size / 16}ms"
                val preRollMs = if (cut.isEmpty() || bare.isEmpty()) null else (cut[0].pcm.size - bare[0].pcm.size) / 16
                prerolls += "$id=${preRollMs ?: "-"}"
                val text = cut.map { m.transcribe(it.pcm).text }.joinToString(" ").trim()
                val n = normalize(text) == normalize(expect)
                if (n) epNorm++
                result("endpointer_text", true, "file=$id norm=$n utterances=${cut.size} pre_roll_ms=${preRollMs ?: "-"} samples=${cut.joinToString("+") { it.pcm.size.toString() }} got=\"$text\" expect=\"$expect\"")
            }
            val ep = Endpointer()
            result("endpointer", utterances > 0, "files=${expected.size} utterances=$utterances norm=$epNorm/${expected.size} chunk_ms=20 start_rms=${ep.startRms} hangover_ms=${ep.hangoverMs} pre_roll_ms=${ep.preRollMs} " +
                "pre_roll_used_ms(what each WAV holds before its first voiced run, at most pre_roll_ms; the fixtures have no silence of their own in front): ${prerolls.joinToString(" ")} spans(start-end/file_length): ${spans.joinToString(" ")}")

            // 5. release
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
        Log.i(TAG, "RESULT ok=${failures.isEmpty()} model=$REPO variant=$variant backend=$backend device=${Build.MODEL} build=${Build.DISPLAY} failures=${failures.joinToString(" | ")}")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private class Cut(val pcm: FloatArray, val span: String)

    /** [pcm] through [ep] in 20 ms chunks, then flush; each utterance with its span in the file (start-end ms, and what ended it). */
    private fun endpoint(pcm: FloatArray, ep: Endpointer): List<Cut> {
        val out = ArrayList<Cut>()
        var fed = 0
        while (fed < pcm.size) {
            val n = minOf(320, pcm.size - fed)
            val evs = ep.feed(pcm.copyOfRange(fed, fed + n))
            fed += n
            for (ev in evs) if (ev is Endpointer.Event.Utterance) out += Cut(ev.pcm, "${(fed - ev.pcm.size) / 16}-${fed / 16}ms(hangover)")
        }
        ep.flush()?.let { out += Cut(it.pcm, "${(fed - it.pcm.size) / 16}-${fed / 16}ms(flush)") }
        return out
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun median(v: List<Double>) = v.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

    /** 16 kHz mono 16-bit PCM WAV -> floats in [-1, 1]; walks the RIFF chunks (ffmpeg writes a LIST chunk before data). */
    private fun readWav(f: File): FloatArray {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        require(String(b.array(), 0, 4, Charsets.US_ASCII) == "RIFF" && String(b.array(), 8, 4, Charsets.US_ASCII) == "WAVE") { "${f.name}: not a RIFF/WAVE file" }
        var p = 12
        var fmtOk = false
        while (p + 8 <= b.limit()) {
            val id = String(b.array(), p, 4, Charsets.US_ASCII)
            val n = b.getInt(p + 4)
            if (id == "fmt ") {
                val format = b.getShort(p + 8).toInt(); val channels = b.getShort(p + 10).toInt(); val rate = b.getInt(p + 12); val bits = b.getShort(p + 22).toInt()
                require(format == 1 && channels == 1 && rate == 16000 && bits == 16) { "${f.name}: format=$format channels=$channels rate=$rate bits=$bits, expected PCM mono 16000 Hz 16-bit" }
                fmtOk = true
            } else if (id == "data") {
                require(fmtOk) { "${f.name}: data before fmt" }
                return FloatArray(n / 2) { i -> b.getShort(p + 8 + 2 * i) / 32768f }
            }
            p += 8 + n + (n and 1)
        }
        error("${f.name}: no data chunk")
    }

    companion object {
        const val TAG = "hfmodels-transcribe"
        const val REPO = "litert-community/Zipformer-medium-CR-CTC-LiteRT"
        /** The development descriptor (catalog/dev, an androidTest asset): its `revision` is the model commit the files were listed at. */
        const val DESCRIPTOR_ASSET = "litert-community__Zipformer-medium-CR-CTC-LiteRT.hfmodels.json"
    }
}
