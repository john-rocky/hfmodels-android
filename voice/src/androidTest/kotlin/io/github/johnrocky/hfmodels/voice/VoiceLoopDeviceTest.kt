package io.github.johnrocky.hfmodels.voice

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.johnrocky.hfmodels.BackendKind
import io.github.johnrocky.hfmodels.BackendPolicy
import io.github.johnrocky.hfmodels.BundledCatalog
import io.github.johnrocky.hfmodels.HfModels
import io.github.johnrocky.hfmodels.LoadOptions
import io.github.johnrocky.hfmodels.ModelRef
import io.github.johnrocky.hfmodels.NetworkPolicy
import io.github.johnrocky.hfmodels.PreparedModel
import io.github.johnrocky.hfmodels.Task
import io.github.johnrocky.hfmodels.Tasks
import io.github.johnrocky.hfmodels.litert.Speak
import io.github.johnrocky.hfmodels.litert.Transcribe
import io.github.johnrocky.hfmodels.voice.VoiceLoop.Event
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The voice loop on a named device: the transcriber, the speaker and one chat model loaded side by side in one
 * process (three HfModels clients on one store, as ToolsDeviceTest), one [VoiceLoop] over them with
 * [RecordingTools] (nothing on the phone changes), and the ten fixed commands through it. Every load is Offline;
 * files are imported from local directories and sha256-checked. RESULT lines under tag `hfmodels-voice`:
 *   1. load: Zipformer medium_fp16 on the GPU and Kitten fp32 on the CPU (development descriptors, test-APK
 *      assets), then the chat model (`llm` / `variant` / `backend`; the `descriptor` asset, or the bundled
 *      catalog's entry at its commit); VmHWM after the three;
 *   2. turn: each command, one line each. input=wav: its WAV and 1 s of silence go through an [Endpointer]
 *      (pre-roll 300 ms, hangover 800 ms) in 20 ms chunks, and the utterance it cuts (the hangover included) to
 *      `turn(pcm)`; input=text: the command's text to `turn(text)`. The line: what was heard, the calls and
 *      success against [FixedCommands], the loop's timing in ms from the end of the utterance (no player, so
 *      ms_first_audio is the end of the first sentence's synthesis), what was said. hangover_ms is the
 *      endpointer's: a speaker hears the first sound hangover_ms + ms_first_audio after they stop talking. Then
 *      a summary: successes, the medians, VmHWM after the turns;
 *   3. play (play=true): the first command once more through a loop with a [SpeechPlayer] (it sounds): the loop's
 *      ms_first_audio (the player's first write) and the same from the player's firstWriteAtNanos;
 *   4. listen (input=wav): the first command's WAV and silence through [VoiceLoop.listen] as a flow of chunks:
 *      the order of the events;
 *   5. release.
 * Arguments: llm (repo id), variant, backend (gpu | cpu), format (runtime | qwenxml | lfm), input (wav | text,
 * default wav), play (default false), dir (default /data/local/tmp/hfmodels/llm), descriptor (asset name; default
 * the bundled catalog), fixtures (default /data/local/tmp/hfmodels-voice/commands).
 *
 *   tools/voiceloop_gate.sh litert-community/gemma-4-E2B-it-litert-lm default gpu runtime wav
 */
@RunWith(AndroidJUnit4::class)
class VoiceLoopDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val testCtx = InstrumentationRegistry.getInstrumentation().context
    private val args = InstrumentationRegistry.getArguments()
    private val llm = args.getString("llm") ?: "litert-community/gemma-4-E2B-it-litert-lm"
    private val variant = args.getString("variant")
    private val backend = args.getString("backend") ?: "gpu"
    private val formatName = args.getString("format") ?: "runtime"
    private val input = args.getString("input") ?: "wav"
    private val play = args.getString("play")?.toBoolean() ?: false
    private val dir = File(args.getString("dir") ?: "/data/local/tmp/hfmodels/llm")
    private val descriptorAsset = args.getString("descriptor")
    private val fixtures = File(args.getString("fixtures") ?: "/data/local/tmp/hfmodels-voice/commands")
    private val failures = ArrayList<String>()

    private fun result(step: String, ok: Boolean, detail: String) {
        Log.i(TAG, "RESULT step=$step ok=$ok input=$input llm=$llm variant=${variant ?: "default"} backend=$backend format=$formatName $detail")
        if (!ok) failures += "$step: $detail"
    }

    @Test fun loadTurnsPlayListenRelease(): Unit = runBlocking {
        val format = when (formatName) { "runtime" -> ToolFormat.Runtime; "qwenxml" -> ToolFormat.QwenXml; "lfm" -> ToolFormat.LfmPythonic; else -> error("format must be runtime, qwenxml or lfm") }
        require(input == "wav" || input == "text") { "input must be wav or text" }
        val tomorrow = LocalDate.now().plusDays(1).toString()
        val asrModels = HfModels(ctx)
        val ttsModels = HfModels(ctx)
        val llmModels = HfModels(ctx)
        val open = ArrayList<PreparedModel>()
        var player: SpeechPlayer? = null
        Log.i(TAG, "device=${Build.MODEL} soc=${Build.SOC_MANUFACTURER}/${Build.SOC_MODEL} build=${Build.DISPLAY} android=${Build.VERSION.RELEASE} llm=$llm variant=${variant ?: "default"} backend=$backend format=$formatName input=$input play=$play tomorrow=$tomorrow store=${llmModels.root}")
        try {
            // 1. load
            val hwmStart = status("VmHWM")
            val asr = load(asrModels, Transcribe, ASR_DESCRIPTOR, "medium_fp16", BackendPolicy.Require(BackendKind.GPU), File("/data/local/tmp/hfmodels/zipformer")).also { open += it.model }
            val tts = load(ttsModels, Speak, TTS_DESCRIPTOR, "fp32", BackendPolicy.Auto, File("/data/local/tmp/hfmodels/kitten")).also { open += it.model }
            val llmPolicy = when (backend) { "cpu" -> BackendPolicy.Require(BackendKind.CPU); "gpu" -> BackendPolicy.Require(BackendKind.GPU); else -> BackendPolicy.Auto }
            val chat = load(llmModels, Tasks.Chat, descriptorAsset, variant, llmPolicy, dir).also { open += it.model }
            val hwmModels = status("VmHWM")
            result("load", true,
                "asr=${asr.model.info.repoId}/${asr.model.info.variantId}/${asr.model.info.profileId} asr_load_ms=${asr.loadMs} tts=${tts.model.info.repoId}/${tts.model.info.variantId}/${tts.model.info.profileId} tts_load_ms=${tts.loadMs} " +
                    "llm_profile=${chat.model.info.profileId} llm_variant=${chat.model.info.variantId} llm_commit=${chat.model.info.commit.take(8)} llm_import_ms=${chat.importMs} llm_load_ms=${chat.loadMs} " +
                    "vmhwm_kb_start=$hwmStart vmhwm_kb_after_models=$hwmModels vmrss_kb=${status("VmRSS")} tts_notes=${q(tts.model.info.notes.filter { "xnnpack" in it }.joinToString(" | "))}")

            // 2. the commands through one loop without a player
            val commands = File(fixtures, "commands.tsv").readLines(Charsets.UTF_8).filter { it.isNotBlank() }.map { it.substringBefore('\t') to it.substringAfter('\t') }
            val expected = FixedCommands.expected(tomorrow)
            val recording = RecordingTools()
            val loop = VoiceLoop(asr.model, chat.model, tts.model, recording.all, VoiceLoopConfig(toolFormat = format))
            val timings = ArrayList<VoiceLoop.TurnTiming>()
            var ok = 0
            var errors = 0
            for ((id, text) in commands) {
                val r = turn(loop, recording, id, text)
                val (success, extra) = FixedCommands.judge(expected.getValue(id), r.calls)
                if (success) ok++
                errors += r.errors.size
                val t = r.timing
                if (t != null) timings += t
                result("turn", t != null,
                    "file=$id heard=${q(r.heard ?: "")} success=$success calls=${r.calls.joinToString(",", "[", "]") { FixedCommands.describe(it) }} extra=$extra " +
                        "ms_transcribe=${f(t?.transcribeMs)} ms_first_token=${f(t?.firstTokenMs)} ms_first_sentence=${f(t?.firstSentenceMs)} ms_first_audio=${f(t?.firstAudioMs)} " +
                        "ms_reply=${f(t?.replyMs)} ms_speak=${f(t?.speakMs)} ms_total=${f(t?.totalMs)} hangover_ms=${if (input == "wav") HANGOVER_MS else 0} " +
                        "utterance_ms=${f(r.utteranceMs)} cut=${r.cut ?: "none"} sentences=${r.sentences.size} first_sentence=${q(r.sentences.firstOrNull() ?: "")} " +
                        "llm_turns=${t?.llmTurns ?: 0} tool_calls=${t?.toolCalls ?: 0} errors=${r.errors.joinToString(" | ", "[", "]") { "${it.code}: ${it.message.take(200)}" }} " +
                        "reply=${q(t?.reply ?: "")} expect=${expected.getValue(id).joinToString("+") { it.label }}")
            }
            val hwmTurns = status("VmHWM")
            result("summary", true,
                "success=$ok/${commands.size} ms_transcribe_median=${f(median(timings.map { it.transcribeMs }))} ms_first_token_median=${f(median(timings.mapNotNull { it.firstTokenMs }))} " +
                    "ms_first_sentence_median=${f(median(timings.mapNotNull { it.firstSentenceMs }))} ms_first_audio_median=${f(median(timings.mapNotNull { it.firstAudioMs }))} " +
                    "ms_reply_median=${f(median(timings.map { it.replyMs }))} ms_speak_median=${f(median(timings.mapNotNull { it.speakMs }))} ms_total_median=${f(median(timings.map { it.totalMs }))} " +
                    "hangover_ms=${if (input == "wav") HANGOVER_MS else 0} first_audio_n=${timings.count { it.firstAudioMs != null }} errors=$errors vmhwm_kb_after_models=$hwmModels vmhwm_kb_after_turns=$hwmTurns vmrss_kb=${status("VmRSS")}")

            // 3. the first command once more, out of the loudspeaker
            val (firstId, firstText) = commands.first()
            if (play) {
                val p = SpeechPlayer(tts.model.sampleRate).also { player = it }
                val sounding = VoiceLoop(asr.model, chat.model, tts.model, recording.all, VoiceLoopConfig(toolFormat = format, player = p))
                val r = turn(sounding, recording, firstId, firstText)
                val t = r.timing
                val fromTrack = r.startNanos?.let { s -> p.firstWriteAtNanos.takeIf { it >= s }?.let { (it - s) / 1e6 } }
                result("play", t?.firstAudioMs != null && fromTrack != null,
                    "file=$firstId heard=${q(r.heard ?: "")} ms_first_audio=${f(t?.firstAudioMs)} ms_first_audio_track=${f(fromTrack)} ms_first_sentence=${f(t?.firstSentenceMs)} ms_speak=${f(t?.speakMs)} " +
                        "ms_total=${f(t?.totalMs)} hangover_ms=${if (input == "wav") HANGOVER_MS else 0} sentences=${r.sentences.size} errors=${r.errors.size} reply=${q(t?.reply ?: "")}")
            }

            // 4. listen over the first command's audio
            if (input == "wav") {
                val chunks = chunks(readWav(File(fixtures, "$firstId.wav")) + FloatArray(SAMPLE_RATE * TRAILING_SILENCE_MS / 1000))
                val events = withTimeout(TURN_TIMEOUT_MS) { loop.listen(flow { for (c in chunks) emit(c) }).toList() }
                val names = events.map { it.javaClass.simpleName }
                val listenOk = names.firstOrNull() == "Listening" && names.lastOrNull() == "Listening" && names.count { it == "Heard" } == 1 && names.count { it == "Done" } == 1
                result("listen", listenOk, "file=$firstId events=${names.joinToString(",", "[", "]")} heard=${q((events.firstOrNull { it is Event.Heard } as Event.Heard?)?.text ?: "")}")
            }

            // 5. release
            val t0 = SystemClock.elapsedRealtime()
            for (m in open.reversed()) m.closeAndJoin()
            open.clear()
            result("release", true, "close_ms=${SystemClock.elapsedRealtime() - t0}")
        } catch (t: Throwable) {
            Log.e(TAG, "failed", t)
            failures += "exception: ${t.javaClass.simpleName}: ${t.message}"
        } finally {
            player?.close()
            for (m in open.reversed()) runCatching { m.closeAndJoin() }
            asrModels.closeAndJoin(); ttsModels.closeAndJoin(); llmModels.closeAndJoin()
        }
        Log.i(TAG, "RESULT ok=${failures.isEmpty()} input=$input llm=$llm variant=${variant ?: "default"} backend=$backend format=$formatName device=${Build.MODEL} build=${Build.DISPLAY} failures=${failures.joinToString(" | ")}")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    // ---- one turn ----

    private class TurnRun(
        val heard: String?, val calls: List<RecordingTools.Call>, val sentences: List<String>, val errors: List<Event.Error>,
        val timing: VoiceLoop.TurnTiming?, val utteranceMs: Double?, val cut: String?, val startNanos: Long?,
    )

    /** One command: its WAV through the endpointer to `turn(pcm)`, or its text to `turn(text)`. */
    private suspend fun turn(loop: VoiceLoop, recording: RecordingTools, id: String, text: String): TurnRun {
        val before = synchronized(recording.calls) { recording.calls.size }
        var utteranceMs: Double? = null
        var cut: String? = null
        val flow = if (input == "wav") {
            val (pcm, how) = endpoint(readWav(File(fixtures, "$id.wav")))
            utteranceMs = pcm.size * 1000.0 / SAMPLE_RATE
            cut = how
            loop.turn(pcm)
        } else loop.turn(text)
        // The loop's clock starts when collection begins: right after this.
        val start = System.nanoTime()
        val events = withTimeout(TURN_TIMEOUT_MS) { flow.toList() }
        val calls = synchronized(recording.calls) { recording.calls.subList(before, recording.calls.size).toList() }
        return TurnRun(
            (events.firstOrNull { it is Event.Heard } as Event.Heard?)?.text, calls,
            events.filterIsInstance<Event.Speaking>().map { it.sentence }, events.filterIsInstance<Event.Error>(),
            (events.lastOrNull() as? Event.Done)?.timing, utteranceMs, cut, start,
        )
    }

    /** The WAV and 1 s of silence through an endpointer in 20 ms chunks: the first utterance and whether the hangover or the end of the audio cut it. */
    private fun endpoint(wav: FloatArray): Pair<FloatArray, String> {
        val ep = Endpointer(preRollMs = 300, hangoverMs = HANGOVER_MS, maxUtteranceMs = 16000)
        for (c in chunks(wav + FloatArray(SAMPLE_RATE * TRAILING_SILENCE_MS / 1000))) {
            ep.feed(c).firstOrNull { it is Endpointer.Event.Utterance }?.let { return (it as Endpointer.Event.Utterance).pcm to "hangover" }
        }
        val rest = ep.flush() ?: error("the endpointer found no speech")
        return rest.pcm to "flush"
    }

    private fun chunks(pcm: FloatArray): List<FloatArray> = (pcm.indices step CHUNK).map { pcm.copyOfRange(it, minOf(it + CHUNK, pcm.size)) }

    // ---- loading ----

    private class Loaded<M : PreparedModel>(val model: M, val importMs: Long, val loadMs: Long)

    /**
     * Inspect, import every file not cached from [from] (laid out like the repo), load; Offline at an explicit commit:
     * the [asset] descriptor's `revision`, or (no asset: the chat model) the bundled catalog's entry for [llm].
     */
    private suspend fun <M : PreparedModel> load(models: HfModels, task: Task<M>, asset: String?, variant: String?, policy: BackendPolicy, from: File): Loaded<M> {
        val descriptor = asset?.let { a -> testCtx.assets.open(a).bufferedReader().use { it.readText() } }
        val repo = descriptor?.let { JSONObject(it).getString("model_id") } ?: llm
        val commit = descriptor?.let { JSONObject(it).getString("revision") }
            ?: BundledCatalog.load(ctx).defaultBinding(repo)?.modelCommit ?: error("$repo is not in the bundled catalog; pass descriptor=<asset>")
        val ref = ModelRef(repo, revision = commit, variant = variant)
        val opts = LoadOptions(backendPolicy = policy, networkPolicy = NetworkPolicy.Offline, descriptorJson = descriptor)
        val plan = models.inspect(ref, task, opts)
        val t0 = SystemClock.elapsedRealtime()
        for (f in plan.files) if (!f.cached) models.importFile(plan, f.id, File(from, f.path))
        val importMs = SystemClock.elapsedRealtime() - t0
        val t1 = SystemClock.elapsedRealtime()
        val model = models.fromPretrained(ref, task, opts)
        return Loaded(model, importMs, SystemClock.elapsedRealtime() - t1)
    }

    // ---- helpers ----

    private fun status(key: String): Long =
        File("/proc/self/status").readLines().first { it.startsWith("$key:") }.substringAfter(':').trim().substringBefore(' ').toLong()

    private fun median(v: List<Double>): Double? = v.sorted().let { s -> if (s.isEmpty()) null else if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

    private fun f(v: Double?) = v?.let { "%.0f".format(it) } ?: "none"

    private fun q(s: String) = "\"" + s.replace("\n", "\\n").replace("\"", "'") + "\""

    /** 16 kHz mono 16-bit PCM WAV -> floats in [-1, 1]; walks the RIFF chunks. */
    private fun readWav(f: File): FloatArray {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var p = 12
        while (p + 8 <= b.limit()) {
            val id = String(b.array(), p, 4, Charsets.US_ASCII)
            val n = b.getInt(p + 4)
            if (id == "data") return FloatArray(n / 2) { i -> b.getShort(p + 8 + 2 * i) / 32768f }
            p += 8 + n + (n and 1)
        }
        error("${f.name}: no data chunk")
    }

    companion object {
        const val TAG = "hfmodels-voice"
        const val ASR_DESCRIPTOR = "litert-community__Zipformer-medium-CR-CTC-LiteRT.hfmodels.json"
        const val TTS_DESCRIPTOR = "litert-community__kitten-tts-nano-0.8.hfmodels.json"
        const val TURN_TIMEOUT_MS = 180_000L
        const val SAMPLE_RATE = 16000
        const val CHUNK = SAMPLE_RATE * 20 / 1000
        const val HANGOVER_MS = 800
        /** Silence after each WAV (the fixtures end where the voice ends), longer than the hangover so the endpointer cuts by it. */
        const val TRAILING_SILENCE_MS = 1000
    }
}
