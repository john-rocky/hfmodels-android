package io.github.johnrocky.hfmodels.litert

import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.decide.Answer
import io.github.johnrocky.hfmodels.decide.DecisionLimits
import io.github.johnrocky.hfmodels.decide.Json
import io.github.johnrocky.hfmodels.decide.Question
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Host-side parity with the publisher of GLiNER2.5-Decide-LiteRT (its Android host and the gliner2 2.0.0
 * batches it captured), without a model:
 *  (a) the Unigram tokenizer against gliner2's tokenizer on the card's edge strings;
 *  (b) the token ids, `[P]` / `[L]` positions and the smallest window that holds them, for every request
 *      of the card's device-gate set (84), built from the request's own tasks dict;
 *  (c) the official decisions and probabilities from the captured fp32 logits, through this module's decode;
 *  (d) the float16 table rows as numpy upcasts them.
 *
 * Needs the card's files (not in this repository): the repo's `android/` and `host_assets/` directories at db801972,
 * laid out as in the repo (no graph needed):
 *   ./gradlew :litert:testDebugUnitTest -Dhfmodels.glinerDecideRoot=<dir>   (or HFMODELS_GLINER_DECIDE_ROOT)
 * Skipped when the property is empty.
 */
class GlinerDecideParityTest {
    private val root = System.getProperty("hfmodels.glinerDecideRoot")?.takeIf { it.isNotBlank() }?.let { File(it) }

    private fun file(path: String): File {
        assumeTrue("set -Dhfmodels.glinerDecideRoot=<GLiNER2.5-Decide-LiteRT files>", root != null && File(root, path).isFile)
        return File(root, path)
    }

    private val tokenizer by lazy { UnigramTokenizer.load(file("host_assets/tokenizer.json")) }

    private fun contract(window: Int) = GlinerDecideContract(tokenizer, DecisionLimits(window, window, 32, listOf("en")), 32)

    private fun fixtures(): List<Map<*, *>> =
        (Json.parseObject(file("android/app/src/debug/assets/gate_fixtures.json").readText())["fixtures"] as List<*>).map { it as Map<*, *> }

    private fun ints(v: Any?): List<Int> = (v as List<*>).map { (it as Number).toInt() }

    /** The request's tasks dict as the card's gate asset keeps it (an ordered list of task objects). */
    private fun tasks(f: Map<*, *>): List<GlinerTask> = (f["tasks"] as List<*>).map { t ->
        t as Map<*, *>
        val descriptions = (t["label_descriptions"] as List<*>?)?.let { pairs -> LinkedHashMap<String, String>().also { m -> for (p in pairs) { p as List<*>; m[p[0] as String] = p[1] as String } } }
        GlinerTask(t["task"] as String, (t["labels"] as List<*>).map { it as String }, t["prompt"] as String?, descriptions)
    }

    @Test fun tokenizerProbesMatchGliner2() {
        val probes = Json.parseObject(file("android/app/src/test/resources/tokenizer_probes.json").readText())["probes"] as List<*>
        var failures = 0
        for (p in probes) {
            p as Map<*, *>
            val expect = ints(p["ids"])
            val got = tokenizer.encodeWord(p["text"] as String).toList()
            if (expect != got) { failures++; println("probe ${Json.dumps(p["text"])}: expected $expect got $got") }
        }
        assertEquals(128011, tokenizer.vocabularySize)
        assertEquals(0, tokenizer.padId)
        assertEquals("tokenizer mismatches (see stdout)", 0, failures)
        println("(a) tokenizer: ${probes.size}/${probes.size} probes identical to gliner2's tokenize + convert_tokens_to_ids")
    }

