package io.github.johnrocky.hfmodels.check

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.Settings
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.johnrocky.hfmodels.BackendKind
import io.github.johnrocky.hfmodels.BackendPolicy
import io.github.johnrocky.hfmodels.BundledCatalog
import io.github.johnrocky.hfmodels.HfModels
import io.github.johnrocky.hfmodels.LoadOptions
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.ModelRef
import io.github.johnrocky.hfmodels.NetworkPolicy
import io.github.johnrocky.hfmodels.PreparedModel
import io.github.johnrocky.hfmodels.Task
import io.github.johnrocky.hfmodels.Tasks
import io.github.johnrocky.hfmodels.litert.Speak
import io.github.johnrocky.hfmodels.litert.Transcribe
import io.github.johnrocky.hfmodels.voice.Endpointer
import io.github.johnrocky.hfmodels.voice.PhoneTools
import io.github.johnrocky.hfmodels.voice.SpeechPlayer
import io.github.johnrocky.hfmodels.voice.VoiceLoop
import io.github.johnrocky.hfmodels.voice.VoiceLoop.Event
import io.github.johnrocky.hfmodels.voice.VoiceLoopConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drop-in device check for an app with the voice loop. It proves, on the connected phone and in the app's own
 * process, what the loop promises: the three models load from the app's store, one spoken command goes from its
 * audio to the phone's real tools (the alarm lands in the Clock app and Android reports it as the next alarm) and
 * out of the loudspeaker, and everything is released. It reports the network state and does not switch it: run it in
 * airplane mode to show that nothing needs the network.
 *
 * Install into an app:
 *   1. copy this file to app/src/androidTest/kotlin/VoiceDeviceCheck.kt (keep the package line);
 *   2. app/build.gradle.kts:
 *        android { defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" } }
 *        dependencies { androidTestImplementation("androidx.test:runner:1.7.0"); androidTestImplementation("androidx.test.ext:junit:1.3.0") }
 *      Until the transcriber's and the speaker's repos carry hfmodels.json, the app ships their development
 *      descriptors as assets (this sample: catalog/dev); the check reads them from the app's assets.
 *   3. the models in the app's store (one load in the app, or the files pushed into its external files dir), the
 *      permissions, and the command's audio (16 kHz mono 16-bit WAV):
 *        adb shell pm grant <applicationId> android.permission.READ_CALENDAR      # PhoneTools.phoneState reads the app's own calendar
 *        adb shell pm grant <applicationId> android.permission.WRITE_CALENDAR
 *        adb push c01.wav /data/local/tmp/hfmodels-voice/commands/c01.wav
 *   4. run it (keep the APKs installed: uninstalling the app deletes its model store):
 *        export ANDROID_SERIAL=<serial>
 *        ./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
 *          -Pandroid.testInstrumentationRunnerArguments.class=io.github.johnrocky.hfmodels.check.VoiceDeviceCheck
 *        adb logcat -d -s hfmodels-check | grep RESULT
 *   The last line is `RESULT ok=true ...` when every step passed; the gradle task fails otherwise.
 *   Arguments: wav (default /data/local/tmp/hfmodels-voice/commands/c01.wav, then <external files>/c01.wav),
 *   expect (default "Set an alarm for seven thirty tomorrow morning."; compared on letters and digits only).
 * Android reports one next alarm, so no alarm may be set at or before the check's 07:30 (the sample README's 07:30
 * included): the check stops first with `RESULT step=precondition ok=false` and the alarm Android reports. The check
 * sets a real alarm at 07:30 and then asks the Clock app to dismiss it by its label; a Clock that does not honour that
 * leaves it (the Samsung Clock did), and the cleanup line names the label to turn off or delete by hand. The network
 * and the cleanup are `RESULT info` lines: reported, not passed or failed.
 * Run it with the screen on and unlocked, or with an app screen that shows over the keyguard: under the keyguard the
 * app has no visible activity and Android drops the Clock app's SET_ALARM activity start (BAL_BLOCK). The check brings
 * the app's launcher activity to the front with the extra `autoload=false`, which a debug build of this sample takes as
 * its scripted mode (it shows over the keyguard and loads nothing); in another app, drop the extra and unlock the phone.
 */
