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
import kotlin.math.abs
import kotlin.math.exp
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Typed decisions with `litert-community/GLiClass-Edge-v3.0-LiteRT` (family `gliclass`) on a named device, through
 * the public API and the development descriptor (catalog/dev, passed explicitly so the run is offline; every file is
 * imported from /data/local/tmp/hfmodels/gliclass, laid out like the repo, and sha256-checked against the
 * descriptor). In one process and one load:
 *   1. load (import, tokenizer, compile), the profile that came up and compile_ms;
 *   2. sequence: the card's device-gate requests that fit this window (its `gate_fixtures.json`: the captured gliclass
 *      0.1.20 calls) vs this module's request string ids, `<<LABEL>>` positions and routing;
 *   3. parity: each of those requests through the loaded graph vs the official fp32 result: argmax, the official
 *      single-label decision through this module's decode (the multi-label sets by the card's sigmoid rule, reported),
 *      max |dp| of the softmax and |dlogit|;
 *   4. demo: the round-1 sieve's 30 chat sentences through `decide(text, question)`, the question with descriptions
 *      (labels = descriptions: r1's policy form) and with keys only, vs the Mac answers of the card's Python host (same
 *      answer n/30, max |dp| <= dp_tol, the encoded length equal to the host's, warm ms per question);
 *   5. timing: `decide(state, 4 questions)` vs 4 x `decide(state, 1 question)`, warm;
 *   6. zoo_input: the Model Zoo Text row's sentence, question and count through decide(), each call split into its stages ([ZooInputBench]);
 *   7. lookup: the host embedding lookup of one request the Model Zoo's way and this module's way ([HostLookupBench]);
 *   8. release.
 * One RESULT line per step under tag `hfmodels-decide`, numbers unrounded. Arguments: variant (s128_fp32 | s256_fp32),
 * backend (gpu | cpu | auto), dir, fixtures (default `<dir>/fixtures`: the card's `gate_fixtures.json`, the sieve's
 * `b_chat.jsonl`, `gliclass_b_chat_policy.jsonl` and `gliclass_b_chat_keys.jsonl`, and round 4's `decide_form.json`),
 * repeats (timing, default 5), dp_tol (default 0.01).
 *
 *   GATE_TEST=GliclassDeviceTest GATE_TAG=decide-gliclass tools/decide_gate.sh s128_fp32 gpu
 */
@RunWith(AndroidJUnit4::class)
class GliclassDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val testCtx = InstrumentationRegistry.getInstrumentation().context
    private val args = InstrumentationRegistry.getArguments()
    private val variant = args.getString("variant") ?: "s128_fp32"
    private val backend = args.getString("backend") ?: "gpu"
    private val repeats = args.getString("repeats")?.toInt() ?: 5
    private val dpTol = args.getString("dp_tol")?.toDouble() ?: 0.01
    private val base = File(args.getString("dir") ?: "/data/local/tmp/hfmodels/gliclass")
    private val fixturesDir = File(args.getString("fixtures") ?: File(base, "fixtures").path)
    private val failures = ArrayList<String>()

    private fun result(step: String, ok: Boolean, detail: String) {
        Log.i(TAG, "RESULT step=$step ok=$ok variant=$variant backend=$backend $detail")
        if (!ok) failures += "$step: $detail"
    }

    @Test fun loadSequenceParityDemoTimingRelease(): Unit = runBlocking {
        val models = HfModels(ctx)
        val policy = when (backend) { "cpu" -> BackendPolicy.Require(BackendKind.CPU); "gpu" -> BackendPolicy.Require(BackendKind.GPU); else -> BackendPolicy.Auto }
        val descriptor = testCtx.assets.open(DESCRIPTOR_ASSET).bufferedReader().use { it.readText() }
        val commit = JSONObject(descriptor).getString("revision")
        val opts = LoadOptions(backendPolicy = policy, networkPolicy = NetworkPolicy.Offline, descriptorJson = descriptor)
        Log.i(TAG, "device=${Build.MODEL} soc=${Build.SOC_MANUFACTURER}/${Build.SOC_MODEL} build=${Build.DISPLAY} android=${Build.VERSION.RELEASE} litert=${BuildConfig.LITERT_VERSION} model=$REPO@${commit.take(8)} variant=$variant backend=$backend")
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
            val compileMs = m.info.notes.firstNotNullOfOrNull { Regex("compile_ms=(\\d+)").find(it)?.groupValues?.get(1) } ?: "?"
            result("load", events.last() is LoadEvent.Ready && events.none { it is LoadEvent.DownloadStarted },
                "import_ms=$importMs load_ms=$loadMs compile_ms=$compileMs profile=${m.info.profileId} window=${m.limits.windowTokens} head=${m.limits.headTokens} max_options=${m.limits.maxOptions} notes=${m.info.notes.joinToString(" | ")}")
            val impl = m as LiteRtDecisionModel
            val c = impl.contract as GliclassContract
            val window = m.limits.windowTokens

            // 2. the publisher's captured requests that fit this window
            val all = (Json.parseObject(File(fixturesDir, "gate_fixtures.json").readText())["fixtures"] as List<*>).map { it as Map<*, *> }
            val rows = all.filter { (it["window"] as Number).toInt() <= window }
            var seqFail = 0
            for (f in rows) {
                val labels = strings(f["labels"])
                val e = c.encode(f["text"] as String, labels, f["prompt"] as String?)
                val fw = c.forward(f["text"] as String, labels, f["prompt"] as String?)
                val positions = ints(f["label_positions"])
                val routingOk = (0 until 25).all { row -> (0 until window).all { col -> fw.routing[row * window + col] == if (row < positions.size && positions[row] == col) 1f else 0f } }
                if (ints(f["ids"]) != e.ids.toList() || positions != e.labelPositions.toList() || !routingOk) {
                    seqFail++
                    Log.w(TAG, "${f["id"]}: ids ${ints(f["ids"]).size} vs ${e.ids.size}; labels $positions vs ${e.labelPositions.toList()}; routing ok $routingOk")
                }
            }
            result("sequence", rows.isNotEmpty() && seqFail == 0, "rows=${rows.size} of ${all.size} mismatches=$seqFail window=$window")

            // 3. parity: each captured request through the loaded graph
            var argmaxRows = 0; var singleRows = 0; var multiRows = 0; var maxDp = 0.0; var maxDl = 0.0; var worst = ""
            val forwardMs = ArrayList<Double>()
            impl.scores(c.forward(rows[0]["text"] as String, strings(rows[0]["labels"]), rows[0]["prompt"] as String?))   // warm
            for (f in rows) {
                val labels = strings(f["labels"])
                val fw = c.forward(f["text"] as String, labels, f["prompt"] as String?)
                val tf = SystemClock.elapsedRealtimeNanos()
                val logits = impl.scores(fw)
                forwardMs += (SystemClock.elapsedRealtimeNanos() - tf) / 1e6
                val oracle = floats(f["logits"])
                val p = GlinerDecode.softmax(logits)
                val ref = GlinerDecode.softmax(oracle)
                val best = GlinerDecode.argmax(p)
                if (best == GlinerDecode.argmax(oracle)) argmaxRows++ else Log.w(TAG, "${f["id"]}: argmax differs; device ${logits.toList()} oracle ${oracle.toList()}")
                val single = (f["pipeline_single"] as List<*>).map { (it as Map<*, *>)["label"] as String }
                if (listOf(labels[best]) == single) singleRows++ else Log.w(TAG, "${f["id"]}: single-label ${labels[best]} vs official $single")
                // The card's multi-label rule (sigmoid >= 0.5, in input order; a repeated label keeps its first position).
                val s = FloatArray(labels.size) { 1f / (1f + exp((-logits[it]).toDouble()).toFloat()) }
                val chosen = LinkedHashMap<String, Float>().also { mm -> labels.forEachIndexed { i, l -> mm[l] = s[i] } }.filterValues { it.toDouble() >= 0.5 }.keys.toList()
                if (chosen == (f["pipeline_multi"] as List<*>).map { (it as Map<*, *>)["label"] as String }) multiRows++
                val dp = p.indices.maxOf { abs(p[it] - ref[it]).toDouble() }
                if (dp > maxDp) { maxDp = dp; worst = f["id"] as String }
                maxDl = maxOf(maxDl, logits.indices.maxOf { abs(logits[it] - oracle[it]).toDouble() })
            }
            val fs = forwardMs.sorted()
            result("parity", argmaxRows == rows.size && singleRows == rows.size && maxDp <= dpTol,
                "rows=${rows.size} argmax_match=$argmaxRows/${rows.size} single_label_equal_official=$singleRows/${rows.size} multi_label_equal_official=$multiRows/${rows.size} max_prob_abs_err=$maxDp (worst $worst) max_logit_abs_err_vs_oracle=$maxDl dp_tol=$dpTol " +
                    "forward_ms_median=${fs[fs.size / 2]} p90=${fs[(fs.size * 9) / 10]} min=${fs.first()} max=${fs.last()}")

            // 4. the sieve's chat sentences through decide(), vs the Mac answers and request lengths of the card's host
            val chat = File(fixturesDir, "b_chat.jsonl").readLines().filter { it.isNotBlank() }.map { Json.parseObject(it) }
            val hostLength = ((Json.parseObject(File(fixturesDir, "decide_form.json").readText())["requests"]) as List<*>).map { it as Map<*, *> }
                .associate { (it["id"] as String to it["form"] as String) to (it["ids"] as List<*>).size }
            for ((form, question, macFile) in listOf(Triple("described", B_MAIN, "gliclass_b_chat_policy.jsonl"), Triple("keys", B_KEYS, "gliclass_b_chat_keys.jsonl"))) {
                val macRows = File(fixturesDir, macFile).readLines().filter { it.isNotBlank() }.map { Json.parseObject(it) }.associateBy { it["id"] as String }
                repeat(2) { m.decide(chat[0]["text"] as String, mapOf("q" to question)) }   // warm
                var same = 0; var demoDp = 0.0; var lengthSame = 0; var correctDevice = 0; var correctMac = 0; var demoWorst = ""
                val ms = ArrayList<Double>(); val lookup = ArrayList<Double>()
                for (r in chat) {
                    val text = r["text"] as String
                    val d = m.decide(text, mapOf("q" to question))
                    ms += d.timing.questionMs[0]; lookup += impl.lastLookupMs
                    val a = d.answers.getValue("q") as Answer.Choice
                    val length = c.forward("q", question, c.stateIds(text)).ids.size
                    if (length == hostLength[r["id"] as String to form]) lengthSame++ else Log.w(TAG, "${r["id"]} $form: encoded length $length vs host ${hostLength[r["id"] as String to form]}")
                    val macRow = macRows.getValue(r["id"] as String)
                    val macP = (macRow["probabilities"] as Map<*, *>).entries.associate { (k, v) -> k.toString() to (v as Number).toDouble() }
                    if (a.choice == macRow["answer"]) same++ else Log.w(TAG, "${r["id"]} $form: ${a.choice} ${a.probabilities} vs mac ${macRow["answer"]} $macP")
                    val dp = macP.keys.maxOf { abs(a.probabilities.getValue(it) - macP.getValue(it)) }
                    if (dp > demoDp) { demoDp = dp; demoWorst = r["id"] as String }
                    if (a.choice == r["label"]) correctDevice++
                    if (macRow["answer"] == r["label"]) correctMac++
                }
                val sm = ms.sorted(); val lk = lookup.sorted()
                result("demo", same == chat.size && demoDp <= dpTol && lengthSame == chat.size,
                    "form=$form rows=${chat.size} same_answer_as_mac=$same/${chat.size} max_prob_abs_err_vs_mac=$demoDp (worst $demoWorst) encoded_length_equal_host=$lengthSame/${chat.size} dp_tol=$dpTol correct_device=$correctDevice/${chat.size} correct_mac=$correctMac/${chat.size} " +
                        "question_ms_median=${sm[sm.size / 2]} p90=${sm[(sm.size * 9) / 10]} min=${sm.first()} max=${sm.last()} lookup_ms_median=${lk[lk.size / 2]}")
            }

            // 5. timing: four questions about one state, batched vs one by one
            val state = "Duplicate charge. I was charged twice for order 4471 and would like the second charge refunded."
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
            val last = m.decide(state, qs)
            result("timing", true, "repeats=$repeats decide_4q_total_ms_median=${batched.sorted()[batched.size / 2]} four_decide_1q_total_ms_median=${single.sorted()[single.size / 2]} state_tokens=${last.stateTokens} per_question_ms=${last.timing.questionMs.joinToString(",")} " +
                "answers=${last.answers.mapValues { (_, a) -> when (a) { is Answer.Choice -> a.choice + " " + a.probabilities.getValue(a.choice); is Answer.Score -> a.score.toString(); is Answer.Noul -> a.noul.toString() } }}")

            // 6. the Model Zoo Text row's input and count, each decide() split into its stages (the Zoo-vs-SDK comparison)
            result("zoo_input", true, ZooInputBench.run(m, impl))

            // 7. the host lookup of one request, the Model Zoo's way and this module's way, over the Zoo row's window (S128)
            val fw0 = c.forward("q", B_MAIN, c.stateIds(chat[0]["text"] as String))
            val padded = IntArray(ZOO_WINDOW) { if (it < fw0.ids.size) fw0.ids[it] else c.padId }
            result("lookup", true, HostLookupBench.run(File(m.info.files.getValue("table")), GliclassContract.HIDDEN, padded, "lut"))

            // 8. release
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

    private fun ints(v: Any?): List<Int> = (v as List<*>).map { (it as Number).toInt() }

    private fun floats(v: Any?): FloatArray = (v as List<*>).map { (it as Number).toFloat() }.toFloatArray()

    private fun strings(v: Any?): List<String> = (v as List<*>).map { it as String }

    companion object {
        /** The window of the litert-samples Model Zoo's GLiClass row (S128), for the lookup comparison. */
        const val ZOO_WINDOW = 128
        const val TAG = "hfmodels-decide"
        const val REPO = "litert-community/GLiClass-Edge-v3.0-LiteRT"
        /** The development descriptor (catalog/dev, an androidTest asset): its revision and files drive the load. */
        const val DESCRIPTOR_ASSET = "litert-community__GLiClass-Edge-v3.0-LiteRT.hfmodels.json"
        /** The round-1 sieve's question B (results/gliclass_b_chat_policy.meta.json): labels = the descriptions. */
        val B_MAIN = Question.Choice("What is this sentence?", linkedMapOf(
            "nothing" to "an opinion, a story, a vague maybe, or something happening right now",
            "promise" to "the speaker commits to do something later",
            "request" to "the speaker asks the listener to do something",
            "plan" to "a time or day agreed to meet or do something",
        ))
        /** The same question with keys only (results/gliclass_b_chat_keys.meta.json). */
        val B_KEYS = Question.Choice("What is this sentence?", "nothing", "promise", "request", "plan")
    }
}
