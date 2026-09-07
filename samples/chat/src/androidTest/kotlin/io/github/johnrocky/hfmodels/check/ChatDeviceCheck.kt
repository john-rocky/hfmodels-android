package io.github.johnrocky.hfmodels.check

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import io.github.johnrocky.hfmodels.BackendKind
import io.github.johnrocky.hfmodels.BackendPolicy
import io.github.johnrocky.hfmodels.HfModels
import io.github.johnrocky.hfmodels.LoadEvent
import io.github.johnrocky.hfmodels.LoadOptions
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.ModelRef
import io.github.johnrocky.hfmodels.Tasks
import io.github.johnrocky.hfmodels.litertlm.SessionState
import io.github.johnrocky.hfmodels.litertlm.text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drop-in device check for any app that uses hfmodels. It proves, on the connected phone and in the
 * app's own process, what the integration promises: the id loads (download, or the copy you pushed),
 * one fixed prompt streams a correct answer, Stop reaches the native side, and release returns.
 *
 * Install into an app:
 *   1. copy this file to app/src/androidTest/kotlin/ChatDeviceCheck.kt (keep the package line);
 *   2. app/build.gradle.kts:
 *        android { defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" } }
 *        dependencies { androidTestImplementation("androidx.test:runner:1.7.0"); androidTestImplementation("androidx.test.ext:junit:1.3.0") }
 *   3. run it against the model id the app uses (the default is the small catalogued model):
 *        export ANDROID_SERIAL=<serial>
 *        ./gradlew :app:connectedDebugAndroidTest \
 *          -Pandroid.testInstrumentationRunnerArguments.class=io.github.johnrocky.hfmodels.check.ChatDeviceCheck \
 *          -Pandroid.testInstrumentationRunnerArguments.model=litert-community/gemma-4-E2B-it-litert-lm
 *        adb logcat -d -s hfmodels-check | grep RESULT
 *   The last line is `RESULT ok=true ...` when every step passed; the gradle task fails otherwise.
 *   Other arguments: backend=cpu|gpu (default: the descriptor's default profile), prompt, expect.
 * The model file lands in the app's private files dir, where the app's own load will find it.
 */
@RunWith(AndroidJUnit4::class)
class ChatDeviceCheck {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val modelId = args.getString("model") ?: "litert-community/LFM2.5-1.2B-Instruct"
    private val prompt = args.getString("prompt") ?: "What is 17 + 25? Answer briefly."
    private val expect = args.getString("expect") ?: "42"
    private val policy = when (args.getString("backend")) { "cpu" -> BackendPolicy.Require(BackendKind.CPU); "gpu" -> BackendPolicy.Require(BackendKind.GPU); else -> BackendPolicy.Auto }

    @Test fun loadAnswerStopRelease(): Unit = runBlocking {
        val failures = ArrayList<String>()
        fun step(name: String, ok: Boolean, values: String) {
            if (!ok) failures += name
            Log.i(TAG, "RESULT step=$name ok=$ok model=$modelId $values")
        }
        val models = HfModels(ctx)
        val t0 = SystemClock.elapsedRealtime()
        val model = try {
            var last: LoadEvent? = null
            models.fromPretrained(ModelRef(modelId), Tasks.Chat, LoadOptions(backendPolicy = policy)) { last = it }.also {
                step("load", true, "profile=${it.info.profileId} initialized=${it.info.components.map { c -> "${c.key}=${c.value.initialized}" }} thinking=${it.thinking.source} load_ms=${SystemClock.elapsedRealtime() - t0} last_event=${last?.javaClass?.simpleName}")
            }
        } catch (e: ModelException) {
            step("load", false, "error=${e.code} reason=${q(e.reason)} load_ms=${SystemClock.elapsedRealtime() - t0}")
            Log.i(TAG, "RESULT ok=false model=$modelId failed=$failures device=${Build.MODEL} build=${Build.DISPLAY}")
            assertTrue("load failed: ${e.code}: ${e.reason}", false)
            return@runBlocking
        }
        try {
            // One turn: the answer must contain `expect`; a thinking model's reasoning must stay out of the text.
            val s = model.createConversation(ConversationConfig(systemInstruction = Contents.of("You are a helpful assistant.")))
            val sb = StringBuilder(); val thought = StringBuilder(); var chunks = 0; var first = -1L
            val t1 = SystemClock.elapsedRealtime()
            try {
                withTimeout(600_000) { s.stream(Contents.of(prompt)).collect { m -> if (chunks == 0) first = SystemClock.elapsedRealtime() - t1; sb.append(m.text); m.channels.values.firstOrNull()?.let { thought.append(it) }; chunks++ } }
                val markers = model.thinking.channels.flatMap { listOf(it.start, it.end) }.map { it.trim() }.filter { it.isNotEmpty() }
                val ok = sb.contains(expect) && chunks > 1 && markers.none { sb.contains(it) } && (!model.thinking.reasonsByDefault || thought.isNotBlank())
                step("answer", ok, "expect=${q(expect)} chunks=$chunks first_chunk_ms=$first total_ms=${SystemClock.elapsedRealtime() - t1} state=${s.state} thought_chars=${thought.length} reply=${q(sb.toString())}")
            } catch (e: ModelException) {
                step("answer", false, "error=${e.code} reason=${q(e.reason)}")
            }
            s.closeAndJoin()

            // Stop: cancel the collecting coroutine mid-reply; chunks must stop and the session becomes INVALID.
            val s2 = model.createConversation(ConversationConfig(systemInstruction = Contents.of("You are a helpful assistant.")))
            var n = 0
            val job = launch(Dispatchers.Default) { runCatching { s2.stream(Contents.of("Count from 1 to 400, separated by commas, with no other text.")).collect { n++ } } }
            val stopped = try {
                withTimeout(120_000) { while (n < 5) delay(20) }
                job.cancelAndJoin()
                val at = n; delay(500)
                n == at && s2.state == SessionState.INVALID
            } catch (e: Throwable) { job.cancelAndJoin(); false }
            step("stop", stopped, "chunks_at_stop=$n state=${s2.state}")
            s2.closeAndJoin()
        } finally {
            // Release: closeAndJoin must return (10 s cap inside the SDK).
            val t2 = SystemClock.elapsedRealtime()
            model.closeAndJoin(); models.closeAndJoin()
            val ms = SystemClock.elapsedRealtime() - t2
            step("release", ms < 10_000, "close_ms=$ms")
            Log.i(TAG, "RESULT ok=${failures.isEmpty()} model=$modelId failed=$failures sdk=${model.info.sdkVersion} runtime=${model.info.runtime} ${model.info.runtimeVersion} device=${Build.MODEL} build=${Build.DISPLAY}")
        }
        assertTrue("failed steps: $failures (adb logcat -d -s hfmodels-check)", failures.isEmpty())
    }

    private fun q(s: String) = "\"" + s.take(160).replace("\n", " ").replace("\"", "'") + "\""

    private companion object { const val TAG = "hfmodels-check" }
}