    @Test fun sequencesMatchTheCapturedBatches() {
        val windows = listOf(128, 256, 512)
        val contracts = windows.associateWith { contract(it) }
        val all = fixtures()
        var failures = 0; var pairs = 0
        for (f in all) {
            val id = f["id"] as String
            val tasks = tasks(f)
            val c = contracts.getValue(128)
            val textIds = c.stateIds(f["text"] as String)
            val e = c.encode(tasks, textIds)
            val expectIds = ints(f["input_ids"])
            val expectLabels = ints(f["label_positions"])
            val expectSpecial = (f["schema_special_indices"] as List<*>).map { ints(it) }
            val ok = expectIds == e.ids.toList() && expectLabels == e.labelPositions.toList() && expectSpecial == e.special.map { it.toList() } &&
                (f["encoded_length"] as Number).toInt() == e.ids.size && (f["label_count"] as Number).toInt() == e.labelPositions.size
            // The window: the smallest that holds the request is the smallest the card ran it at; every listed window takes it, a smaller one refuses it.
            val fitting = windows.filter { w -> runCatching { contracts.getValue(w).forward(tasks, textIds) }.isSuccess }
            val listed = ints(f["windows"])
            val windowOk = fitting.firstOrNull() == listed.min() && fitting.containsAll(listed)
            for (w in windows.filter { it !in fitting }) {
                try { contracts.getValue(w).forward(tasks, textIds); fail("$id fits $w") } catch (x: ModelException) { assertEquals(ErrorCode.CONTEXT_LIMIT_EXCEEDED, x.code) }
            }
            for (w in listed) {
                val fw = contracts.getValue(w).forward(tasks, textIds)
                val routingOk = (0 until 32).all { row -> (0 until w).all { col -> fw.routing[row * w + col] == if (row < expectLabels.size && expectLabels[row] == col) 1f else 0f } }
                if (!routingOk || fw.ids.toList() != expectIds) { failures++; println("$id @$w: routing or ids differ") }
                pairs++
            }
            if (!ok || !windowOk) {
                failures++
                println("$id: ids ${expectIds.size} vs ${e.ids.size}, first diff at ${expectIds.zip(e.ids.toList()).indexOfFirst { it.first != it.second }}; labels $expectLabels vs ${e.labelPositions.toList()}; special $expectSpecial vs ${e.special.map { it.toList() }}; windows fitting $fitting listed $listed")
            }
        }
        // The question path: the card's "question over a passage" request is the family rule's task (answer + prompt).
        val passage = all.first { (it["id"] as String).startsWith("readme_18") }
        val t = tasks(passage).single()
        val q = Question.Choice(t.prompt!!, *t.labels.toTypedArray())
        val c = contract(128)
        val viaQuestion = c.forward("q", q, c.stateIds(passage["text"] as String))
        assertEquals(ints(passage["input_ids"]), viaQuestion.ids.toList())
        assertEquals(84, all.size)
        assertEquals(126, pairs)
        assertEquals("sequence mismatches out of ${all.size} (see stdout)", 0, failures)
        println("(b) sequences: ${all.size}/${all.size} requests with identical ids, [P]/[L] positions and smallest window; $pairs (request, window) pairs with identical padded routing; the passage question via Question.Choice identical")
    }

    @Test fun capturedLogitsDecodeToTheOfficialDecisions() {
        var tasksChecked = 0; var multiLabel = 0; var maxDp = 0.0; var failures = 0
        for (f in fixtures()) {
            val logits = (f["oracle_logits"] as List<*>).map { (it as Number).toFloat() }.toFloatArray()
            val probs = (f["oracle_probs"] as List<*>).map { (it as Number).toDouble() }
            val official = (f["official_result"] as List<*>).map { it as Map<*, *> }
            var offset = 0
            for ((i, t) in (f["tasks"] as List<*>).withIndex()) {
                t as Map<*, *>
                val labels = (t["labels"] as List<*>).map { it as String }
                val slice = logits.copyOfRange(offset, offset + labels.size)
                val ref = probs.subList(offset, offset + labels.size)
                offset += labels.size
                if (t["multi_label"] == true) { multiLabel++; continue }   // gliner2's multi-label rule has no Question form
                val a = GlinerDecode.answer(Question.Choice(t["task"] as String, *labels.toTypedArray()), slice) as Answer.Choice
                val expect = (official[i]["labels"] as List<*>).single() as String
                val dp = labels.indices.maxOf { abs(a.probabilities.getValue(labels[it]) - ref[it]) }
                maxDp = maxOf(maxDp, dp)
                tasksChecked++
                if (a.choice != expect || dp > 1e-6) { failures++; println("${f["id"]} ${t["task"]}: ${a.choice} vs official $expect, max |dp| $dp") }
            }
        }
        assertEquals("decode mismatches out of $tasksChecked (see stdout)", 0, failures)
        println("(c) decode: $tasksChecked/$tasksChecked single-label tasks give the official decision; max |dp| vs the oracle $maxDp; $multiLabel multi-label tasks not decoded (no Question form)")
    }

