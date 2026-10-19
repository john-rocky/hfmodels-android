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
import io.github.johnrocky.hfmodels.samples.translate.InputGate
import io.github.johnrocky.hfmodels.samples.translate.ParakeetGraphTask
import io.github.johnrocky.hfmodels.samples.translate.TranslateLoop
import io.github.johnrocky.hfmodels.samples.translate.TranslateLoop.Event
import io.github.johnrocky.hfmodels.samples.translate.TranslateModels
import io.github.johnrocky.hfmodels.samples.translate.TurnRecord
import io.github.johnrocky.hfmodels.samples.translate.Wav
import io.github.johnrocky.hfmodels.samples.translate.asPlayer
import io.github.johnrocky.hfmodels.samples.translate.q
import io.github.johnrocky.hfmodels.samples.translate.turnLine
import io.github.johnrocky.hfmodels.samples.translate.utterancesOf
import io.github.johnrocky.hfmodels.voice.Endpointer
import io.github.johnrocky.hfmodels.voice.SpeechPlayer
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
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
 * the median from the end of speech to the first English sound over the Spanish turns.
 *
 * The fixtures (not in this repository: the ten sentences read by macOS voices and the failure-path files, 16 kHz mono
 * 16-bit) in `<fixtures>` (default /data/local/tmp/translate): `sentences.tsv` (id, dir, src, ref, sec, checks),
 * `mac_asr_30s.tsv` (the Mac host's transcripts, id<TAB>text), `wav/e01.wav … n05.wav`, `silence_5s.wav`, `noise_5s.wav`,
 * `long_35s.wav`, `fr_input.wav`.
 *
 * Steps: load / turn (x20) / nothing_heard_silence / nothing_heard_noise / gate_noise / long_35s / french (both buttons,
 * behaviour recorded) / missing_file (a load of a file the store does not have, offline: the screen's words) /
 * stop (a turn stopped mid-translation, then the next turn) / memory / release, and `RESULT info` lines for the network.
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
        val loop = TranslateLoop({ t.transcribeDetailed(it) }, { text, target, created -> tr.translate(text, target, created) }, speaker, p?.asPlayer())
        val backends = "asr:cpu,mt:${models.mtBackend.lowercase()},tts:${if (speaker != null) "cpu" else "none"}"
        suspend fun turn(direction: Direction, utterance: FloatArray, input: String): Pair<List<Event>, TurnRecord?> {
            val events = withTimeout(TURN_TIMEOUT_MS) { loop.turn(direction, utterance, System.nanoTime(), HANGOVER_MS, input).toList() }
            val r = (events.lastOrNull() as? Event.Done)?.record
            r?.let { Log.i(TAG, turnLine(it, backends, TranslateModels.network(ctx), TranslateModels.vmHwmKb())) }
            return events to r
        }

        // 2. the ten sentences, [rounds] times
        val rows = File(dir, "sentences.tsv").readLines().drop(1).filter { it.isNotBlank() }.map { it.split('\t') }
        val mac = File(dir, "mac_asr_30s.tsv").takeIf { it.canRead() }?.readLines()?.filter { it.isNotBlank() }?.associate { it.substringBefore('\t') to it.substringAfter('\t') }.orEmpty()
        val hwmLoaded = TranslateModels.vmHwmKb()
        val spoken = ArrayList<TurnRecord>()
        val written = ArrayList<TurnRecord>()
        for (round in 1..rounds) for (row in rows) {
            val id = row[0]
            val direction = Direction.of(row[1]) ?: continue
            val utterance = utterancesOf(Wav.read(File(dir, "wav/$id.wav")).first, Endpointer(maxUtteranceMs = WINDOW_MS)).firstOrNull()
            if (utterance == null) { step("turn", false, "round=$round id=$id reason=\"the endpointer found no speech\""); continue }
            val (events, r) = turn(direction, utterance, "file:$id")
            val ok = r != null && r.heard.isNotEmpty() && r.translation.isNotEmpty() && r.errors.isEmpty() && (!direction.spoken || speaker == null || r.firstAudioMs != null)
            step("turn", ok, "round=$round id=$id dir=${direction.tag} heard=${q(r?.heard.orEmpty())} mac=${q(mac[id].orEmpty())} same_as_mac=${r?.heard == mac[id]} " +
                "translation=${q(r?.translation.orEmpty())} ref=${q(row.getOrNull(3).orEmpty())} spoken=${q(r?.spoken?.joinToString(" ").orEmpty())} " +
                "ms_end_of_speech_to_sound=${f(r?.endOfSpeechToSoundMs)} ms_end_of_speech_to_text=${f(r?.endOfSpeechToTextMs)} errors=${events.filterIsInstance<Event.Error>().size}")
            if (r != null && ok) (if (direction.spoken) spoken else written) += r
        }
        val hwmTurns = TranslateModels.vmHwmKb()
        Log.i(TAG, "RESULT step=summary ok=true turns=${spoken.size + written.size} es_en_turns=${spoken.size} en_es_turns=${written.size} " +
            "l1_median_ms_end_of_speech_to_sound=${f(median(spoken.mapNotNull { it.endOfSpeechToSoundMs }))} " +
            "median_ms_transcribe=${f(median(spoken.mapNotNull { it.transcribeMs }))} median_asr_encode_ms=${f(median(spoken.mapNotNull { it.asr?.encodeMs }))} " +
            "median_asr_decode_ms=${f(median(spoken.mapNotNull { it.asr?.decodeMs }))} median_ms_translate_create=${f1(median(spoken.mapNotNull { it.translateCreateMs }))} " +
            "median_ms_translate_first_token=${f(median(spoken.mapNotNull { it.translateFirstTokenMs }))} median_ms_first_sentence=${f(median(spoken.mapNotNull { it.firstSentenceMs }))} " +
            "median_ms_first_synth=${f(median(spoken.mapNotNull { it.firstSynthMs }))} median_ms_first_audio=${f(median(spoken.mapNotNull { it.firstAudioMs }))} " +
            "en_es_median_ms_end_of_speech_to_text=${f(median(written.mapNotNull { it.endOfSpeechToTextMs }))} hangover_ms=$HANGOVER_MS mt_backend=${models.mtBackend}")
        step("memory", true, "vmhwm_kb_before_load=$hwmBefore vmhwm_kb_after_load=$hwmLoaded vmhwm_kb_after_turns=$hwmTurns")

        // 3. nothing to hear: the endpointer cuts nothing from silence or quiet noise; the gate refuses the noise as an utterance.
        for (name in listOf("silence_5s", "noise_5s")) {
            val pcm = Wav.read(File(dir, "wav/$name.wav")).first
            val n = utterancesOf(pcm, Endpointer(maxUtteranceMs = WINDOW_MS)).size
            step("nothing_heard_${name.substringBefore('_')}", n == 0, "file=$name.wav utterances=$n screen=\"Nothing heard\"")
        }
        val noise = Wav.read(File(dir, "wav/noise_5s.wav")).first
        val (gateEvents, gateRecord) = turn(Direction.ES_EN, noise, "file:noise_5s(whole)")
        step("gate_noise", gateEvents.any { it is Event.NothingHeard } && gateRecord?.heard.isNullOrEmpty(),
            "reason=${q(gateRecord?.nothingHeard.orEmpty())} rms=${String.format(Locale.US, "%.4f", gateRecord?.rms ?: 0.0)} floor=${InputGate.DEFAULT_FLOOR_RMS}")

        // 4. 35 s of speech: the endpointer cuts it at the 30 s window; each part is a turn.
        val long = utterancesOf(Wav.read(File(dir, "wav/long_35s.wav")).first, Endpointer(maxUtteranceMs = WINDOW_MS))
        val parts = long.map { u -> turn(Direction.ES_EN, u, "file:long_35s").second }
        step("long_35s", long.size >= 2 && parts.all { it != null && it.heard.isNotEmpty() },
            "utterances=${long.size} seconds=${long.joinToString(",", "[", "]") { String.format(Locale.US, "%.2f", it.size / 16000.0) }} " +
                "heard=${parts.joinToString(" | ") { q(it?.heard.orEmpty()) }} pieces=${parts.joinToString(",", "[", "]") { (it?.asr?.pieces ?: 0).toString() }} " +
                "ms_end_of_speech_to_sound=${parts.joinToString(",", "[", "]") { f(it?.endOfSpeechToSoundMs) }}")

        // 5. French into both buttons: what happens is recorded (parakeet finds the language itself; Hy-MT2 translates what it gets).
        val french = utterancesOf(Wav.read(File(dir, "wav/fr_input.wav")).first, Endpointer(maxUtteranceMs = WINDOW_MS)).firstOrNull()
        for (d in Direction.entries) {
            val r = french?.let { turn(d, it, "file:fr_input").second }
            step("french_${d.tag}", r != null, "heard=${q(r?.heard.orEmpty())} translation=${q(r?.translation.orEmpty())} errors=${q(r?.errors?.joinToString(" | ").orEmpty())}")
        }

        // 6. Stop in the middle of a Spanish turn (as the button does), then the next turn.
        val e03 = utterancesOf(Wav.read(File(dir, "wav/e03.wav")).first, Endpointer(maxUtteranceMs = WINDOW_MS)).first()
        val seen = java.util.concurrent.CopyOnWriteArrayList<Event>()
        val job = launch(Dispatchers.Default) { loop.turn(Direction.ES_EN, e03, System.nanoTime(), HANGOVER_MS, "file:e03-stop").collect { seen += it } }
        val until = SystemClock.elapsedRealtime() + TURN_TIMEOUT_MS
        while (seen.none { it is Event.Translation } && SystemClock.elapsedRealtime() < until && job.isActive) delay(10)
        val s0 = SystemClock.elapsedRealtime()
        job.cancelAndJoin()
        val stopMs = SystemClock.elapsedRealtime() - s0
        val (_, after) = turn(Direction.ES_EN, utterancesOf(Wav.read(File(dir, "wav/e01.wav")).first, Endpointer(maxUtteranceMs = WINDOW_MS)).first(), "file:e01-after-stop")
        step("stop", stopMs < 2_000 && seen.none { it is Event.Done } && after != null && after.translation.isNotEmpty(),
            "stop_ms=$stopMs events_before_stop=${seen.size} next_turn_translation=${q(after?.translation.orEmpty())}")

        // 7. the network, as reported (this check does not switch it)
        Log.i(TAG, "RESULT info network=$net airplane_mode=$airplane")

        // 8. release
        val r0 = SystemClock.elapsedRealtime()
        p?.close(); onPlayer(null)
        models.closeAndJoin()
        val closeMs = SystemClock.elapsedRealtime() - r0
        step("release", closeMs < 10_000, "close_ms=$closeMs")
    }

    /** A load of a file the store does not have, with no network: the SDK's OFFLINE_CACHE_MISS and the app's words for it. */
    private suspend fun missingFile(step: (String, Boolean, String) -> Unit) {
        val real = ctx.assets.open("descriptors/${TranslateModels.GRAPH_DESCRIPTOR}").use { it.readBytes().toString(Charsets.UTF_8) }
        val missing = real.replace("parakeet_tdt_0.6b_v3_30s_i8_stateful.tflite", "parakeet_missing.tflite")
            .replace("378935f8897f0c713ad2aa97d939c73c44e9f26546e12c1cd6af9c76f386f06a", "0".repeat(64))
        val client = HfModels(ctx)
        try {
            client.fromPretrained(ModelRef(TranslateModels.GRAPH_ID, revision = TranslateModels.GRAPH_COMMIT), ParakeetGraphTask, LoadOptions(networkPolicy = NetworkPolicy.Offline, descriptorJson = missing))
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
        const val WINDOW_MS = 30_000
        const val HANGOVER_MS = 800
        const val TURN_TIMEOUT_MS = 120_000L
    }
}
