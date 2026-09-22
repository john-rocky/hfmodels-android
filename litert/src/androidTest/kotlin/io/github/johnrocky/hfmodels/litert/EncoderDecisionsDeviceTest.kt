package io.github.johnrocky.hfmodels.litert

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.johnrocky.hfmodels.BackendKind
import io.github.johnrocky.hfmodels.BackendPolicy
import io.github.johnrocky.hfmodels.HfModels
import io.github.johnrocky.hfmodels.LoadEvent
import io.github.johnrocky.hfmodels.LoadOptions
import io.github.johnrocky.hfmodels.ModelRef
import io.github.johnrocky.hfmodels.NetworkPolicy
import io.github.johnrocky.hfmodels.decide.Answer
import io.github.johnrocky.hfmodels.decide.Json
import io.github.johnrocky.hfmodels.decide.Question
import io.github.johnrocky.hfmodels.decide.TypedDecisions
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Typed decisions on a named device: side-loaded laya graphs (the publisher's checkpoint converted
 * to LiteRT; not yet published, so the descriptor here is explicit and every file is imported from
 * /data/local/tmp/hfmodels/laya), then, in one process and one load:
 *   1. load (import, tokenizer, compile) and the profile that came up;
 *   2. parity: the captured rows (the official `Agent.predict` on the same sequences) vs this phone —
 *      marker logits, rounded answers, argmax;
 *   3. timing: warm per-question ms; `decide(state, 4 questions)` vs 4 x `decide(state, 1 question)`;
 *   4. the SemIf authored144 rows vs the official laya answers computed on a Mac (agreement, accuracy);
 *   5. release.
 * One RESULT line per step under tag `hfmodels-decide`. Arguments: variant (en_s512_wfp16 |
 * en_s256_wfp16 | ml_s256_fp32), backend (gpu | cpu | auto), rows (parity rows, default all),
 * authored (authored144 rows, default all), repeats (timing, default 5).
 *
 *   ./gradlew :litert:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.johnrocky.hfmodels.litert.EncoderDecisionsDeviceTest \
 *     -Pandroid.testInstrumentationRunnerArguments.variant=en_s512_wfp16 -Pandroid.testInstrumentationRunnerArguments.backend=gpu
 */