@RunWith(AndroidJUnit4::class)
class VoiceDeviceCheck {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val wavArg = args.getString("wav")
    private val expect = args.getString("expect") ?: "Set an alarm for seven thirty tomorrow morning."

    @Test fun loadTurnAlarmRelease(): Unit = runBlocking {
        val failures = ArrayList<String>()
        fun step(name: String, ok: Boolean, values: String) {
            if (!ok) failures += name
            Log.i(TAG, "RESULT step=$name ok=$ok $values")
        }
        val net = network()
        val airplane = Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        Log.i(TAG, "device=${Build.MODEL} build=${Build.DISPLAY} package=${ctx.packageName} network=$net airplane_mode=$airplane")
        // The app in front, as a user has it: the alarm intent is an activity start, and a visible app may start one.
        ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let { launch ->
            runCatching { InstrumentationRegistry.getInstrumentation().startActivitySync(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("autoload", false)) }
                .onFailure { Log.w(TAG, "could not bring the app to the front: $it") }
        }
        val asrModels = HfModels(ctx); val ttsModels = HfModels(ctx); val llmModels = HfModels(ctx)
        val open = ArrayList<PreparedModel>()
        var player: SpeechPlayer? = null
        var label: String? = null
        try {
            // 0. the precondition: Android reports one next alarm, so an alarm at or before the check's 07:30 would hide
            // the one the check sets (or stand in for it).
            val alarms = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val checkAt = nextSevenThirty(System.currentTimeMillis())
            val already = alarms.nextAlarmClock?.triggerTime
            if (already != null && already < checkAt + 60_000) {
                throw PreconditionFailed("Android's next alarm is ${hhmm(already)}, at or before the check's ${hhmm(checkAt)}; turn it off or delete it in the Clock app and run the check again")
            }

            // 1. load, from the store (Offline when the phone has no network)
            val policy = if (net == "none") NetworkPolicy.Offline else NetworkPolicy.Any
            val (asr, asrMs) = load(asrModels, Transcribe, ASR_ID, "medium_fp16", BackendPolicy.Require(BackendKind.GPU), ASR_DESCRIPTOR, policy).also { open += it.first }
            val (tts, ttsMs) = load(ttsModels, Speak, TTS_ID, "fp32", BackendPolicy.Auto, TTS_DESCRIPTOR, policy).also { open += it.first }
            val (chat, llmMs) = load(llmModels, Tasks.Chat, LLM_ID, null, BackendPolicy.Require(BackendKind.GPU), null, policy).also { open += it.first }
            step("load", true, "asr=${asr.info.repoId}/${asr.info.variantId}/${asr.info.profileId} asr_ms=$asrMs tts=${tts.info.repoId}/${tts.info.variantId}/${tts.info.profileId} tts_ms=$ttsMs " +
                "llm=${chat.info.repoId}/${chat.info.variantId}/${chat.info.profileId} llm_ms=$llmMs network_policy=$policy")

            // 2. the command: its WAV through the loop's endpointer, the utterance to turn(pcm), the phone's real tools
            val wav = listOfNotNull(wavArg?.let(::File), File("/data/local/tmp/hfmodels-voice/commands/c01.wav"), ctx.getExternalFilesDir(null)?.let { File(it, "c01.wav") }).first { it.canRead() }
            val config = VoiceLoopConfig()
            val utterance = endpoint(readWav(wav), config.endpointer.hangoverMs)
            val p = SpeechPlayer(tts.sampleRate).also { player = it }
            val loop = VoiceLoop(asr, chat, tts, PhoneTools.all(ctx), config.copy(player = p))
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val before = am.nextAlarmClock?.triggerTime
            val events = withTimeout(TURN_TIMEOUT_MS) { loop.turn(utterance).toList() }
            val heard = (events.firstOrNull { it is Event.Heard } as Event.Heard?)?.text.orEmpty()
            val calls = events.filterIsInstance<Event.ToolCalled>()
            val alarm = calls.firstOrNull { it.name == "set_alarm" && num(it.args["hour"]) == 7 && num(it.args["minute"]) == 30 && !it.result.startsWith("Error") }
            label = alarm?.args?.get("label")?.toString()
            val t = (events.lastOrNull() as? Event.Done)?.timing
            val speaking = events.count { it is Event.Speaking }
            val errors = events.filterIsInstance<Event.Error>()
            // The Clock app takes the intent on its own time: wait for Android to report the alarm.
            var after = am.nextAlarmClock?.triggerTime
            val until = SystemClock.elapsedRealtime() + ALARM_WAIT_MS
            while (alarm != null && !isSevenThirty(after) && SystemClock.elapsedRealtime() < until) { delay(200); after = am.nextAlarmClock?.triggerTime }
            val state = PhoneTools.phoneState(ctx)
            val nextLine = state.lineSequence().first()
            val alarmSet = alarm != null && isSevenThirty(after) && nextLine.endsWith("07:30")
            step("turn", words(heard) == words(expect) && alarm != null && alarmSet && t?.firstAudioMs != null && speaking > 0 && errors.isEmpty(),
                "wav=${wav.path} heard=${q(heard)} expect=${q(expect)} heard_ok=${words(heard) == words(expect)} " +
                    "calls=${calls.joinToString(",", "[", "]") { "${it.name}${it.args}" }} tool_ok=${alarm != null} alarm_set=$alarmSet " +
                    "next_alarm_before=${hhmm(before)} next_alarm_after=${hhmm(after)} phone=${q(nextLine)} " +
                    "ms_first_audio=${f(t?.firstAudioMs)} hangover_ms=${config.endpointer.hangoverMs} ms_total=${f(t?.totalMs)} ms_transcribe=${f(t?.transcribeMs)} " +
                    "ms_first_token=${f(t?.firstTokenMs)} speaking=$speaking errors=${errors.joinToString(" | ", "[", "]") { "${it.code}: ${it.message.take(160)}" }} " +
                    "spoken=${q(t?.spoken.orEmpty())} reply=${q(t?.reply.orEmpty())}")

            // 3. the network, as reported (this check does not switch it)
            Log.i(TAG, "RESULT info network=$net airplane_mode=$airplane")

            // 4. release: the loop, the player, the three models
            val t0 = SystemClock.elapsedRealtime()
            loop.closeAndJoin()
            p.close(); player = null
            for (m in open.reversed()) m.closeAndJoin()
            open.clear()
            asrModels.closeAndJoin(); ttsModels.closeAndJoin(); llmModels.closeAndJoin()
            val closeMs = SystemClock.elapsedRealtime() - t0
            step("release", closeMs < 10_000, "close_ms=$closeMs")
        } catch (e: PreconditionFailed) {
            step("precondition", false, "reason=${q(e.message.orEmpty())}")
        } catch (e: ModelException) {
            step("exception", false, "error=${e.code} reason=${q(e.reason)}")
        } catch (e: Exception) {
            Log.e(TAG, "failed", e)
            step("exception", false, "error=${e.javaClass.simpleName} message=${q(e.message.orEmpty())}")
        } finally {
            player?.close()
            for (m in open.reversed()) runCatching { m.closeAndJoin() }
            asrModels.closeAndJoin(); ttsModels.closeAndJoin(); llmModels.closeAndJoin()
            dismiss(label)
            Log.i(TAG, "RESULT ok=${failures.isEmpty()} failed=$failures network=$net device=${Build.MODEL} build=${Build.DISPLAY}")
        }
        assertTrue("failed steps: $failures (adb logcat -d -s hfmodels-check)", failures.isEmpty())
    }

