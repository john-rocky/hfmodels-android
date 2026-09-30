package io.github.johnrocky.hfmodels.litert

import io.github.johnrocky.hfmodels.decide.Answer
import io.github.johnrocky.hfmodels.decide.Json
import io.github.johnrocky.hfmodels.decide.Question
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.exp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Host-side parity with the publisher of Julia-1 (SupersonicLabs/Julia-1), without a model: the same
 * token ids, marker positions and qtype as `julia/data.py sequence()` for a subset of the rows the
 * publisher's runtime answered (`tools/julia1_fixture.py` builds it from the conversion's oracle
 * dumps), the publisher's `predict_typed` decoding of the captured logits, the float16 table read.
 *
 * The sequence test needs the repo's `tokenizer.json` (34 MB, not in this repository):
 *   ./gradlew :litert:testDebugUnitTest -Dhfmodels.julia1Root=<dir with tokenizer.json>
 * (the layout of `litert-community/Julia-1-LiteRT`). Skipped when the property is empty.
 */
class JuliaParityTest {
    private val root = System.getProperty("hfmodels.julia1Root")?.takeIf { it.isNotBlank() }?.let { File(it) }

    private fun rows(): List<Map<*, *>> {
        val s = javaClass.classLoader!!.getResourceAsStream("julia1/julia1_rows_subset.json.gz") ?: error("missing test resource")
        val text = GZIPInputStream(s).bufferedReader().use { it.readText() }
        return (Json.parseObject(text)["rows"] as List<*>).map { it as Map<*, *> }
    }

    private fun tokenizer(): HfTokenizer {
        assumeTrue("set -Dhfmodels.julia1Root=<dir with tokenizer.json>", root != null && File(root, "tokenizer.json").isFile)
        return HfTokenizer.load(File(root, "tokenizer.json"), HfTokenizer.SpecialTokens("<bos>", "<eos>", "<mask>", "<pad>", "<unk>"))
    }