@RunWith(AndroidJUnit4::class)
class EncoderDecisionsDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val variant = args.getString("variant") ?: "en_s512_wfp16"
    private val backend = args.getString("backend") ?: "gpu"
    private val rowLimit = args.getString("rows")?.toInt() ?: Int.MAX_VALUE
    private val authoredLimit = args.getString("authored")?.toInt() ?: Int.MAX_VALUE
    private val repeats = args.getString("repeats")?.toInt() ?: 5
    private val base = File(args.getString("dir") ?: "/data/local/tmp/hfmodels/laya")
    private val failures = ArrayList<String>()

    private fun result(step: String, ok: Boolean, detail: String) {
        Log.i(TAG, "RESULT step=$step ok=$ok variant=$variant backend=$backend $detail")
        if (!ok) failures += "$step: $detail"
    }

    @Test fun loadParityTimingRelease(): Unit = runBlocking {
        val v = VARIANTS.getValue(variant)
        val models = HfModels(ctx)
        val policy = when (backend) { "cpu" -> BackendPolicy.Require(BackendKind.CPU); "gpu" -> BackendPolicy.Require(BackendKind.GPU); else -> BackendPolicy.Auto }
        val opts = LoadOptions(backendPolicy = policy, networkPolicy = NetworkPolicy.Offline, descriptorJson = descriptor(variant))
        Log.i(TAG, "device=${Build.MODEL} build=${Build.DISPLAY} android=${Build.VERSION.RELEASE} litert=${BuildConfig.LITERT_VERSION} variant=$variant backend=$backend")
        var model: TypedDecisions? = null
        try {
            // 1. load
            val ref = REF.copy(variant = variant)
            val plan = models.inspect(ref, EncoderDecisions, opts)
            val t0 = SystemClock.elapsedRealtime()
            for (f in plan.files) if (!f.cached) models.importFile(plan, f.id, File(base, v.local.getValue(f.id)))
            val importMs = SystemClock.elapsedRealtime() - t0
            val events = ArrayList<LoadEvent>()
            val t1 = SystemClock.elapsedRealtime()
            val m = models.fromPretrained(ref, EncoderDecisions, opts) { events += it }
            model = m
            val loadMs = SystemClock.elapsedRealtime() - t1
            result("load", events.last() is LoadEvent.Ready && events.none { it is LoadEvent.DownloadStarted },
                "import_ms=$importMs load_ms=$loadMs profile=${m.info.profileId} window=${m.limits.windowTokens} head=${m.limits.headTokens} notes=${m.info.notes.joinToString(" | ")}")
            val impl = m as LiteRtDecisionModel

            // 2. parity on the captured rows
            val rows = (Json.parseObject(gz(File(base, "fixtures/${v.rows}")))["rows"] as List<*>).map { it as Map<*, *> }
            val fixtures = (Json.parse(gz(File(base, "fixtures/${v.fixtures}"))) as List<*>).map { it as Map<*, *> }.associateBy { it["id"] as String }
            var checked = 0; var argmaxFlips = 0; var maxLogitErr = 0.0; var maxProbErr = 0.0; var maxActErr = 0.0; var exact = 0; var maxScoreErr = 0.0
            val perQuestionMs = ArrayList<Double>()
            var worst = ""
            for (r in rows) {
                if (checked >= rowLimit) break
                if ((r["sequence_ids"] as List<*>).size > m.limits.windowTokens) continue
                val f = fixtures.getValue(r["fixture_id"] as String)
                val q = Question.fromMap((f["questions"] as Map<*, *>)[r["question_id"]] as Map<*, *>)
                val d = m.decide(f["state"]!!, mapOf("q" to q))
                perQuestionMs += d.timing.questionMs[0]
                val (raw, act) = impl.lastRaw!!
                val refRaw = (r["raw_logits"] as List<*>).map { (it as Number).toDouble() }
                val refAct = (r["raw_act_logits"] as List<*>).map { (it as Number).toDouble() }
                // The expected answer: the captured (official fp32) logits decoded with the calibration THIS variant declares, so a
                // fitted calibration file and the publisher's config are both checked against the same reference logits.
                val official = LayaDecode.answer(q, refRaw.map { it.toFloat() }.toFloatArray(), refAct.map { it.toFloat() }.toFloatArray(), impl.calibration).toMap()
                val got = d.answers.getValue("q")
                checked++
                val le = refRaw.indices.maxOf { abs(refRaw[it] - raw[it]) }
                val ae = refAct.indices.maxOf { abs(refAct[it] - act[it]) } / maxOf(1.0, refAct.maxOf { abs(it) })
                val refP = (official["probabilities"] as? Map<*, *>)?.values?.map { (it as Number).toDouble() }
                val gotP = when (got) { is Answer.Choice -> got.probabilities.values.toList(); is Answer.Score -> got.probabilities.values.toList(); is Answer.Noul -> listOf(1 - got.noul, got.noul) }
                val pe = if (refP != null) refP.indices.maxOf { abs(refP[it] - gotP[it]) } else abs((official["noul"] as Number).toDouble() - (got as Answer.Noul).noul)
                val refArg = refRaw.indices.maxByOrNull { refRaw[it] }!!; val gotArg = raw.indices.maxByOrNull { raw[it] }!!
                if (refArg != gotArg) argmaxFlips++
                if (got is Answer.Score) maxScoreErr = maxOf(maxScoreErr, abs((official["score"] as Number).toDouble() - got.score))
                if (Json.dumps(got.toMap()) == Json.dumps(official)) exact++
                if (le > maxLogitErr) { maxLogitErr = le; worst = r["row_id"] as String }
                maxProbErr = maxOf(maxProbErr, pe); maxActErr = maxOf(maxActErr, ae)
            }
            val sorted = perQuestionMs.sorted()
            result("parity", checked > 0 && argmaxFlips == 0 && maxProbErr <= 0.01,
                "rows=$checked calibration=${File(m.info.files.getValue("config")).name} argmax_flips=$argmaxFlips max_marker_logit_abs_err=${"%.5f".format(maxLogitErr)} (worst $worst) max_prob_abs_err=${"%.5f".format(maxProbErr)} max_score_abs_err=${"%.5f".format(maxScoreErr)} max_act_logit_rel_err=${"%.2e".format(maxActErr)} exact_rounded=$exact/$checked question_ms_median=${"%.1f".format(sorted[sorted.size / 2])} p90=${"%.1f".format(sorted[(sorted.size * 9) / 10])} min=${"%.1f".format(sorted.first())} max=${"%.1f".format(sorted.last())}")

            // 3. timing: shared (one call, N questions) vs direct (N calls, one question each), warm
            val tf = fixtures.getValue(v.timingFixture)
            val tqs = (tf["questions"] as Map<*, *>).entries.associate { (k, x) -> k.toString() to Question.fromMap(x as Map<*, *>) }
            val state = tf["state"]!!
            repeat(2) { m.decide(state, tqs) }
            val shared = ArrayList<Double>(); val direct = ArrayList<Double>(); val stateMs = ArrayList<Double>()
            repeat(repeats) {
                val d = m.decide(state, tqs); shared += d.timing.totalMs; stateMs += d.timing.stateMs
                var sum = 0.0
                for ((k, q) in tqs) sum += m.decide(state, mapOf(k to q)).timing.totalMs
                direct += sum
            }
            val pre = m.prefill(state)
            val viaPrefill = ArrayList<Double>()
            repeat(repeats) { viaPrefill += pre.decide(tqs).timing.totalMs }
            pre.close()
            val sd = m.decide(state, tqs)
            result("timing", true, "questions=${tqs.size} state_tokens=${sd.stateTokens} shared_ms_median=${"%.1f".format(shared.sorted()[shared.size / 2])} direct_ms_median=${"%.1f".format(direct.sorted()[direct.size / 2])} prefill_then_decide_ms_median=${"%.1f".format(viaPrefill.sorted()[viaPrefill.size / 2])} state_tokenize_ms_median=${"%.2f".format(stateMs.sorted()[stateMs.size / 2])} per_question_ms=${sd.timing.questionMs.joinToString(",") { "%.1f".format(it) }} repeats=$repeats")

            // 4. the SemIf authored144 rows vs the official laya answers (Mac), if the file was pushed
            val oracleFile = File(base, "fixtures/authored144_laya_oracle.json")
            if (oracleFile.isFile) {
                val oracle = Json.parseObject(oracleFile.readText())
                val block = (oracle["rows"] as Map<*, *>)[v.oracleKey] as? Map<*, *>
                if (block != null && (block["window"] as Number).toInt() == m.limits.windowTokens) {
                    val orows = (block["answers"] as List<*>).map { it as Map<*, *> }
                    // authored144.jsonl.gz holds one JSON object per line
                    val lines = gz(File(base, "fixtures/authored144.jsonl.gz")).lineSequence().filter { it.isNotBlank() }.map { Json.parseObject(it) }.associateBy { it["id"] as String }
                    var n = 0; var agree = 0; var maxP = 0.0; var correctDevice = 0; var correctOracle = 0
                    val perFamily = HashMap<String, IntArray>()   // [device correct, oracle correct, total]
                    for (o in orows) {
                        if (n >= authoredLimit) break
                        val row = lines.getValue(o["id"] as String)
                        val options = row["options"] as List<*>
                        val q = Question.Choice(row["question"] as String, LinkedHashMap(options.associate { (it as Map<*, *>)["id"] as String to it["description"] as String? }))
                        val got = m.decide(row["state"] as String, mapOf("q" to q)).answers.getValue("q") as Answer.Choice
                        val official = o["official"] as Map<*, *>
                        n++
                        if (got.choice == official["choice"]) agree++
                        val op = (official["probabilities"] as Map<*, *>).entries.associate { (k, x) -> k.toString() to (x as Number).toDouble() }
                        maxP = maxOf(maxP, got.probabilities.entries.maxOf { (k, x) -> abs(x - op.getValue(k)) })
                        val label = (row["label"] as Number).toInt()
                        val ids = options.map { (it as Map<*, *>)["id"] as String }
                        val fam = perFamily.getOrPut(row["family"] as String) { IntArray(3) }
                        fam[2]++
                        if (ids.indexOf(got.choice) == label) { correctDevice++; fam[0]++ }
                        if (ids.indexOf(official["choice"] as String) == label) { correctOracle++; fam[1]++ }
                    }
                    val famStr = perFamily.entries.joinToString(" ") { (k, a) -> "$k=${a[0]}/${a[1]}/${a[2]}" }
                    result("authored144", n > 0 && agree == n, "rows=$n agree_with_official=$agree max_prob_abs_err=${"%.5f".format(maxP)} accuracy_device=${"%.3f".format(correctDevice.toDouble() / n)} accuracy_official=${"%.3f".format(correctOracle.toDouble() / n)} per_family(device/official/n)=$famStr")
                } else result("authored144", true, "skipped: no oracle block for ${v.oracleKey} at window ${m.limits.windowTokens}")
            } else result("authored144", true, "skipped: ${oracleFile.path} not pushed")

            // 5. release
            val t2 = SystemClock.elapsedRealtime()
            m.closeAndJoin()
            result("release", true, "close_ms=${SystemClock.elapsedRealtime() - t2}")
            model = null
        } catch (t: Throwable) {
            Log.e(TAG, "failed", t)
            failures += "exception: ${t.javaClass.simpleName}: ${t.message}"
        } finally {
            runCatching { model?.closeAndJoin() }
            models.closeAndJoin()
        }
        Log.i(TAG, "RESULT ok=${failures.isEmpty()} variant=$variant backend=$backend device=${Build.MODEL} build=${Build.DISPLAY} failures=${failures.joinToString(" | ")}")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun gz(f: File): String = GZIPInputStream(f.inputStream()).bufferedReader().use { it.readText() }

    private class V(val local: Map<String, String>, val rows: String, val fixtures: String, val timingFixture: String, val oracleKey: String)

    companion object {
        const val TAG = "hfmodels-decide"
        /** The publisher's repo at the commit the tokenizer and config files come from; the graphs are development copies of the conversion and not on the Hub yet. */
        val REF = ModelRef("convaiinnovations/laya", revision = "1c5edc17a7acd8701df6fc341c0d179f1c62c982")
        private val VARIANTS = mapOf(
            "en_s512_wfp16" to V(mapOf("main" to "laya_en_s512_wfp16.tflite", "act" to "laya_act_head_fp32.tflite", "tokenizer" to "tokenizer/tokenizer.json", "tokenizer_config" to "tokenizer/tokenizer_config.json", "config" to "rl_agent_config.json"), "en_rows.json.gz", "en_fixtures.json.gz", "A01", "en"),
            "en_s256_wfp16" to V(mapOf("main" to "laya_en_s256_wfp16.tflite", "act" to "laya_act_head_fp32.tflite", "tokenizer" to "tokenizer/tokenizer.json", "tokenizer_config" to "tokenizer/tokenizer_config.json", "config" to "rl_agent_config.json"), "en_rows.json.gz", "en_fixtures.json.gz", "A01", "en256"),
            "en_s512_fp32" to V(mapOf("main" to "laya_en_s512_fp32.tflite", "act" to "laya_act_head_fp32.tflite", "tokenizer" to "tokenizer/tokenizer.json", "tokenizer_config" to "tokenizer/tokenizer_config.json", "config" to "rl_agent_config.json"), "en_rows.json.gz", "en_fixtures.json.gz", "A01", "en"),
            "en_s256_fp32" to V(mapOf("main" to "laya_en_s256_fp32.tflite", "act" to "laya_act_head_fp32.tflite", "tokenizer" to "tokenizer/tokenizer.json", "tokenizer_config" to "tokenizer/tokenizer_config.json", "config" to "rl_agent_config.json"), "en_rows.json.gz", "en_fixtures.json.gz", "A01", "en256"),
            "ml_s256_fp32" to V(mapOf("main" to "laya_ml_s256_fp32.tflite", "act" to "laya_ml_act_head_fp32.tflite", "tokenizer" to "multilingual/tokenizer/tokenizer.json", "tokenizer_config" to "multilingual/tokenizer/tokenizer_config.json", "config" to "laya_ml_calibration.json"), "ml_rows_s256.json.gz", "ml_fixtures.json.gz", "ML_A01", "ml"),
            "ml_s256_wfp16" to V(mapOf("main" to "laya_ml_s256_wfp16.tflite", "act" to "laya_ml_act_head_fp32.tflite", "tokenizer" to "multilingual/tokenizer/tokenizer.json", "tokenizer_config" to "multilingual/tokenizer/tokenizer_config.json", "config" to "laya_ml_calibration.json"), "ml_rows_s256.json.gz", "ml_fixtures.json.gz", "ML_A01", "ml"),
            "ml_s512_fp32" to V(mapOf("main" to "laya_ml_s512_fp32.tflite", "act" to "laya_ml_act_head_fp32.tflite", "tokenizer" to "multilingual/tokenizer/tokenizer.json", "tokenizer_config" to "multilingual/tokenizer/tokenizer_config.json", "config" to "laya_ml_calibration.json"), "ml_rows_s256.json.gz", "ml_fixtures.json.gz", "ML_A01", "ml512"),
        )

        fun descriptor(variantId: String): String = LayaDevDescriptor.json(variantId)
    }
}