    @Test fun tableRowsUpcastLikeNumpy() {
        val rows = Json.parseObject(file("android/app/src/test/resources/embedding_rows_fp16.json").readText())
        val table = file("host_assets/word_embeddings_fp16.bin")
        assertEquals((rows["table_bytes"] as Number).toLong(), table.length())
        var checked = 0
        TokenTable.open(table, 1024, TokenTable.Dtype.FLOAT16).use { t ->
            assertEquals(128011, t.rows)
            for (r in rows["rows"] as List<*>) {
                r as Map<*, *>
                val id = (r["id"] as Number).toInt()
                val hex = r["float32_bits_hex"] as String
                val got = FloatArray(1024)
                t.copyRow(id, got, 0)
                for (col in 0 until 1024) assertEquals("row $id column $col", hex.substring(col * 8, col * 8 + 8).toLong(16).toInt(), got[col].toRawBits())
                checked++
            }
        }
        assertEquals(2, checked)
        println("(d) table: rows ${(rows["rows"] as List<*>).map { (it as Map<*, *>)["id"] }} bit-identical to numpy's float16 -> float32 upcast (1024 values each)")
    }

    /**
     * The packed plan (`pack_questions`) against gliner2: every captured multi-task request of the card's gate set whose
     * tasks a Question can say (single-label, no label descriptions) becomes a decide() with one question per task
     * (the task name as its id, the prompt as its instructions, the labels as its options), and the plan's one forward
     * carries the captured ids and `[L]` routing at the request's smallest window, each question reading its own slots.
     */
    @Test fun packedPlanReplaysTheCapturedMultiTaskRequests() {
        val contracts = listOf(128, 256, 512).associateWith { contract(it).packing(true) }
        var replayed = 0; var failures = 0
        for (f in fixtures()) {
            val tasks = tasks(f)
            if (tasks.size < 2 || (f["tasks"] as List<*>).any { t -> t as Map<*, *>; t["multi_label"] == true || t["label_descriptions"] != null }) continue
            val questions = LinkedHashMap<String, Question>()
            for (t in tasks) questions[t.name] = Question.Choice(t.prompt ?: "", *t.labels.toTypedArray())
            val window = ints(f["windows"]).min()
            val c = contracts.getValue(window)
            val plan = c.plan(questions, c.stateIds(f["text"] as String))
            val labels = ints(f["label_positions"])
            val b = plan.single()
            val routingOk = (0 until 32).all { row -> (0 until window).all { col -> b.forward.routing[row * window + col] == if (row < labels.size && labels[row] == col) 1f else 0f } }
            var from = 0
            val slotsOk = b.questions.map { it.id } == tasks.map { it.name } && b.questions.zip(tasks).all { (p, t) -> (p.scores == (from until from + t.labels.size)).also { from += t.labels.size } }
            if (b.forward.ids.toList() != ints(f["input_ids"]) || !routingOk || !slotsOk) { failures++; println("${f["id"]}: packed plan differs (ids ${b.forward.ids.size} vs ${ints(f["input_ids"]).size}, routing $routingOk, slots $slotsOk)") }
            replayed++
        }
        assertEquals(24, replayed)
        assertEquals("packed-plan mismatches out of $replayed (see stdout)", 0, failures)
        println("(e) packed plan: $replayed/$replayed captured multi-task requests rebuilt from Questions in one forward with identical ids, [L] routing and per-question slots")
    }

