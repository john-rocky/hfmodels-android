package io.github.johnrocky.hfmodels.litert

import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.decide.Answer
import io.github.johnrocky.hfmodels.decide.DecisionLimits
import io.github.johnrocky.hfmodels.decide.DecisionTiming
import io.github.johnrocky.hfmodels.decide.Decisions
import io.github.johnrocky.hfmodels.decide.Question
import java.io.File

/**
 * What one decision-encoder family decides for the shared runtime ([LiteRtDecisionModel]): how a
 * question becomes one forward (the option text, the token sequence and its routing input), which
 * graph inputs and outputs carry it, how the option scores are read back and how they become an
 * answer. The runtime owns the graphs, the token table, the model thread and the timing, and never
 * looks inside a question.
 *  - `laya` / `julia`: the marker sequence with the question type as an input; the graph scores every
 *    position and the host gathers the option markers ([MarkerContract]).
 *  - `gliner2_decide`: GLiNER2's schema sequence with one routing row per label; the graph returns one
 *    logit per label slot ([GlinerDecideContract]).
 *  - `gliclass`: GLiClass's label-then-text string with one routing row per `<<LABEL>>`; one logit per
 *    label slot ([GliclassContract]).
 *  - `deberta_decision`: the Open-Decision Collator's typed sequence with two routing inputs (the question's
 *    and the option's text spans); one logit per option slot ([DebertaDecisionContract]).
 */
internal interface DecisionContract {
    /** The `handler_config.family` id. */
    val family: String
    val limits: DecisionLimits
    /** The token at the padding positions (a host-lookup graph looks its row up as well). */
    val padId: Int
    /** A small graph run on the CPU after the main one (laya's act head), or null. */
    val head: Head?
    /** What `info.notes` says about this family's decoding. */
    val notes: List<String>

    /** The state's token ids: serialized the publisher's way and tokenized once, the part `prefill` shares between questions. */
    fun stateIds(state: Any): IntArray

    /** One question's forward against the state: checked, and fitted to the window or refused (`INVALID_INPUT`, `CONTEXT_LIMIT_EXCEEDED`). */
    fun forward(questionId: String, q: Question, stateIds: IntArray): Forward

    /**
     * The forwards that answer [questions] against the state, in question order. By default one forward per question
     * ([forward]); a family whose publisher packs a request's questions into one sequence overrides it (deberta_decision,
     * and gliner2_decide with `pack_questions`).
     */
    fun plan(questions: Map<String, Question>, stateIds: IntArray): List<Batch> = questions.map { (id, q) ->
        val t = System.nanoTime()
        val f = forward(id, q, stateIds)
        Batch(f, listOf(Planned(id, q, 0 until f.reads.size)), (System.nanoTime() - t) / 1e6)
    }

    /**
     * The main graph's inputs by role and the outputs this family reads. `inputShape` / `outputShape` give a
     * signature tensor's dimensions (null when the graph has no such name); throw when the graph does not fit.
     */
    fun signature(hostLookup: Boolean, inputShape: (String) -> List<Int>?, outputShape: (String) -> List<Int>?): Signature

    /** The option scores of one forward, read from the main graph's outputs. */
    fun scores(f: Forward, output: (String) -> FloatArray): FloatArray

    /** The option scores (and the head's logits when the family has a head) -> the answer in the publisher's form. */
    fun decode(q: Question, scores: FloatArray, headLogits: FloatArray?): Answer
}

/**
 * One question's graph inputs: the unpadded token ids, the family's routing input, and where its option scores are read.
 * `extraInputs`: the values of the family's further float inputs ([Signature.extraInputs], same order), written after
 * the routing input (deberta_decision's option routing); empty for the other families.
 */
internal class Forward(val ids: IntArray, val routing: FloatArray, val reads: IntArray, val stateTokens: Int, val stateTruncated: Boolean, val extraInputs: List<FloatArray> = emptyList())

/**
 * Signature names of the main graph: the token input (ids, or embeddings on a host-lookup graph), the attention mask, the routing input,
 * the family's further float inputs in the order [Forward.extraInputs] fills them (deberta_decision: `o_routing`), the outputs.
 */
internal class Signature(val tokens: String, val attention: String, val routing: String, val outputs: List<String>, val extraInputs: List<String> = emptyList()) {
    val inputs: List<String> get() = listOf(tokens, attention, routing) + extraInputs
}

/** One question of a [Batch]: its id, the question, and where its option scores sit in the batch's scores. */
internal class Planned(val id: String, val question: Question, val scores: IntRange)

/** One forward and the questions it answers, in question order; `buildMs`: building the forward. */
internal class Batch(val forward: Forward, val questions: List<Planned>, val buildMs: Double)

/**
 * decide() without the graph: the contract's plan, one [run] per batch (option scores and head logits), each question's
 * scores sliced from its batch and decoded. Questions answered by one forward each get that forward's time (building,
 * running, decoding); `totalMs` is the real total. The JVM parity tests drive it with captured logits.
 */
