package io.github.johnrocky.hfmodels.probe.scoring

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.SessionConfig
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.exp
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "One prefill, N decisions" on a language-model bundle, through the Kotlin API of the
 * kotlin-text-scoring branch: prefill a rendered prompt once, save a checkpoint, score each option
 * letter as a continuation, rewind between letters. Compared with a published oracle (the fp32
 * last-position logits over the letter slots for the same prompts) and timed against the direct
 * arm (a fresh prefill per letter).
 *
 * Arguments: model (bundle path on the device), backend (gpu | cpu), rows (default all 144),
 * direct (rows to time the direct arm on, default 12).
 * One RESULT line per step under tag `hfmodels-scoring`.
 */
@RunWith(AndroidJUnit4::class)
class TextScoringDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val modelPath = args.getString("model") ?: "/data/local/tmp/hfmodels/Qwen3-0.6B.litertlm"
    private val backend = args.getString("backend") ?: "gpu"
    private val rowLimit = args.getString("rows")?.toInt() ?: Int.MAX_VALUE
    private val directRows = args.getString("direct")?.toInt() ?: 12
    private val failures = ArrayList<String>()

    private fun result(step: String, ok: Boolean, detail: String) {
        Log.i(TAG, "RESULT step=$step ok=$ok backend=$backend model=${File(modelPath).name} $detail")
        if (!ok) failures += "$step: $detail"
    }

    private fun softmax(negScores: DoubleArray): DoubleArray {
        val m = negScores.max(); val e = negScores.map { exp(it - m) }; val s = e.sum()
        return e.map { it / s }.toDoubleArray()
    }

    @Test fun onePrefillManyDecisions() {
        assertTrue("push the bundle to $modelPath first", File(modelPath).isFile)
        // The fixture is committed gzipped; the Android build packages it decompressed under the plain name.
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val fixtureText = runCatching { assets.open("authored144_qwen3_0_6b_oracle.json").bufferedReader().use { it.readText() } }
            .getOrElse { GZIPInputStream(assets.open("authored144_qwen3_0_6b_oracle.json.gz")).bufferedReader().use { it.readText() } }
        val fixture = JSONObject(fixtureText)
        val letters = fixture.getString("letters")
        val rows = fixture.getJSONArray("rows")
        Log.i(TAG, "device=${Build.MODEL} build=${Build.DISPLAY} android=${Build.VERSION.RELEASE} backend=$backend model=$modelPath rows=${rows.length()}")
        // The runtime's loader hides the linker's reason; load once here so the real message reaches the log.
        try { System.loadLibrary("litertlm_jni") } catch (e: UnsatisfiedLinkError) { Log.e(TAG, "loadLibrary: ${e.message}") }
        val engine = Engine(EngineConfig(modelPath = modelPath, backend = if (backend == "cpu") Backend.CPU() else Backend.GPU(), cacheDir = File(ctx.cacheDir, "litertlm").apply { mkdirs() }.path))
        val t0 = SystemClock.elapsedRealtime()
        try { engine.initialize() } catch (t: Throwable) { result("init", false, "${t.javaClass.simpleName}: ${t.message}"); assertTrue(failures.joinToString(), failures.isEmpty()) }
        result("init", true, "init_ms=${SystemClock.elapsedRealtime() - t0}")
        try {
            var checked = 0; var argmaxAgree = 0; var maxPErr = 0.0; var sumPErr = 0.0; var stepMismatch = 0; var rewindDrift = 0.0; var correct = 0
            val prefillMs = ArrayList<Double>(); val scoreMs = ArrayList<Double>(); val sharedMs = ArrayList<Double>(); val directMs = ArrayList<Double>()
            val perFamily = HashMap<String, IntArray>()
            for (i in 0 until rows.length()) {
                if (checked >= rowLimit) break
                val r = rows.getJSONObject(i)
                val prompt = r.getString("prompt")
                val n = r.getJSONArray("option_ids").length()
                val oracle = r.getJSONArray("oracle_probabilities").let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
                // shared arm: one prefill, a checkpoint, N scorings with a rewind between them
                val session = engine.createSession(SessionConfig(applyPromptTemplate = false))
                val ts = System.nanoTime()
                session.runPrefill(listOf(InputData.Text(prompt)))
                val tp = System.nanoTime()
                val step = session.currentStep
                if (step != r.getInt("input_tokens")) stepMismatch++
                session.saveCheckpoint("q")
                val neg = DoubleArray(n)
                for (k in 0 until n) {
                    val tk = System.nanoTime()
                    // Measured: the runtime returns the log probability of the target (negative; higher = more likely),
                    // although engine.h documents the sum of the negative log probabilities. Used as a log probability here.
                    neg[k] = session.runTextScoring(listOf(letters[k].toString()))[0].score.toDouble()
                    scoreMs += (System.nanoTime() - tk) / 1e6
                    session.rewindToCheckpoint("q")
                }
                val te = System.nanoTime()
                // the same letter again after the rewinds: a drift means the rewind did not restore the position
                val again = session.runTextScoring(listOf(letters[0].toString()))[0].score.toDouble()
                rewindDrift = maxOf(rewindDrift, abs(again - neg[0]))
                session.close()
                if (checked < 6) Log.i(TAG, "row ${r.getString("id").take(8)} step=$step expected=${r.getInt("input_tokens")} logp_device=${neg.joinToString(",") { "%.3f".format(it) }} logp_oracle=${oracle.joinToString(",") { "%.3f".format(Math.log(maxOf(it, 1e-12))) }} A_again=${"%.3f".format(again)}")
                prefillMs += (tp - ts) / 1e6; sharedMs += (te - ts) / 1e6
                val p = softmax(neg)
                val gotArg = p.indices.maxByOrNull { p[it] }!!; val refArg = oracle.indices.maxByOrNull { oracle[it] }!!
                if (gotArg == refArg) argmaxAgree++
                val err = p.indices.maxOf { abs(p[it] - oracle[it]) }; maxPErr = maxOf(maxPErr, err); sumPErr += err
                val fam = perFamily.getOrPut(r.getString("family")) { IntArray(2) }; fam[1]++
                if (gotArg == r.getInt("label")) { correct++; fam[0]++ }
                checked++
                // direct arm on the first rows: a fresh prefill per letter
                if (checked <= directRows) {
                    val td = System.nanoTime()
                    for (k in 0 until n) {
                        val s2 = engine.createSession(SessionConfig(applyPromptTemplate = false))
                        s2.runPrefill(listOf(InputData.Text(prompt)))
                        s2.runTextScoring(listOf(letters[k].toString()))
                        s2.close()
                    }
                    directMs += (System.nanoTime() - td) / 1e6
                }
                if (checked % 12 == 0) Log.i(TAG, "progress rows=$checked argmax_agree=$argmaxAgree max_p_err=${"%.4f".format(maxPErr)}")
            }
            fun med(l: List<Double>) = if (l.isEmpty()) 0.0 else l.sorted()[l.size / 2]
            val famStr = perFamily.entries.joinToString(" ") { (k, a) -> "$k=${a[0]}/${a[1]}" }
            result("oracle", checked > 0 && argmaxAgree == checked, "rows=$checked argmax_agree=$argmaxAgree/$checked max_p_abs_err=${"%.4f".format(maxPErr)} mean_p_abs_err=${"%.4f".format(sumPErr / maxOf(1, checked))} accuracy=${"%.3f".format(correct.toDouble() / maxOf(1, checked))} per_family=$famStr prefill_step_mismatch=$stepMismatch rewind_drift_max=${"%.5f".format(rewindDrift)}")
            result("timing", true, "prefill_ms_median=${"%.1f".format(med(prefillMs))} score_ms_median=${"%.1f".format(med(scoreMs))} shared_3_options_ms_median=${"%.1f".format(med(sharedMs))} direct_3_prefills_ms_median=${"%.1f".format(med(directMs))} (direct rows=${directMs.size}) ratio_direct_over_shared=${"%.2f".format(med(directMs) / maxOf(1e-9, med(sharedMs)))}")
        } catch (t: Throwable) {
            Log.e(TAG, "failed", t); failures += "exception: ${t.javaClass.simpleName}: ${t.message}"
        } finally {
            val tc = SystemClock.elapsedRealtime()
            runCatching { engine.close() }
            result("release", true, "close_ms=${SystemClock.elapsedRealtime() - tc}")
        }
        Log.i(TAG, "RESULT ok=${failures.isEmpty()} backend=$backend device=${Build.MODEL} build=${Build.DISPLAY} failures=${failures.joinToString(" | ")}")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    companion object { const val TAG = "hfmodels-scoring" }
}
