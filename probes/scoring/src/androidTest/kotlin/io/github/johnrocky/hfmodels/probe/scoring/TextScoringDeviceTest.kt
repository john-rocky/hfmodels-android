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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "One prefill, N decisions" on a language-model bundle, through the Kotlin API of the
 * kotlin-text-scoring branch: prefill a rendered prompt once, save a checkpoint, score each option
 * letter as a continuation, rewind between letters. Compared with a published oracle (the fp32
 * last-position logits over the letter slots for the same prompts, and, when the fixture carries
 * them, the same bundle's own graph read on a Mac) and timed against the direct arm (a fresh
 * prefill per letter).
 *
 * Arguments: model (bundle path on the device), backend (gpu | cpu), rows (default all 144),
 * direct (rows to time the direct arm on, default 12), fixture (asset name, default
 * authored144_qwen3_0_6b_oracle.json). A fixture may carry `strip_prefix`: a literal removed from
 * the start of every prompt because the runtime prepends the bundle's own start token to a raw
 * session (MiniCPM5 declares `<s>`), so the session sees the frozen ids exactly once.
 *
 * One RESULT line per step under tag `hfmodels-scoring`. The probe's `ok` is about the binding
 * (the run completed, the score of a letter did not drift after a rewind); agreement with the
 * oracle is a measurement of the quantized bundle, reported, not asserted. Every RESULT line is
 * also written to the app's files dir (`files/scoring/<fixture>-<backend>.txt`, the per-row device
 * probabilities next to it as `.rows.jsonl`), readable with `adb shell run-as <applicationId> cat
 * files/scoring/...`, so a rotated logcat loses nothing.
 */
