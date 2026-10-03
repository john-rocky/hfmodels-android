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
import io.github.johnrocky.hfmodels.speech.Speaker
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The voice loop's middle on a named device: the transcriber, the speaker and one chat model loaded side
 * by side in one process (three HfModels clients on one store), the fixed commands through [ToolRunner]
 * with [RecordingTools] (nothing on the phone changes), and the first sentence of one reply through the
 * speaker. Every load is Offline; files are imported from local directories and sha256-checked. RESULT
 * lines under tag `hfmodels-tools`:
 *   1. load: Zipformer medium_fp16 on the GPU and Kitten fp32 on the CPU (development descriptors, test-APK
 *      assets), then the chat model (`llm` / `variant` / `backend`; the `descriptor` asset, or the bundled
 *      catalog's entry at its commit); VmHWM before the chat model, after it and after everything; each plan
 *      inspected again through another client must find its files cached; c01.wav through the transcriber
 *      with all three loaded;
 *   2. command: each command of commands.tsv through `turn`, one line each (the calls with the arguments as
 *      the model gave them, success against the expected calls below, extra calls, timing, the reply), then
 *      a summary (ms_decode and the rates' denominator: per model turn, from sending its message to its last
 *      chunk, as TurnTiming.decodeMs; the first-token median leaves out turns without a chunk);
 *   3. first_audio: the first command again; its first sentence, cut by [SentenceSplitter] as the text
 *      streams, through the speaker while the model goes on; ms from the turn's start to the PCM; and
 *      whether its calls and reply equal the first run's (a new conversation per turn on the same loaded
 *      model, LiteRT-LM#3165);
 *   4. release: the three models.
 * Expected calls (`tomorrow` = the day after the run): c01 set_alarm 7:30, c02 set_alarm 6:15, c03 set_timer
 * 10, c04 set_timer 45, c05 get_current_datetime, c06 get_calendar_events tomorrow, c07 add_calendar_event
 * title with "dentist" at tomorrow 17:00, c08 add_calendar_event title with "standup" or "stand up" at
 * tomorrow 09:00, c09 set_alarm 8:00 and set_timer 20 (any order), c10 set_alarm 21:30. A command succeeds
 * when every expected call is there with the arguments listed (others are not checked); further calls are
 * counted as `extra` and do not fail it.
 * Arguments: llm (repo id), variant, backend (gpu | cpu), format (runtime | qwenxml | lfm), dir (default
 * /data/local/tmp/hfmodels/llm), descriptor (asset name; default the bundled catalog), thinking (default
 * false), commands (ids, comma-separated; default all), fresh (default false: true loads the chat model
 * again before every command after the first, for a model that may carry state between conversations),
 * system (default: ToolRunner's text with the minute of the request; fixed: today at 08:00 for every request;
 * functiongemma: FunctionGemma's card's developer message),
 * fixtures (default /data/local/tmp/hfmodels-voice/commands).
 *
 *   tools/tools_gate.sh litert-community/Qwen3-1.7B int4 gpu runtime
 */
@RunWith(AndroidJUnit4::class)
class ToolsDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val testCtx = InstrumentationRegistry.getInstrumentation().context
    private val args = InstrumentationRegistry.getArguments()
    private val llm = args.getString("llm") ?: "litert-community/functiongemma-270m-ft-mobile-actions"
    private val variant = args.getString("variant")
    private val backend = args.getString("backend") ?: "cpu"
    private val formatName = args.getString("format") ?: "runtime"
    private val dir = File(args.getString("dir") ?: "/data/local/tmp/hfmodels/llm")
    private val descriptorAsset = args.getString("descriptor")
    private val thinking = args.getString("thinking")?.toBoolean() ?: false
    private val fresh = args.getString("fresh")?.toBoolean() ?: false
    /**
     * `default` (ToolRunner's, with the minute of each request), `fixed` (ToolRunner's text with today at 08:00 for every
     * request: the same prompt minute after minute, so two runs of one command can be compared) or `functiongemma` (the
     * developer message google/functiongemma-270m-it's card prescribes, without the date).
     */
    private val system = args.getString("system") ?: "default"
    private val only = args.getString("commands")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
    private val fixtures = File(args.getString("fixtures") ?: "/data/local/tmp/hfmodels-voice/commands")
    private val failures = ArrayList<String>()

    private fun result(step: String, ok: Boolean, detail: String) {
        Log.i(TAG, "RESULT step=$step ok=$ok llm=$llm variant=${variant ?: "default"} backend=$backend format=$formatName $detail")
        if (!ok) failures += "$step: $detail"
    }

    @Test fun loadCommandsFirstAudioRelease(): Unit = runBlocking {
        val format = when (formatName) { "runtime" -> ToolFormat.Runtime; "qwenxml" -> ToolFormat.QwenXml; "lfm" -> ToolFormat.LfmPythonic; else -> error("format must be runtime, qwenxml or lfm") }
        val tomorrow = LocalDate.now().plusDays(1).toString()
        val asrModels = HfModels(ctx)
        val ttsModels = HfModels(ctx)
        val llmModels = HfModels(ctx)
        val open = ArrayList<PreparedModel>()
        Log.i(TAG, "device=${Build.MODEL} soc=${Build.SOC_MANUFACTURER}/${Build.SOC_MODEL} build=${Build.DISPLAY} android=${Build.VERSION.RELEASE} llm=$llm variant=${variant ?: "default"} backend=$backend format=$formatName thinking=$thinking tomorrow=$tomorrow store=${llmModels.root}")
        try {
            // 1. load: transcriber, speaker, then the chat model
            val hwmStart = status("VmHWM")
            val asrLoad = load(asrModels, Transcribe, ASR_DESCRIPTOR, "medium_fp16", BackendPolicy.Require(BackendKind.GPU), File("/data/local/tmp/hfmodels/zipformer"))
            val asr = asrLoad.model.also { open += it }
            val ttsLoad = load(ttsModels, Speak, TTS_DESCRIPTOR, "fp32", BackendPolicy.Auto, File("/data/local/tmp/hfmodels/kitten"))
            val tts = ttsLoad.model.also { open += it }
            val hwmBeforeLlm = status("VmHWM")
            val llmPolicy = when (backend) { "cpu" -> BackendPolicy.Require(BackendKind.CPU); "gpu" -> BackendPolicy.Require(BackendKind.GPU); else -> BackendPolicy.Auto }
            val llmLoad = load(llmModels, Tasks.Chat, descriptorAsset, variant, llmPolicy, dir)
            var chat = llmLoad.model.also { open += it }
            val hwmAfterLlm = status("VmHWM")
            // One store, three clients: each plan seen through another client finds its files.
            val crossCached = listOf(
                llmModels.inspect(asrLoad.ref, Transcribe, asrLoad.opts).files.all { it.cached },
                asrModels.inspect(ttsLoad.ref, Speak, ttsLoad.opts).files.all { it.cached },
                ttsModels.inspect(llmLoad.ref, Tasks.Chat, llmLoad.opts).files.all { it.cached },
            )
            val sameRoot = asrModels.root == ttsModels.root && ttsModels.root == llmModels.root
            // RecordingTools stands in for PhoneTools: the model must see the same declarations (constructing PhoneTools touches nothing).
            val sameDeclarations = PhoneTools.all(ctx).map { it.descriptionJson().toString() } == RecordingTools().all.map { it.descriptionJson().toString() }
            val c01 = asr.transcribe(readWav(File(fixtures, "c01.wav")))
            result("load", crossCached.all { it } && sameRoot && sameDeclarations,
                "asr=${asr.info.repoId}/${asr.info.variantId}/${asr.info.profileId} asr_load_ms=${asrLoad.loadMs} tts=${tts.info.repoId}/${tts.info.variantId}/${tts.info.profileId} tts_load_ms=${ttsLoad.loadMs} " +
                    "llm_profile=${chat.info.profileId} llm_variant=${chat.info.variantId} llm_commit=${chat.info.commit.take(8)} llm_import_ms=${llmLoad.importMs} llm_load_ms=${llmLoad.loadMs} thinking_channels=${chat.thinking.channels.size} " +
                    "vmhwm_kb_start=$hwmStart before_llm=$hwmBeforeLlm after_llm=$hwmAfterLlm same_root=$sameRoot cross_client_cached=$crossCached recording_tools_declare_like_phone_tools=$sameDeclarations cache_bytes=${llmModels.cacheBytes()} " +
                    "asr_c01_with_all_loaded=\"${c01.text}\" asr_c01_ms=${"%.1f".format(c01.timing.totalMs)} llm_notes=${chat.info.notes.joinToString(" | ")}")

            // 2. the commands
            val commands = File(fixtures, "commands.tsv").readLines(Charsets.UTF_8).filter { it.isNotBlank() }
                .map { it.substringBefore('\t') to it.substringAfter('\t') }.filter { only == null || it.first in only }
            val expected = expected(tomorrow)
            val recording = RecordingTools()
            val systemText: (String) -> String = when (system) {
                "default" -> { now -> ToolRunner.defaultSystemInstruction(now) }
                "fixed" -> { _ -> ToolRunner.defaultSystemInstruction(java.text.SimpleDateFormat("EEEE, yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + " 08:00") }
                "functiongemma" -> { _ -> "You are a model that can do function calling with the following functions" }
                else -> error("system must be default, fixed or functiongemma")
            }
            var runner = ToolRunner(chat, recording.all, format, systemText, thinking = thinking)
            val runs = LinkedHashMap<String, Run>()
            var ok = 0
            var reloadMs = 0L
            for ((index, command) in commands.withIndex()) {
                val (id, text) = command
                if (fresh && index > 0) {
                    // A fresh load per command: nothing a previous request left in the model's state can reach this one.
                    val t = SystemClock.elapsedRealtime()
                    open.remove(chat); chat.closeAndJoin()
                    chat = llmModels.fromPretrained(llmLoad.ref, Tasks.Chat, llmLoad.opts).also { open += it }
                    reloadMs += SystemClock.elapsedRealtime() - t
                    runner = ToolRunner(chat, recording.all, format, systemText, thinking = thinking)
                }
                val r = turn(runner, recording, text)
                runs[id] = r
                val (success, extra) = judge(expected.getValue(id), r.calls)
                if (success) ok++
                result("command", true, "file=$id success=$success calls=${r.calls.joinToString(",", "[", "]") { describe(it) }} extra=$extra turns=${r.timing.turns} " +
                    "ms_first_token=${"%.0f".format(r.timing.firstTokenMs)} ms_reply=${"%.0f".format(r.timing.replyMs)} ms_decode=${"%.0f".format(r.timing.decodeMs)} chunks=${r.timing.chunks} chars=${r.timing.chars} thought_chars=${r.thoughtChars} " +
                    "tool_ms=${"%.1f".format(r.toolMs)} ${r.failed?.let { "failed=${q(it)} " } ?: ""}reply=${q(r.reply)} expect=${expected.getValue(id).joinToString("+") { it.label }}")
            }
            val timings = runs.values.map { it.timing }
            val decodeMs = timings.sumOf { it.decodeMs }
            result("summary", true, "success=$ok/${commands.size} ms_first_token_median=${"%.0f".format(median(timings.map { it.firstTokenMs }.filter { it >= 0 }))} ms_reply_median=${"%.0f".format(median(timings.map { it.replyMs }))} " +
                "turns_median=${median(timings.map { it.turns.toDouble() })} chars_per_s=${"%.1f".format(timings.sumOf { it.chars } * 1000.0 / decodeMs)} chunks_per_s=${"%.1f".format(timings.sumOf { it.chunks } * 1000.0 / decodeMs)} " +
                "failed=${runs.count { it.value.failed != null }} extra_total=${runs.entries.sumOf { judge(expected.getValue(it.key), it.value.calls).second }} fresh_load_each=$fresh reload_ms_total=$reloadMs system=$system")

            // 3. the first command again, with its first sentence through the speaker as the text streams
            val (firstId, firstText) = commands.first()
            val again = turn(runner, recording, firstText, tts)
            val first = runs.getValue(firstId)
            result("first_audio", again.firstAudioMs != null,
                "file=$firstId ms_first_audio_from_text=${again.firstAudioMs?.let { "%.0f".format(it) } ?: "none"} first_sentence=${q(again.firstSentence ?: "")} ms_sentence_ready=${again.sentenceAtMs?.let { "%.0f".format(it) } ?: "none"} " +
                    "ms_synth=${again.synthMs?.let { "%.1f".format(it) } ?: "none"} audio_s=${again.audioS?.let { "%.2f".format(it) } ?: "none"} ms_reply=${"%.0f".format(again.timing.replyMs)} " +
                    "same_calls_as_first=${again.calls.map { describe(it) } == first.calls.map { describe(it) }} same_reply_as_first=${again.reply == first.reply} reply=${q(again.reply)}")
            result("memory", true, "vmhwm_kb_after_all=${status("VmHWM")} vmrss_kb=${status("VmRSS")} before_llm=$hwmBeforeLlm after_llm=$hwmAfterLlm")

            // 4. release
            val t0 = SystemClock.elapsedRealtime()
            for (m in open.reversed()) m.closeAndJoin()
            open.clear()
            result("release", true, "close_ms=${SystemClock.elapsedRealtime() - t0}")
        } catch (t: Throwable) {
            Log.e(TAG, "failed", t)
            failures += "exception: ${t.javaClass.simpleName}: ${t.message}"
        } finally {
            for (m in open.reversed()) runCatching { m.closeAndJoin() }
            asrModels.closeAndJoin(); ttsModels.closeAndJoin(); llmModels.closeAndJoin()
        }
        Log.i(TAG, "RESULT ok=${failures.isEmpty()} llm=$llm variant=${variant ?: "default"} backend=$backend format=$formatName device=${Build.MODEL} build=${Build.DISPLAY} failures=${failures.joinToString(" | ")}")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    // ---- loading ----

    private class Loaded<M : PreparedModel>(val ref: ModelRef, val opts: LoadOptions, val model: M, val importMs: Long, val loadMs: Long)

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
        return Loaded(ref, opts, model, importMs, SystemClock.elapsedRealtime() - t1)
    }

    // ---- one turn ----

    private class Run(
        val calls: List<RecordingTools.Call>, val reply: String, val failed: String?, val timing: TurnTiming, val thoughtChars: Int, val toolMs: Double,
        val firstSentence: String?, val sentenceAtMs: Double?, val firstAudioMs: Double?, val synthMs: Double?, val audioS: Double?,
    )

    /** The first sentence's audio: when its PCM was ready (ms from the turn's start), the synthesis ms and the audio's length. */
    private class Spoken(val atMs: Double, val synthMs: Double, val audioS: Double)

    /** One request; with [speaker], the first sentence goes to it as soon as the streamed text completes one. */
    private suspend fun turn(runner: ToolRunner, recording: RecordingTools, text: String, speaker: Speaker? = null): Run = coroutineScope {
        val before = synchronized(recording.calls) { recording.calls.size }
        val t0 = System.nanoTime()
        val visible = StringBuilder()
        var thoughtChars = 0
        var toolMs = 0.0
        var reply = ""
        var failed: String? = null
        var timing: TurnTiming? = null
        var sentence: String? = null
        var sentenceAt: Double? = null
        var synth: Deferred<Spoken>? = null
        fun startSpeaking(s: String) {
            if (speaker == null || sentence != null || s.isBlank()) return
            sentence = s
            sentenceAt = (System.nanoTime() - t0) / 1e6
            synth = async(Dispatchers.Default) {
                val a = speaker.synthesize(s)
                Spoken((System.nanoTime() - t0) / 1e6, a.timing.totalMs, a.samples.size.toDouble() / a.sampleRate)
            }
        }
        withTimeout(TURN_TIMEOUT_MS) {
            runner.turn(text).collect { e ->
                when (e) {
                    is ToolEvent.Thinking -> thoughtChars += e.delta.length
                    is ToolEvent.Text -> {
                        visible.append(e.delta)
                        if (speaker != null && sentence == null) SENTENCE_END.find(visible)?.let { m -> SentenceSplitter.split(visible.substring(0, m.range.last + 1)).firstOrNull()?.let(::startSpeaking) }
                    }
                    is ToolEvent.ToolCalled -> toolMs += e.ms
                    is ToolEvent.Done -> { reply = e.reply; timing = e.timing; if (speaker != null && sentence == null) SentenceSplitter.split(e.reply).firstOrNull()?.let(::startSpeaking) }
                    is ToolEvent.Failed -> { failed = e.reason; timing = e.timing }
                }
            }
        }
        val spoken = synth?.await()
        val calls = synchronized(recording.calls) { recording.calls.subList(before, recording.calls.size).toList() }
        Run(calls, reply, failed, timing!!, thoughtChars, toolMs, sentence, sentenceAt, spoken?.atMs, spoken?.synthMs, spoken?.audioS)
    }

    // ---- judging ----

    private class Expect(val name: String, val label: String, val check: (Map<String, Any?>) -> Boolean)

    private fun num(a: Map<String, Any?>, k: String): Double? = when (val v = a[k]) {
        is Number -> v.toDouble()
        null -> null
        else -> v.toString().trim().toDoubleOrNull()
    }

    private fun minuteOf(a: Map<String, Any?>, k: String): String? = a[k]?.toString()?.trim()?.replace('T', ' ')?.take(16)

    private fun alarm(h: Int, m: Int) = Expect("set_alarm", "set_alarm{hour=$h,minute=$m}") { a -> num(a, "hour") == h.toDouble() && num(a, "minute") == m.toDouble() }
    private fun timer(min: Int) = Expect("set_timer", "set_timer{minutes=$min}") { a -> num(a, "minutes") == min.toDouble() }

    private fun expected(tomorrow: String): Map<String, List<Expect>> = mapOf(
        "c01" to listOf(alarm(7, 30)),
        "c02" to listOf(alarm(6, 15)),
        "c03" to listOf(timer(10)),
        "c04" to listOf(timer(45)),
        "c05" to listOf(Expect("get_current_datetime", "get_current_datetime") { true }),
        "c06" to listOf(Expect("get_calendar_events", "get_calendar_events{date=$tomorrow}") { a -> a["date"]?.toString()?.trim()?.take(10) == tomorrow }),
        "c07" to listOf(Expect("add_calendar_event", "add_calendar_event{title~dentist,start=$tomorrow 17:00}") { a ->
            a["title"]?.toString()?.lowercase()?.contains("dentist") == true && minuteOf(a, "start") == "$tomorrow 17:00"
        }),
        "c08" to listOf(Expect("add_calendar_event", "add_calendar_event{title~standup,start=$tomorrow 09:00}") { a ->
            a["title"]?.toString()?.lowercase()?.let { it.contains("standup") || it.contains("stand up") || it.contains("stand-up") } == true && minuteOf(a, "start") == "$tomorrow 09:00"
        }),
        "c09" to listOf(alarm(8, 0), timer(20)),
        "c10" to listOf(alarm(21, 30)),
    )

    /** (every expected call found, each by its own call; the calls left over). */
    private fun judge(expect: List<Expect>, calls: List<RecordingTools.Call>): Pair<Boolean, Int> {
        val used = BooleanArray(calls.size)
        var found = 0
        for (e in expect) {
            val i = calls.indices.firstOrNull { !used[it] && calls[it].name == e.name && runCatching { e.check(calls[it].args) }.getOrDefault(false) } ?: continue
            used[i] = true
            found++
        }
        return (found == expect.size) to (calls.size - found)
    }

    private fun describe(c: RecordingTools.Call) = c.name + c.args.entries.joinToString(",", "{", "}") { "${it.key}=${it.value}" }

    // ---- helpers ----

    private fun status(key: String): Long =
        File("/proc/self/status").readLines().first { it.startsWith("$key:") }.substringAfter(':').trim().substringBefore(' ').toLong()

    private fun median(v: List<Double>) = v.sorted().let { s -> if (s.isEmpty()) Double.NaN else if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

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
        const val TAG = "hfmodels-tools"
        const val ASR_DESCRIPTOR = "litert-community__Zipformer-medium-CR-CTC-LiteRT.hfmodels.json"
        const val TTS_DESCRIPTOR = "litert-community__kitten-tts-nano-0.8.hfmodels.json"
        const val TURN_TIMEOUT_MS = 180_000L
        /** A sentence end with a space after it: the first sentence is complete (the Done event covers the last one). */
        val SENTENCE_END = Regex("[.!?。！？]+(?=\\s)")
    }
}
