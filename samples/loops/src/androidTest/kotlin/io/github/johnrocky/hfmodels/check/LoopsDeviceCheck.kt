package io.github.johnrocky.hfmodels.check

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.HfModels
import io.github.johnrocky.hfmodels.LoadOptions
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.NetworkPolicy
import io.github.johnrocky.hfmodels.Tasks
import io.github.johnrocky.hfmodels.samples.loops.Answer
import io.github.johnrocky.hfmodels.samples.loops.Loop
import io.github.johnrocky.hfmodels.samples.loops.Loops
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app's own code path on the connected phone ([Loops], the class the screen drives): each file loads and answers,
 * one engine at a time; a new conversation answers as the first one did; Think puts the reasoning outside the text;
 * Stop ends an answer and the model answers again; release returns. Then the failure paths the screen shows as
 * sentences, walked with the repo's int8 file standing in for a file that is not on the phone (the check never pushes
 * it): downloads off, and a download stopped part way, then continued and stopped again, then removed.
 *
 *     adb shell am instrument -w -e class io.github.johnrocky.hfmodels.check.LoopsDeviceCheck \
 *         io.github.johnrocky.hfmodels.samples.loops.test/androidx.test.runner.AndroidJUnitRunner
 *     adb logcat -d -s hfmodels-check | grep RESULT
 *
 * Push both files first (README) or the first loads download them. `-e download false` skips the download steps.
 */
@RunWith(AndroidJUnit4::class)
class LoopsDeviceCheck {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val withDownload = args.getString("download") != "false"

    private companion object {
        const val TAG = "hfmodels-check"
        const val PROMPT = "What is 17 + 25? Answer briefly."
        const val COUNT = "Count from 1 to 400, separated by commas, with no other text."
        val MARKERS = listOf("<think>", "</think>")
    }

