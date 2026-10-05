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
 * Typed decisions with `litert-community/GLiNER2.5-Decide-LiteRT` (family `gliner2_decide`) on a named
 * device, through the public API and the development descriptor (catalog/dev, passed explicitly so the
 * run is offline; every file is imported from /data/local/tmp/hfmodels/gliner-decide, laid out like the
 * repo, and sha256-checked against the descriptor). In one process and one load:
 *   1. load (import, tokenizer, compile), the profile that came up and compile_ms;
 *   2. sequence: the publisher's captured gliner2 inputs (the card's device-gate set, the 42 requests listed
 *      for this window) vs this module's ids, `[P]` / `[L]` positions and routing;
 *   3. parity: each of those requests through the loaded graph vs the official fp32 result: per-task argmax,
 *      the official decisions (single-label tasks through this module's decode; the 11 multi-label tasks by
 *      the card's sigmoid rule here, the SDK has no multi-label question), max |dp| and |dlogit|;
 *   4. demo: the round-1 sieve's 30 chat sentences through `decide(text, question)` with its question, vs the
 *      Mac answers of the card's Python host (same answer n/30, max |dp|, warm ms per question);
 *   5. pack (when its fixture is there): the same 30 sentences with four questions, one forward per question
 *      (decide()) and the four packed into one gliner2 request (the `pack_questions` plan, run through the loaded
 *      graph), each form vs the Mac host's answers in that form, and how many answers the packing changes here;
 *   6. timing: `decide(state, 4 questions)` vs 4 x `decide(state, 1 question)`, and the four packed, warm;
 *   7. zoo_input: the Model Zoo Text row's sentence, question and count through decide(), each call split into its stages ([ZooInputBench]);
 *   8. lookup: the host embedding lookup of one request the Model Zoo's way and this module's way ([HostLookupBench]);
 *   9. release.
 * One RESULT line per step under tag `hfmodels-decide`, numbers unrounded. Arguments: variant (s128_wfp16 |
 * s256_wfp16 | s512_wfp16 | s128_npu_wfp16 | s256_npu_wfp16 | s512_fp16hw_wfp16 | s128_fp32), backend (gpu | cpu | npu | auto), dir, fixtures (default
 * `<dir>/fixtures`: the card's `gate_fixtures.json`, the sieve's `b_chat.jsonl` and `gliner-decide_b_chat_policy.jsonl`,
 * r11's `gliner-decide_b_chat_pack4.jsonl`), pack=0 (skips step 5), repeats (timing, default 5), dp_tol (default 0.01), and two changes to
 * the variant in the descriptor this run loads (the RESULT load line names them): gpu_precision (fp32 | fp16_with_fp32_accum
 * | fp16 | default) and npu_profile=1 (adds an npu profile to a variant that declares none, to measure that graph on the
 * NPU). An npu run needs the Qualcomm libraries in the test APK (tools/fetch_npu_libs.sh litert/src/androidTest/jniLibs/arm64-v8a
 * v81) and counts only with LiteRT's `Replacing 1 out of 1 node(s) with delegate (DispatchDelegate)` in the gate log.
 *
 *   GATE_TEST=GlinerDecideDeviceTest GATE_TAG=decide-gliner tools/decide_gate.sh s128_wfp16 gpu
 */
@RunWith(AndroidJUnit4::class)
class GlinerDecideDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val testCtx = InstrumentationRegistry.getInstrumentation().context
    private val args = InstrumentationRegistry.getArguments()
    private val variant = args.getString("variant") ?: "s128_wfp16"
    private val backend = args.getString("backend") ?: "gpu"
    private val repeats = args.getString("repeats")?.toInt() ?: 5
    private val dpTol = args.getString("dp_tol")?.toDouble() ?: 0.01
    private val gpuPrecision = args.getString("gpu_precision")
    private val npuProfile = args.getString("npu_profile") == "1"
    private val packStep = args.getString("pack") != "0"
    private val base = File(args.getString("dir") ?: "/data/local/tmp/hfmodels/gliner-decide")
    private val fixturesDir = File(args.getString("fixtures") ?: File(base, "fixtures").path)
    private val failures = ArrayList<String>()

    /** The descriptor with this run's changes to its variant: `gpu_precision` in handler_config, an npu profile copied from the gpu one. */
    private fun patched(text: String): Pair<String, String> {
        if (gpuPrecision == null && !npuProfile) return text to "none"
        val d = JSONObject(text)
        val variants = d.getJSONArray("variants")
        val done = ArrayList<String>()
        for (i in 0 until variants.length()) {
            val v = variants.getJSONObject(i)
            if (v.getString("id") != variant) continue
            if (gpuPrecision != null) { v.getJSONObject("handler_config").put("gpu_precision", gpuPrecision); done += "gpu_precision=$gpuPrecision" }
            val profiles = v.getJSONArray("profiles")
            if (npuProfile && (0 until profiles.length()).none { profiles.getJSONObject(it).getString("id") == "npu" }) {
                val gpu = (0 until profiles.length()).map { profiles.getJSONObject(it) }.first { it.getString("id") == "gpu" }
                profiles.put(JSONObject(gpu.toString()).put("id", "npu").put("priority", 120).put("components", JSONObject().put("inference", "npu")).put("fallback_profiles", org.json.JSONArray()))
                done += "npu_profile_added"
            }
        }
        return d.toString() to done.joinToString(",").ifEmpty { "none" }
    }

    private fun result(step: String, ok: Boolean, detail: String) {
        Log.i(TAG, "RESULT step=$step ok=$ok variant=$variant backend=$backend $detail")
        if (!ok) failures += "$step: $detail"
    }

    @Test fun loadSequenceParityDemoTimingRelease(): Unit = runBlocking {
        val models = HfModels(ctx)
        val policy = when (backend) { "cpu" -> BackendPolicy.Require(BackendKind.CPU); "gpu" -> BackendPolicy.Require(BackendKind.GPU); "npu" -> BackendPolicy.Require(BackendKind.NPU); else -> BackendPolicy.Auto }
        val (descriptor, patch) = patched(testCtx.assets.open(DESCRIPTOR_ASSET).bufferedReader().use { it.readText() })
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
                "import_ms=$importMs load_ms=$loadMs compile_ms=$compileMs profile=${m.info.profileId} window=${m.limits.windowTokens} head=${m.limits.headTokens} max_options=${m.limits.maxOptions} " +
                    "descriptor_patch=$patch npu_libs_ready=${LiteRtNpu.ready(ctx)} notes=${m.info.notes.joinToString(" | ")}")
            val impl = m as LiteRtDecisionModel
            val c = impl.contract as GlinerDecideContract
            val window = m.limits.windowTokens

            // 2. the publisher's captured inputs for this window
            val all = (Json.parseObject(File(fixturesDir, "gate_fixtures.json").readText())["fixtures"] as List<*>).map { it as Map<*, *> }
            val rows = all.filter { window in ints(it["windows"]) }
            var seqFail = 0
            for (f in rows) {
                val tasks = tasks(f)
                val textIds = c.stateIds(f["text"] as String)
                val e = c.encode(tasks, textIds)
                val fw = c.forward(tasks, textIds)
                val labels = ints(f["label_positions"])
                val routingOk = (0 until 32).all { row -> (0 until window).all { col -> fw.routing[row * window + col] == if (row < labels.size && labels[row] == col) 1f else 0f } }
                if (ints(f["input_ids"]) != e.ids.toList() || labels != e.labelPositions.toList() || (f["schema_special_indices"] as List<*>).map { ints(it) } != e.special.map { it.toList() } || !routingOk) {
                    seqFail++
                    Log.w(TAG, "${f["id"]}: ids ${ints(f["input_ids"]).size} vs ${e.ids.size}; labels $labels vs ${e.labelPositions.toList()}; routing ok $routingOk")
                }
            }
            result("sequence", rows.isNotEmpty() && seqFail == 0, "rows=${rows.size} mismatches=$seqFail window=$window")

            // 3. parity: each captured request through the loaded graph
            var argmaxRows = 0; var officialRows = 0; var maxDp = 0.0; var maxDl = 0.0; var maxDmac = 0.0; var worst = ""
            val forwardMs = ArrayList<Double>()
            impl.scores(c.forward(tasks(rows[0]), c.stateIds(rows[0]["text"] as String)))   // warm
            for (f in rows) {
                val tasks = tasks(f)
                val fw = c.forward(tasks, c.stateIds(f["text"] as String))
                val tf = SystemClock.elapsedRealtimeNanos()
                val logits = impl.scores(fw)
                forwardMs += (SystemClock.elapsedRealtimeNanos() - tf) / 1e6
                val oracle = floats(f["oracle_logits"])
                val oracleP = (f["oracle_probs"] as List<*>).map { (it as Number).toDouble() }
                val mac = ((f["mac_cpu_wfp16_fp16_logits"] as Map<*, *>?)?.get(window.toString()))?.let { floats(it) }
                val official = (f["official_result"] as List<*>).map { it as Map<*, *> }
                var argmaxOk = true; var officialOk = true; var offset = 0
                for ((i, t) in (f["tasks"] as List<*>).withIndex()) {
                    t as Map<*, *>
                    val labels = (t["labels"] as List<*>).map { it as String }
                    val k = labels.size
                    val got = logits.copyOfRange(offset, offset + k)
                    val ref = oracle.copyOfRange(offset, offset + k)
                    if (GlinerDecode.argmax(got) != GlinerDecode.argmax(ref)) argmaxOk = false
                    val expect = (official[i]["labels"] as List<*>).map { it as String }
                    val p: List<Double>
                    val chosen: List<String>
                    if (t["multi_label"] == true) {
                        // The card's DecideDecoder rule for multi-label heads (sigmoid, every label >= cls_threshold, else the argmax).
                        val s = FloatArray(k) { 1f / (1f + exp((-got[it]).toDouble()).toFloat()) }
                        p = s.map { it.toDouble() }
                        chosen = labels.indices.filter { s[it].toDouble() >= (t["cls_threshold"] as Number).toDouble() }.ifEmpty { listOf(GlinerDecode.argmax(s)) }.map { labels[it] }
                    } else {
                        val a = c.decode(Question.Choice(t["task"] as String, *labels.toTypedArray()), got, null) as Answer.Choice
                        p = labels.map { a.probabilities.getValue(it) }
                        chosen = listOf(a.choice)
                    }
                    if (chosen != expect) officialOk = false
                    val dp = (0 until k).maxOf { abs(p[it] - oracleP[offset + it]) }
                    if (dp > maxDp) { maxDp = dp; worst = f["id"] as String }
                    maxDl = maxOf(maxDl, (0 until k).maxOf { abs(got[it] - ref[it]).toDouble() })
                    if (mac != null) maxDmac = maxOf(maxDmac, (0 until k).maxOf { abs(got[it] - mac[offset + it]).toDouble() })
                    offset += k
                }
                if (argmaxOk) argmaxRows++ else Log.w(TAG, "${f["id"]}: argmax differs; device ${logits.copyOf(offset).toList()} oracle ${oracle.toList()}")
                if (officialOk) officialRows++ else Log.w(TAG, "${f["id"]}: decisions differ from the official result")
            }
            val fs = forwardMs.sorted()
            result("parity", argmaxRows == rows.size && officialRows == rows.size && maxDp <= dpTol,
                "rows=${rows.size} argmax_match=$argmaxRows/${rows.size} decisions_equal_official=$officialRows/${rows.size} max_prob_abs_err=$maxDp (worst $worst) max_logit_abs_err_vs_oracle=$maxDl max_logit_abs_err_vs_mac_cpu=$maxDmac dp_tol=$dpTol " +
                    "forward_ms_median=${fs[fs.size / 2]} p90=${fs[(fs.size * 9) / 10]} min=${fs.first()} max=${fs.last()} " +
                    "first10_median=${median(forwardMs.take(10))} last10_median=${median(forwardMs.takeLast(10))}")

            // 4. the sieve's chat sentences through decide(), vs the Mac answers of the card's host
            val chat = File(fixturesDir, "b_chat.jsonl").readLines().filter { it.isNotBlank() }.map { Json.parseObject(it) }
            val macRows = File(fixturesDir, "gliner-decide_b_chat_policy.jsonl").readLines().filter { it.isNotBlank() }.map { Json.parseObject(it) }.associateBy { it["id"] as String }
            repeat(2) { m.decide(chat[0]["text"] as String, mapOf("q" to B_MAIN)) }   // warm
            var same = 0; var demoDp = 0.0; var correctDevice = 0; var correctMac = 0; var demoWorst = ""
            val ms = ArrayList<Double>(); val lookup = ArrayList<Double>()
            for (r in chat) {
                val d = m.decide(r["text"] as String, mapOf("q" to B_MAIN))
                ms += d.timing.questionMs[0]; lookup += impl.lastLookupMs
                val a = d.answers.getValue("q") as Answer.Choice
                val macRow = macRows.getValue(r["id"] as String)
                val macP = (macRow["probabilities"] as Map<*, *>).entries.associate { (k, v) -> k.toString() to (v as Number).toDouble() }
                if (a.choice == macRow["answer"]) same++ else Log.w(TAG, "${r["id"]}: ${a.choice} ${a.probabilities} vs mac ${macRow["answer"]} $macP")
                val dp = macP.keys.maxOf { abs(a.probabilities.getValue(it) - macP.getValue(it)) }
                if (dp > demoDp) { demoDp = dp; demoWorst = r["id"] as String }
                if (a.choice == r["label"]) correctDevice++
                if (macRow["answer"] == r["label"]) correctMac++
            }
            val sm = ms.sorted(); val lk = lookup.sorted()
            result("demo", same == chat.size,
                "rows=${chat.size} same_answer_as_mac=$same/${chat.size} max_prob_abs_err_vs_mac=$demoDp (worst $demoWorst) correct_device=$correctDevice/${chat.size} correct_mac=$correctMac/${chat.size} " +
                    "question_ms_median=${sm[sm.size / 2]} p90=${sm[(sm.size * 9) / 10]} min=${sm.first()} max=${sm.last()} lookup_ms_median=${lk[lk.size / 2]} " +
                    "first10_median=${median(ms.take(10))} last10_median=${median(ms.takeLast(10))}")

            // 5. the same sentences with four questions: one forward per question, and the four packed into one request
            val packed = c.packing(true)
            val packRows = File(fixturesDir, PACK_FIXTURE).takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }?.map { Json.parseObject(it) }?.associateBy { it["id"] as String }
            if (!packStep) result("pack", true, "skipped: pack=0")
            else if (packRows == null) result("pack", true, "skipped: ${File(fixturesDir, PACK_FIXTURE).path} not pushed")
            else {
                // The packed form through the loaded graph: decide()'s own loop with the packing plan.
                val decidePacked: (String) -> io.github.johnrocky.hfmodels.decide.Decisions = { text ->
                    val t0 = System.nanoTime()
                    val ids = packed.stateIds(text)
                    DecisionRun.decide(packed, ids, PACK4, REPO, (System.nanoTime() - t0) / 1e6, t0) { f -> impl.scores(f) to null }
                }
                repeat(2) { m.decide(chat[0]["text"] as String, PACK4); decidePacked(chat[0]["text"] as String) }   // warm
                var sameSingle = 0; var samePacked = 0; var changedHere = 0; var changedMac = 0; var n = 0
                var dpSingle = 0.0; var dpPacked = 0.0; var dpForms = 0.0; var forwards = 0; var worst = ""
                val singleMs = ArrayList<Double>(); val packedMs = ArrayList<Double>()
                for (r in chat) {
                    val id = r["id"] as String
                    val text = r["text"] as String
                    val mac = packRows.getValue(id)
                    val ds = m.decide(text, PACK4)
                    val dpk = decidePacked(text)
                    singleMs += ds.timing.totalMs; packedMs += dpk.timing.totalMs
                    forwards = maxOf(forwards, packed.plan(PACK4, packed.stateIds(text)).size)
                    for ((qid, q) in PACK4) {
                        val a = ds.answers.getValue(qid); val b = dpk.answers.getValue(qid)
                        val ms = (mac["single"] as Map<*, *>)[qid] as Map<*, *>; val mp = (mac["packed"] as Map<*, *>)[qid] as Map<*, *>
                        val pa = probabilities(q, a); val pb = probabilities(q, b)
                        val pms = macProbabilities(q, ms)
                        val pmp = macProbabilities(q, mp)
                        if (argmax(pa) == (ms["argmax"] as Number).toInt()) sameSingle++ else Log.w(TAG, "pack $id/$qid single: $pa vs mac $pms")
                        if (argmax(pb) == (mp["argmax"] as Number).toInt()) samePacked++ else Log.w(TAG, "pack $id/$qid packed: $pb vs mac $pmp")
                        if (argmax(pa) != argmax(pb)) changedHere++
                        if ((ms["argmax"] as Number).toInt() != (mp["argmax"] as Number).toInt()) changedMac++
                        dpSingle = maxOf(dpSingle, pa.indices.maxOf { abs(pa[it] - pms[it]) })
                        val d = pb.indices.maxOf { abs(pb[it] - pmp[it]) }
                        if (d > dpPacked) { dpPacked = d; worst = "$id/$qid" }
                        dpForms = maxOf(dpForms, pa.indices.maxOf { abs(pa[it] - pb[it]) })
                        n++
                    }
                }
                val ss = singleMs.sorted(); val pk = packedMs.sorted()
                result("pack", sameSingle == n && samePacked == n && dpSingle <= dpTol && dpPacked <= dpTol,
                    "rows=${chat.size} questions=${PACK4.keys.joinToString(",")} single_same_as_mac=$sameSingle/$n packed_same_as_mac=$samePacked/$n max_prob_abs_err_single_vs_mac=$dpSingle max_prob_abs_err_packed_vs_mac=$dpPacked (worst $worst) " +
                        "answers_changed_by_packing_here=$changedHere/$n answers_changed_by_packing_mac=$changedMac/$n max_prob_abs_diff_single_vs_packed_here=$dpForms packed_forwards_max=$forwards " +
                        "decide_4q_single_total_ms_median=${ss[ss.size / 2]} decide_4q_packed_total_ms_median=${pk[pk.size / 2]} packed_p90=${pk[(pk.size * 9) / 10]}")
            }

            // 6. timing: four questions about one state, batched vs one by one, and packed
            val state = "Duplicate charge. I was charged twice for order 4471 and would like the second charge refunded."
            val qs = linkedMapOf(
                "department" to Question.Choice("Which department should handle this request?", linkedMapOf("billing" to "invoices, payments, refunds", "technical" to "bugs, outages", "other" to "everything else")),
                "urgency" to Question.Score("How urgent is this request?", listOf("not urgent", "soon", "critical deadline or blocking issue")),
                "refund_requested" to Question.Noul("Does the user explicitly request a refund?"),
                "tone" to Question.Choice("What is the tone of the message?", linkedMapOf("polite" to "calm and polite", "angry" to "upset or angry", "neutral" to "neutral")),
            )
            val stateIdsPacked = packed.stateIds(state)
            val runPacked = { val t0 = System.nanoTime(); DecisionRun.decide(packed, packed.stateIds(state), qs, REPO, 0.0, t0) { f -> impl.scores(f) to null } }
            val batched = ArrayList<Double>(); val single = ArrayList<Double>(); val packedTotal = ArrayList<Double>()
            m.decide(state, qs); runPacked()   // warm
            for (i in 0 until repeats) {
                batched += m.decide(state, qs).timing.totalMs
                val t = SystemClock.elapsedRealtimeNanos()
                for ((k, q) in qs) m.decide(state, mapOf(k to q))
                single += (SystemClock.elapsedRealtimeNanos() - t) / 1e6
                packedTotal += runPacked().timing.totalMs
            }
            val last = m.decide(state, qs)
            val lastPacked = runPacked()
            val show: (io.github.johnrocky.hfmodels.decide.Decisions) -> String = { d -> d.answers.mapValues { (_, a) -> when (a) { is Answer.Choice -> a.choice + " " + a.probabilities.getValue(a.choice); is Answer.Score -> a.score.toString(); is Answer.Noul -> a.noul.toString() } }.toString() }
            result("timing", true, "repeats=$repeats decide_4q_total_ms_median=${batched.sorted()[batched.size / 2]} four_decide_1q_total_ms_median=${single.sorted()[single.size / 2]} " +
                "decide_4q_packed_total_ms_median=${packedTotal.sorted()[packedTotal.size / 2]} packed_forwards=${packed.plan(qs, stateIdsPacked).size} state_tokens=${last.stateTokens} per_question_ms=${last.timing.questionMs.joinToString(",")} " +
                "answers=${show(last)} answers_packed=${show(lastPacked)}")

            // 7. the Model Zoo Text row's input and count, each decide() split into its stages (the Zoo-vs-SDK comparison)
            result("zoo_input", true, ZooInputBench.run(m, impl))

            // 8. the host lookup of one request, the Model Zoo's way and this module's way (the same padded ids, the same table)
            val fw0 = c.forward("q", B_MAIN, c.stateIds(chat[0]["text"] as String))
            val padded = IntArray(window) { if (it < fw0.ids.size) fw0.ids[it] else c.padId }
            result("lookup", true, HostLookupBench.run(File(m.info.files.getValue("table")), GlinerDecideContract.HIDDEN, padded, "lut"))

            // 9. release
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

    /** The median the other fields use (the upper one of an even count), of the values in run order: the first and the last ten of a step show the heat inside it. */
    private fun median(v: List<Double>): Double = v.sorted()[v.size / 2]

    /** An answer's probabilities in label order: a choice's keys, a score's levels, a noul's false / true side. */
    private fun probabilities(q: Question, a: Answer): List<Double> = when (a) {
        is Answer.Choice -> (q as Question.Choice).criteria.keys.map { a.probabilities.getValue(it) }
        is Answer.Score -> (q as Question.Score).criteria.indices.map { a.probabilities.getValue(it.toString()) }
        is Answer.Noul -> listOf(1.0 - a.noul, a.noul)
    }

    /** The Mac host's probabilities of one question in label order (r11's fixture keys: a choice's keys, a score's "0", "1", …, a noul's "no" / "yes"). */
    private fun macProbabilities(q: Question, m: Map<*, *>): List<Double> {
        val p = m["probabilities"] as Map<*, *>
        val keys = when (q) { is Question.Choice -> q.criteria.keys.toList(); is Question.Score -> q.criteria.indices.map { it.toString() }; is Question.Noul -> listOf("no", "yes") }
        return keys.map { k -> (p[k] as? Number ?: throw IllegalStateException("$PACK_FIXTURE: no probability for '$k'")).toDouble() }
    }

    /** First index of the maximum. */
    private fun argmax(p: List<Double>): Int { var b = 0; for (i in 1 until p.size) if (p[i] > p[b]) b = i; return b }

    private fun floats(v: Any?): FloatArray = (v as List<*>).map { (it as Number).toFloat() }.toFloatArray()

    /** The request's tasks dict as the card's gate asset keeps it. */
    private fun tasks(f: Map<*, *>): List<GlinerTask> = (f["tasks"] as List<*>).map { t ->
        t as Map<*, *>
        val descriptions = (t["label_descriptions"] as List<*>?)?.let { pairs -> LinkedHashMap<String, String>().also { m -> for (p in pairs) { p as List<*>; m[p[0] as String] = p[1] as String } } }
        GlinerTask(t["task"] as String, (t["labels"] as List<*>).map { it as String }, t["prompt"] as String?, descriptions)
    }

    companion object {
        const val TAG = "hfmodels-decide"
        const val REPO = "litert-community/GLiNER2.5-Decide-LiteRT"
        /** The development descriptor (catalog/dev, an androidTest asset): its revision and files drive the load. */
        const val DESCRIPTOR_ASSET = "litert-community__GLiNER2.5-Decide-LiteRT.hfmodels.json"
        /** The round-1 sieve's question B (results/gliner-decide_b_chat_policy.meta.json). */
        val B_MAIN = Question.Choice("What is this sentence?", linkedMapOf(
            "nothing" to "an opinion, a story, a vague maybe, or something happening right now",
            "promise" to "the speaker commits to do something later",
            "request" to "the speaker asks the listener to do something",
            "plan" to "a time or day agreed to meet or do something",
        ))
        /** r11's four questions about one chat sentence (r11/pack4_questions.json): question B and three short ones, sized to fit one 128-token request together. */
        val PACK4: Map<String, Question> = linkedMapOf(
            "kind" to B_MAIN,
            "who" to Question.Choice("Who acts?", linkedMapOf("speaker" to "the speaker", "listener" to "the listener", "nobody" to "nobody")),
            "urgency" to Question.Score("How urgent?", listOf("not urgent", "urgent")),
            "time" to Question.Noul("A day or time?"),
        )
        /** The Mac host's answers to [PACK4] in both forms (r11/pack_score.py). */
        const val PACK_FIXTURE = "gliner-decide_b_chat_pack4.jsonl"
    }
}
