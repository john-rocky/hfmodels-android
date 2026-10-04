package io.github.johnrocky.hfmodels.litert

import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.decide.Answer
import io.github.johnrocky.hfmodels.decide.DecisionLimits
import io.github.johnrocky.hfmodels.decide.Json
import io.github.johnrocky.hfmodels.decide.Question
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Host-side parity with the publisher of Open-Decision-DeBERTa-v3-Large-LiteRT (its Python and Android hosts and the
 * author's implementation the conversion captured), without a model:
 *  (a) the Unigram tokenizer (the source DeBERTa-v3 normalizer) against Python `tokenizers` on the card's edge strings;
 *  (b) the ids, question spans and option spans of every captured request (the author's Collator, all questions in
 *      one sequence) and its smallest window, and the padded routing inputs at that window;
 *  (c) the author's answers from the captured logits (the card's decoder cases and its device-gate set), through this
 *      module's decode;
 *  (d) the float16 table: sampled values and special bit patterns as numpy widens them;
 *  (e) decide() form: the round-1 sieve's 30 chat sentences in six question forms, built from the Question alone, against
 *      the card's Python host (options, ids, spans), the host's logits decoded against its probabilities, the round-1
 *      Mac answers ("key: description" options: r1's policy form; keys), and the same questions packed into one request.
 *
 * Needs, under one root (not in this repository): from the repo litert-community/Open-Decision-DeBERTa-v3-Large-LiteRT
 * at 7a276235 `tokenizer.json`, `word_embeddings_fp16.bin` and `android/`; the conversion run's `fixtures/requests.json`,
 * `oracle.json` and `app_gate_fixtures.json` (1,809 captured Collator requests and the 160-request device-gate set; not
 * published); the round-1 files `r1/open-decision_b_chat_policy.jsonl`, `r1/open-decision_b_chat_keys.jsonl`; and
 * `r4/decide_form.json` (make_decide_form_fixtures.py, the card's host):
 *   ./gradlew :litert:testDebugUnitTest -Dhfmodels.debertaDecisionRoot=<dir>   (or HFMODELS_DEBERTA_DECISION_ROOT)
 * Skipped when the property is empty; a set root with a missing file fails.
 */
class DebertaDecisionParityTest {
    private val root = System.getProperty("hfmodels.debertaDecisionRoot")?.takeIf { it.isNotBlank() }?.let { File(it) }

    private fun file(path: String): File {
        assumeTrue("set -Dhfmodels.debertaDecisionRoot=<Open-Decision-DeBERTa-v3-Large-LiteRT files> (HFMODELS_DEBERTA_DECISION_ROOT)", root != null)
        val f = File(root, path)
        assertTrue("missing $f under the parity root", f.isFile)
        return f
    }

    private val tokenizer by lazy { UnigramTokenizer.load(file("tokenizer.json")) }

    private fun contract(window: Int) = DebertaDecisionContract(tokenizer, DecisionLimits(window, window, 128, listOf("en")), 128, 256, 1.05)

    private fun ints(v: Any?): List<Int> = (v as List<*>).map { (it as Number).toInt() }

    private fun floats(v: Any?): FloatArray = (v as List<*>).map { (it as Number).toFloat() }.toFloatArray()

    private fun spans(v: Any?): List<List<Int>> = (v as List<*>).map { ints(it) }

    private fun IntRange.pair(): List<Int> = listOf(first, last + 1)

    private fun records(path: String, key: String): List<Map<*, *>> = (Json.parseObject(file(path).readText())[key] as List<*>).map { it as Map<*, *> }

    /** An author-form question (`type`, `instructions`, `options`) as the Question whose family-rule options are the same strings. */
    private fun question(o: Map<*, *>): Question {
        val instructions = o["instructions"] as String
        val options = (o["options"] as List<*>?)?.map { it as String }
        return when (o["type"]) {
            "choice" -> Question.Choice(instructions, *options!!.toTypedArray())
            "score" -> Question.Score(instructions, options!!)
            else -> Question.Noul(instructions)
        }
    }

    @Test fun tokenizerProbesMatchPythonTokenizers() {
        val probes = records("android/sample/app/src/test/resources/tokenizer_probes.json", "probes")
        var failures = 0
        for (p in probes) {
            val got = tokenizer.encode(p["text"] as String).toList()
            if (got != ints(p["ids"])) { failures++; println("probe ${Json.dumps(p["text"])}: expected ${p["ids"]} got $got") }
        }
        // 128,000 pieces and the added tokens up to [OPT]; the word table has 128,100 rows (the checkpoint's padded embedding).
        assertEquals(128004, tokenizer.vocabularySize)
        assertEquals(0, tokenizer.padId)
        assertEquals(listOf(1, 2, 128001, 128002, 128003), listOf("[CLS]", "[SEP]", "[STATE]", "[Q]", "[OPT]").map { tokenizer.addedTokenId(it) })
        assertEquals("tokenizer mismatches (see stdout)", 0, failures)
        println("(a) tokenizer: ${probes.size}/${probes.size} probes identical to tokenizers' encode(text, add_special_tokens=False)")
    }

    @Test fun sequencesMatchTheCollator() {
        val requests = records("fixtures/requests.json", "requests").associateBy { it["id"] as String }
        val oracle = records("fixtures/oracle.json", "records")
        val c256 = contract(256); val c512 = contract(512)
        var failures = 0; var at256 = 0; var routed = 0
        for (o in oracle) {
            val r = requests.getValue(o["id"] as String)
            val questions = (r["questions"] as List<*>).map { question(it as Map<*, *>) }
            val stateIds = c512.stateIds(r["state"] as String)
            val e = c512.encode(questions, stateIds)
            var ok = e.ids.toList() == ints(o["ids"]) && e.questionSpans.map { it.pair() } == spans(o["q_spans"]) &&
                e.optionSpans.map { g -> g.map { it.pair() } } == (o["opt_spans"] as List<*>).map { spans(it) }
            val fits256 = (o["fits"] as Map<*, *>)["s256"] == true
            val f256 = runCatching { c256.forward(questions, stateIds) }
            if (fits256) { if (f256.isFailure) ok = false else at256++ } else if ((f256.exceptionOrNull() as? ModelException)?.code != ErrorCode.CONTEXT_LIMIT_EXCEEDED) ok = false
            // The routing inputs at the smallest window: row j is 1/len over option j's question text, and over option j's text.
            val w = if (fits256) 256 else 512
            val f = (if (fits256) f256.getOrNull() else c512.forward(questions, stateIds))
            if (f != null) {
                val qExpect = FloatArray(128 * w); val oExpect = FloatArray(128 * w)
                var slot = 0
                for ((qs, group) in spans(o["q_spans"]).zip((o["opt_spans"] as List<*>).map { spans(it) })) {
                    for (os in group) {
                        if (qs[1] > qs[0]) for (t in qs[0] until qs[1]) qExpect[slot * w + t] = 1f / (qs[1] - qs[0])
                        if (os[1] > os[0]) for (t in os[0] until os[1]) oExpect[slot * w + t] = 1f / (os[1] - os[0])
                        slot++
                    }
                }
                if (!f.routing.contentEquals(qExpect) || f.extraInputs.size != 1 || !f.extraInputs[0].contentEquals(oExpect) || f.reads.size != slot) ok = false
                routed++
            }
            if (!ok) { failures++; println("${o["id"]}: ids / spans / window / routing differ") }
        }
        assertEquals(1809, oracle.size)
        assertEquals("sequence mismatches out of ${oracle.size} (see stdout)", 0, failures)
        println("(b) sequences: ${oracle.size}/${oracle.size} requests with the Collator's ids, question spans, option spans and smallest window ($at256 at 256, ${oracle.size - at256} at 512); $routed padded q_routing / o_routing pairs identical")
    }

    @Test fun capturedLogitsDecodeToTheAuthorsAnswers() {
        var checked = 0; var maxDp = 0.0; var failures = 0
        val cases = records("android/sample/app/src/test/resources/decoder_cases.json", "cases") + records("fixtures/app_gate_fixtures.json", "requests")
        for (c in cases) {
            val questions = (c["questions"] as List<*>).map { question(it as Map<*, *>) }
            for ((i, q) in questions.withIndex()) {
                val logits = floats((c["logits"] as List<*>)[i])
                val api = (c["api"] as List<*>)[i] as Map<*, *>
                val a = DebertaDecisionDecode.answer(q, logits, 1.05)
                val (ok, dp) = when (a) {
                    is Answer.Choice -> {
                        val ref = api["probabilities"] as Map<*, *>
                        val dp = ref.entries.maxOf { (k, v) -> abs(a.probabilities.getValue(k as String) - (v as Number).toDouble()) }
                        (a.choice == api["choice"] && abs((a.extras["max_probability"] as Double) - (api["confidence"] as Number).toDouble()) < 1e-6) to dp
                    }
                    is Answer.Score -> {
                        val ref = (api["probabilities"] as Map<*, *>).values.map { (it as Number).toDouble() }
                        (abs(a.score - (api["score"] as Number).toDouble()) < 1e-5) to ref.indices.maxOf { abs(a.probabilities.getValue(it.toString()) - ref[it]) }
                    }
                    is Answer.Noul -> (abs(a.noul - (api["noul"] as Number).toDouble()) < 1e-6) to abs(a.noul - (api["noul"] as Number).toDouble())
                }
                maxDp = maxOf(maxDp, dp)
                checked++
                if (!ok) { failures++; println("${c["id"]} q$i: $a vs the author's $api") }
            }
        }
        assertEquals("decode mismatches out of $checked (see stdout)", 0, failures)
        println("(c) decode: $checked/$checked questions (${cases.size} requests: the card's decoder cases and device-gate set) give the author's answer; max |dp| vs the author's float32 probabilities $maxDp")
    }

    @Test fun tableValuesWidenLikeNumpy() {
        val rows = Json.parseObject(file("android/sample/app/src/test/resources/embedding_rows_fp16.json").readText())
        val table = file("word_embeddings_fp16.bin")
        var checked = 0
        RandomAccessFile(table, "r").use { raw ->
            TokenTable.open(table, 1024, TokenTable.Dtype.FLOAT16).use { t ->
                assertEquals(128100, t.rows)
                val row = FloatArray(1024)
                for (s in rows["samples"] as List<*>) {
                    s as Map<*, *>
                    val r = (s["row"] as Number).toInt(); val c = (s["col"] as Number).toInt()
                    raw.seek((r.toLong() * 1024 + c) * 2)
                    val lo = raw.read(); val hi = raw.read()
                    assertEquals("row $r col $c bits", (s["bits"] as Number).toInt(), lo or (hi shl 8))
                    t.copyRow(r, row, 0)
                    assertEquals("row $r col $c", (s["float_bits"] as Number).toLong().toInt(), row[c].toRawBits())
                    checked++
                }
            }
        }
        val specials = rows["special_patterns"] as List<*>
        for (p in specials) {
            p as Map<*, *>
            val got = TokenTable.halfToFloat((p["bits"] as Number).toInt())
            if (p["is_nan"] == true) assertTrue(got.isNaN()) else assertEquals((p["float_bits"] as Number).toLong().toInt(), got.toRawBits())
        }
        println("(d) table: $checked sampled values bit-identical to numpy's float16 -> float32, ${specials.size} special bit patterns")
    }

    @Test fun decideFormMatchesTheCardHostAndRound1() {
        val fixture = Json.parseObject(file("r4/decide_form.json").readText())
        val requests = (fixture["requests"] as List<*>).map { it as Map<*, *> }
        val c = contract(256)
        var failures = 0; var maxDp = 0.0
        val sdk = HashMap<Pair<String, String>, Answer>()
        for (r in requests) {
            val q = Question.fromMap(r["question"] as Map<*, *>)
            val stateIds = c.stateIds(r["text"] as String)
            val f = c.forward("q", q, stateIds)
            val e = c.encode(listOf(q), stateIds)
            var ok = DebertaDecisionContract.options(q) == r["options"] && f.ids.toList() == ints(r["ids"]) &&
                e.questionSpans.map { it.pair() } == spans(r["q_spans"]) && e.optionSpans.map { g -> g.map { it.pair() } } == (r["opt_spans"] as List<*>).map { spans(it) }
            val a = c.decode(q, floats(r["logits"]), null)
            sdk[r["id"] as String to r["form"] as String] = a
            val hostP = (r["probabilities"] as List<*>).map { (it as Number).toDouble() }
            val p = when (a) { is Answer.Choice -> a.probabilities.values.toList(); is Answer.Score -> a.probabilities.values.toList(); is Answer.Noul -> listOf(1.0 - a.noul, a.noul) }
            val dp = hostP.indices.maxOf { abs(p[it] - hostP[it]) }
            maxDp = maxOf(maxDp, dp)
            ok = ok && dp <= 1e-12 && when (a) {
                is Answer.Choice -> a.choice == r["choice"]
                is Answer.Score -> abs(a.score - (r["score"] as Number).toDouble()) <= 1e-12
                is Answer.Noul -> abs(a.noul - (r["noul"] as Number).toDouble()) <= 1e-12
            }
            if (!ok) { failures++; println("${r["id"]} ${r["form"]}: differs from the card host (max |dp| $dp)") }
        }
        assertEquals("decide()-form mismatches out of ${requests.size} (see stdout)", 0, failures)
        val forms = requests.groupingBy { it["form"] as String }.eachCount()
        println("(e) decide() form: ${requests.size}/${requests.size} requests ($forms) with the card host's options, ids and spans from the Question alone; decode vs the host's probabilities max |dp| $maxDp")
        // The round-1 Mac answers: r1's policy form is options "key: description" (the keys of the SDK form 'policy'), its keys form the keys.
        for ((r1Form, form) in listOf("policy" to "policy", "keys" to "keys")) {
            val r1 = file("r1/open-decision_b_chat_$r1Form.jsonl").readLines().filter { it.isNotBlank() }.map { Json.parseObject(it) }
            var same = 0; var dp = 0.0
            for (row in r1) {
                val a = sdk.getValue(row["id"] as String to form) as Answer.Choice
                val shortKeys = (row["probabilities"] as Map<*, *>).keys.map { it as String }
                val sdkByShort = shortKeys.zip(a.probabilities.values).toMap()
                if (shortKeys[a.probabilities.keys.indexOf(a.choice)] == row["answer"]) same++
                dp = maxOf(dp, (row["probabilities"] as Map<*, *>).entries.maxOf { (k, v) -> abs(sdkByShort.getValue(k as String) - (v as Number).toDouble()) })
            }
            assertEquals("r1 $r1Form answers", r1.size, same)
            println("(e) round 1 $r1Form (SDK form '$form'): same answer $same/${r1.size}, max |dp| $dp")
        }
        // The author's form: the six questions of a row in one request.
        val packed = (fixture["packed"] as List<*>).map { it as Map<*, *> }
        var packedOk = 0; var packedDp = 0.0
        for (r in packed) {
            val questions = (r["questions"] as List<*>).map { Question.fromMap(it as Map<*, *>) }
            val e = c.encode(questions, c.stateIds(r["text"] as String))
            val f = c.forward(questions, c.stateIds(r["text"] as String))
            var ok = e.ids.toList() == ints(r["ids"]) && e.questionSpans.map { it.pair() } == spans(r["q_spans"]) && f.reads.size == questions.sumOf { DebertaDecisionContract.options(it).size }
            for ((i, q) in questions.withIndex()) {
                val ref = (r["probabilities"] as List<*>)[i] as List<*>
                val p = DebertaDecisionDecode.probabilities(floats((r["logits"] as List<*>)[i]), 1.05)
                val dp = p.indices.maxOf { abs(p[it] - (ref[it] as Number).toDouble()) }
                packedDp = maxOf(packedDp, dp)
                if (dp > 1e-12) ok = false
                if (DebertaDecisionContract.options(q) != ((r["author_questions"] as List<*>)[i] as Map<*, *>)["options"] ?: DebertaDecisionContract.NOUL_OPTIONS) ok = false
            }
            if (ok) packedOk++ else println("packed ${r["id"]}: differs")
        }
        assertEquals(packed.size, packedOk)
        println("(e) packed: ${packed.size}/${packed.size} rows' six questions in one request with the card host's ids and spans; decode max |dp| $packedDp; one question per forward vs packed in the host: max |dp| ${fixture["single_vs_packed_max_abs_dp"]}, answers changed ${fixture["single_vs_packed_answer_changes"]}")
    }

    /** The routing a packed forward must carry: row j is 1/len over option j's question text, and over option j's text. */
    private fun expectedRouting(qSpans: List<List<Int>>, optSpans: List<List<List<Int>>>, w: Int): Pair<FloatArray, FloatArray> {
        val q = FloatArray(128 * w); val o = FloatArray(128 * w)
        var slot = 0
        for ((qs, group) in qSpans.zip(optSpans)) for (os in group) {
            if (qs[1] > qs[0]) for (t in qs[0] until qs[1]) q[slot * w + t] = 1f / (qs[1] - qs[0])
            if (os[1] > os[0]) for (t in os[0] until os[1]) o[slot * w + t] = 1f / (os[1] - os[0])
            slot++
        }
        return q to o
    }

    private fun questionMap(qs: List<Question>) = LinkedHashMap<String, Question>().also { m -> qs.forEachIndexed { i, q -> m["q$i"] = q } }

    /** decide()'s path ([DecisionRun] over the plan) on the author's captured requests: every request in one forward, the captured logits sliced per question and decoded. */
    @Test fun packedDecideGivesTheAuthorsAnswers() {
        val requests = records("android/sample/app/src/test/resources/decoder_cases.json", "cases") + records("fixtures/app_gate_fixtures.json", "requests")
        var questionsSeen = 0; var same = 0; var maxDp = 0.0; var idsChecked = 0; var fit256 = 0; var split256 = 0; var refused256 = 0
        val perWindow = linkedMapOf(512 to IntArray(3), 256 to IntArray(3))   // requests in one forward, questions, same answers
        for ((c, w) in listOf(contract(512) to 512, contract(256) to 256)) {
            for (r in requests) {
                val qs = (r["questions"] as List<*>).map { question(it as Map<*, *>) }
                val map = questionMap(qs)
                val stateIds = c.stateIds(r["state"] as String)
                val plan = try { c.plan(map, stateIds) } catch (e: ModelException) {
                    // A question that does not fit 256 tokens even alone (a long state takes 256): refused, as the author's 256 graph would.
                    assertEquals(256, w); assertEquals(ErrorCode.CONTEXT_LIMIT_EXCEEDED, e.code)
                    (r["ids"] as List<*>?)?.let { assertTrue(it.size > 256) }
                    refused256++
                    continue
                }
                if (w == 256 && plan.size > 1) {
                    // Too long for one 256-token forward: split in question order, every question once, each forward within the window and the slots.
                    assertEquals(map.keys.toList(), plan.flatMap { b -> b.questions.map { it.id } })
                    for (b in plan) {
                        assertTrue(b.forward.ids.size <= 256 && b.forward.reads.size <= 128)
                        assertEquals((0 until b.forward.reads.size).toList(), b.questions.flatMap { it.scores.toList() })
                    }
                    split256++
                    continue
                }
                assertEquals("${r["id"]} at $w: one forward", 1, plan.size)
                if (w == 256) fit256++
                val f = plan.single().forward
                (r["ids"] as List<*>?)?.let { ids ->
                    assertEquals("${r["id"]} at $w: ids", ints(ids), f.ids.toList())
                    val (q, o) = expectedRouting(spans(r["q_spans"]), (r["opt_spans"] as List<*>).map { spans(it) }, w)
                    assertTrue("${r["id"]} at $w: routing", f.routing.contentEquals(q) && f.extraInputs.single().contentEquals(o))
                    idsChecked++
                }
                val logits = (r["logits"] as List<*>).flatMap { (it as List<*>).map { v -> (v as Number).toFloat() } }.toFloatArray()
                val d = DecisionRun.decide(c, stateIds, map, "m", 0.0, System.nanoTime()) { fw -> assertEquals(f.ids.toList(), fw.ids.toList()); logits to null }
                perWindow.getValue(w)[0]++
                assertEquals(1, d.timing.questionMs.toSet().size)
                for ((i, a) in d.answers.values.withIndex()) {
                    val api = (r["api"] as List<*>)[i] as Map<*, *>
                    val (ok, dp) = when (a) {
                        is Answer.Choice -> (a.choice == api["choice"]) to (api["probabilities"] as Map<*, *>).entries.maxOf { (k, v) -> abs(a.probabilities.getValue(k as String) - (v as Number).toDouble()) }
                        is Answer.Score -> (abs(a.score - (api["score"] as Number).toDouble()) < 1e-5) to (api["probabilities"] as Map<*, *>).values.withIndex().maxOf { (k, v) -> abs(a.probabilities.getValue(k.toString()) - (v as Number).toDouble()) }
                        is Answer.Noul -> (abs(a.noul - (api["noul"] as Number).toDouble()) < 1e-6) to abs(a.noul - (api["noul"] as Number).toDouble())
                    }
                    questionsSeen++; perWindow.getValue(w)[1]++
                    if (ok) { same++; perWindow.getValue(w)[2]++ } else println("${r["id"]} at $w q$i: $a vs the author's $api")
                    maxDp = maxOf(maxDp, dp)
                }
            }
        }
        assertEquals("packed decide() answers", questionsSeen, same)
        val (r512, q512, s512) = perWindow.getValue(512).toList(); val (r256, q256, s256) = perWindow.getValue(256).toList()
        println("packed decide(): at 512 $r512/${requests.size} requests in one forward, $s512/$q512 questions give the author's answer; at 256 $r256 in one forward ($s256/$q256 questions the author's answer), $split256 split in question order, $refused256 refused (a question does not fit alone); max |dp| $maxDp; $idsChecked forwards with the Collator's ids and routing")
    }

    /** The round-1 sentences' six questions through decide()'s plan: one forward with the card host's packed ids and routing, its logits decoded to the host's probabilities. */
    @Test fun packedPlanMatchesTheHostRequest() {
        val fixture = Json.parseObject(file("r4/decide_form.json").readText())
        val packed = (fixture["packed"] as List<*>).map { it as Map<*, *> }
        val c = contract(256)
        var ok = 0; var maxDp = 0.0
        for (r in packed) {
            val qs = (r["questions"] as List<*>).map { Question.fromMap(it as Map<*, *>) }
            val stateIds = c.stateIds(r["text"] as String)
            val plan = c.plan(questionMap(qs), stateIds)
            val f = plan.single().forward
            val (q, o) = expectedRouting(spans(r["q_spans"]), (r["opt_spans"] as List<*>).map { spans(it) }, 256)
            val logits = (r["logits"] as List<*>).flatMap { (it as List<*>).map { v -> (v as Number).toFloat() } }.toFloatArray()
            val d = DecisionRun.decide(c, stateIds, questionMap(qs), "m", 0.0, System.nanoTime()) { logits to null }
            var rowOk = f.ids.toList() == ints(r["ids"]) && f.routing.contentEquals(q) && f.extraInputs.single().contentEquals(o)
            for ((i, a) in d.answers.values.withIndex()) {
                val ref = ((r["probabilities"] as List<*>)[i] as List<*>).map { (it as Number).toDouble() }
                val p = when (a) { is Answer.Choice -> a.probabilities.values.toList(); is Answer.Score -> a.probabilities.values.toList(); is Answer.Noul -> listOf(1.0 - a.noul, a.noul) }
                val dp = ref.indices.maxOf { abs(p[it] - ref[it]) }
                maxDp = maxOf(maxDp, dp)
                if (dp > 1e-12) rowOk = false
            }
            if (rowOk) ok++ else println("packed ${r["id"]}: differs from the host")
        }
        assertEquals(packed.size, ok)
        println("packed plan: $ok/${packed.size} rows' six questions in one forward with the card host's ids and q / o routing; decide() probabilities vs the host's max |dp| $maxDp")
    }

    @Test fun planSplitsInQuestionOrderAndRefusesWhatCannotFit() {
        val c = contract(256)
        val state = c.stateIds((0 until 60).joinToString(" ") { "word$it" })
        fun questions(n: Int) = linkedMapOf<String, Question>(
            "a" to Question.Choice("Which of these words appear in the text?", *(0 until n).map { "word number $it" }.toTypedArray()),
            "b" to Question.Score("How long is the text?", listOf("very short", "short", "medium", "long", "very long")),
            "c" to Question.Noul("The text lists words."),
        )
        fun fits(qs: Collection<Question>) = runCatching { c.encode(qs.toList(), state) }.isSuccess
        // The smallest first question that still fits alone but not with the other two.
        val qs = (2..60).map { questions(it) }.first { q -> fits(listOf(q.getValue("a"))) && !fits(q.values) }
        val plan = c.plan(qs, state)
        assertTrue(plan.size >= 2)
        assertEquals(listOf("a", "b", "c"), plan.flatMap { b -> b.questions.map { it.id } })
        for ((i, b) in plan.withIndex()) {
            val group = b.questions.map { it.question }
            assertEquals(c.encode(group, state).ids.toList(), b.forward.ids.toList())
            // Greedy in question order: the next question would not have fitted this forward.
            if (i + 1 < plan.size) assertTrue(!fits(group + plan[i + 1].questions.first().question))
        }
        // Option slots: two 100-option choices cannot share one forward's 128 slots.
        val wide = linkedMapOf<String, Question>(
            "x" to Question.Choice("x?", *(0 until 100).map { "x$it" }.toTypedArray()),
            "y" to Question.Choice("y?", *(0 until 100).map { "y$it" }.toTypedArray()),
        )
        assertEquals(listOf(listOf("x"), listOf("y")), contract(512).plan(wide, c.stateIds("short")).map { b -> b.questions.map { it.id } })
        // A question that does not fit alone is refused as forward() refuses it.
        val long = linkedMapOf<String, Question>("ok" to Question.Noul("fine"), "too long" to Question.Choice((0 until 300).joinToString(" ") { "very" }, "a", "b"))
        try { c.plan(long, state); fail("a question longer than the window was planned") } catch (e: ModelException) { assertEquals(ErrorCode.CONTEXT_LIMIT_EXCEEDED, e.code); assertEquals("too long", e.details["question"]) }
    }

    @Test fun limitsAndTheStateCut() {
        val c = contract(256)
        val b = Question.Choice("What is this sentence?", linkedMapOf("nothing" to "an opinion", "promise" to null))
        assertEquals(listOf("an opinion", "promise"), DebertaDecisionContract.options(b))
        assertEquals(listOf("no", "yes"), DebertaDecisionContract.options(Question.Noul("q", linkedMapOf("false" to "no refund", "true" to "a refund"))))
        try { c.forward("q", Question.Score("q", (0 until 11).map { "l$it" }), c.stateIds("text")); fail("11 levels accepted") } catch (e: ModelException) { assertEquals(ErrorCode.INVALID_INPUT, e.code) }
        try { c.forward("q", Question.Choice("q", *(0 until 129).map { "k$it" }.toTypedArray()), c.stateIds("text")); fail("129 options accepted") } catch (e: ModelException) { assertEquals(ErrorCode.INVALID_INPUT, e.code) }
        val long = (0 until 400).joinToString(" ") { "word$it" }
        val ids = c.stateIds(long)
        assertTrue(ids.size > 256)
        // The state is cut at 256 tokens, so a 256 window cannot hold it with a question; 512 can, and reports the cut.
        try { c.forward("q", b, ids); fail("a cut state plus a question fits 256") } catch (e: ModelException) { assertEquals(ErrorCode.CONTEXT_LIMIT_EXCEEDED, e.code) }
        val f = contract(512).forward("q", b, ids)
        assertEquals(256, f.stateTokens); assertTrue(f.stateTruncated)
        assertEquals(listOf(1, 128001), f.ids.take(2)); assertEquals(128002, f.ids[2 + 256]); assertEquals(2, f.ids.last())
    }

    /** No model files needed: the development descriptor the device gate side-loads parses and declares the family's handler_config. */
    @Test fun developmentDescriptorDeclaresTheFamily() {
        val f = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "catalog/dev/litert-community__Open-Decision-DeBERTa-v3-Large-LiteRT.hfmodels.json") }.first { it.isFile }
        val d = io.github.johnrocky.hfmodels.descriptor.Descriptor.parse(f.readText(), "litert-community/Open-Decision-DeBERTa-v3-Large-LiteRT")
        assertEquals("s256_wfp16", d.defaultVariant)
        assertEquals(mapOf("s256_wfp16" to 256, "s512_wfp16" to 512, "s256_fp32" to 256), d.variants.associate { it.id to it.handlerConfig.getInt("window") })
        for (v in d.variants) {
            val hc = v.handlerConfig
            assertEquals(DebertaDecisionContract.FAMILY, hc.getString("family"))
            assertEquals(listOf(1024, 128, 256), listOf(hc.getInt("hidden"), hc.getInt("option_slots"), hc.getInt("state_tokens")))
            assertEquals(1.05, hc.getDouble("temperature"), 0.0)
            assertEquals(listOf("gpu", "cpu"), v.profiles.map { it.id })
            assertEquals(listOf("cpu"), v.profile("gpu")!!.fallbackProfiles)
        }
    }
}
