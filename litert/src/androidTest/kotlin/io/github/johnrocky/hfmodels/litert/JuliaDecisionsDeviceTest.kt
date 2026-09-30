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
import kotlin.math.exp
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Typed decisions with `litert-community/Julia-1-LiteRT` on a named device, through the public API
 * and the bundled catalog entry (its descriptor is passed explicitly so the run is offline; every file
 * is imported from /data/local/tmp/hfmodels/julia1, laid out like the repo, and sha256-checked against
 * the descriptor). In one process and one load:
 *   1. load (import, tokenizer, compile) and the profile that came up;
 *   2. sequence: the publisher's token ids, marker positions and qtype (`julia/data.py sequence()`) for
 *      every fixture row that fits the window, vs this module's builder;
 *   3. parity: `decide(state, question)` for every fitting row vs the publisher's runtime (CPU FP32 raw
 *      marker logits, softmax at T=1): same argmax, max |dp|, raw-logit error, per type; the device's
 *      and the publisher's correct counts on the typed-decisions rows;
 *   4. timing: `decide(state, 4 questions)` vs 4 x `decide(state, 1 question)`, warm;
 *   5. release.
 * One RESULT line per step under tag `hfmodels-decide`. Arguments: variant (s512_fp32 | s1024_fp32),
 * backend (gpu | cpu | auto), rows (parity rows, default all), repeats (timing, default 5), dp_tol
 * (default 0.01), dir. The fixture is `tools/julia1_fixture.py`'s device file, pushed as
 * `<dir>/fixtures/julia1_rows.json.gz`.
 *
 *   GATE_TEST=JuliaDecisionsDeviceTest GATE_TAG=decide-julia1 tools/decide_gate.sh s512_fp32 gpu
 */
