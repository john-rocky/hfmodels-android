package io.github.johnrocky.hfmodels.check

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.johnrocky.hfmodels.BackendKind
import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.HfModels
import io.github.johnrocky.hfmodels.LoadOptions
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.ModelRef
import io.github.johnrocky.hfmodels.NetworkPolicy
import io.github.johnrocky.hfmodels.samples.translate.Direction
import io.github.johnrocky.hfmodels.samples.translate.EarlyEndpointer
import io.github.johnrocky.hfmodels.samples.translate.EarlyTurn
import io.github.johnrocky.hfmodels.samples.translate.Gate
import io.github.johnrocky.hfmodels.samples.translate.InputGate
import io.github.johnrocky.hfmodels.samples.translate.Language
import io.github.johnrocky.hfmodels.samples.translate.Qwen3AsrTask
import io.github.johnrocky.hfmodels.samples.translate.TranslateLoop
import io.github.johnrocky.hfmodels.samples.translate.TranslateLoop.Event
import io.github.johnrocky.hfmodels.samples.translate.TranslateModels
import io.github.johnrocky.hfmodels.samples.translate.TurnRecord
import io.github.johnrocky.hfmodels.samples.translate.TurnStart
import io.github.johnrocky.hfmodels.samples.translate.Wav
import io.github.johnrocky.hfmodels.samples.translate.asPlayer
import io.github.johnrocky.hfmodels.samples.translate.earlyTurnsOf
import io.github.johnrocky.hfmodels.samples.translate.listen
import io.github.johnrocky.hfmodels.samples.translate.q
import io.github.johnrocky.hfmodels.samples.translate.turnLine
import io.github.johnrocky.hfmodels.samples.translate.utterancesOf
import io.github.johnrocky.hfmodels.samples.translate.wavChunks
import io.github.johnrocky.hfmodels.voice.Endpointer
import io.github.johnrocky.hfmodels.voice.SpeechPlayer
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device check for samples/translate, in the app's own process: the three models load from the app's store (pushed copies
 * are imported; offline when the phone has no network), the ten fixed sentences go through the endpointer and the turn
 * as a microphone's would (the direction from `sentences.tsv`), twice (20 turns), and the failure paths run. It prints
 * one `RESULT` line per step under `adb logcat -s hfmodels-check`, the transcripts beside the Mac's, and a summary with
 * the median from the end of speech to the first English sound over the Spanish (or French) turns.
 *
 * The 20 turns start as the app's do, at the early cut ([EarlyEndpointer]: 300 ms of silence after speech), with the
 * file cut at once: the turn's clock starts at the early cut (`hangover_ms` 300), and its gate opens 500 ms later, when
 * the endpointer's 800 ms hangover would have ended, so no sound comes before it; the end of speech to the first sound is
 * `hangover_ms + ms_first_audio` (the TURN line's `l1`). In this form no early turn runs before the standing one:
 * `discarded_early` counts the early cuts that voice voided in the file. The `pause_resume` step runs the press path
 * itself ([listen]) on a WAV at the microphone's pace: a sentence with 500 ms of silence inside, so the early turn at the
 * pause is cancelled when the voice comes back, and the one utterance's turn hears both halves.
 *
 * The fixtures (not in this repository: the ten sentences read by macOS voices and the failure-path files, 16 kHz mono
 * 16-bit) in `<fixtures>` (default /data/local/tmp/translate): `sentences.tsv` (id, dir, src, ref, sec, checks),
 * `mac_asr.tsv` (the same recognizer's transcripts on a Mac, id<TAB>text), `wav/<id>.wav` for every row,
 * `silence_5s.wav`, `noise_5s.wav`, `long_35s.wav` (three utterances in the 15 s window), one sentence in the other
 * language (`fr_input.wav` for the Spanish pair, rows es-en / en-es; `es_input.wav` for the French pair, rows fr-en /
 * en-fr), and `pause_resume.wav` with `pause_resume.tsv` (dir, src with `|` at the pause, the words the transcript must
 * hold, the words the translation must hold: `;` between the halves, `|` between alternatives). The rows' `dir` gives
 * the pair; the failure paths use its buttons.
 *
 * Steps: load / turn (x20) / nothing_heard_silence / nothing_heard_noise / gate_noise / long_35s / french (both buttons,
 * behaviour recorded; spanish for the French pair) / missing_file (a load of a file the store does not have, offline:
 * the screen's words) / stop (a turn stopped mid-translation, then the next turn) / pause_resume / memory / release, and
 * `RESULT info` lines for the network.
 * Arguments: fixtures (dir), rounds (default 2), mt_backend (gpu|cpu; default the app's choice).
 *   ./gradlew :samples:translate:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
 *     -Pandroid.testInstrumentationRunnerArguments.class=io.github.johnrocky.hfmodels.check.TranslateDeviceCheck
 *   adb logcat -d -s hfmodels-check | grep RESULT
 * The last line is `RESULT ok=true ...` when every step passed. Sound plays from the loudspeaker during the Spanish turns.
 */
@RunWith(AndroidJUnit4::class)
class TranslateDeviceCheck {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val dir = File(args.getString("fixtures") ?: "/data/local/tmp/translate")
    private val rounds = args.getString("rounds")?.toIntOrNull() ?: 2
    private val mtPreference = when (args.getString("mt_backend")) { "gpu" -> BackendKind.GPU; "cpu" -> BackendKind.CPU; else -> null }

    @Test fun loadTranslateFailRelease(): Unit = runBlocking {
        val failures = ArrayList<String>()
        fun step(name: String, ok: Boolean, values: String) {
            if (!ok) failures += name
            Log.i(TAG, "RESULT step=$name ok=$ok $values")
        }
        val net = TranslateModels.network(ctx)
        val airplane = Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        Log.i(TAG, "device=${Build.MODEL} build=${Build.DISPLAY} package=${ctx.packageName} network=$net airplane_mode=$airplane fixtures=${dir.path} rounds=$rounds")
        // The app in front, as a user has it (its process then runs in the foreground cpuset); autoload=false shows the
        // screen over the keyguard in a debug build and loads nothing there.
        ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let { launch ->
            runCatching { InstrumentationRegistry.getInstrumentation().startActivitySync(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("autoload", false)) }
                .onFailure { Log.w(TAG, "could not bring the app to the front: $it") }
        }
        val policy = if (net == "none") NetworkPolicy.Offline else NetworkPolicy.Any
        val models = TranslateModels(ctx)
        var player: SpeechPlayer? = null
        try {
            // 0. a model file the store does not have, offline: the words the screen shows.
            missingFile(::step)

            // 1. load
            val hwmBefore = TranslateModels.vmHwmKb()
            val t0 = SystemClock.elapsedRealtime()
            val loaded = try {
                models.load(policy, mtPreference)
                true
            } catch (e: TranslateModels.Failure) {
                step("load", false, "role=${q(e.role)} code=${e.code} reason=${q(e.reason)} load_ms=${SystemClock.elapsedRealtime() - t0}")
                false
            }
            if (loaded) afterLoad(models, policy, net, airplane, hwmBefore, t0, ::step) { player = it }
        } catch (e: Exception) {
            Log.e(TAG, "failed", e)
            step("exception", false, "error=${e.javaClass.simpleName} message=${q(e.message.orEmpty().take(300))}")
        } finally {
            runCatching { player?.close() }
            models.closeAndJoin()
            Log.i(TAG, "RESULT ok=${failures.isEmpty()} failed=${failures.joinToString(",", "[", "]")} network=$net device=${Build.MODEL} build=${Build.DISPLAY}")
        }
        assertTrue("failed steps: $failures (adb logcat -d -s hfmodels-check)", failures.isEmpty())
    }

    /** Every step after a load that worked. [onPlayer] hands the player out, so a failure still closes it. */
    private suspend fun kotlinx.coroutines.CoroutineScope.afterLoad(
        models: TranslateModels, policy: NetworkPolicy, net: String, airplane: Boolean, hwmBefore: Long?, t0: Long,
        step: (String, Boolean, String) -> Unit, onPlayer: (SpeechPlayer?) -> Unit,
    ) {
        val speaker = models.speaker
        step("load", speaker != null, "load_ms=${SystemClock.elapsedRealtime() - t0} models=${q(models.lines.joinToString(" | ") { it.text })} mt_backend=${models.mtBackend} " +
            "gpu_check=${q(models.gpuCheck)} speaker_failure=${q(models.speakerFailure ?: "")} network_policy=$policy vmhwm_kb_before=$hwmBefore vmhwm_kb=${TranslateModels.vmHwmKb()}")
        val p = speaker?.let { SpeechPlayer(it.sampleRate) }.also { onPlayer(it) }
        val t = models.transcriber!!
        val tr = models.translator!!
        // Every hearing call counted, and the ones a cancel ended (the pause_resume step's early turn).
        val hearCalls = AtomicInteger()
        val hearCancelled = AtomicInteger()
        val loop = TranslateLoop({ pcm, d ->
            hearCalls.incrementAndGet()
            try { t.transcribe(pcm, d.heard) } catch (e: CancellationException) { hearCancelled.incrementAndGet(); throw e }
        }, { text, target, created -> tr.translate(text, target, created) }, speaker, p?.asPlayer())
        val backends = "asr:cpu,mt:${models.mtBackend.lowercase()},tts:${if (speaker != null) "cpu" else "none"}"
        fun logTurn(r: TurnRecord?) { if (r != null) Log.i(TAG, turnLine(r, backends, TranslateModels.network(ctx), TranslateModels.vmHwmKb())) }
        // A turn that starts from [cut] as the app's would: at the early cut with its gate opened 500 ms later, or at the
        // endpointer's cut with the gate open.
        suspend fun turn(direction: Direction, cut: EarlyTurn, input: String): Pair<List<Event>, TurnRecord?> {
            val start = System.nanoTime()
            val gate = if (cut.early) Gate() else Gate.opened(start)
            val opener = if (cut.early) launch { delay((HANGOVER_MS - EarlyEndpointer.EARLY_MS).toLong()); gate.open() } else null
            val events = try {
                withTimeout(TURN_TIMEOUT_MS) { loop.turn(direction, cut.pcm, start, cut.hangoverMs, input, gate, cut.early, cut.voidedEarly, EarlyEndpointer.EARLY_MS).toList() }
            } finally {
                opener?.cancel()
            }
            val r = (events.lastOrNull() as? Event.Done)?.record
            logTurn(r)
            return events to r
        }
        fun cutsOf(file: String): List<EarlyTurn> = earlyTurnsOf(Wav.read(File(dir, "wav/$file.wav")).first, EarlyEndpointer(Endpointer(maxUtteranceMs = WINDOW_MS)))

        // 2. the ten sentences, [rounds] times
        val rows = File(dir, "sentences.tsv").readLines().drop(1).filter { it.isNotBlank() }.map { it.split('\t') }
        // The pair of the rows (Spanish: es-en / en-es, French: fr-en / en-fr): the failure paths use its buttons.
        val language = rows.firstNotNullOfOrNull { Direction.of(it[1])?.language } ?: Language.DEFAULT
        val other = Language.entries.first { it != language }
        val mac = File(dir, "mac_asr.tsv").takeIf { it.canRead() }?.readLines()?.filter { it.isNotBlank() }?.associate { it.substringBefore('\t') to it.substringAfter('\t') }.orEmpty()
        val hwmLoaded = TranslateModels.vmHwmKb()
        val spoken = ArrayList<TurnRecord>()
        val written = ArrayList<TurnRecord>()
        for (round in 1..rounds) for (row in rows) {
            val id = row[0]
            val direction = Direction.of(row[1]) ?: continue
            val cut = cutsOf(id).firstOrNull()
            if (cut == null) { step("turn", false, "round=$round id=$id reason=\"the endpointer found no speech\""); continue }
            val (events, r) = turn(direction, cut, "file:$id")
            val ok = r != null && r.heard.isNotEmpty() && r.translation.isNotEmpty() && r.errors.isEmpty() && (!direction.spoken || speaker == null || r.firstAudioMs != null)
            step("turn", ok, "round=$round id=$id dir=${direction.tag} heard=${q(r?.heard.orEmpty())} mac=${q(mac[id].orEmpty())} same_as_mac=${r?.heard == mac[id]} " +
                "translation=${q(r?.translation.orEmpty())} ref=${q(row.getOrNull(3).orEmpty())} spoken=${q(r?.spoken?.joinToString(" ").orEmpty())} " +
                "early=${r?.early} ms_end_of_speech_to_sound=${f(r?.endOfSpeechToSoundMs)} ms_end_of_speech_to_text=${f(r?.endOfSpeechToTextMs)} errors=${events.filterIsInstance<Event.Error>().size}")
            if (r != null && ok) (if (direction.spoken) spoken else written) += r
        }
        val hwmTurns = TranslateModels.vmHwmKb()
        val toEnglish = language.toEnglish.tag.replace('-', '_')
        val fromEnglish = language.fromEnglish.tag.replace('-', '_')
        Log.i(TAG, "RESULT step=summary ok=true turns=${spoken.size + written.size} ${toEnglish}_turns=${spoken.size} ${fromEnglish}_turns=${written.size} " +
            "early_turns=${spoken.count { it.early }} spec_ms=${EarlyEndpointer.EARLY_MS} " +
            "l1_median_ms_end_of_speech_to_sound=${f(median(spoken.mapNotNull { it.endOfSpeechToSoundMs }))} " +
            "median_ms_transcribe=${f(median(spoken.mapNotNull { it.transcribeMs }))} median_asr_first_chunk_ms=${f(median(spoken.mapNotNull { it.asr?.firstChunkMs }))} " +
            "median_asr_decode_ms=${f(median(spoken.mapNotNull { it.asr?.decodeMs }))} median_ms_translate_create=${f1(median(spoken.mapNotNull { it.translateCreateMs }))} " +
            "median_ms_translate_first_token=${f(median(spoken.mapNotNull { it.translateFirstTokenMs }))} median_ms_first_chunk=${f(median(spoken.mapNotNull { it.firstChunkMs }))} " +
            "median_ms_first_sentence=${f(median(spoken.mapNotNull { it.firstSentenceMs }))} median_ms_first_synth=${f(median(spoken.mapNotNull { it.firstSynthMs }))} " +
            "median_ms_gate=${f(median(spoken.mapNotNull { it.gateMs }))} median_ms_first_audio=${f(median(spoken.mapNotNull { it.firstAudioMs }))} " +
            "${fromEnglish}_median_ms_end_of_speech_to_text=${f(median(written.mapNotNull { it.endOfSpeechToTextMs }))} hangover_ms=$HANGOVER_MS mt_backend=${models.mtBackend}")
        step("memory", true, "vmhwm_kb_before_load=$hwmBefore vmhwm_kb_after_load=$hwmLoaded vmhwm_kb_after_turns=$hwmTurns")

        // 3. nothing to hear: the endpointer cuts nothing from silence or quiet noise; the gate refuses the noise as an utterance.
        for (name in listOf("silence_5s", "noise_5s")) {
            val pcm = Wav.read(File(dir, "wav/$name.wav")).first
            val n = utterancesOf(pcm, Endpointer(maxUtteranceMs = WINDOW_MS)).size
            step("nothing_heard_${name.substringBefore('_')}", n == 0, "file=$name.wav utterances=$n screen=\"Nothing heard\"")
        }
        val noise = Wav.read(File(dir, "wav/noise_5s.wav")).first
        val (gateEvents, gateRecord) = turn(language.toEnglish, EarlyTurn(noise, false, HANGOVER_MS, 0), "file:noise_5s(whole)")
        step("gate_noise", gateEvents.any { it is Event.NothingHeard } && gateRecord?.heard.isNullOrEmpty(),
            "reason=${q(gateRecord?.nothingHeard.orEmpty())} rms=${String.format(Locale.US, "%.4f", gateRecord?.rms ?: 0.0)} floor=${InputGate.DEFAULT_FLOOR_RMS}")

        // 4. 35 s of speech: the endpointer cuts it at the 15 s window (three utterances); each part is a turn.
        val long = cutsOf("long_35s")
        val parts = long.map { u -> turn(language.toEnglish, u, "file:long_35s").second }
        step("long_35s", long.size == 3 && parts.all { it != null && it.heard.isNotEmpty() },
            "utterances=${long.size} seconds=${long.joinToString(",", "[", "]") { String.format(Locale.US, "%.2f", it.pcm.size / 16000.0) }} early=${long.joinToString(",", "[", "]") { it.early.toString() }} " +
                "heard=${parts.joinToString(" | ") { q(it?.heard.orEmpty()) }} asr_chunks=${parts.joinToString(",", "[", "]") { (it?.asr?.chunks ?: 0).toString() }} " +
                "ms_end_of_speech_to_sound=${parts.joinToString(",", "[", "]") { f(it?.endOfSpeechToSoundMs) }}")

        // 5. The other language into both buttons (French for the Spanish pair, Spanish for the French pair): what happens
        // is recorded (Hy-MT2 translates what it gets).
        val otherInput = "${other.tag}_input"
        val foreign = cutsOf(otherInput).firstOrNull()
        for (d in language.directions) {
            val r = foreign?.let { turn(d, it, "file:$otherInput").second }
            step("${other.label.lowercase()}_${d.tag}", r != null, "heard=${q(r?.heard.orEmpty())} translation=${q(r?.translation.orEmpty())} errors=${q(r?.errors?.joinToString(" | ").orEmpty())}")
        }

        // 6. Stop in the middle of a turn into English (as the button does), then the next turn: the pair's third and first
        // sentences into English (e03 and e01 for the Spanish pair).
        val intoEnglish = rows.filter { Direction.of(it[1]) == language.toEnglish }.map { it[0] }
        val stopId = intoEnglish.getOrElse(2) { intoEnglish.first() }
        val nextId = intoEnglish.first()
        val stopUtterance = utterancesOf(Wav.read(File(dir, "wav/$stopId.wav")).first, Endpointer(maxUtteranceMs = WINDOW_MS)).first()
        val seen = CopyOnWriteArrayList<Event>()
        val job = launch(Dispatchers.Default) { loop.turn(language.toEnglish, stopUtterance, System.nanoTime(), HANGOVER_MS, "file:$stopId-stop").collect { seen += it } }
        val until = SystemClock.elapsedRealtime() + TURN_TIMEOUT_MS
        while (seen.none { it is Event.Translation } && SystemClock.elapsedRealtime() < until && job.isActive) delay(10)
        val s0 = SystemClock.elapsedRealtime()
        job.cancelAndJoin()
        val stopMs = SystemClock.elapsedRealtime() - s0
        val (_, after) = turn(language.toEnglish, cutsOf(nextId).first(), "file:$nextId-after-stop")
        step("stop", stopMs < 2_000 && seen.none { it is Event.Done } && after != null && after.translation.isNotEmpty(),
            "stop_ms=$stopMs events_before_stop=${seen.size} next_turn_translation=${q(after?.translation.orEmpty())}")

        // 7. A pause shorter than the hangover, at the microphone's pace through the press path: the early turn at the pause
        // is cancelled when the voice comes back, the utterance goes on, and its one turn hears both halves.
        pauseResume(loop, language, hearCalls, hearCancelled, ::logTurn, step)

        // 8. the network, as reported (this check does not switch it), and the pair the rows gave
        Log.i(TAG, "RESULT info network=$net airplane_mode=$airplane pair=${language.tag}")

        // 9. release
        val r0 = SystemClock.elapsedRealtime()
        p?.close(); onPlayer(null)
        models.closeAndJoin()
        val closeMs = SystemClock.elapsedRealtime() - r0
        step("release", closeMs < 10_000, "close_ms=$closeMs")
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.pauseResume(
        loop: TranslateLoop, language: Language, hearCalls: AtomicInteger, hearCancelled: AtomicInteger,
        logTurn: (TurnRecord?) -> Unit, step: (String, Boolean, String) -> Unit,
    ) {
        val row = File(dir, "pause_resume.tsv").takeIf { it.canRead() }?.readLines()?.drop(1)?.firstOrNull { it.isNotBlank() }?.split('\t')
        val wav = File(dir, "wav/pause_resume.wav")
        if (row == null || row.size < 4 || !wav.canRead()) { step("pause_resume", false, "reason=\"no pause_resume.tsv or wav/pause_resume.wav in ${dir.path}\""); return }
        val direction = Direction.of(row[0]) ?: language.toEnglish
        val pcm = Wav.read(wav).first
        val utterances = utterancesOf(pcm, Endpointer(maxUtteranceMs = WINDOW_MS)).size
        val started = CopyOnWriteArrayList<Pair<TurnStart, Job>>()
        val done = ConcurrentHashMap<TurnStart, TurnRecord>()
        val calls0 = hearCalls.get()
        val cancelled0 = hearCancelled.get()
        val heard = withTimeout(TURN_TIMEOUT_MS) {
            listen(wavChunks(pcm, HANGOVER_MS + 200, realTime = true), EarlyEndpointer(Endpointer(maxUtteranceMs = WINDOW_MS)), early = true, input = "file:pause_resume",
                stopRequested = { false }, noSpeechMs = null, startTurn = { s ->
                    launch(Dispatchers.Default) {
                        val e = loop.turn(direction, s.pcm, s.cutNanos, s.hangoverMs, s.input, s.gate, s.early, s.discardedEarly, s.specMs).toList()
                        (e.lastOrNull() as? Event.Done)?.record?.let { done[s] = it }
                    }.also { started += s to it }
                })
        }
        withTimeout(TURN_TIMEOUT_MS) { heard.turn?.join() }
        for ((_, j) in started) j.join()
        val r = heard.start?.let { done[it] }
        logTurn(r)
        val cancelledTurns = started.count { it.second.isCancelled }
        val heardOk = r != null && hasAll(r.heard, row[2])
        val translationOk = r != null && hasAll(r.translation, row[3])
        step("pause_resume", utterances == 1 && heard.discardedEarly == 1 && cancelledTurns == 1 && r != null && r.early && heardOk && translationOk,
            "dir=${direction.tag} utterances=$utterances turns_started=${started.size} discarded_early=${heard.discardedEarly} cancelled_turns=$cancelledTurns " +
                "hearing_calls=${hearCalls.get() - calls0} hearing_cancelled=${hearCancelled.get() - cancelled0} early=${r?.early} " +
                "heard=${q(r?.heard.orEmpty())} heard_has_both=$heardOk translation=${q(r?.translation.orEmpty())} translation_has_both=$translationOk " +
                "ms_end_of_speech_to_sound=${f(r?.endOfSpeechToSoundMs)} ms_gate=${f(r?.gateMs)}")
    }

    /** Every `;` group of [keys] has one `|` alternative in [text] (case and accents ignored). */
    private fun hasAll(text: String, keys: String): Boolean {
        val t = fold(text)
        return keys.split(';').map { it.trim() }.filter { it.isNotEmpty() }.all { g -> g.split('|').any { t.contains(fold(it.trim())) } }
    }

    private fun fold(s: String) = java.text.Normalizer.normalize(s.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    /** A load of a file the store does not have, with no network: the SDK's OFFLINE_CACHE_MISS and the app's words for it. */
    private suspend fun missingFile(step: (String, Boolean, String) -> Unit) {
        val real = ctx.assets.open("descriptors/${TranslateModels.ASR_DESCRIPTOR}").use { it.readBytes().toString(Charsets.UTF_8) }
        val missing = real.replace(TranslateModels.ASR_FILE, "Qwen3-ASR-missing.litertlm").replace(TranslateModels.ASR_SHA256, "0".repeat(64))
        check(missing != real) { "the descriptor names neither ${TranslateModels.ASR_FILE} nor its sha256" }
        val client = HfModels(ctx)
        try {
            client.fromPretrained(ModelRef(TranslateModels.ASR_ID, revision = TranslateModels.ASR_COMMIT), Qwen3AsrTask, LoadOptions(networkPolicy = NetworkPolicy.Offline, descriptorJson = missing))
            step("missing_file", false, "reason=\"a missing file loaded\"")
        } catch (e: ModelException) {
            val words = TranslateModels.explain(e)
            step("missing_file", e.code == ErrorCode.OFFLINE_CACHE_MISS && words.contains("not on this phone"), "code=${e.code} screen=${q("Speech recognition not loaded: $words")}")
        } finally {
            client.closeAndJoin()
        }
    }

    private fun median(v: List<Double>): Double? = if (v.isEmpty()) null else v.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

    private fun f(v: Double?) = v?.let { String.format(Locale.US, "%.0f", it) } ?: "none"

    private fun f1(v: Double?) = v?.let { String.format(Locale.US, "%.1f", it) } ?: "none"

    private companion object {
        const val TAG = "hfmodels-check"
        /** Qwen3-ASR's window, as the app's endpointer cuts (TranslateViewModel.WINDOW_MS). */
        const val WINDOW_MS = 15_000
        const val HANGOVER_MS = 800
        const val TURN_TIMEOUT_MS = 120_000L
    }
}