    companion object {
        /** The SDK question for a fixture row (the request form the publisher's `predict_typed` renders to these options). */
        fun question(r: Map<*, *>): Question {
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

        fun softmax(z: List<Double>): List<Double> { val m = z.max(); val e = z.map { exp(it - m) }; val s = e.sum(); return e.map { it / s } }
    }

    @Test fun sequencesMatchThePublisher() {
        val tok = tokenizer()
        assertEquals(2, tok.clsId); assertEquals(1, tok.sepId); assertEquals(4, tok.maskId); assertEquals(0, tok.padId)
        for ((window, head) in listOf(512 to 507, 1024 to 512)) {
            val builder = DecisionSequenceBuilder(tok, window, head, DecisionFamily.JULIA)
            var checked = 0; var failures = 0; var truncated = 0
            for (r in rows()) {
                val fits = (r["fits"] as Map<*, *>)[window.toString()] as Boolean
                val q = question(r)
                builder.validate(q)
                val built = builder.build(q, builder.stateIds(builder.serializeState(r["state"]!!)))
                if (!fits) { assertTrue("row ${r["id"]} should report a cut at $window", built.stateTruncated); truncated++; continue }
                val expectIds = (r["ids"] as List<*>).map { (it as Number).toInt() }
                val expectMarkers = (r["markers"] as List<*>).map { (it as Number).toInt() }
                checked++
                if (expectIds != built.ids.toList() || expectMarkers != built.markers.toList() || (r["qtype"] as Number).toInt() != built.qtype || built.stateTruncated) {
                    failures++
                    println("${r["id"]} @$window: ids ${expectIds.size} vs ${built.ids.size}; first diff at ${expectIds.zip(built.ids.toList()).indexOfFirst { it.first != it.second }}; markers $expectMarkers vs ${built.markers.toList()}")
                }
            }
            assertTrue("no rows checked", checked > 0)
            assertEquals("sequence mismatches out of $checked at window $window (see stdout)", 0, failures)
            println("window $window: $checked sequences identical to the publisher's sequence(); $truncated rows longer than the window reported as truncated")
        }
    }

    @Test fun decodeMatchesPredictTyped() {
        var checked = 0
        for (r in rows()) {
            val q = question(r)
            val z = (r["logits"] as List<*>).map { (it as Number).toDouble() }
            val p = softmax(z.map { it.toFloat().toDouble() })   // the graph returns float32 scores; the reference host widens them
            val a = JuliaDecode.answer(q, z.map { it.toFloat() }.toFloatArray())
            val keys = (r["keys"] as List<*>).map { it as String }
            val best = p.indices.maxByOrNull { p[it] }!!
            when (a) {
                is Answer.Choice -> {
                    assertEquals(keys[best], a.choice)
                    for (i in keys.indices) assertEquals(p[i], a.probabilities.getValue(keys[i]), 1e-12)
                    assertEquals(p[best], a.extras["max_probability"] as Double, 1e-12)
                }
                is Answer.Score -> {
                    assertEquals(p.indices.sumOf { it * p[it] }, a.score, 1e-12)
                    for (i in p.indices) assertEquals(p[i], a.probabilities.getValue(i.toString()), 1e-12)
                    assertEquals(p[best], a.extras["max_probability"] as Double, 1e-12)
                }
                is Answer.Noul -> { assertEquals(p[1], a.noul, 1e-12); assertTrue(a.extras.isEmpty()) }
            }
            assertTrue(a.confidence in 0.0..1.0)
            checked++
        }
        assertTrue(checked > 0)
    }

    @Test fun answerJsonHasThePublisherFields() {
        val a = JuliaDecode.answer(Question.Choice("Which team?", linkedMapOf("billing" to "Billing and payment disputes", "shipping" to "Shipping and delivery")), floatArrayOf(1.5f, -0.5f))
        val o = a.toMap()
        assertEquals(listOf("type", "choice", "probabilities", "confidence", "max_probability"), o.keys.toList())
        assertEquals("billing", o["choice"])
        assertEquals(1.0, (o["probabilities"] as Map<*, *>).values.sumOf { it as Double }, 1e-12)
        val n = JuliaDecode.answer(Question.Noul("Is a refund requested?"), floatArrayOf(-1f, 1f)).toMap()
        assertEquals(listOf("type", "noul", "confidence"), n.keys.toList())
        assertEquals(1.0 / (1.0 + exp(-2.0)), n["noul"] as Double, 1e-12)
    }

    @Test fun juliaRendersDescriptionsNotKeys() {
        val tok = tokenizer()
        val b = DecisionSequenceBuilder(tok, 512, 507, DecisionFamily.JULIA)
        assertEquals(listOf("Billing and payment disputes", "shipping"), b.renderOptions(Question.Choice("q", linkedMapOf("billing" to "Billing and payment disputes", "shipping" to null))))
        assertEquals(listOf("low", "high"), b.renderOptions(Question.Score("q", listOf("low", "high"))))
        assertEquals(listOf("false", "true"), b.renderOptions(Question.Noul("q")))
        assertEquals(listOf("no refund", "a refund is asked for"), b.renderOptions(Question.Noul("q", linkedMapOf("false" to "no refund", "true" to "a refund is asked for"))))
        val tooMany = Question.Choice("q", *(0 until 21).map { "k$it" }.toTypedArray())
        try { b.validate(tooMany); error("21 options accepted") } catch (e: io.github.johnrocky.hfmodels.ModelException) { assertEquals(io.github.johnrocky.hfmodels.ErrorCode.INVALID_INPUT, e.code) }
    }

    @Test fun halfToFloatIsExact() {
        val cases = mapOf(
            0x0000 to 0f, 0x8000 to -0f, 0x3C00 to 1f, 0xC000 to -2f, 0x3800 to 0.5f, 0x7BFF to 65504f, 0x0001 to 5.9604645E-8f,
            0x03FF to 6.097555E-5f, 0x0400 to 6.1035156E-5f, 0x3555 to 0.33325195f, 0x7C00 to Float.POSITIVE_INFINITY, 0xFC00 to Float.NEGATIVE_INFINITY,
        )
        for ((h, f) in cases) assertEquals("0x%04x".format(h), f.toRawBits(), TokenTable.halfToFloat(h).toRawBits())
        assertTrue(TokenTable.halfToFloat(0x7E00).isNaN())
        assertEquals(65536, TokenTable.HALF_TO_FLOAT.size)
        assertEquals(1f, TokenTable.HALF_TO_FLOAT[0x3C00], 0f)
    }

    @Test fun tokenTableReadsRows() {
        val f = File.createTempFile("table", ".bin")
        try {
            // 3 rows x 4 columns, little-endian float16: row r column c = r + c/4
            val hidden = 4
            val bytes = java.nio.ByteBuffer.allocate(3 * hidden * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val halves = intArrayOf(0x0000, 0x3400, 0x3800, 0x3A00, 0x3C00, 0x3D00, 0x3E00, 0x3F00, 0x4000, 0x4080, 0x4100, 0x4180)
            for (h in halves) bytes.putShort(h.toShort())
            f.writeBytes(bytes.array())
            TokenTable.open(f, hidden, TokenTable.Dtype.FLOAT16).use { t ->
                assertEquals(3, t.rows)
                val dst = FloatArray(2 * hidden)
                t.copyRow(2, dst, 0); t.copyRow(0, dst, hidden)
                assertEquals(listOf(2f, 2.25f, 2.5f, 2.75f, 0f, 0.25f, 0.5f, 0.75f), dst.toList())
                try { t.copyRow(3, dst, 0); error("row 3 accepted") } catch (e: io.github.johnrocky.hfmodels.ModelException) { assertEquals(io.github.johnrocky.hfmodels.ErrorCode.INFERENCE_FAILED, e.code) }
            }
            try { TokenTable.open(f, 5, TokenTable.Dtype.FLOAT16); error("ragged table accepted") } catch (e: io.github.johnrocky.hfmodels.ModelException) { assertEquals(io.github.johnrocky.hfmodels.ErrorCode.MANIFEST_INVALID, e.code) }
        } finally { f.delete() }
    }
}
