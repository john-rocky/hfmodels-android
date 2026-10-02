package io.github.johnrocky.hfmodels.litert

import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.decide.Json
import io.github.johnrocky.hfmodels.decide.Question
import kotlin.math.max

/**
 * The decision-encoder families this module runs. They share one sequence layout (below) and
 * differ in how a question's options are rendered, which tokens frame the sequence, and how the
 * marker scores become an answer:
 *  - `laya` (convaiinnovations/laya): options carry their key or level (`key: description`,
 *    `level i: text`, `false: …` / `true: …`), a temperature per question type, a separate act head.
 *  - `julia` (SupersonicLabs/Julia-1): the option text is the criteria description itself (a choice
 *    key only when it has no description; the literal words `false` / `true` for a noul without
 *    criteria), 2 to 20 options, softmax at T=1, no act head. The graph takes host-looked-up
 *    embeddings instead of token ids.
 */
internal enum class DecisionFamily(val id: String, val graphOutputs: List<String>) {
    LAYA("laya", listOf("token_logits", "pooled_cls")),
    JULIA("julia", listOf("token_logits"));

    companion object {
        fun parse(id: String): DecisionFamily = entries.firstOrNull { it.id == id }
            ?: throw ModelException(ErrorCode.UNSUPPORTED_CONFIGURATION, "handler_config.family '$id' is not supported by this release (${entries.joinToString { it.id }})")
    }
}

/**
 * The sequence both families' publishers build (`laya/common.py`, `julia/data.py`, the same
 * "upstream-compatible marker serialization"), ported once and checked row by row against the
 * publishers' own ids:
 *
 *   CLS <type> question: <instructions> SEP [MASK] opt0 [MASK] opt1 … SEP <state> SEP
 *
 * Each option is one mask marker plus at most 48 text tokens; the head (instructions + options) is
 * budgeted to `headMaxLen` tokens, the state fills what remains of `maxLen` and is cut at the end
 * when longer (the publishers' non-strict mode; the SDK reports the cut as `Decisions.truncated`).
 * When everything fits, the ids equal the strict mode's exactly. The model scores every position;
 * the host gathers the marker positions.
 */
internal class DecisionSequenceBuilder(private val tok: HfTokenizer, val maxLen: Int, val headMaxLen: Int, val family: DecisionFamily) {
    class Built(val ids: IntArray, val markers: IntArray, val qtype: Int, val stateTokens: Int, val stateTruncated: Boolean)

    /** The text form of a state: strings as they are, anything structured as the publisher's `json.dumps`. */
    fun serializeState(state: Any): String = if (state is String) state else Json.dumps(state)

    /** Tokenizes the serialized state once (the part `prefill` shares between questions). */
    fun stateIds(serialized: String): IntArray = tok.encode(serialized.replace(tok.maskToken, " "))

    fun renderOptions(q: Question): List<String> = when (family) {
        DecisionFamily.LAYA -> when (q) {
            is Question.Choice -> q.criteria.map { (k, v) -> if (v == null || v == "") k else "$k: $v" }
            is Question.Score -> q.criteria.mapIndexed { i, c -> "level $i: $c" }
            is Question.Noul -> {
                val f = q.criteria?.get("false"); val t = q.criteria?.get("true")
                listOf(
                    "false: " + (if (f == null || f == "") "no, the statement does not hold" else f),
                    "true: " + (if (t == null || t == "") "yes, the statement holds" else t),
                )
            }
        }
        DecisionFamily.JULIA -> when (q) {
            // `predict_typed`: the option text is the description; the key is only the answer id. A key without
            // a description stands for itself, as in the publisher's list API where the option text is the id.
            is Question.Choice -> q.criteria.map { (k, v) -> if (v == null || v == "") k else v }
            is Question.Score -> q.criteria
            is Question.Noul -> listOf(
                q.criteria?.get("false")?.takeIf { it.isNotEmpty() } ?: "false",
                q.criteria?.get("true")?.takeIf { it.isNotEmpty() } ?: "true",
            )
        }
    }

    fun qtype(q: Question): Int = when (q) { is Question.Choice -> 0; is Question.Score -> 1; is Question.Noul -> 2 }

    /** The publisher's request validation that does not depend on the window (`validate_row`). */
    fun validate(q: Question) {
        if (family != DecisionFamily.JULIA) return
        val opts = renderOptions(q)
        if (opts.size > JULIA_MAX_OPTIONS) throw ModelException(ErrorCode.INVALID_INPUT, "this model scores 2 to $JULIA_MAX_OPTIONS options per question; the question has ${opts.size}", details = mapOf("options" to opts.size.toString()))
        if (opts.any { it.isBlank() }) throw ModelException(ErrorCode.INVALID_INPUT, "every option needs a non-empty text (a choice key or its description)")
    }

    fun build(q: Question, stateIds: IntArray): Built {
        val mask = tok.maskToken
        val typeName = when (q) { is Question.Choice -> "choice"; is Question.Score -> "score"; is Question.Noul -> "noul" }
        val ins = q.instructions.replace(mask, " ")
        var head = tok.encode("$typeName question: $ins")
        val opts = renderOptions(q)
        var optIds: List<IntArray> = opts.map { o -> intArrayOf(tok.maskId) + tok.encode(" " + o.replace(mask, " ")).let { if (it.size > 48) it.copyOf(48) else it } }
        var optBudget = headMaxLen - optIds.sumOf { it.size }
        if (optBudget < 16) {
            val per = max(4, (headMaxLen - 16) / max(1, optIds.size))
            optIds = optIds.map { if (it.size > per) it.copyOf(per) else it }
            optBudget = headMaxLen - optIds.sumOf { it.size }
        }
        val headKeep = max(8, optBudget)
        if (head.size > headKeep) head = head.copyOf(headKeep)
        val ids = IntList(maxLen)
        ids.add(tok.clsId); ids.addAll(head); ids.add(tok.sepId)
        val markers = IntArray(optIds.size)
        for ((i, o) in optIds.withIndex()) { markers[i] = ids.size; ids.addAll(o) }
        ids.add(tok.sepId)
        val room = max(0, maxLen - ids.size - 1)
        val st = if (stateIds.size > room) stateIds.copyOf(room) else stateIds
        ids.addAll(st)
        ids.add(tok.sepId)
        val all = ids.toArray()
        val cut = if (all.size > maxLen) all.copyOf(maxLen) else all
        return Built(cut, markers.filter { it < maxLen }.toIntArray(), qtype(q), st.size, stateIds.size > room)
    }

    companion object {
        /** `julia/data.py validate_row`: a native request carries 2 to 20 options. */
        const val JULIA_MAX_OPTIONS = 20
    }
}
