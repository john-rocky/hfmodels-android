package io.github.johnrocky.hfmodels.check

import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.johnrocky.hfmodels.LoadEvent
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.NetworkPolicy
import io.github.johnrocky.hfmodels.samples.promises.DecisionModels
import io.github.johnrocky.hfmodels.samples.promises.Promises
import io.github.johnrocky.hfmodels.samples.promises.SampleChat
import io.github.johnrocky.hfmodels.samples.promises.Sentence
import io.github.johnrocky.hfmodels.samples.promises.Sentences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device check for samples/promises. On the connected phone and in the app's own process it proves what the
 * screen relies on: the model loads from the app's descriptor asset (its files from the app's private
 * storage, a copy pushed to its external files dir, or a download), the Sample conversation goes through the
 * app's own code (Sentences.split, Promises.judge) and lands in the bundles its labels expect, a sentence too
 * long for the 128-token window comes back as "too long" instead of an error, and release returns.
 *
 *   adb push gliner25_decide_s128_wfp16.tflite word_embeddings_fp16.bin tokenizer.json \
 *       /sdcard/Android/data/io.github.johnrocky.hfmodels.samples.promises/files/    # optional: else the load downloads 0.93 GB
 *   ./gradlew :samples:promises:assembleDebug :samples:promises:assembleDebugAndroidTest
 *   adb install -r samples/promises/build/outputs/apk/debug/promises-debug.apk
 *   adb install -r samples/promises/build/outputs/apk/androidTest/debug/promises-debug-androidTest.apk
 *   adb shell am instrument -w -e class io.github.johnrocky.hfmodels.check.PromisesDeviceCheck -e backend gpu -e network offline \
 *       io.github.johnrocky.hfmodels.samples.promises.test/androidx.test.runner.AndroidJUnitRunner
 *   adb logcat -d -s hfmodels-check | grep RESULT
 *
 * `connectedDebugAndroidTest` runs it as well, but uninstalls the app afterwards, and the imported model with it.
 * The last line is `RESULT ok=true ...` when every step passed; every line names the variant and the profile.
 * Arguments: backend=npu|gpu|cpu (npu: variant s128_npu_wfp16 on the NPU, which needs Qualcomm's runtime in the app
 * and the variant's graph pushed, README.md "NPU"; gpu, cpu: variant s128_wfp16 on that backend; default: the app's
 * own choice, DecisionModels.choose), network=offline|any (default any).
 */
@RunWith(AndroidJUnit4::class)
class PromisesDeviceCheck {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val backend: String? = args.getString("backend")
    private val network = if (args.getString("network") == "offline") NetworkPolicy.Offline else NetworkPolicy.Any

