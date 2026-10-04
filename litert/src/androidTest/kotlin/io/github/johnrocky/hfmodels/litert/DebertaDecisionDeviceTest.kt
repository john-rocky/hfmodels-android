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
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Typed decisions with `litert-community/Open-Decision-DeBERTa-v3-Large-LiteRT` (family `deberta_decision`) on a
 * named device, through the public API and the development descriptor (catalog/dev, passed explicitly so the run is
 * offline; every file is imported from /data/local/tmp/hfmodels/open-decision, laid out like the repo, and
 * sha256-checked against the descriptor). In one process and one load:
 *   1. load (import, tokenizer, compile), the profile that came up and compile_ms;
 *   2. sequence: the card's device-gate requests that fit this window (the conversion run's `app_gate_fixtures.json`:
 *      the author's Collator ids and spans, all questions of a request in one sequence) vs this module's;
 *   3. parity: each of those requests through the loaded graph, packed as the author does, vs the author's
 *      implementation: per-question argmax of the captured logits, the author's answers through this module's
 *      decode, max |dp| vs the author's probabilities and |dlogit|;
 *   4. demo: the round-1 sieve's 30 chat sentences through `decide(text, question)`, the question with the options
 *      "key: description" (r1's policy form) and with keys only, vs the Mac answers of the card's Python host (same
 *      answer n/30, max |dp| <= dp_tol, the encoded length equal to the host's, warm ms per question);
 *   5. timing: `decide(state, 4 questions)` (packed into one forward, as the author's decide()) vs 4 x `decide(state, 1 question)`, warm;
 *   6. lookup: the host embedding lookup of one request the Model Zoo's way and this module's way ([HostLookupBench]);
 *   7. release.
 * One RESULT line per step under tag `hfmodels-decide`, numbers unrounded. Arguments: variant (s256_wfp16 |
 * s512_wfp16), backend (gpu | cpu | auto), dir, fixtures (default `<dir>/fixtures`: `app_gate_fixtures.json`, the
 * sieve's `b_chat.jsonl`, `open-decision_b_chat_policy.jsonl` and `open-decision_b_chat_keys.jsonl`, and round 4's
 * `decide_form.json`), repeats (timing, default 5), dp_tol (default 0.01).
 *
 *   GATE_TEST=DebertaDecisionDeviceTest GATE_TAG=decide-opendecision tools/decide_gate.sh s256_wfp16 gpu
 */
@RunWith(AndroidJUnit4::class)
class DebertaDecisionDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val testCtx = InstrumentationRegistry.getInstrumentation().context
    private val args = InstrumentationRegistry.getArguments()
    private val variant = args.getString("variant") ?: "s256_wfp16"
    private val backend = args.getString("backend") ?: "gpu"
    private val repeats = args.getString("repeats")?.toInt() ?: 5
    private val dpTol = args.getString("dp_tol")?.toDouble() ?: 0.01
    private val base = File(args.getString("dir") ?: "/data/local/tmp/hfmodels/open-decision")
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
            val c = impl.contract as DebertaDecisionContract
            val window = m.limits.windowTokens

            // 2. the author's Collator requests that fit this window
            val all = (Json.parseObject(File(fixturesDir, "app_gate_fixtures.json").readText())["requests"] as List<*>).map { it as Map<*, *> }
            val rows = all.filter { (it["ids"] as List<*>).size <= window }
            var seqFail = 0
            for (f in rows) {
                val e = c.encode(questions(f), c.stateIds(f["state"] as String))
                if (ints(f["ids"]) != e.ids.toList() || spans(f["q_spans"]) != e.questionSpans.map { listOf(it.first, it.last + 1) } ||
                    (f["opt_spans"] as List<*>).map { spans(it) } != e.optionSpans.map { g -> g.map { listOf(it.first, it.last + 1) } }) {
                    seqFail++
                    Log.w(TAG, "${f["id"]}: ids ${ints(f["ids"]).size} vs ${e.ids.size} or spans differ")
                }
            }
            result("sequence", rows.isNotEmpty() && seqFail == 0, "rows=${rows.size} of ${all.size} mismatches=$seqFail window=$window")

            // 3. parity: each request through the loaded graph, all its questions in one forward
            var argmaxQs = 0; var answerQs = 0; var questionsSeen = 0; var maxDp = 0.0; var maxDl = 0.0; var worst = ""
            val forwardMs = ArrayList<Double>()
            impl.scores(c.forward(questions(rows[0]), c.stateIds(rows[0]["state"] as String)))   // warm
            for (f in rows) {
                val qs = questions(f)
                val fw = c.forward(qs, c.stateIds(f["state"] as String))
                val tf = SystemClock.elapsedRealtimeNanos()
                val logits = impl.scores(fw)
                forwardMs += (SystemClock.elapsedRealtimeNanos() - tf) / 1e6
                var offset = 0
                for ((i, q) in qs.withIndex()) {
                    val oracle = floats((f["logits"] as List<*>)[i])
                    val got = logits.copyOfRange(offset, offset + oracle.size)
                    offset += oracle.size
                    questionsSeen++
                    if (GlinerDecode.argmax(got) == GlinerDecode.argmax(oracle)) argmaxQs++ else Log.w(TAG, "${f["id"]} q$i: argmax differs; device ${got.toList()} oracle ${oracle.toList()}")
                    val api = (f["api"] as List<*>)[i] as Map<*, *>
                    val a = c.decode(q, got, null)
                    val (same, dp) = when (a) {
                        is Answer.Choice -> (a.choice == api["choice"]) to (api["probabilities"] as Map<*, *>).entries.maxOf { (k, v) -> abs(a.probabilities.getValue(k as String) - (v as Number).toDouble()) }
                        is Answer.Score -> {
                            val ref = (api["probabilities"] as Map<*, *>).values.map { (it as Number).toDouble() }
                            val p = a.probabilities.values.toList()
                            (p.indices.maxBy { p[it] } == ref.indices.maxBy { ref[it] }) to ref.indices.maxOf { abs(p[it] - ref[it]) }
                        }
                        is Answer.Noul -> ((a.noul >= 0.5) == ((api["noul"] as Number).toDouble() >= 0.5)) to abs(a.noul - (api["noul"] as Number).toDouble())
                    }
                    if (same) answerQs++ else Log.w(TAG, "${f["id"]} q$i: $a vs the author's $api")
                    if (dp > maxDp) { maxDp = dp; worst = "${f["id"]} q$i" }
                    maxDl = maxOf(maxDl, got.indices.maxOf { abs(got[it] - oracle[it]).toDouble() })
                }
            }
            val fs = forwardMs.sorted()
            result("parity", argmaxQs == questionsSeen && answerQs == questionsSeen && maxDp <= dpTol,
                "rows=${rows.size} questions=$questionsSeen argmax_match=$argmaxQs/$questionsSeen answers_equal_author=$answerQs/$questionsSeen max_prob_abs_err=$maxDp (worst $worst) max_logit_abs_err_vs_author=$maxDl dp_tol=$dpTol " +
                    "forward_ms_median=${fs[fs.size / 2]} p90=${fs[(fs.size * 9) / 10]} min=${fs.first()} max=${fs.last()}")

            // 4. the sieve's chat sentences through decide(), vs the Mac answers and request lengths of the card's host
            val chat = File(fixturesDir, "b_chat.jsonl").readLines().filter { it.isNotBlank() }.map { Json.parseObject(it) }
            val hostLength = ((Json.parseObject(File(fixturesDir, "decide_form.json").readText())["requests"]) as List<*>).map { it as Map<*, *> }
                .associate { (it["id"] as String to it["form"] as String) to (it["ids"] as List<*>).size }
            for ((form, question, macFile) in listOf(Triple("policy", B_POLICY, "open-decision_b_chat_policy.jsonl"), Triple("keys", B_KEYS, "open-decision_b_chat_keys.jsonl"))) {
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
                    // The Mac rows name the options by their short key; the policy question's keys are "key: description", in the same order.
                    val macRow = macRows.getValue(r["id"] as String)
                    val shortKeys = (macRow["probabilities"] as Map<*, *>).keys.map { it as String }
                    val macP = (macRow["probabilities"] as Map<*, *>).values.map { (it as Number).toDouble() }
                    val p = a.probabilities.values.toList()
                    val answer = shortKeys[a.probabilities.keys.indexOf(a.choice)]
                    if (answer == macRow["answer"]) same++ else Log.w(TAG, "${r["id"]} $form: $answer $p vs mac ${macRow["answer"]} $macP")
                    val dp = macP.indices.maxOf { abs(p[it] - macP[it]) }
                    if (dp > demoDp) { demoDp = dp; demoWorst = r["id"] as String }
                    if (answer == r["label"]) correctDevice++
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
            result("timing", true, "repeats=$repeats forwards_for_4q=${c.plan(qs, c.stateIds(state)).size} decide_4q_total_ms_median=${batched.sorted()[batched.size / 2]} four_decide_1q_total_ms_median=${single.sorted()[single.size / 2]} state_tokens=${last.stateTokens} per_question_ms=${last.timing.questionMs.joinToString(",")} " +
                "answers=${last.answers.mapValues { (_, a) -> when (a) { is Answer.Choice -> a.choice + " " + a.probabilities.getValue(a.choice); is Answer.Score -> a.score.toString(); is Answer.Noul -> a.noul.toString() } }}")

            // 6. the host lookup of one request, the Model Zoo's way and this module's way, over the Zoo row's window (S256)
            val fw0 = c.forward("q", B_POLICY, c.stateIds(chat[0]["text"] as String))
            val padded = IntArray(ZOO_WINDOW) { if (it < fw0.ids.size) fw0.ids[it] else c.padId }
            result("lookup", true, HostLookupBench.run(File(m.info.files.getValue("table")), DebertaDecisionContract.HIDDEN, padded, "arith"))

            // 7. release
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

    private fun spans(v: Any?): List<List<Int>> = (v as List<*>).map { ints(it) }

    /** The request's author-form questions (`type`, `instructions`, `options`) as Questions whose family-rule options are the same strings. */
    private fun questions(f: Map<*, *>): List<Question> = (f["questions"] as List<*>).map { o ->
        o as Map<*, *>
        val instructions = o["instructions"] as String
        val options = (o["options"] as List<*>?)?.map { it as String }
        when (o["type"]) {
            "choice" -> Question.Choice(instructions, *options!!.toTypedArray())
            "score" -> Question.Score(instructions, options!!)
            else -> Question.Noul(instructions)
        }
    }

    companion object {
        /** The window of the litert-samples Model Zoo's Open-Decision row (S256), for the lookup comparison. */
        const val ZOO_WINDOW = 256
        const val TAG = "hfmodels-decide"
        const val REPO = "litert-community/Open-Decision-DeBERTa-v3-Large-LiteRT"
        /** The development descriptor (catalog/dev, an androidTest asset): its revision and files drive the load. */
        const val DESCRIPTOR_ASSET = "litert-community__Open-Decision-DeBERTa-v3-Large-LiteRT.hfmodels.json"
        /** The round-1 sieve's question B as r1 sent it to this card (results/open-decision_b_chat_policy.meta.json): options "key: description". */
        val B_POLICY = Question.Choice("What is this sentence?",
            "nothing: an opinion, a story, a vague maybe, or something happening right now",
            "promise: the speaker commits to do something later",
            "request: the speaker asks the listener to do something",
            "plan: a time or day agreed to meet or do something",
        )
        /** The same question with keys only (results/open-decision_b_chat_keys.meta.json). */
        val B_KEYS = Question.Choice("What is this sentence?", "nothing", "promise", "request", "plan")
    }
}
