package io.github.johnrocky.hfmodels.litert

import io.github.johnrocky.hfmodels.decide.Answer
import io.github.johnrocky.hfmodels.decide.Question
import kotlin.math.max

/**
 * `julia/typed.py predict_typed`: the marker scores of one question -> the answer, the way the
 * publisher's runtime reports it. No temperature and no calibration (`inference-policy.json` says
 * `calibration: null`): the probabilities are the softmax of the raw scores at T=1, unrounded
 * ("full softmax probabilities, without display rounding"). `choice` is the winning key, `score` the
 * expected zero-based rubric index, `noul` P(true); choice and score also carry `max_probability`.
 * `confidence` is the SDK's field (1 minus the normalized entropy; `max(p, 1-p)` for noul); the
 * publisher's runtime has no such field.
 */
internal object JuliaDecode {
    fun probabilities(rawLogits: FloatArray): DoubleArray = LayaDecode.softmax(DoubleArray(rawLogits.size) { rawLogits[it].toDouble() })

    fun answer(q: Question, rawLogits: FloatArray): Answer {
        val p = probabilities(rawLogits)
        var best = 0
        for (i in p.indices) if (p[i] > p[best]) best = i   // the first index of the maximum, like Python's max over the range
        val extras: Map<String, Any?> = if (q is Question.Noul) emptyMap() else linkedMapOf("max_probability" to p[best])
        return when (q) {
            is Question.Choice -> {
                val keys = q.criteria.keys.toList()
                Answer.Choice(keys[best], LinkedHashMap(keys.indices.associate { keys[it] to p[it] }), LayaDecode.confidence(p), extras)
            }
            is Question.Score -> {
                var s = 0.0
                for (i in p.indices) s += i * p[i]
                Answer.Score(s, LinkedHashMap(q.criteria.indices.associate { it.toString() to q.criteria[it] }), LinkedHashMap(p.indices.associate { it.toString() to p[it] }), LayaDecode.confidence(p), extras)
            }
            is Question.Noul -> Answer.Noul(p[1], max(p[1], 1.0 - p[1]), extras)
        }
    }
}