    @Test fun loadSortRelease(): Unit = runBlocking {
        val failures = ArrayList<String>()
        val d = DecisionModels(ctx)
        val choice = DecisionModels.choose(d.npuRuntime, backend)
        // The variant and profile every line names: the requested ones until the load says which it opened.
        var variant = choice.variant
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
            d.load(choice, network) { events += it }.also { m ->
                variant = m.info.variantId
                profile = m.info.profileId
                val compileMs = m.info.notes.firstNotNullOfOrNull { Regex("compile_ms=(\\d+)").find(it)?.groupValues?.get(1) } ?: "?"
                step("load", true, "backend=${backend ?: "default"} policy=${choice.policy} npu_runtime=${d.npuRuntime} npu_failure=${d.npuFailure?.let { q(it) }} " +
                    "compile_ms=$compileMs load_ms=${SystemClock.elapsedRealtime() - t0} warmup_ms=${d.warmupMs} " +
                    "downloaded=${events.any { it is LoadEvent.DownloadStarted }} network=$network fallback=${m.info.fallbackHistory} commit=${m.info.commit.take(8)}")
            }
        } catch (t: Throwable) {
            step("load", false, "backend=${backend ?: "default"} policy=${choice.policy} npu_runtime=${d.npuRuntime} error=${what(t)} load_ms=${SystemClock.elapsedRealtime() - t0}")
            Log.i(TAG, "RESULT ok=false variant=$variant profile=$profile failed=$failures device=${Build.MODEL} build=${Build.DISPLAY}")
            d.models.closeAndJoin()
            assertTrue("load failed: ${what(t)}", false)
            return@runBlocking
        }
        // Any error is recorded as a failure of the step it happened in, so a RESULT ok=true line never follows it.
        var current = "sort"
        try {
            // The Sample conversation, cut and asked exactly as the screen does it.
            val sentences = Sentences.split(SampleChat.TEXT)
            val t1 = SystemClock.elapsedRealtimeNanos()
            val verdicts = sentences.map { Promises.judge(model, it) }
            val totalMs = (SystemClock.elapsedRealtimeNanos() - t1) / 1e6
            for ((i, v) in verdicts.withIndex()) {
                Log.i(TAG, "i=$i answer=${v.key} expected=${SampleChat.labelOf(v.sentence.text)} p=${v.probability} ms=${v.ms} question_ms=${v.questionMs} tokens=${v.tokens} " +
                    "probabilities=${v.probabilities} sender=${v.sentence.sender} text=${q(v.sentence.text)}")
            }
            fun count(key: String) = verdicts.count { it.key == key }
            val agree = verdicts.count { it.key == SampleChat.labelOf(it.sentence.text) }
            val ms = verdicts.filter { it.decided }.map { it.ms }
            val questionMs = verdicts.filter { it.decided }.map { it.questionMs }
            val disagree = verdicts.filter { it.key != SampleChat.labelOf(it.sentence.text) }.map { "${q(it.sentence.text)}=${it.key}" }
            step("sort", sentences.size == SampleChat.LINES.size && agree == sentences.size,
                "n=${sentences.size} promise=${count("promise")} request=${count("request")} plan=${count("plan")} nothing=${count("nothing")} too_long=${count(Promises.TOO_LONG)} " +
                    "expected_agree=$agree/${sentences.size} ms_median=${Promises.median(ms)} ms_p90=${Promises.p90(ms)} ms_min=${ms.minOrNull()} ms_max=${ms.maxOrNull()} " +
                    "question_ms_median=${Promises.median(questionMs)} question_ms_p90=${Promises.p90(questionMs)} total_ms=$totalMs disagree=$disagree")

            // A sentence that does not fit the window with the question: shown as "too long", never an error.
            current = "too_long"
            val long = Promises.judge(model, Sentence("Them", LONG))
            step("too_long", long.key == Promises.TOO_LONG, "answer=${long.key} tokens=${long.tokens} words=${LONG.split(' ').size}")
        } catch (t: Throwable) {
            step(current, false, "error=${what(t)}")
        } finally {
            // Release: closeAndJoin must return (10 s cap inside the SDK).
            val t2 = SystemClock.elapsedRealtime()
            val released = runCatching { d.release(); d.models.closeAndJoin() }
            val closeMs = SystemClock.elapsedRealtime() - t2
            step("release", released.isSuccess && closeMs < 10_000, "close_ms=$closeMs" + (released.exceptionOrNull()?.let { " error=${what(it)}" } ?: ""))
            Log.i(TAG, "RESULT ok=${failures.isEmpty()} model=${DecisionModels.REPO}@${model.info.commit.take(8)} variant=$variant profile=$profile failed=$failures sdk=${model.info.sdkVersion} runtime=${model.info.runtime} ${model.info.runtimeVersion} " +
                "device=${Build.MODEL} soc=${Build.SOC_MODEL} build=${Build.DISPLAY} thermal=$thermalBefore->${power.currentThermalStatus}")
        }
        assertTrue("failed steps: $failures (adb logcat -d -s hfmodels-check)", failures.isEmpty())
    }

    private fun q(s: String) = "\"" + s.take(160).replace("\n", " ").replace("\"", "'") + "\""

    /** An error as one RESULT field: the SDK's code and reason, or the exception's class and message. */
    private fun what(t: Throwable) = if (t is ModelException) "${t.code} reason=${q(t.reason)}" else "${t.javaClass.simpleName} reason=${q(t.message ?: "")}"

    private companion object {
        const val TAG = "hfmodels-check"
        /** 95 words: with the question, 163 tokens (the published tokenizer), well past the 128-token window. */
        const val LONG = "So the plan for the whole weekend, if nothing changes and the weather holds up the way the forecast says it will, " +
            "is that we leave early on the first morning, drive out past the lake and the old farms, stop for breakfast at that small place " +
            "by the bridge, then walk the long trail up to the ridge, have lunch at the top, come back down before it gets dark, and maybe, " +
            "if everyone still has the energy for it, cook dinner together at the cabin and stay up late talking by the fire."
    }
}
