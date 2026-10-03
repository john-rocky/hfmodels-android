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
 * Host-side parity with the publisher of GLiClass-Edge-v3.0-LiteRT (its Python and Android hosts and the gliclass
 * 0.1.20 calls the conversion captured), without a model:
 *  (a) the tokenizer against Python `tokenizers` (with special tokens) on the conversion's stress strings and on
 *      every captured request string;
 *  (b) the request string, ids, `<<LABEL>>` positions and the smallest window of every captured request, built
 *      from its text, labels and prompt, and the padded routing at every window that holds it;
 *  (c) the official single-label decisions and probabilities from the captured fp32 logits, through this module's decode;
 *  (d) decide() form: the round-1 sieve's 30 chat sentences in six question forms, built from the Question alone,
 *      against the card's Python host (request string, ids, positions, window), the host's logits decoded against
 *      its probabilities, and the round-1 Mac answers (labels = descriptions: r1's policy form; keys).
 *
 * Needs, under one root (not in this repository): the repo litert-community/GLiClass-Edge-v3.0-LiteRT at 88c90950
 * (`host_assets/`, `android/`, `fixtures/`), the conversion run's `fixtures/oracle.json`, `requests.json` and
 * `tokenizer_stress.json` (552 captured calls and 261 strings; the card publishes the 152 shareable ones as
 * `oracle_public.json`), the round-1 files `r1/b_chat.jsonl`, `r1/gliclass_b_chat_policy.jsonl`,
 * `r1/gliclass_b_chat_keys.jsonl`, and `r4/decide_form.json` (make_decide_form_fixtures.py, the card's host):
 *   ./gradlew :litert:testDebugUnitTest -Dhfmodels.gliclassRoot=<dir>   (or HFMODELS_GLICLASS_ROOT)
 * Skipped when the property is empty; a set root with a missing file fails.
 */
class GliclassParityTest {
    private val root = System.getProperty("hfmodels.gliclassRoot")?.takeIf { it.isNotBlank() }?.let { File(it) }

    private fun file(path: String): File {
        assumeTrue("set -Dhfmodels.gliclassRoot=<GLiClass-Edge-v3.0-LiteRT files> (HFMODELS_GLICLASS_ROOT)", root != null)
        val f = File(root, path)
        assertTrue("missing $f under the parity root", f.isFile)
        return f
    }

    private val tokenizer by lazy { ByteLevelBpeTokenizer.load(file("host_assets/tokenizer.json")) }

    private fun contract(window: Int) = GliclassContract(tokenizer, DecisionLimits(window, window, 25, listOf("en")), 25)

    private fun ints(v: Any?): List<Int> = (v as List<*>).map { (it as Number).toInt() }

    private fun floats(v: Any?): FloatArray = (v as List<*>).map { (it as Number).toFloat() }.toFloatArray()

    private fun records(path: String, key: String): List<Map<*, *>> = (Json.parseObject(file(path).readText())[key] as List<*>).map { it as Map<*, *> }

    @Test fun tokenizerMatchesPythonTokenizers() {
        val stress = records("fixtures/tokenizer_stress.json", "cases")
        var failures = 0
        for (c in stress) {
            val got = tokenizer.encode(c["text"] as String).toList()
            if (got != ints(c["ids"])) { failures++; println("stress ${c["id"]} ${Json.dumps(c["text"])}: expected ${c["ids"]} got $got") }
        }
        val oracle = records("fixtures/oracle.json", "records")
        for (r in oracle) {
            val got = tokenizer.encode(r["string"] as String).toList()
            if (got != ints(r["input_ids"])) { failures++; println("oracle ${r["id"]}: ids differ") }
        }
        assertEquals(50370, tokenizer.vocabularySize)
        assertEquals(listOf(50281, 50282, 50283), listOf(tokenizer.clsId, tokenizer.sepId, tokenizer.padId))
        assertEquals(listOf(50368, 50369), listOf(tokenizer.tokenId("<<LABEL>>"), tokenizer.tokenId("<<SEP>>")))
        assertEquals("tokenizer mismatches (see stdout)", 0, failures)
        println("(a) tokenizer: ${stress.size}/${stress.size} stress strings and ${oracle.size}/${oracle.size} captured request strings identical to Python tokenizers")
    }

    @Test fun requestsMatchTheCapturedCalls() {
        val requests = records("fixtures/requests.json", "requests").associateBy { it["id"] as String }
        val oracle = records("fixtures/oracle.json", "records")
        val c128 = contract(128); val c256 = contract(256)
        var failures = 0; var pairs = 0; var at128 = 0
        for (r in oracle) {
            val q = requests.getValue(r["id"] as String)
            val labels = (q["labels"] as List<*>).map { it as String }
            val prompt = q["prompt"] as String?
            val e = c256.encode(q["text"] as String, labels, prompt)
            val fits = r["fits"] as Map<*, *>
            var ok = e.linearized == r["string"] && e.ids.toList() == ints(r["input_ids"]) && e.labelPositions.toList() == ints(r["label_positions"])
            // The smallest window: s128 takes what the oracle marks as fitting 128 and refuses the rest.
            val fits128 = runCatching { c128.forward(q["text"] as String, labels, prompt) }
            if (fits["s128"] == true) { if (fits128.isFailure) ok = false else at128++ }
            else if ((fits128.exceptionOrNull() as? ModelException)?.code != ErrorCode.CONTEXT_LIMIT_EXCEEDED) ok = false
            for ((w, c) in listOf(128 to c128, 256 to c256)) {
                if (w == 128 && fits["s128"] != true) continue
                val f = c.forward(q["text"] as String, labels, prompt)
                val positions = ints(r["label_positions"])
                val routingOk = (0 until 25).all { row -> (0 until w).all { col -> f.routing[row * w + col] == if (row < positions.size && positions[row] == col) 1f else 0f } }
                if (!routingOk || f.ids.toList() != ints(r["input_ids"]) || f.reads.toList() != labels.indices.toList()) ok = false
                pairs++
            }
            if (!ok) { failures++; println("${r["id"]}: request differs") }
        }
        assertEquals(552, oracle.size)
        assertEquals("request mismatches out of ${oracle.size} (see stdout)", 0, failures)
        println("(b) requests: ${oracle.size}/${oracle.size} with identical string, ids, <<LABEL>> positions and smallest window ($at128 at 128, ${oracle.size - at128} at 256); $pairs (request, window) pairs with identical padded routing")
    }

    @Test fun capturedLogitsDecodeToTheOfficialDecisions() {
        val requests = records("fixtures/requests.json", "requests").associateBy { it["id"] as String }
        val oracle = records("fixtures/oracle.json", "records")
        var maxDp = 0.0; var failures = 0
        for (r in oracle) {
            val logits = floats(r["logits"])
            val p = GlinerDecode.softmax(logits)
            val best = GlinerDecode.argmax(p)
            val official = (r["pipeline_single"] as List<*>).single() as Map<*, *>
            val ref = floats(r["softmax"])
            val dp = ref.indices.maxOf { abs(p[it].toDouble() - ref[it].toDouble()) }
            maxDp = maxOf(maxDp, dp)
            val labels = (requests.getValue(r["id"] as String)["labels"] as List<*>).map { it as String }
            if (labels[best] != official["label"] || abs(p[best].toDouble() - (official["score"] as Number).toDouble()) > 1e-6 || dp > 1e-6) {
                failures++; println("${r["id"]}: ${labels[best]} ${p[best]} vs official ${official["label"]} ${official["score"]}, max |dp| $dp")
            }
        }
        assertEquals("decode mismatches out of ${oracle.size} (see stdout)", 0, failures)
        println("(c) decode: ${oracle.size}/${oracle.size} single-label decisions equal the official pipeline's; max |dp| vs its softmax $maxDp")
    }

    @Test fun decideFormMatchesTheCardHostAndRound1() {
        val fixture = Json.parseObject(file("r4/decide_form.json").readText())
        val requests = (fixture["requests"] as List<*>).map { it as Map<*, *> }
        val c128 = contract(128); val c256 = contract(256)
        var failures = 0; var maxDp = 0.0
        val sdk = HashMap<Pair<String, String>, Answer>()
        for (r in requests) {
            val q = Question.fromMap(r["question"] as Map<*, *>)
            val text = r["text"] as String
            val labels = GliclassContract.labels(q)
            val prompt = GliclassContract.prompt(q)
            val e = c256.encode(text, labels, prompt)
            val w = (r["window"] as Number).toInt()
            // decide()'s path: the state as stateIds, the request from the Question.
            val viaQuestion = runCatching { c128.forward("q", q, c128.stateIds(text)) }
            var ok = labels == r["labels"] && prompt == r["prompt"] && e.linearized == r["string"] && e.ids.toList() == ints(r["ids"]) &&
                e.labelPositions.toList() == ints(r["label_positions"]) && (if (w == 128) viaQuestion.getOrNull()?.ids?.toList() == ints(r["ids"]) else viaQuestion.isFailure)
            val a = c128.decode(q, floats(r["logits"]), null)
            sdk[r["id"] as String to r["form"] as String] = a
            val hostP = (r["probabilities"] as List<*>).map { (it as Number).toDouble() }
            val p = when (a) { is Answer.Choice -> a.probabilities.values.toList(); is Answer.Score -> a.probabilities.values.toList(); is Answer.Noul -> listOf(1.0 - a.noul, a.noul) }
            val dp = hostP.indices.maxOf { abs(p[it] - hostP[it]) }
            maxDp = maxOf(maxDp, dp)
            ok = ok && dp <= 1e-6 && when (a) {
                is Answer.Choice -> a.choice == r["choice"]
                is Answer.Score -> abs(a.score - (r["score"] as Number).toDouble()) <= 1e-6
                is Answer.Noul -> abs(a.noul - (r["noul"] as Number).toDouble()) <= 1e-6
            }
            if (!ok) { failures++; println("${r["id"]} ${r["form"]}: differs from the card host (max |dp| $dp)") }
        }
        assertEquals("decide()-form mismatches out of ${requests.size} (see stdout)", 0, failures)
        val forms = requests.groupingBy { it["form"] as String }.eachCount()
        println("(d) decide() form: ${requests.size}/${requests.size} requests ($forms) with the card host's string, ids, positions and window from the Question alone; decode vs the host's probabilities max |dp| $maxDp")
        // The round-1 Mac answers: r1's policy form is labels = descriptions (the family rule's "described"), its keys form the keys.
        for ((r1Form, form) in listOf("policy" to "described", "keys" to "keys")) {
            val r1 = file("r1/gliclass_b_chat_$r1Form.jsonl").readLines().filter { it.isNotBlank() }.map { Json.parseObject(it) }
            var same = 0; var dp = 0.0
            for (row in r1) {
                val a = sdk.getValue(row["id"] as String to form) as Answer.Choice
                if (a.choice == row["answer"]) same++
                val ref = row["probabilities"] as Map<*, *>
                dp = maxOf(dp, ref.entries.maxOf { (k, v) -> abs(a.probabilities.getValue(k as String) - (v as Number).toDouble()) })
            }
            assertEquals("r1 $r1Form answers", r1.size, same)
            println("(d) round 1 $r1Form (SDK form '$form'): same answer $same/${r1.size}, max |dp| $dp")
        }
    }

    /** decide()'s path ([DecisionRun] over the default plan): the six questions of a sentence, one request each, the host's logits decoded per question; the state's real token count. */
    @Test fun decideRunsOneRequestPerQuestion() {
        val requests = (Json.parseObject(file("r4/decide_form.json").readText())["requests"] as List<*>).map { it as Map<*, *> }
        val c = contract(128)
        var rows = 0; var questions = 0; var same = 0
        for ((id, group) in requests.groupBy { it["id"] as String }) {
            val text = group.first()["text"] as String
            val map = LinkedHashMap<String, Question>().also { m -> for (r in group) m[r["form"] as String] = Question.fromMap(r["question"] as Map<*, *>) }
            val byIds = group.associate { ints(it["ids"]) to floats(it["logits"]) }
            val stateIds = c.stateIds(text)
            val plan = c.plan(map, stateIds)
            assertEquals(map.keys.map { listOf(it) }, plan.map { b -> b.questions.map { it.id } })
            for (b in plan) assertEquals("$id: the text's tokens", tokenizer.encode(text).size - 2, b.forward.stateTokens)
            val d = DecisionRun.decide(c, stateIds, map, "m", 0.0, System.nanoTime()) { f -> byIds.getValue(f.ids.toList()) to null }
            assertEquals(map.size, d.timing.questionMs.size)
            assertEquals(tokenizer.encode(text).size - 2, d.stateTokens)
            for (r in group) {
                questions++
                val a = d.answers.getValue(r["form"] as String)
                if (when (a) {
                        is Answer.Choice -> a.choice == r["choice"]
                        is Answer.Score -> abs(a.score - (r["score"] as Number).toDouble()) <= 1e-6
                        is Answer.Noul -> abs(a.noul - (r["noul"] as Number).toDouble()) <= 1e-6
                    }) same++
            }
            rows++
        }
        assertEquals(questions, same)
        assertEquals(0, c.forward("q", Question.Noul("Is it empty?"), c.stateIds("")).stateTokens)
        println("decide(): $rows sentences x 6 questions, one request per question; $same/$questions answers equal the card host's; the state's token count equals its own tokens in every request")
    }

    /** The rules decide() applies before any model call (the tokenizer from the root). */
    @Test fun questionsBecomeOneRequestTheWayTheSieveAskedThem() {
        assumeTrue("set -Dhfmodels.gliclassRoot", root != null)
        val c = contract(128)
        val b = Question.Choice("What is this sentence?", linkedMapOf("nothing" to "an opinion", "promise" to "the speaker commits", "other" to null))
        assertEquals(listOf("an opinion", "the speaker commits", "other"), GliclassContract.labels(b))
        assertEquals("What is this sentence? ", GliclassContract.prompt(b))
        assertEquals("<<LABEL>>an opinion<<LABEL>>the speaker commits<<LABEL>>other<<SEP>>What is this sentence? I will call.",
            c.encode("I will call.", GliclassContract.labels(b), GliclassContract.prompt(b)).linearized)
        assertEquals(listOf("no", "yes"), GliclassContract.labels(Question.Noul("Is a refund requested?")))
        assertEquals(listOf("no refund", "a refund"), GliclassContract.labels(Question.Noul("q", linkedMapOf("false" to "no refund", "true" to "a refund"))))
        val tooMany = Question.Choice("q", *(0 until 26).map { "k$it" }.toTypedArray())
        try { c.forward("q", tooMany, c.stateIds("text")); fail("26 options accepted") } catch (e: ModelException) { assertEquals(ErrorCode.INVALID_INPUT, e.code) }
        assertEquals(25, c.forward("q", Question.Choice("q", *(0 until 25).map { "k$it" }.toTypedArray()), c.stateIds("text")).reads.size)
        try { c.forward("q", b, c.stateIds("x <<LABEL>> y")); fail("a marker in the text accepted") } catch (e: ModelException) { assertEquals(ErrorCode.INVALID_INPUT, e.code) }
        val long = (0 until 150).joinToString(" ") { "word$it" }
        try { c.forward("q", b, c.stateIds(long)); fail("a long state was accepted") } catch (e: ModelException) { assertEquals(ErrorCode.CONTEXT_LIMIT_EXCEEDED, e.code) }
        val s = c.stateIds("I will call you tomorrow.")
        val f = c.forward("q", b, s)
        assertEquals(tokenizer.encode("I will call you tomorrow.").size - 2, f.stateTokens)
        assertTrue(f.extraInputs.isEmpty())
    }

    /** No root needed: the float16 widening every host-lookup family uses, for all 65,536 bit patterns (subnormals included). */
    @Test fun halfToFloatMatchesAnIndependentDecodeForEveryBitPattern() {
        for (bits in 0 until 65536) {
            val got = TokenTable.HALF_TO_FLOAT[bits]
            val sign = if (bits and 0x8000 != 0) -1.0 else 1.0
            val exponent = (bits ushr 10) and 0x1f
            val mantissa = bits and 0x3ff
            val expect = when (exponent) {
                0x1f -> if (mantissa == 0) (sign * Double.POSITIVE_INFINITY).toFloat() else Float.NaN
                0 -> (sign * Math.scalb(mantissa.toDouble(), -24)).toFloat()
                else -> (sign * Math.scalb(1024.0 + mantissa, exponent - 25)).toFloat()
            }
            if (expect.isNaN()) assertTrue("bits 0x${bits.toString(16)}", got.isNaN()) else assertEquals("bits 0x${bits.toString(16)}", expect.toRawBits(), got.toRawBits())
        }
        println("halfToFloat: 65536/65536 bit patterns equal an independent decode (2046 subnormals, both zeros, infinities, NaN)")
    }

    /** No model files needed: the development descriptor the device gate side-loads parses and declares the family's handler_config. */
    @Test fun developmentDescriptorDeclaresTheFamily() {
        val f = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "catalog/dev/litert-community__GLiClass-Edge-v3.0-LiteRT.hfmodels.json") }.first { it.isFile }
        val d = io.github.johnrocky.hfmodels.descriptor.Descriptor.parse(f.readText(), "litert-community/GLiClass-Edge-v3.0-LiteRT")
        assertEquals("s128_fp32", d.defaultVariant)
        assertEquals(mapOf("s128_fp32" to 128, "s256_fp32" to 256), d.variants.associate { it.id to it.handlerConfig.getInt("window") })
        for (v in d.variants) {
            val hc = v.handlerConfig
            assertEquals(GliclassContract.FAMILY, hc.getString("family"))
            assertEquals(25, hc.getInt("label_slots"))
            assertEquals(384, hc.getInt("hidden"))
            assertEquals(setOf("main", "table", "tokenizer"), hc.getJSONObject("files").keys().asSequence().toSet())
            assertEquals(listOf("gpu", "cpu"), v.profiles.map { it.id })
            assertEquals(listOf("cpu"), v.profile("gpu")!!.fallbackProfiles)
        }
    }
}