    /** The packed plan's limits: one question keeps the family form, a split at the window and at the label slots, gliner2's settings rule, refusals. */
    @Test fun packedPlanSplitsWhereOneForwardCannotHoldTheQuestions() {
        val c = contract(128).packing(true)
        val text = c.stateIds("Duplicate charge. I was charged twice for order 4471 and would like the second charge refunded.")
        val b = Question.Choice("Which department should handle this request?", linkedMapOf("billing" to "invoices, payments, refunds", "technical" to "bugs, outages", "other" to "everything else"))
        // One question: the task 'answer', identical to the unpacked contract.
        assertEquals(contract(128).forward("q", b, text).ids.toList(), c.plan(mapOf("dept" to b), text).single().forward.ids.toList())
        // Two short questions: one forward, the tasks named by the question ids.
        val two = linkedMapOf("dept" to b, "refund" to Question.Noul("Does the user explicitly request a refund?"))
        val one = c.plan(two, text).single()
        assertEquals(c.encode(listOf(GlinerTask("dept", GlinerDecideContract.labels(b), b.instructions), GlinerTask("refund", listOf("no", "yes"), "Does the user explicitly request a refund?")), text).ids.toList(), one.forward.ids.toList())
        assertEquals(listOf(0 until 3, 3 until 5), one.questions.map { it.scores })
        // Label slots: 12 + 12 + 12 labels -> two forwards (24, then 12).
        val wide = linkedMapOf("x" to Question.Choice("a", *(0 until 12).map { "x$it" }.toTypedArray()), "y" to Question.Choice("b", *(0 until 12).map { "y$it" }.toTypedArray()), "z" to Question.Choice("c", *(0 until 12).map { "z$it" }.toTypedArray()))
        assertEquals(listOf(listOf("x", "y"), listOf("z")), contract(512).packing(true).plan(wide, text).map { p -> p.questions.map { it.id } })
        // Window: questions that fit alone but not together split, in question order.
        val long = (0 until 12).associate { "q$it" to Question.Choice("Is this sentence about topic number $it of the list?", "yes it is about it", "no it is not") }
        val split = c.plan(LinkedHashMap(long), text)
        assertTrue(split.size > 1)
        assertEquals(long.keys.toList(), split.flatMap { p -> p.questions.map { it.id } })
        assertTrue(split.all { it.forward.ids.size <= 128 })
        // gliner2 would decode task "a" (prompt string "a: b c") with the settings of task "a: b": they go to two forwards.
        assertEquals(1, GlinerDecideContract.owner("a: b c", listOf("a", "a: b")))
        assertEquals(0, GlinerDecideContract.owner("answer: q", listOf("answer", "ans")))
        assertEquals(listOf(listOf("a"), listOf("a: b")), c.plan(linkedMapOf("a" to Question.Choice("b c", "p", "q"), "a: b" to Question.Choice("x", "p", "q")), text).map { p -> p.questions.map { it.id } })
        // A question that does not fit alone is refused as the forward refuses it.
        val tooLong = (0 until 200).joinToString(" ") { "word$it" }
        try { c.plan(two, c.stateIds(tooLong)); fail("a long state was accepted") } catch (e: ModelException) { assertEquals(ErrorCode.CONTEXT_LIMIT_EXCEEDED, e.code) }
        try { c.plan(linkedMapOf("dept" to b, "many" to Question.Choice("q", *(0 until 33).map { "k$it" }.toTypedArray())), text); fail("33 options accepted") } catch (e: ModelException) { assertEquals(ErrorCode.INVALID_INPUT, e.code) }
    }

