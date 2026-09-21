package io.github.johnrocky.hfmodels.litert

import io.github.johnrocky.hfmodels.decide.Answer
import io.github.johnrocky.hfmodels.decide.Json
import io.github.johnrocky.hfmodels.decide.Question
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Host-side parity with the publisher's code, without a model: the same token ids and marker
 * positions as `laya.common.build_sequence` for every captured row, the same ids as the upstream
 * tokenizers for a list of edge cases, and the same rounded answers as the official
 * `Agent.predict` when decoding the captured raw logits.
 *
 * Needs the publisher's tokenizer files (not in this repository):
 *   ./gradlew :litert:testDebugUnitTest -Dhfmodels.layaRoot=<dir with tokenizer/, multilingual/tokenizer/, rl_agent_config.json>
 * (the layout of `convaiinnovations/laya` at 1c5edc17). Skipped when the property is empty.
 */
class LayaParityTest {
    private val root = System.getProperty("hfmodels.layaRoot")?.takeIf { it.isNotBlank() }?.let { File(it) }

    private fun resource(name: String): String {
        val s = javaClass.classLoader!!.getResourceAsStream("laya/$name") ?: error("missing test resource laya/$name")
        return (if (name.endsWith(".gz")) GZIPInputStream(s) else s).bufferedReader().use { it.readText() }
    }

    private fun tokenizer(sub: String): HfTokenizer {
        assumeTrue("set -Dhfmodels.layaRoot=<laya files>", root != null && File(root, "$sub/tokenizer/tokenizer.json").isFile)
        return HfTokenizer.load(File(root, "$sub/tokenizer/tokenizer.json"), File(root, "$sub/tokenizer/tokenizer_config.json"))
    }

    @Test fun tokenizerEdgeCasesMatchUpstream() {
        val en = tokenizer(""); val ml = tokenizer("multilingual")
        val cases = Json.parse(resource("tokenizer_cases.json")) as List<*>
        var failures = 0
        for (c in cases) {
            c as Map<*, *>
            val text = c["text"] as String
            for ((name, tok) in listOf("en" to en, "ml" to ml)) {
                val expect = (c[name] as List<*>).map { (it as Number).toInt() }
                val got = tok.encode(text).toList()
                if (expect != got) { failures++; println("$name ${text.replace("\n", "\\n")}: expected $expect got $got") }
            }
        }
        assertEquals("tokenizer mismatches (see stdout)", 0, failures)
    }

    private fun sequenceParity(sub: String, rowsFile: String, fixturesFile: String, window: Int, head: Int) {
        val tok = tokenizer(sub)
        val builder = LayaSequenceBuilder(tok, window, head)
        val rows = (Json.parseObject(resource(rowsFile))["rows"] as List<*>).associateBy { (it as Map<*, *>)["row_id"] as String }
        val fixtures = Json.parse(resource(fixturesFile)) as List<*>
        var checked = 0; var failures = 0
        for (f in fixtures) {
            f as Map<*, *>
            val fid = f["id"] as String
            val state = f["state"]!!
            val stateIds = builder.stateIds(builder.serializeState(state))
            val questions = f["questions"] as Map<*, *>
            for ((qid, qv) in questions) {
                val row = rows["$fid/$qid"] as? Map<*, *> ?: continue
                val q = Question.fromMap(qv as Map<*, *>)
                val built = builder.build(q, stateIds)
                val expectIds = (row["sequence_ids"] as List<*>).map { (it as Number).toInt() }
                val expectMarkers = (row["marker_positions"] as List<*>).map { (it as Number).toInt() }
                checked++
                if (expectIds != built.ids.toList() || expectMarkers != built.markers.toList() || (row["qtype"] as Number).toInt() != built.qtype) {
                    failures++
                    println("$fid/$qid: ids ${expectIds.size} vs ${built.ids.size}; first diff at ${expectIds.zip(built.ids.toList()).indexOfFirst { it.first != it.second }}; markers $expectMarkers vs ${built.markers.toList()}")
                    println("   expected ${expectIds.take(60)}"); println("   got      ${built.ids.toList().take(60)}")
                }
            }
        }
        assertTrue("no rows checked", checked > 0)
        assertEquals("sequence mismatches out of $checked (see stdout)", 0, failures)
        println("$sub: $checked sequences identical to the publisher's build_sequence")
    }

