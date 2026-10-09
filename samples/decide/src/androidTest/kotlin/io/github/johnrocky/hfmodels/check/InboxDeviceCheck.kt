package io.github.johnrocky.hfmodels.check

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.johnrocky.hfmodels.BackendKind
import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.LoadEvent
import io.github.johnrocky.hfmodels.LoadOptions
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.ModelRef
import io.github.johnrocky.hfmodels.NetworkPolicy
import io.github.johnrocky.hfmodels.litert.EncoderDecisions
import io.github.johnrocky.hfmodels.samples.decide.DecisionModels
import io.github.johnrocky.hfmodels.samples.decide.Inbox
import io.github.johnrocky.hfmodels.samples.decide.InboxPanel
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device check for the inbox screen of samples/decide (InboxActivity). On the connected phone and in the app's
 * own process it runs what the screen runs (DecisionModels.load, Inbox.paste / unread / sort) and proves: the
 * model loads; the 24 hand-labelled panel texts land where their labels say (at least 19 of the 21 that are not
 * scams); five pasted lines become five texts with an answer each; the unread count is read when the app holds
 * READ_SMS (the count only: no text or sender of the phone's own messages is logged); a sort cancelled half-way
 * ends without an error and the next sort runs; release returns and a second close is no error; a variant the
 * descriptor does not have, and offline a variant whose files are not on the phone, fail with the SDK's code;
 * an empty paste comes back as a sentence.
 *
 *   adb push laya_en_s256_fp32.tflite laya_act_head_fp32.tflite tokenizer.json tokenizer_config.json rl_agent_config.json \
 *       /sdcard/Android/data/io.github.johnrocky.hfmodels.samples.decide/files/    # optional: else the load downloads 1.69 GB
 *   ./gradlew :samples:decide:assembleDebug :samples:decide:assembleDebugAndroidTest
 *   adb install -r samples/decide/build/outputs/apk/debug/decide-debug.apk
 *   adb install -r samples/decide/build/outputs/apk/androidTest/debug/decide-debug-androidTest.apk
 *   adb shell am instrument -w -e class io.github.johnrocky.hfmodels.check.InboxDeviceCheck -e backend gpu -e network offline \
 *       io.github.johnrocky.hfmodels.samples.decide.test/androidx.test.runner.AndroidJUnitRunner
 *   adb logcat -d -s hfmodels-check | grep RESULT
 *
 * The push goes to a directory that exists once the app has run (or `adb shell mkdir -p` it); the five files are the
 * variant's (litert-community/laya-LiteRT, hfmodels.json), each hashed against it before it is imported. `network=offline`
 * makes no request and needs one earlier online load of the id on the phone (it saves the binding); `network=any`
 * (the default) also works on a phone that never loaded it. `connectedDebugAndroidTest` runs it as well, but uninstalls
 * the app afterwards, and the imported model with it. One `RESULT step=<name> ok=<bool> …` line per step, every line
 * naming the variant and the profile; the last line is `RESULT ok=<all> …` with `failure_paths=<ok>/4` (stop, release,
 * missing-model, empty-paste). Arguments: variant (default en_s256_fp32), backend=gpu|cpu|auto (default gpu),
 * network=offline|any (default any).
 */
@RunWith(AndroidJUnit4::class)
class InboxDeviceCheck {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val backend: BackendKind? = when (args.getString("backend")) { "cpu" -> BackendKind.CPU; "auto" -> null; else -> BackendKind.GPU }
    private val network = if (args.getString("network") == "offline") NetworkPolicy.Offline else NetworkPolicy.Any

    @Test fun loadSortRelease(): Unit = runBlocking {
        val failures = ArrayList<String>()
        val failurePaths = ArrayList<Boolean>()
        val d = DecisionModels(ctx)
        // The variant and profile every line names: the requested ones until the load says which it opened.
        var variant = args.getString("variant") ?: "en_s256_fp32"
        var profile = "none"
        fun step(name: String, ok: Boolean, values: String) {
            if (!ok) failures += name
            Log.i(TAG, "RESULT step=$name ok=$ok model=${DecisionModels.REPO} variant=$variant profile=$profile $values")
        }
        val power = ctx.getSystemService(PowerManager::class.java)
        val thermalBefore = power.currentThermalStatus
        val events = ArrayList<LoadEvent>()
        val t0 = SystemClock.elapsedRealtime()
        val model = try {
            d.load(variant, backend, network) { events += it }.also { m ->
                variant = m.info.variantId
                profile = m.info.profileId
                val compileMs = m.info.notes.firstNotNullOfOrNull { Regex("compile_ms=(\\d+)").find(it)?.groupValues?.get(1) } ?: "?"
                step("load", true, "backend=${backend?.name ?: "auto"} network=$network compile_ms=$compileMs load_ms=${SystemClock.elapsedRealtime() - t0} " +
                    "downloaded=${events.any { it is LoadEvent.DownloadStarted }} fallback=${m.info.fallbackHistory} commit=${m.info.commit.take(8)}")
            }
        } catch (t: Throwable) {
            step("load", false, "backend=${backend?.name ?: "auto"} network=$network error=${what(t)} load_ms=${SystemClock.elapsedRealtime() - t0}")
            Log.i(TAG, "RESULT ok=false variant=$variant profile=$profile failed=$failures device=${Build.MODEL} build=${Build.DISPLAY}")
            d.models.closeAndJoin()
            assertTrue("load failed: ${what(t)}", false)
            return@runBlocking
        }
        // Any error is recorded as a failure of the step it happened in, so a RESULT ok=true line never follows it.
        var current = "panel"
        try {
            // The panel, sorted exactly as the screen sorts it (one decide per text, the question of InboxPanel).
            val panel = Inbox.panel()
            val t1 = SystemClock.elapsedRealtimeNanos()
            val sorted = Inbox.sort(model, panel)
            val totalMs = (SystemClock.elapsedRealtimeNanos() - t1) / 1e6
            for ((i, s) in sorted.withIndex()) {
                Log.i(TAG, "panel i=${i + 1} label=${s.text.label} answer=${q(s.choice)} expected=${q(expected(s) ?: "-")} p=${s.probabilities[s.choice]} ms=${s.ms} text=${q(s.text.text)}")
            }
            val (agree, of) = Inbox.agreement(sorted)
            val ms = sorted.map { it.ms }
            val disagree = sorted.filter { it.text.label != "scam" && expected(it) != it.choice }.map { "#${it.text.id + 1}:${it.text.label}=${short(it.choice)}" }
            val scams = sorted.filter { it.text.label == "scam" }.map { "#${it.text.id + 1}=${short(it.choice)}" }
            step("panel", sorted.size == panel.size && of == 21 && agree >= 19,
                "agree=$agree/$of n=${sorted.size} ms_median=${Inbox.median(ms)} ms_p90=${Inbox.p90(ms)} ms_min=${ms.minOrNull()} ms_max=${ms.maxOrNull()} ms_first=${ms.firstOrNull()} " +
                    "total_ms=$totalMs bins=${InboxPanel.SHORT.zip(InboxPanel.OPTIONS).map { (name, o) -> "$name:${sorted.count { it.choice == o }}" }} disagree=$disagree scams=$scams")

            // Five lines as the clipboard would hand them over (blank lines, spaces and a CRLF included): five texts, five answers.
            current = "paste"
            val pasted = (Inbox.paste(PASTE) as? Inbox.Import.Texts)?.texts.orEmpty()
            val answers = Inbox.sort(model, pasted)
            val pastedAgree = answers.withIndex().count { (i, s) -> s.choice == PASTE_EXPECTED.getOrNull(i) }
            step("paste", pasted.size == 5 && answers.size == 5 && pasted.all { it.sender == Inbox.PASTED },
                "rows=${pasted.size} answers=${answers.map { short(it.choice) }} expected_agree=$pastedAgree/5 ms_median=${Inbox.median(answers.map { it.ms })}")

            // The phone's own unread texts through the screen's import: the count only.
            current = "inbox-count"
            if (ctx.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
                val n = (Inbox.unread(ctx.contentResolver) as? Inbox.Import.Texts)?.texts?.size ?: 0
                step("inbox-count", true, "read_sms=granted count=$n")
            } else {
                step("inbox-count", true, "read_sms=not_granted count=skipped")
            }

            // Stop: cancel the sort while a text is being answered; it ends without an error, the next sort runs.
            current = "stop"
            val answered = AtomicInteger(0)
            var cancelled = false
            var error: Throwable? = null
            val job = launch(Dispatchers.Default) {
                try {
                    Inbox.sort(model, panel) { _, _ -> answered.incrementAndGet() }
                } catch (c: CancellationException) {
                    cancelled = true; throw c
                } catch (t: Throwable) {
                    error = t
                }
            }
            withTimeout(60_000) { while (answered.get() < 3 && job.isActive) delay(5) }
            delay(40) // into the next text's forward
            val atCancel = answered.get()
            val tc = SystemClock.elapsedRealtimeNanos()
            job.cancel(); job.join()
            val cancelMs = (SystemClock.elapsedRealtimeNanos() - tc) / 1e6
            val again = Inbox.sort(model, panel.take(3))
            val stopOk = cancelled && error == null && answered.get() < panel.size && again.size == 3
            failurePaths += stopOk
            step("stop", stopOk, "answered_at_cancel=$atCancel answered_after=${answered.get()} of=${panel.size} cancel_to_end_ms=$cancelMs cancelled=$cancelled " +
                "error=${error?.let { what(it) }} again=${again.size}/3 again_ms_median=${Inbox.median(again.map { it.ms })}")
        } catch (t: Throwable) {
            step(current, false, "error=${what(t)}")
            if (current == "stop") failurePaths += false
        } finally {
            // Release: closeAndJoin must return (10 s cap inside the SDK); a second close of everything is no error.
            val t2 = SystemClock.elapsedRealtime()
            val released = runCatching { d.release(); d.models.closeAndJoin() }
            val closeMs = SystemClock.elapsedRealtime() - t2
            val second = runCatching { model.closeAndJoin(); model.close(); d.release(); d.models.closeAndJoin(); d.models.close() }
            val afterClose = try {
                Inbox.sortOne(model, Inbox.Text(0, Inbox.PASTED, "See you tomorrow.")); "answered"
            } catch (e: ModelException) { e.code.name } catch (t: Throwable) { t.javaClass.simpleName }
            val releaseOk = released.isSuccess && second.isSuccess && closeMs < 10_000
            failurePaths += releaseOk
            step("release", releaseOk, "close_ms=$closeMs second_close=${second.exceptionOrNull()?.let { what(it) } ?: "ok"} decide_after_close=$afterClose" +
                (released.exceptionOrNull()?.let { " error=${what(it)}" } ?: ""))
        }

        // A model that is not on the phone, through the load the screen uses: the SDK's code, as the screen shows it.
        val d2 = DecisionModels(ctx)
        try {
            val noVariant = loadError(d2, NO_SUCH_VARIANT)
            val notHere = notOnPhone(d2, variant)
            val notHereError = notHere?.let { loadError(d2, it) }
            val missingOk = noVariant?.code == ErrorCode.VARIANT_NOT_FOUND && (notHere == null || notHereError?.code == ErrorCode.OFFLINE_CACHE_MISS)
            failurePaths += missingOk
            step("missing-model", missingOk,
                "no_such_variant=$NO_SUCH_VARIANT code=${noVariant?.code ?: "loaded"} screen=${q(noVariant?.let { Inbox.failure(it) } ?: "")} " +
                    "offline_variant=${notHere ?: "none_missing"} offline_code=${notHereError?.code ?: if (notHere == null) "skipped" else "loaded"} offline_screen=${q(notHereError?.let { Inbox.failure(it) } ?: "")}")
        } catch (t: Throwable) {
            failurePaths += false
            step("missing-model", false, "error=${what(t)}")
        } finally {
            runCatching { d2.release(); d2.models.closeAndJoin() }
        }

        // Paste with nothing on the clipboard: a sentence for the screen, never an empty list to sort.
        val empties = listOf<CharSequence?>(null, "", " \n\t\r\n  ")
        val results = empties.map { Inbox.paste(it) }
        val emptyOk = results.all { it is Inbox.Import.Empty && it.message.isNotBlank() }
        failurePaths += emptyOk
        step("empty-paste", emptyOk, "inputs=${empties.size} empty=${results.count { it is Inbox.Import.Empty }} message=${q((results.first() as? Inbox.Import.Empty)?.message ?: "")}")

        Log.i(TAG, "RESULT ok=${failures.isEmpty()} model=${DecisionModels.REPO}@${model.info.commit.take(8)} variant=$variant profile=$profile failed=$failures " +
            "failure_paths=${failurePaths.count { it }}/${failurePaths.size} sdk=${model.info.sdkVersion} runtime=${model.info.runtime} ${model.info.runtimeVersion} " +
            "device=${Build.MODEL} soc=${Build.SOC_MODEL} build=${Build.DISPLAY} thermal=$thermalBefore->${power.currentThermalStatus}")
        assertTrue("failed steps: $failures (adb logcat -d -s hfmodels-check)", failures.isEmpty())
    }

    /** The load the screen runs, offline; the ModelException it fails with, or null when it loaded (then closed). */
    private suspend fun loadError(d: DecisionModels, variant: String): ModelException? = try {
        d.load(variant, backend, NetworkPolicy.Offline)
        d.release()
        null
    } catch (e: ModelException) {
        e
    }

    /**
     * One of the app's variants other than [loaded] whose files are neither in the app's store nor pushed to its external
     * files dir (where the SDK would import them from), so an offline load of it must stop; null when every one is here.
     */
    private suspend fun notOnPhone(d: DecisionModels, loaded: String): String? {
        val pushed = ctx.getExternalFilesDir(null)
        return d.variants.filter { it != loaded }.firstOrNull { v ->
            val plan = runCatching { d.models.inspect(ModelRef(DecisionModels.REPO, variant = v), EncoderDecisions, LoadOptions(networkPolicy = NetworkPolicy.Offline)) }.getOrNull()
            plan != null && plan.bytesToDownload > 0 && plan.files.filter { !it.cached }.none { f ->
                val name = f.path.substringAfterLast('/')
                pushed != null && (File(pushed, name).isFile || File(File(pushed, "hfmodels"), name).isFile)
            }
        }
    }

    private fun expected(s: Inbox.Sorted): String? = s.text.label?.let { InboxPanel.LABEL_OPTION[it] }

    private fun short(option: String) = InboxPanel.SHORT.getOrElse(InboxPanel.OPTIONS.indexOf(option)) { option }

    private fun q(s: String) = "\"" + s.take(160).replace("\n", " ").replace("\"", "'") + "\""

    /** An error as one RESULT field: the SDK's code and reason, or the exception's class and message. */
    private fun what(t: Throwable) = if (t is ModelException) "${t.code} reason=${q(t.reason)}" else "${t.javaClass.simpleName} reason=${q(t.message ?: "")}"

    private companion object {
        const val TAG = "hfmodels-check"
        /** Not in laya-LiteRT's hfmodels.json. */
        const val NO_SUCH_VARIANT = "en_s999_fp32"
        /** Five invented texts (the names are in NAMES.txt), with what a clipboard can carry around them. */
        const val PASTE = "  Your Kelderbank verification code is 551902. Do not share it.\n\n" +
            "Parcelwick: your parcel arrives tomorrow between 9 am and 1 pm.\r\n" +
            "Reminder: your Cobblemere Water bill of \$54.20 is due on Monday.\n   \n" +
            "Molarbrook Dental: your cleaning is booked for Thu 15 Oct at 10:30 am.\n" +
            "Thanks for dinner last night, it was lovely to see you!  \n"
        /** What each of the five asks of you, in order (logged as expected_agree, not part of the line). */
        val PASTE_EXPECTED = listOf(InboxPanel.OPTIONS[1], InboxPanel.OPTIONS[2], InboxPanel.OPTIONS[3], InboxPanel.OPTIONS[4], InboxPanel.OPTIONS[0])
    }
}