    @Test fun bothFilesAnswerStopAndFailWithSentences(): Unit = runBlocking {
        val failures = ArrayList<String>()
        fun step(name: String, ok: Boolean, values: String) {
            if (!ok) failures += name
            Log.i(TAG, "RESULT step=$name ok=$ok $values")
        }
        fun row(a: Answer) = "variant=${a.loop.variant} backend=${a.backend} chunks=${a.timing.chunks} first_token_ms=${a.timing.firstTokenMs?.let { Math.round(it) }} " +
            "tok_s=${a.timing.tokensPerSecond?.let { Math.round(it * 100) / 100.0 }} total_ms=${Math.round(a.timing.totalMs)} load_ms=${a.loadMs} thought_chars=${a.thought.length} reply=${q(a.text)}"
        fun clean(a: Answer) = MARKERS.none { a.text.contains(it) }

        val models = HfModels(ctx)
        val loops = Loops(models, ctx.getExternalFilesDir(null))
        var sdk = ""; var runtime = ""
        try {
            for (l in Loop.values()) step("files-${l.passes}", true, "variant=${l.variant} state=${loops.fileState(l).javaClass.simpleName}")

            // Each file, one engine at a time, thinking off as the screen starts.
            val a1 = withTimeout(900_000) { loops.answer(Loop.ONE, PROMPT, think = false) }
            val i1 = loops.model!!.info
            sdk = i1.sdkVersion; runtime = "${i1.runtime} ${i1.runtimeVersion}"
            step("load-1", i1.variantId == "int4_1loop" && i1.components["language"]?.initialized?.name == "GPU",
                "variant=${i1.variantId} profile=${i1.profileId} initialized=${i1.components.map { "${it.key}=${it.value.initialized}" }} commit=${i1.commit.take(8)} load_ms=${a1.loadMs}")
            step("answer-1", a1.text.contains("42") && a1.timing.chunks > 1 && clean(a1), row(a1))
            val t1 = withTimeout(600_000) { loops.answer(Loop.ONE, PROMPT, think = true) }
            step("think-1", t1.thought.isNotBlank() && t1.text.contains("42") && clean(t1), row(t1) + " first_text_ms=${t1.firstTextMs?.let { Math.round(it) }}")

            val a2 = withTimeout(900_000) { loops.answer(Loop.TWO, PROMPT, think = false) }
            val i2 = loops.model!!.info
            step("answer-2", i2.variantId == "int4" && a2.loadMs != null && a2.text.contains("42") && a2.timing.chunks > 1 && clean(a2),
                "initialized=${i2.components.map { "${it.key}=${it.value.initialized}" }} " + row(a2))
            // A new conversation on the loaded model: the same greedy answer as the first one.
            val f2 = withTimeout(600_000) { loops.answer(Loop.TWO, PROMPT, think = false) }
            step("fresh-2", f2.text == a2.text && f2.loadMs == null, row(f2))

            // Stop mid-answer: the turn ends, the partial text stays, and the next question gets an answer.
            val chunks = AtomicInteger()
            val counting = async(Dispatchers.Default) { runCatching { loops.answer(Loop.TWO, COUNT, think = false) { _, _ -> chunks.incrementAndGet() } } }
            val reached = withTimeoutOrNull(120_000) { while (chunks.get() < 5 && !counting.isCompleted) delay(20); chunks.get() >= 5 } ?: false
            val endedFirst = counting.isCompleted
            counting.cancel()
            val cancelled = runCatching { counting.await() }.exceptionOrNull() is CancellationException
            if (!reached || endedFirst || !cancelled) {
                step("stop", false, "reached=$reached ended_first=$endedFirst cancelled=$cancelled")
            } else {
                val sentence = loops.sentence(Loop.TWO, CancellationException("stopped"))
                val partial = loops.partialText
                val after = withTimeout(600_000) { loops.answer(Loop.TWO, PROMPT, think = false) }
                step("stop", sentence.startsWith("Stopped after") && after.text.contains("42"), "sentence=${q(sentence)} partial=${q(partial.ifEmpty { "-" })} then=${q(after.text)}")
            }

            val r0 = SystemClock.elapsedRealtime()
            loops.release(); loops.release()
            step("release", SystemClock.elapsedRealtime() - r0 < 10_000 && loops.model == null, "close_ms=${SystemClock.elapsedRealtime() - r0}")

            // What the screen refuses before the model.
            step("empty-question", Loops.refusal("   ") != null && Loops.refusal(PROMPT) == null, "sentence=${q(Loops.refusal("   ") ?: "-")}")

            // A file that is not on the phone, with downloads off: the int8 file stands in for one of the two.
            val missing = Loops(models, null, NetworkPolicy.Offline) { "int8" }
            missing.fileState(Loop.ONE)
            val miss = try { missing.answer(Loop.ONE, PROMPT, think = false); null } catch (e: ModelException) { e }
            val missSentence = miss?.let { missing.sentence(Loop.ONE, it) } ?: "-"
            step("missing-file", miss?.code == ErrorCode.OFFLINE_CACHE_MISS && missSentence.contains("not on this phone and downloads are off"), "code=${miss?.code} sentence=${q(missSentence)}")

            if (withDownload) {
                // A download stopped part way keeps its part; the next run continues from it.
                val dl = Loops(models, null, NetworkPolicy.Any) { "int8" }
                val first = stopDownloadAt(dl, 16L shl 20)
                // The SDK stops the cancelled transfer on its own thread: let it close the part before the next run opens it.
                delay(3_000)
                val second = stopDownloadAt(dl, 32L shl 20)
                delay(3_000)
                step("download-stop", first.startsWith("Download stopped at") && first.contains("The part stays on the phone"), "sentence=${q(first)}")
                step("download-continue", (dl.resumedFrom ?: 0) >= 16L shl 20 && second.startsWith("Download stopped at"), "resumed_from=${dl.resumedFrom} sentence=${q(second)}")
                val plan = models.inspect(dl.ref(Loop.ONE), Tasks.Chat, LoadOptions(networkPolicy = NetworkPolicy.Offline))
                val freed = models.evict(plan)
                step("download-removed", freed > 0, "freed_bytes=$freed (the stand-in's part)")
            }
        } catch (e: ModelException) {
            step("unexpected", false, "error=${e.code} reason=${q(e.reason)}")
        } catch (e: Throwable) {
            step("unexpected", false, "error=${e.javaClass.simpleName} reason=${q(e.message ?: "")}")
        } finally {
            val c0 = SystemClock.elapsedRealtime()
            loops.release(); models.closeAndJoin()
            Log.i(TAG, "RESULT ok=${failures.isEmpty()} failed=$failures close_ms=${SystemClock.elapsedRealtime() - c0} sdk=$sdk runtime=$runtime device=${Build.MODEL} build=${Build.DISPLAY}")
        }
        assertTrue("failed steps: $failures (adb logcat -d -s hfmodels-check)", failures.isEmpty())
    }

    /** Starts [loops]' first file loading, cancels it once [bytes] of the download are on the phone, returns the screen's sentence. */
    private suspend fun stopDownloadAt(loops: Loops, bytes: Long): String = coroutineScope {
        val run = async(Dispatchers.Default) { runCatching { loops.answer(Loop.ONE, PROMPT, think = false) } }
        withTimeoutOrNull(300_000) { while ((loops.downloadBytes ?: 0) < bytes && !run.isCompleted) delay(50) }
        val ended = if (run.isCompleted) run.await().exceptionOrNull() else null
        if (ended != null) return@coroutineScope "the load failed first: ${loops.sentence(Loop.ONE, ended)}"
        run.cancel()
        val t = runCatching { run.await() }.exceptionOrNull() ?: return@coroutineScope "the load ended by itself"
        loops.sentence(Loop.ONE, t)
    }

    private fun q(s: String) = "\"" + s.take(160).replace("\n", " ").replace("\"", "'") + "\""
}