@RunWith(AndroidJUnit4::class)
class JuliaDecisionsDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val testCtx = InstrumentationRegistry.getInstrumentation().context
    private val args = InstrumentationRegistry.getArguments()
    private val variant = args.getString("variant") ?: "s512_fp32"
    private val backend = args.getString("backend") ?: "gpu"
    private val rowLimit = args.getString("rows")?.toInt() ?: Int.MAX_VALUE
    private val repeats = args.getString("repeats")?.toInt() ?: 5
    private val dpTol = args.getString("dp_tol")?.toDouble() ?: 0.01
    private val base = File(args.getString("dir") ?: "/data/local/tmp/hfmodels/julia1")
    private val failures = ArrayList<String>()

    private fun result(step: String, ok: Boolean, detail: String) {
        Log.i(TAG, "RESULT step=$step ok=$ok variant=$variant backend=$backend $detail")
        if (!ok) failures += "$step: $detail"
    }

    @Test fun loadSequenceParityTimingRelease(): Unit = runBlocking {
        val models = HfModels(ctx)
        val policy = when (backend) { "cpu" -> BackendPolicy.Require(BackendKind.CPU); "gpu" -> BackendPolicy.Require(BackendKind.GPU); else -> BackendPolicy.Auto }
        val entry = JSONObject(testCtx.assets.open(ENTRY_ASSET).bufferedReader().use { it.readText() })
        val commit = entry.getString("model_commit")
        val opts = LoadOptions(backendPolicy = policy, networkPolicy = NetworkPolicy.Offline, descriptorJson = entry.getJSONObject("descriptor").toString())
        Log.i(TAG, "device=${Build.MODEL} build=${Build.DISPLAY} android=${Build.VERSION.RELEASE} litert=${BuildConfig.LITERT_VERSION} model=$REPO@${commit.take(8)} variant=$variant backend=$backend")
        var model: TypedDecisions? = null
        try {
            // 1. load
            val ref = ModelRef(REPO, revision = commit, variant = variant)
            val plan = models.inspect(ref, EncoderDecisions, opts)
            val t0 = SystemClock.elapsedRealtime()
            for (f in plan.files) if (!f.cached) models.importFile(plan, f.id, File(base, f.path))
            val importMs = SystemClock.elapsedRealtime() - t0
            val events = ArrayList<LoadEvent>()
            val t1 = SystemClock.elapsedRealtime()
            val m = models.fromPretrained(ref, EncoderDecisions, opts) { events += it }
            model = m
            val loadMs = SystemClock.elapsedRealtime() - t1
            result("load", events.last() is LoadEvent.Ready && events.none { it is LoadEvent.DownloadStarted },
                "import_ms=$importMs load_ms=$loadMs profile=${m.info.profileId} window=${m.limits.windowTokens} head=${m.limits.headTokens} max_options=${m.limits.maxOptions} notes=${m.info.notes.joinToString(" | ")}")
            val impl = m as LiteRtDecisionModel
            val window = m.limits.windowTokens

            // 2. the publisher's sequences
            val all = (Json.parseObject(gz(File(base, "fixtures/julia1_rows.json.gz")))["rows"] as List<*>).map { it as Map<*, *> }
            val rows = all.filter { (it["fits"] as Map<*, *>)[window.toString()] as Boolean }
            var seqChecked = 0; var seqFail = 0; var truncatedRows = 0
            for (r in all) {
                val q = question(r)
                impl.builder.validate(q)
                val built = impl.builder.build(q, impl.builder.stateIds(impl.builder.serializeState(r["state"]!!)))
                if (r !in rows) { if (built.stateTruncated) truncatedRows++ else { seqFail++; Log.w(TAG, "${r["id"]}: longer than $window but not reported as truncated") }; continue }
                seqChecked++
                val ids = (r["ids"] as List<*>).map { (it as Number).toInt() }
                val markers = (r["markers"] as List<*>).map { (it as Number).toInt() }
                if (ids != built.ids.toList() || markers != built.markers.toList() || (r["qtype"] as Number).toInt() != built.qtype || built.stateTruncated) {
                    seqFail++
                    Log.w(TAG, "${r["id"]}: ids ${ids.size} vs ${built.ids.size} first diff at ${ids.zip(built.ids.toList()).indexOfFirst { it.first != it.second }}; markers $markers vs ${built.markers.toList()}")
                }
            }
            result("sequence", seqChecked > 0 && seqFail == 0, "rows=$seqChecked mismatches=$seqFail longer_than_window=$truncatedRows (reported truncated) window=$window")

            // 3. parity with the publisher's runtime, through decide()
            var checked = 0; var flips = 0; var maxDp = 0.0; var maxDl = 0.0; var over = 0; var worst = ""
            val perType = HashMap<String, IntArray>()   // type -> [rows, flips, device correct, oracle correct, gold rows]
            val ms = ArrayList<Double>(); val lookup = ArrayList<Double>()
            for (r in rows.take(rowLimit)) {
                val q = question(r)
                val type = r["type"] as String
                val keys = (r["keys"] as List<*>).map { it as String }
                val d = m.decide(r["state"]!!, mapOf("q" to q))
                ms += d.timing.questionMs[0]; lookup += impl.lastLookupMs
                val z = (r["logits"] as List<*>).map { (it as Number).toDouble() }
                val ref = softmax(z.map { it.toFloat().toDouble() })
                val got = probabilities(d.answers.getValue("q"), keys)
                val refBest = ref.indices.maxByOrNull { ref[it] }!!
                val gotBest = got.indices.maxByOrNull { got[it] }!!
                val dp = ref.indices.maxOf { abs(ref[it] - got[it]) }
                val raw = impl.lastRaw!!.first
                val dl = z.indices.maxOf { abs(z[it] - raw[it]) }
                checked++
                val pt = perType.getOrPut(type) { IntArray(5) }
                pt[0]++
                if (refBest != gotBest) { flips++; pt[1]++; Log.w(TAG, "${r["id"]}: argmax ${keys[gotBest]} vs publisher ${keys[refBest]} (p ${got[gotBest]} vs ${ref[refBest]})") }
                if (dp > maxDp) { maxDp = dp; worst = r["id"] as String }
                if (dl > maxDl) maxDl = dl
                if (dp > 0.01) over++
                val gold = r["gold"] as? String
                if (gold != null) { pt[4]++; if (keys[gotBest] == gold) pt[2]++; if (keys[refBest] == gold) pt[3]++ }
                if (checked % 200 == 0) Log.i(TAG, "parity $checked/${minOf(rows.size, rowLimit)}: flips=$flips max_dp=${"%.5f".format(maxDp)} median_ms=${"%.1f".format(ms.sorted()[ms.size / 2])}")
            }
            val sorted = ms.sorted(); val lk = lookup.sorted()
            val types = perType.entries.sortedBy { it.key }.joinToString(" ") { (k, a) -> "$k=${a[0]}rows/${a[1]}flips/device_correct ${a[2]}/${a[4]}/publisher_correct ${a[3]}/${a[4]}" }
            result("parity", checked > 0 && flips == 0 && maxDp <= dpTol,
                "rows=$checked argmax_flips=$flips max_prob_abs_err=${"%.5f".format(maxDp)} (worst $worst) rows_over_0.01=$over max_marker_logit_abs_err=${"%.4f".format(maxDl)} dp_tol=$dpTol " +
                    "question_ms_median=${"%.1f".format(sorted[sorted.size / 2])} p90=${"%.1f".format(sorted[(sorted.size * 9) / 10])} min=${"%.1f".format(sorted.first())} max=${"%.1f".format(sorted.last())} lookup_ms_median=${"%.2f".format(lk[lk.size / 2])} per_type: $types")

            // 4. timing: four questions about one state, batched vs one by one
            val state = mapOf("subject" to "Duplicate charge", "body" to "I was charged twice for order 4471 and would like the second charge refunded.")
            val qs = linkedMapOf(
                "department" to Question.Choice("Which department should handle this request?", linkedMapOf("billing" to "invoices, payments, refunds", "technical" to "bugs, outages", "other" to "everything else")),
                "urgency" to Question.Score("How urgent is this request?", listOf("not urgent", "soon", "critical deadline or blocking issue")),
                "refund_requested" to Question.Noul("Does the user explicitly request a refund?"),
                "tone" to Question.Choice("What is the tone of the message?", linkedMapOf("polite" to "calm and polite", "angry" to "upset or angry", "neutral" to "neutral")),
            )
            val batched = ArrayList<Double>(); val single = ArrayList<Double>()
            m.decide(state, qs)   // warm
            for (i in 0 until repeats) {
                batched += m.decide(state, qs).timing.totalMs
                val t = SystemClock.elapsedRealtimeNanos()
                for ((k, q) in qs) m.decide(state, mapOf(k to q))
                single += (SystemClock.elapsedRealtimeNanos() - t) / 1e6
            }
            val ans = m.decide(state, qs).answers
            result("timing", true, "repeats=$repeats decide_4q_total_ms_median=${"%.1f".format(batched.sorted()[batched.size / 2])} four_decide_1q_total_ms_median=${"%.1f".format(single.sorted()[single.size / 2])} answers=${ans.mapValues { (_, a) -> when (a) { is Answer.Choice -> a.choice + " " + "%.3f".format(a.probabilities.getValue(a.choice)); is Answer.Score -> "%.3f".format(a.score); is Answer.Noul -> "%.3f".format(a.noul) } }}")

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
        Log.i(TAG, "RESULT ok=${failures.isEmpty()} model=$REPO variant=$variant backend=$backend device=${Build.MODEL} build=${Build.DISPLAY} failures=${failures.joinToString(" | ")}")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun gz(f: File): String = GZIPInputStream(f.inputStream()).bufferedReader().use { it.readText() }

    /** The SDK question for a fixture row (the publisher's `predict_typed` renders it to the row's options). */
    private fun question(r: Map<*, *>): Question {
        val q = r["question"] as String
        val keys = (r["keys"] as List<*>).map { it as String }
        val options = (r["options"] as List<*>).map { it as String }
        return when (r["type"] as String) {
            "choice" -> Question.Choice(q, LinkedHashMap(keys.indices.associate { keys[it] to options[it] }))
            "score" -> Question.Score(q, options)
            "noul" -> Question.Noul(q, if (options == listOf("false", "true")) null else linkedMapOf("false" to options[0], "true" to options[1]))
            else -> error("type ${r["type"]}")
        }
    }

    private fun probabilities(a: Answer, keys: List<String>): List<Double> = when (a) {
        is Answer.Choice -> keys.map { a.probabilities.getValue(it) }
        is Answer.Score -> keys.indices.map { a.probabilities.getValue(it.toString()) }
        is Answer.Noul -> listOf(1.0 - a.noul, a.noul)
    }

    private fun softmax(z: List<Double>): List<Double> { val mx = z.max(); val e = z.map { exp(it - mx) }; val s = e.sum(); return e.map { it / s } }

    companion object {
        const val TAG = "hfmodels-decide"
        const val REPO = "litert-community/Julia-1-LiteRT"
        /** The bundled catalog entry (catalog/entries, an androidTest asset): its descriptor and model commit drive the load. */
        const val ENTRY_ASSET = "litert-community__Julia-1-LiteRT.json"
    }
}