    @Test fun englishSequencesMatchUpstream() = sequenceParity("", "en_rows.json.gz", "en_fixtures.json.gz", 512, 192)

    @Test fun multilingualSequencesMatchUpstream() = sequenceParity("multilingual", "ml_rows_s256.json.gz", "ml_fixtures.json.gz", 256, 256)

    private fun decodeParity(sub: String, rowsFile: String, fixturesFile: String) {
        assumeTrue(root != null && File(root, "$sub/rl_agent_config.json".trimStart('/')).isFile)
        val cal = LayaCalibration.parse(File(root, "$sub/rl_agent_config.json".trimStart('/')).readText())
        val rows = Json.parseObject(resource(rowsFile))["rows"] as List<*>
        val fixtures = (Json.parse(resource(fixturesFile)) as List<*>).associateBy { (it as Map<*, *>)["id"] as String }
        var checked = 0; var failures = 0
        for (r in rows) {
            r as Map<*, *>
            val f = fixtures[r["fixture_id"] as String] as Map<*, *>
            val q = Question.fromMap((f["questions"] as Map<*, *>)[r["question_id"]] as Map<*, *>)
            val raw = (r["raw_logits"] as List<*>).map { (it as Number).toFloat() }.toFloatArray()
            val act = (r["raw_act_logits"] as List<*>).map { (it as Number).toFloat() }.toFloatArray()
            val got = LayaDecode.answer(q, raw, act, cal).toMap()
            val expect = r["official_answer"] as Map<*, *>
            checked++
            val diff = compare(expect, got)
            if (diff != null) { failures++; println("${r["row_id"]}: $diff\n   expected ${Json.dumps(expect)}\n   got      ${Json.dumps(got)}") }
        }
        assertEquals("decode mismatches out of $checked", 0, failures)
        println("$sub: $checked answers decoded like the official predict (rounded fields within 1e-4)")
    }

    /** Structural equality with numbers compared at 1e-4 (the reference rounds to 4 decimals; half-way cases may round differently). */
    private fun compare(a: Any?, b: Any?): String? {
        return when (a) {
            is Map<*, *> -> { if (b !is Map<*, *> || a.keys != b.keys) return "keys ${a.keys} vs ${(b as? Map<*, *>)?.keys}"; a.keys.firstNotNullOfOrNull { k -> compare(a[k], b[k])?.let { "$k: $it" } } }
            is Number -> if (b !is Number || abs(a.toDouble() - b.toDouble()) > 1.0001e-4) "$a vs $b" else null
            else -> if (a != b) "$a vs $b" else null
        }
    }

    @Test fun englishAnswersDecodeLikeTheOfficialPredict() = decodeParity("", "en_rows.json.gz", "en_fixtures.json.gz")

    @Test fun multilingualAnswersDecodeLikeTheOfficialPredict() = decodeParity("multilingual", "ml_rows_s256.json.gz", "ml_fixtures.json.gz")

    @Test fun answerJsonHasTheReferenceShape() {
        val a = Answer.Choice("billing", linkedMapOf("billing" to 0.6751, "other" to 0.3249), 0.2906, linkedMapOf("action" to linkedMapOf("act_probability" to 1.0)))
        assertEquals("""{"type": "choice", "choice": "billing", "probabilities": {"billing": 0.6751, "other": 0.3249}, "confidence": 0.2906, "action": {"act_probability": 1.0}}""", Json.dumps(a.toMap()))
    }
}