internal object DecisionRun {
    fun decide(
        contract: DecisionContract, stateIds: IntArray, questions: Map<String, Question>, model: String, stateMs: Double, t0: Long,
        run: (Forward) -> Pair<FloatArray, FloatArray?>,
    ): Decisions {
        if (questions.isEmpty()) throw ModelException(ErrorCode.INVALID_INPUT, "no questions")
        val answers = HashMap<String, Answer>()
        val ms = HashMap<String, Double>()
        var stateTokens = stateIds.size
        var truncated = false
        for (b in contract.plan(questions, stateIds)) {
            val tb = System.nanoTime()
            stateTokens = b.forward.stateTokens
            truncated = truncated || b.forward.stateTruncated
            val (raw, headLogits) = run(b.forward)
            for (p in b.questions) answers[p.id] = contract.decode(p.question, raw.copyOfRange(p.scores.first, p.scores.last + 1), headLogits)
            val batchMs = b.buildMs + (System.nanoTime() - tb) / 1e6
            for (p in b.questions) ms[p.id] = batchMs
        }
        if (answers.size != questions.size) throw IllegalStateException("the ${contract.family} plan answered ${answers.size} of ${questions.size} questions")
        return Decisions(LinkedHashMap(questions.keys.associateWith { answers.getValue(it) }), model,
            DecisionTiming(stateMs, questions.keys.map { ms.getValue(it) }, (System.nanoTime() - t0) / 1e6), stateTokens, truncated)
    }
}

/** A graph run on the CPU after the main one; `feed` builds its inputs from the main graph's outputs and the option scores. */
internal class Head(val file: File, val inputs: List<String>, val output: String, val feed: (main: (String) -> FloatArray, scores: FloatArray) -> Map<String, FloatArray>)

/**
 * `laya` and `julia`: [DecisionSequenceBuilder]'s marker sequence, the question type as a one-hot input
 * (`qtype_onehot`), a score per position (`token_logits`) from which the host gathers the option markers;
 * laya's graph also returns `pooled_cls` for its act head. Token ids go in as `input_ids`, or as looked-up
 * rows (`inputs_embeds`) when the variant ships a token table.
 */
internal class MarkerContract(
    /** Test hook as well: device parity checks build the publisher's rows through it. */
    val builder: DecisionSequenceBuilder,
    /** Test hook: the laya temperature settings this load decodes with (null on a family without calibration). */
    val calibration: LayaCalibration?,
    override val limits: DecisionLimits,
    override val padId: Int,
    actFile: File?,
) : DecisionContract {
    override val family: String get() = builder.family.id

    override val head: Head? = actFile?.let { f ->
        Head(f, listOf("pooled_cls", "feats"), "act_logits") { main, raw -> linkedMapOf("pooled_cls" to main("pooled_cls"), "feats" to LayaDecode.actFeatures(raw)) }
    }

    override val notes: List<String> = when (builder.family) {
        DecisionFamily.LAYA -> if (actFile == null) listOf("no act head in this variant: action.act_probability is not reported") else emptyList()
        DecisionFamily.JULIA -> listOf("no calibration in this family: probabilities are the softmax of the raw marker scores (T=1), unrounded, as the publisher's runtime reports them")
    }

    override fun stateIds(state: Any): IntArray = builder.stateIds(builder.serializeState(state))

    override fun forward(questionId: String, q: Question, stateIds: IntArray): Forward {
        builder.validate(q)
        val built = builder.build(q, stateIds)
        val options = builder.renderOptions(q).size
        if (built.markers.size != options) throw ModelException(
            ErrorCode.CONTEXT_LIMIT_EXCEEDED, "question '$questionId' does not fit the ${builder.maxLen}-token window: ${built.markers.size} of $options options kept",
            details = mapOf("question" to questionId, "window" to builder.maxLen.toString()),
        )
        return Forward(built.ids, FloatArray(3).also { it[built.qtype] = 1f }, built.markers, built.stateTokens, built.stateTruncated)
    }

    override fun signature(hostLookup: Boolean, inputShape: (String) -> List<Int>?, outputShape: (String) -> List<Int>?) =
        Signature(if (hostLookup) "inputs_embeds" else "input_ids", "attention_mask", "qtype_onehot", builder.family.graphOutputs)

    override fun scores(f: Forward, output: (String) -> FloatArray): FloatArray {
        val logits = output("token_logits")
        return FloatArray(f.reads.size) { logits[f.reads[it]] }
    }

    override fun decode(q: Question, scores: FloatArray, headLogits: FloatArray?): Answer = when (builder.family) {
        DecisionFamily.LAYA -> LayaDecode.answer(q, scores, headLogits ?: floatArrayOf(0f, 0f), calibration!!)
        DecisionFamily.JULIA -> JuliaDecode.answer(q, scores)
    }
}
