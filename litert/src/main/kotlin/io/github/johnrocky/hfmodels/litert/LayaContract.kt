package io.github.johnrocky.hfmodels.litert

import io.github.johnrocky.hfmodels.decide.Answer
import io.github.johnrocky.hfmodels.decide.Json
import io.github.johnrocky.hfmodels.decide.Question
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/*
 * The laya-specific host side of a decision encoder (convaiinnovations/laya 0.3.4): the publisher's
 * temperature settings and the pure-NumPy `host_decode` contract that the graph conversion verified
 * row by row against the official `Agent.predict`. The sequence itself is DecisionSequenceBuilder.
 */

/** The publisher's temperature settings (`rl_agent_config.json`). */
internal class LayaCalibration(val temperature: DoubleArray, val byOptions: Map<String, Double>, val maxLen: Int, val headMaxLen: Int, val encoder: String) {
    companion object {
        fun parse(text: String): LayaCalibration {
            val o = Json.parseObject(text)
            val t = (o["temperature"] as? List<*>)?.map { (it as Number).toDouble() } ?: listOf(1.0, 1.0, 1.0)
            val by = (o["temperature_by_options"] as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to (v as Number).toDouble() } ?: emptyMap()
            // A fitted calibration file keeps the window facts under `provenance`; the publisher's config keeps them at the top.
            val prov = o["provenance"] as? Map<*, *>
            fun num(key: String) = (o[key] as? Number)?.toInt() ?: (prov?.get(key) as? Number)?.toInt()
            return LayaCalibration(t.toDoubleArray(), by, num("max_len") ?: 512, num("head_max_len") ?: 192, (o["encoder"] ?: prov?.get("model"))?.toString() ?: "")
        }
    }

    fun bucket(qtype: Int, k: Int): String {
        val name = arrayOf("choice", "score", "noul")[qtype]
        val size = when { k <= 2 -> "2"; k <= 5 -> "3-5"; k <= 10 -> "6-10"; else -> "11+" }
        return "$name:$size"
    }

    fun scale(qtype: Int, k: Int): Double = byOptions[bucket(qtype, k)] ?: temperature.getOrElse(qtype) { 1.0 }
}

/** `host_decode.py`: marker logits + act logits -> the answer dictionary, with the reference's rounding. */
internal object LayaDecode {
    fun softmax(v: DoubleArray): DoubleArray {
        val m = v.max()
        val e = DoubleArray(v.size) { exp(v[it] - m) }
        val s = e.sum()
        return DoubleArray(v.size) { e[it] / s }
    }

    /** The four act-head features from the raw (untempered) marker logits: top1, top1-top2, entropy / ln(max(K,2)), max(K,2)/255. */
    fun actFeatures(rawLogits: FloatArray): FloatArray {
        val p = softmax(DoubleArray(rawLogits.size) { rawLogits[it].toDouble() })
        val k = max(p.size, 2)
        val sorted = p.sortedDescending()
        val top1 = sorted[0]
        val top2 = if (sorted.size > 1) sorted[1] else 0.0
        var ent = 0.0
        for (x in p) ent -= x * ln(max(x, 1e-9))
        ent /= ln(k.toDouble())
        return floatArrayOf(top1.toFloat(), (top1 - top2).toFloat(), ent.toFloat(), k.toFloat() / 255f)
    }

    fun probabilities(rawLogits: FloatArray, qtype: Int, cal: LayaCalibration): DoubleArray {
        val scale = max(1e-3, cal.scale(qtype, rawLogits.size))
        val z = DoubleArray(rawLogits.size) { rawLogits[it].toDouble() / scale }
        return softmax(z)
    }

    fun confidence(p: DoubleArray): Double {
        val k = p.size
        if (k < 2) return 1.0
        var ent = 0.0
        for (x in p) ent -= x * ln(x.coerceIn(1e-12, 1.0))
        return (1.0 - ent / ln(k.toDouble())).coerceIn(0.0, 1.0)
    }

    fun round4(x: Double): Double = Math.round(x * 10000.0) / 10000.0

    fun answer(q: Question, rawLogits: FloatArray, actLogits: FloatArray, cal: LayaCalibration): Answer {
        val qtype = when (q) { is Question.Choice -> 0; is Question.Score -> 1; is Question.Noul -> 2 }
        val p = probabilities(rawLogits, qtype, cal)
        val actP = softmax(DoubleArray(actLogits.size) { actLogits[it].toDouble() })[0]
        val extras = linkedMapOf<String, Any?>("action" to linkedMapOf("act_probability" to round4(actP)))
        return when (q) {
            is Question.Choice -> {
                val keys = q.criteria.keys.toList()
                var best = 0
                for (i in p.indices) if (p[i] > p[best]) best = i
                Answer.Choice(keys[best], keys.indices.associate { keys[it] to round4(p[it]) }.let { LinkedHashMap(it) }, round4(confidence(p)), extras)
            }
            is Question.Score -> {
                var s = 0.0
                for (i in p.indices) s += i * p[i]
                Answer.Score(round4(s), LinkedHashMap(q.criteria.indices.associate { it.toString() to q.criteria[it] }), LinkedHashMap(p.indices.associate { it.toString() to round4(p[it]) }), round4(confidence(p)), extras)
            }
            is Question.Noul -> Answer.Noul(round4(p[1]), round4(max(p[1], 1.0 - p[1])), extras)
        }
    }
}