    /**
     * Asks the Clock app to dismiss the check's alarm by its label and reports whether Android still shows 07:30 next
     * (an info line: no step passes or fails on it).
     */
    private suspend fun dismiss(label: String?) {
        if (label == null) return
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val asked = runCatching {
            ctx.startActivity(Intent(AlarmClock.ACTION_DISMISS_ALARM)
                .putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val until = SystemClock.elapsedRealtime() + ALARM_WAIT_MS
        while (isSevenThirty(am.nextAlarmClock?.triggerTime) && SystemClock.elapsedRealtime() < until) delay(200)
        val left = isSevenThirty(am.nextAlarmClock?.triggerTime)
        Log.i(TAG, "RESULT info cleanup dismiss_asked=${asked.isSuccess} next_alarm=${hhmm(am.nextAlarmClock?.triggerTime)} " +
            if (left) "left=true todo=${q("turn off or delete the 07:30 alarm labelled '$label' in the Clock app by hand")}" else "left=false")
    }

    private suspend fun <M : PreparedModel> load(models: HfModels, task: Task<M>, id: String, variant: String?, backend: BackendPolicy, descriptorAsset: String?, policy: NetworkPolicy): Pair<M, Long> {
        val descriptor = descriptorAsset?.let { a -> ctx.assets.open(a).bufferedReader().use { it.readText() } }
        val commit = descriptor?.let { JSONObject(it).getString("revision") } ?: BundledCatalog.load(ctx).defaultBinding(id)?.modelCommit
        val t0 = SystemClock.elapsedRealtime()
        val m = models.fromPretrained(ModelRef(id, revision = commit, variant = variant), task, LoadOptions(backendPolicy = backend, networkPolicy = policy, descriptorJson = descriptor))
        return m to SystemClock.elapsedRealtime() - t0
    }

    /** The WAV and 1 s of silence through an endpointer as the loop's listen uses it (20 ms chunks): the utterance it cuts. */
    private fun endpoint(wav: FloatArray, hangoverMs: Int): FloatArray {
        val ep = VoiceLoopConfig().endpointer
        val all = wav + FloatArray(16000 * (hangoverMs + 200) / 1000)
        for (i in all.indices step 320) {
            val cut = ep.feed(all.copyOfRange(i, minOf(i + 320, all.size))).firstOrNull { it is Endpointer.Event.Utterance } as Endpointer.Event.Utterance?
            if (cut != null) return cut.pcm
        }
        return ep.flush()?.pcm ?: error("the endpointer found no speech in the WAV")
    }

    private fun network(): String {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (cm.activeNetwork == null) "none" else "up"
    }

    /** The next 07:30 after [now], local time. */
    private fun nextSevenThirty(now: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 7); set(Calendar.MINUTE, 30); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    private fun isSevenThirty(at: Long?): Boolean = at != null && Calendar.getInstance().apply { timeInMillis = at }.let { it.get(Calendar.HOUR_OF_DAY) == 7 && it.get(Calendar.MINUTE) == 30 }

    private fun hhmm(at: Long?): String = at?.let { java.text.SimpleDateFormat("EEE-HH:mm", java.util.Locale.US).format(java.util.Date(it)) } ?: "none"

    private fun num(v: Any?): Int? = when (v) { is Number -> v.toInt(); null -> null; else -> v.toString().trim().toDoubleOrNull()?.toInt() }

    private fun words(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun f(v: Double?) = v?.let { "%.0f".format(it) } ?: "none"

    private fun q(s: String) = "\"" + s.take(300).replace("\n", " ").replace("\"", "'") + "\""

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

    private class PreconditionFailed(message: String) : Exception(message)

    private companion object {
        const val TAG = "hfmodels-check"
        const val ASR_ID = "litert-community/Zipformer-medium-CR-CTC-LiteRT"
        const val TTS_ID = "litert-community/kitten-tts-nano-0.8"
        const val LLM_ID = "litert-community/gemma-4-E2B-it-litert-lm"
        const val ASR_DESCRIPTOR = "litert-community__Zipformer-medium-CR-CTC-LiteRT.hfmodels.json"
        const val TTS_DESCRIPTOR = "litert-community__kitten-tts-nano-0.8.hfmodels.json"
        const val TURN_TIMEOUT_MS = 180_000L
        const val ALARM_WAIT_MS = 5_000L
    }
}