    /** No model files needed: the development descriptor the device gate side-loads parses and declares the family's handler_config. */
    @Test fun developmentDescriptorDeclaresTheFamily() {
        val f = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "catalog/dev/litert-community__GLiNER2.5-Decide-LiteRT.hfmodels.json") }.first { it.isFile }
        val d = io.github.johnrocky.hfmodels.descriptor.Descriptor.parse(f.readText(), "litert-community/GLiNER2.5-Decide-LiteRT")
        assertEquals("s128_wfp16", d.defaultVariant)
        assertEquals(mapOf("s128_wfp16" to 128, "s256_wfp16" to 256, "s512_wfp16" to 512, "s128_npu_wfp16" to 128, "s128_fp32" to 128), d.variants.associate { it.id to it.handlerConfig.getInt("window") })
        for (v in d.variants) {
            assertEquals(GlinerDecideContract.FAMILY, v.handlerConfig.getString("family"))
            assertEquals(32, v.handlerConfig.getInt("label_slots"))
            assertEquals("gpu", v.defaultProfile)
            assertEquals(listOf("cpu"), v.profile("gpu")!!.fallbackProfiles)
            assertEquals(if (v.id == "s128_npu_wfp16") listOf("npu", "gpu", "cpu") else listOf("gpu", "cpu"), v.profiles.map { it.id })
        }
        // The NPU profile is asked for by name (BackendPolicy.Require) and never falls back.
        assertEquals(emptyList<String>(), d.variants.single { it.id == "s128_npu_wfp16" }.profile("npu")!!.fallbackProfiles)
        assertEquals(io.github.johnrocky.hfmodels.BackendKind.NPU, d.variants.single { it.id == "s128_npu_wfp16" }.profile("npu")!!.components["inference"])
    }

    @Test fun questionsBecomeOneTaskTheWayTheSieveAskedThem() {
        val c = contract(128)
        val b = Question.Choice("What is this sentence?", linkedMapOf("nothing" to "an opinion", "promise" to "the speaker commits", "other" to null))
        val t = c.task(b)
        assertEquals("answer", t.name); assertEquals("What is this sentence?", t.prompt)
        assertEquals(listOf("an opinion", "the speaker commits", "other"), t.labels)
        assertEquals(listOf("(", "[P]", "answer: What is this sentence?", "(", "[L]", "an opinion", "[L]", "the speaker commits", "[L]", "other", ")", ")"), GlinerDecideContract.schemaTokens(t))
        assertEquals(listOf("no", "yes"), c.task(Question.Noul("Is a refund requested?")).labels)
        assertEquals(listOf("no refund", "a refund"), c.task(Question.Noul("q", linkedMapOf("false" to "no refund", "true" to "a refund"))).labels)
        assertEquals(listOf("low", "high"), c.task(Question.Score("q", listOf("low", "high"))).labels)
        val n = GlinerDecode.answer(Question.Noul("q"), floatArrayOf(-1f, 1f)) as Answer.Noul
        assertEquals(GlinerDecode.softmax(floatArrayOf(-1f, 1f))[1].toDouble(), n.noul, 0.0)
        assertEquals(listOf("type", "noul", "confidence"), n.toMap().keys.toList())
        val s = GlinerDecode.answer(Question.Score("q", listOf("a", "b", "c")), floatArrayOf(0f, 0f, 0f)) as Answer.Score
        assertEquals(1.0, s.score, 1e-6)
        val tooMany = Question.Choice("q", *(0 until 33).map { "k$it" }.toTypedArray())
        try { c.forward("q", tooMany, c.stateIds("text")); fail("33 options accepted") } catch (e: ModelException) { assertEquals(ErrorCode.INVALID_INPUT, e.code) }
        try { c.forward("q", Question.Choice("q", "[SEP_TEXT]", "b"), c.stateIds("text")); fail("a structural label accepted") } catch (e: ModelException) { assertEquals(ErrorCode.INVALID_INPUT, e.code) }
        val long = (0 until 200).joinToString(" ") { "word$it" }
        try { c.forward("q", b, c.stateIds(long)); fail("a long state was accepted") } catch (e: ModelException) { assertEquals(ErrorCode.CONTEXT_LIMIT_EXCEEDED, e.code) }
        assertTrue(contract(512).forward("q", b, c.stateIds(long)).ids.size in 129..512)
    }
}