@RunWith(AndroidJUnit4::class)
class TextScoringDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val modelPath = args.getString("model") ?: "/data/local/tmp/hfmodels/Qwen3-0.6B.litertlm"
    private val backend = args.getString("backend") ?: "gpu"
    private val rowLimit = args.getString("rows")?.toInt() ?: Int.MAX_VALUE
    private val directRows = args.getString("direct")?.toInt() ?: 12
    private val fixtureName = args.getString("fixture") ?: "authored144_qwen3_0_6b_oracle.json"
    private val failures = ArrayList<String>()
    private val lines = ArrayList<String>()
    private val outDir = File(ctx.filesDir, "scoring").apply { mkdirs() }
    private val outTag = "${fixtureName.removeSuffix(".json")}-$backend"
    private val linesFile = File(outDir, "$outTag.txt")
    private val rowsFile = File(outDir, "$outTag.rows.jsonl")

    private fun result(step: String, ok: Boolean, detail: String) {
        val line = "RESULT step=$step ok=$ok backend=$backend model=${File(modelPath).name} fixture=$fixtureName $detail"
        Log.i(TAG, line)
        lines += line
        linesFile.appendText(line + "\n")
        if (!ok) failures += "$step: $detail"
    }

    private fun softmax(negScores: DoubleArray): DoubleArray {
        val m = negScores.max(); val e = negScores.map { exp(it - m) }; val s = e.sum()
        return e.map { it / s }.toDoubleArray()
    }

    private fun med(l: List<Double>) = if (l.isEmpty()) 0.0 else l.sorted()[l.size / 2]
    private fun p90(l: List<Double>) = if (l.isEmpty()) 0.0 else l.sorted()[(l.size * 9) / 10]
    private fun argmax(v: DoubleArray) = v.indices.maxByOrNull { v[it] }!!
    private fun doubles(a: JSONArray) = DoubleArray(a.length()) { a.getDouble(it) }

    @Test fun onePrefillManyDecisions() {
        linesFile.writeText(""); rowsFile.writeText("")
        assertTrue("push the bundle to $modelPath first", File(modelPath).isFile)
        // The fixture is committed gzipped; the Android build packages it decompressed under the plain name.
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val fixtureText = runCatching { assets.open(fixtureName).bufferedReader().use { it.readText() } }
            .getOrElse { GZIPInputStream(assets.open("$fixtureName.gz")).bufferedReader().use { it.readText() } }
        val fixture = JSONObject(fixtureText)
        val letters = fixture.getString("letters")
        val stripPrefix = fixture.optString("strip_prefix", "")
        val rows = fixture.getJSONArray("rows")
        Log.i(TAG, "device=${Build.MODEL} build=${Build.DISPLAY} android=${Build.VERSION.RELEASE} backend=$backend model=$modelPath fixture=$fixtureName rows=${rows.length()} strip_prefix=${stripPrefix.ifEmpty { "(none)" }}")
        // The runtime's loader hides the linker's reason; load once here so the real message reaches the log.
        try { System.loadLibrary("litertlm_jni") } catch (e: UnsatisfiedLinkError) { Log.e(TAG, "loadLibrary: ${e.message}") }
        val engine = Engine(EngineConfig(modelPath = modelPath, backend = if (backend == "cpu") Backend.CPU() else Backend.GPU(), cacheDir = File(ctx.cacheDir, "litertlm").apply { mkdirs() }.path))
        val t0 = SystemClock.elapsedRealtime()
        try { engine.initialize() } catch (t: Throwable) { result("init", false, "${t.javaClass.simpleName}: ${t.message}"); assertTrue(lines.joinToString("\n"), failures.isEmpty()) }
        result("init", true, "init_ms=${SystemClock.elapsedRealtime() - t0}")
        try {
            var checked = 0; var argmaxAgree = 0; var maxPErr = 0.0; var sumPErr = 0.0; var stepMismatch = 0; var rewindDrift = 0.0; var correct = 0
            var graphRows = 0; var graphAgree = 0; var graphMaxPErr = 0.0; var graphSumPErr = 0.0
            val prefillMs = ArrayList<Double>(); val scoreMs = ArrayList<Double>(); val sharedMs = ArrayList<Double>(); val directMs = ArrayList<Double>()
            val perFamily = HashMap<String, IntArray>()
            var firstStep = ""
            for (i in 0 until rows.length()) {
                if (checked >= rowLimit) break
                val r = rows.getJSONObject(i)
                val rawPrompt = r.getString("prompt")
                val prompt = if (stripPrefix.isNotEmpty() && rawPrompt.startsWith(stripPrefix)) rawPrompt.substring(stripPrefix.length) else rawPrompt
                val n = r.getJSONArray("option_ids").length()
                val oracle = doubles(r.getJSONArray("oracle_probabilities"))
                val graph = r.optJSONArray("graph_probabilities")?.let { doubles(it) }
                // shared arm: one prefill, a checkpoint, N scorings with a rewind between them
                val session = engine.createSession(SessionConfig(applyPromptTemplate = false))
                val ts = System.nanoTime()
                session.runPrefill(listOf(InputData.Text(prompt)))
                val tp = System.nanoTime()
                val step = session.getCurrentStep()
                val expected = r.getInt("input_tokens")
                if (step != expected) stepMismatch++
                if (firstStep.isEmpty()) firstStep = "$step/$expected"
                session.saveCheckpoint("q")
                val neg = DoubleArray(n)
                val targetTokens = IntArray(n)
                for (k in 0 until n) {
                    val tk = System.nanoTime()
                    // The score is the target's log probability after the prefill (negative; higher is more likely).
                    val resp = session.runTextScoring(listOf(letters[k].toString()))
                    neg[k] = resp.scores[0].toDouble()
                    targetTokens[k] = resp.tokenLengths?.getOrNull(0) ?: -1
                    scoreMs += (System.nanoTime() - tk) / 1e6
                    session.rewindToCheckpoint("q")
                }
                val te = System.nanoTime()
                // the same letter again after the rewinds: a drift means the rewind did not restore the position
                val again = session.runTextScoring(listOf(letters[0].toString())).scores[0].toDouble()
                rewindDrift = maxOf(rewindDrift, abs(again - neg[0]))
                session.close()
                if (checked < 6) {
                    val rowLine = "row ${r.getString("id").take(8)} step=$step expected=$expected logp_device=${neg.joinToString(",") { "%.3f".format(it) }} logp_oracle=${oracle.joinToString(",") { "%.3f".format(Math.log(maxOf(it, 1e-12))) }} A_again=${"%.3f".format(again)} target_tokens=${targetTokens.joinToString(",")}"
                    Log.i(TAG, rowLine); linesFile.appendText(rowLine + "\n")
                }
                prefillMs += (tp - ts) / 1e6; sharedMs += (te - ts) / 1e6
                val p = softmax(neg)
                val gotArg = argmax(p); val refArg = argmax(oracle)
                if (gotArg == refArg) argmaxAgree++
                val err = p.indices.maxOf { abs(p[it] - oracle[it]) }; maxPErr = maxOf(maxPErr, err); sumPErr += err
                if (graph != null) {
                    graphRows++
                    if (gotArg == argmax(graph)) graphAgree++
                    val ge = p.indices.maxOf { abs(p[it] - graph[it]) }; graphMaxPErr = maxOf(graphMaxPErr, ge); graphSumPErr += ge
                }
                val fam = perFamily.getOrPut(r.getString("family")) { IntArray(2) }; fam[1]++
                if (gotArg == r.getInt("label")) { correct++; fam[0]++ }
                checked++
                rowsFile.appendText(JSONObject().put("id", r.getString("id")).put("option_ids", r.getJSONArray("option_ids"))
                    .put("probabilities", JSONArray(p.toList())).put("logp", JSONArray(neg.toList())).put("step", step).put("expected_step", expected)
                    .put("prefill_ms", (tp - ts) / 1e6).put("decision_ms", (te - ts) / 1e6).put("rewind_again_logp", again).toString() + "\n")
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
            // timing first: the numbers are the point of the shared arm and must survive an agreement below 100 %
            result("timing", checked > 0, "rows=$checked prefill_ms_median=${"%.1f".format(med(prefillMs))} prefill_ms_p90=${"%.1f".format(p90(prefillMs))} score_ms_median=${"%.1f".format(med(scoreMs))} score_ms_p90=${"%.1f".format(p90(scoreMs))} shared_${letters.length.coerceAtMost(3)}_options_ms_median=${"%.1f".format(med(sharedMs))} shared_ms_p90=${"%.1f".format(p90(sharedMs))} shared_ms_min=${"%.1f".format(sharedMs.minOrNull() ?: 0.0)} direct_3_prefills_ms_median=${"%.1f".format(med(directMs))} (direct rows=${directMs.size}) ratio_direct_over_shared=${"%.2f".format(med(directMs) / maxOf(1e-9, med(sharedMs)))}")
            val famStr = perFamily.entries.joinToString(" ") { (k, a) -> "$k=${a[0]}/${a[1]}" }
            val graphStr = if (graphRows > 0) " graph_argmax_agree=$graphAgree/$graphRows graph_max_p_abs_err=${"%.4f".format(graphMaxPErr)} graph_mean_p_abs_err=${"%.4f".format(graphSumPErr / graphRows)}" else ""
            result("oracle", checked > 0 && rewindDrift <= 1e-3, "rows=$checked argmax_agree=$argmaxAgree/$checked max_p_abs_err=${"%.4f".format(maxPErr)} mean_p_abs_err=${"%.4f".format(sumPErr / maxOf(1, checked))} accuracy=${"%.3f".format(correct.toDouble() / maxOf(1, checked))} per_family=$famStr$graphStr prefill_step_mismatch=$stepMismatch first_step/expected=$firstStep rewind_drift_max=${"%.5f".format(rewindDrift)}")
        } catch (t: Throwable) {
            Log.e(TAG, "failed", t); failures += "exception: ${t.javaClass.simpleName}: ${t.message}"
            linesFile.appendText("EXCEPTION ${t.javaClass.simpleName}: ${t.message}\n")
        } finally {
            val tc = SystemClock.elapsedRealtime()
            runCatching { engine.close() }
            result("release", true, "close_ms=${SystemClock.elapsedRealtime() - tc}")
        }
        val summary = "RESULT ok=${failures.isEmpty()} backend=$backend device=${Build.MODEL} build=${Build.DISPLAY} failures=${failures.joinToString(" | ")}"
        Log.i(TAG, summary); linesFile.appendText(summary + "\n")
        assertTrue((lines + summary).joinToString("\n"), failures.isEmpty())
    }

    companion object { const val TAG = "hfmodels-scoring" }
}
