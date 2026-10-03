// The sequence and the read-out follow litert-community/Open-Decision-DeBERTa-v3-Large-LiteRT@7a276235b795e8ad3ae7ac6a9f237daa2098863a, decision_litert.py and android/sample/app/src/main/java/com/opendecision/{DecisionInputs,DecisionDecoder}.kt (Apache-2.0, same author; the author's typed_decisions Collator and readout), checked against the Collator's captured requests (DebertaDecisionParityTest).
package io.github.johnrocky.hfmodels.litert

import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.PrepareHost
import io.github.johnrocky.hfmodels.decide.Answer
import io.github.johnrocky.hfmodels.decide.DecisionLimits
import io.github.johnrocky.hfmodels.decide.Json
import io.github.johnrocky.hfmodels.decide.Question
import java.io.File
import kotlin.math.exp
import kotlin.math.max
import org.json.JSONObject

/** One Collator request: the unpadded ids, each question's text span and its options' text spans (half-open, markers excluded). */
internal class DebertaEncoded(val ids: IntArray, val questionSpans: List<IntRange>, val optionSpans: List<List<IntRange>>, val stateTokens: Int, val stateTruncated: Boolean)

/**
 * `deberta_decision` (litert-community/Open-Decision-DeBERTa-v3-Large-LiteRT): the DeBERTa-v3-large encoder and
 * the typed-decision head of com-kotobalabs/open-jev-deberta-v3-large, one logit per option slot. The sequence is
 * the author's Collator: `[CLS] [STATE] state ([Q] instructions ([OPT] option)+)+ [SEP]`, every text tokenized on
 * its own with no special tokens ([UnigramTokenizer.encode]), the state cut to its first `state_tokens` (256)
 * tokens. The graph takes the looked-up embeddings, the attention mask and two routing inputs, `q_routing` and
 * `o_routing [1, optionSlots, window]`: row j is 1/len over the text tokens of option j's question, and of option j.
 *
 * The questions of one decide() go into one forward, as the author's `decide()` does ([plan]); when they do not fit
 * the window or the option slots they are split in question order into several forwards. The options are the family
 * rule's strings: a choice's descriptions (the key when there is none; the answer is the key), a score's levels, a
 * noul's fixed `no` / `yes` (its criteria are not read, the author's schema fixes them). Decoding is the author's
 * read-out: softmax of the logits / `temperature` (1.05) in double precision, `choice` = the first maximum, `score` =
 * Σ i·pᵢ, `noul` = p(yes); the author's `confidence` (the top probability) goes to `extras.max_probability`,
 * `confidence` is the SDK's field. A request longer than the window,
 * or with more options than the graph has slots, is refused; only the state is cut.
 */
internal class DebertaDecisionContract(
    private val tokenizer: UnigramTokenizer,
    override val limits: DecisionLimits,
    private val optionSlots: Int,
    private val stateTokenLimit: Int,
    private val temperature: Double,
) : DecisionContract {
    override val family: String get() = FAMILY
    override val padId: Int get() = tokenizer.padId
    override val head: Head? get() = null
    override val notes: List<String> = listOf(
        "the questions of one decide() in one forward as the author's decide() does, split in question order when they do not fit the window or the option slots: options = a choice's descriptions (the key when there is none), a score's levels, a noul's no / yes; " +
            "the state is cut to its first $stateTokenLimit tokens; a request longer than the ${limits.windowTokens}-token window or with more than $optionSlots options is refused; " +
            "probabilities are the softmax of the logits / $temperature (the author's calibration), extras.max_probability is the author's confidence",
    )
    private val window = limits.windowTokens
    private fun marker(token: String) = tokenizer.addedTokenId(token) ?: throw ModelException(ErrorCode.MANIFEST_INVALID, "tokenizer.json has no $token")
    private val clsMarker = marker("[CLS]")
    private val sepMarker = marker("[SEP]")
    private val stateMarker = marker("[STATE]")
    private val questionMarker = marker("[Q]")
    private val optionMarker = marker("[OPT]")

    /** The whole state's ids (the forward cuts them to `state_tokens`), serialized the way decide() takes it: a String as given, anything else as its JSON. */
    override fun stateIds(state: Any): IntArray = tokenizer.encode(if (state is String) state else Json.dumps(state))

    override fun forward(questionId: String, q: Question, stateIds: IntArray): Forward = forward(listOf(q), stateIds, questionId)

    /**
     * The author's form: every question in one forward while the state (cut to `state_tokens`), the questions and their
     * options fit the window and the option slots; otherwise the questions are split in order, a new forward starting at
     * the first question that does not fit. A question that does not fit alone is refused as [forward] refuses it.
     */
    override fun plan(questions: Map<String, Question>, stateIds: IntArray): List<Batch> {
        class Cost(val id: String, val q: Question, val tokens: Int, val options: Int, val ms: Double)
        // [CLS] [STATE] state ... [SEP]; each question adds [Q] instructions and [OPT] option per option.
        val frame = 2 + minOf(stateIds.size, stateTokenLimit) + 1
        val costs = questions.map { (id, q) ->
            val t = System.nanoTime()
            val options = options(q, id)
            val tokens = 1 + tokenizer.encode(q.instructions).size + options.sumOf { 1 + tokenizer.encode(it).size }
            if (frame + tokens > window || options.size > optionSlots) forward(listOf(q), stateIds, id)   // refuses with the forward's error
            Cost(id, q, tokens, options.size, (System.nanoTime() - t) / 1e6)
        }
        val groups = ArrayList<MutableList<Cost>>()
        var tokens = 0; var options = 0
        for (c in costs) {
            if (groups.isEmpty() || frame + tokens + c.tokens > window || options + c.options > optionSlots) { groups += ArrayList<Cost>(); tokens = 0; options = 0 }
            groups.last() += c; tokens += c.tokens; options += c.options
        }
        return groups.map { g ->
            val t = System.nanoTime()
            val f = forward(g.map { it.q }, stateIds, g.first().id)
            var from = 0
            val planned = g.map { c -> Planned(c.id, c.q, from until from + c.options).also { from += c.options } }
            Batch(f, planned, g.sumOf { it.ms } + (System.nanoTime() - t) / 1e6)
        }
    }

    /** Every question in one forward, the author's `decide()` (the parity checks replay the Collator's captured requests through it). */
    fun forward(questions: List<Question>, stateIds: IntArray, questionId: String = "q"): Forward {
        val e = encode(questions, stateIds, questionId)
        val count = e.optionSpans.sumOf { it.size }
        val qRouting = FloatArray(optionSlots * window)
        val oRouting = FloatArray(optionSlots * window)
        var slot = 0
        for ((qs, spans) in e.questionSpans.zip(e.optionSpans)) {
            for (os in spans) {
                if (!qs.isEmpty()) for (t in qs) qRouting[slot * window + t] = 1f / (qs.last - qs.first + 1)
                if (!os.isEmpty()) for (t in os) oRouting[slot * window + t] = 1f / (os.last - os.first + 1)
                slot++
            }
        }
        return Forward(e.ids, qRouting, IntArray(count) { it }, e.stateTokens, e.stateTruncated, listOf(oRouting))
    }

    /** The Collator's `encode_one` with the window and option-slot checks of the graph; spans are half-open token ranges as [IntRange]s (`until`). */
    fun encode(questions: List<Question>, stateIds: IntArray, questionId: String = "q"): DebertaEncoded {
        val ids = IntList(window)
        ids.add(clsMarker); ids.add(stateMarker)
        val kept = minOf(stateIds.size, stateTokenLimit)
        for (i in 0 until kept) ids.add(stateIds[i])
        val questionSpans = ArrayList<IntRange>()
        val optionSpans = ArrayList<List<IntRange>>()
        for (q in questions) {
            val options = options(q, questionId)
            val instructions = tokenizer.encode(q.instructions)
            questionSpans += (ids.size + 1) until (ids.size + 1 + instructions.size)
            ids.add(questionMarker); ids.addAll(instructions)
            optionSpans += options.map { o ->
                val tokens = tokenizer.encode(o)
                val span = (ids.size + 1) until (ids.size + 1 + tokens.size)
                ids.add(optionMarker); ids.addAll(tokens)
                span
            }
        }
        ids.add(sepMarker)
        val count = optionSpans.sumOf { it.size }
        if (count > optionSlots) throw ModelException(
            ErrorCode.INVALID_INPUT, "question '$questionId' has $count options; this model scores at most $optionSlots per forward",
            details = mapOf("question" to questionId, "options" to count.toString()),
        )
        if (ids.size > window) throw ModelException(
            ErrorCode.CONTEXT_LIMIT_EXCEEDED, "question '$questionId' does not fit the $window-token window: the state (cut to $stateTokenLimit tokens), the instructions and the options take ${ids.size} tokens; only the state is cut (a variant with a larger window holds up to 512)",
            details = mapOf("question" to questionId, "window" to window.toString(), "tokens" to ids.size.toString()),
        )
        return DebertaEncoded(ids.toArray(), questionSpans, optionSpans, kept, stateIds.size > stateTokenLimit)
    }

    override fun signature(hostLookup: Boolean, inputShape: (String) -> List<Int>?, outputShape: (String) -> List<Int>?): Signature {
        if (!hostLookup) throw IllegalStateException("a deberta_decision graph takes inputs_embeds: the variant needs files.table")
        val expect = mapOf(EMBEDS to listOf(1, window, HIDDEN), ATTENTION to listOf(1, window), Q_ROUTING to listOf(1, optionSlots, window), O_ROUTING to listOf(1, optionSlots, window))
        for ((name, shape) in expect) if (inputShape(name) != shape) throw IllegalStateException("input $name has shape ${inputShape(name)}, not $shape; not an Open-Decision graph for window $window")
        if (outputShape(OUTPUT) != listOf(1, 1, 1, optionSlots)) throw IllegalStateException("output $OUTPUT has shape ${outputShape(OUTPUT)}, not [1, 1, 1, $optionSlots]")
        return Signature(EMBEDS, ATTENTION, Q_ROUTING, listOf(OUTPUT), extraInputs = listOf(O_ROUTING))
    }

    override fun scores(f: Forward, output: (String) -> FloatArray): FloatArray {
        val logits = output(OUTPUT)
        return FloatArray(f.reads.size) { logits[f.reads[it]] }
    }

    override fun decode(q: Question, scores: FloatArray, headLogits: FloatArray?): Answer = DebertaDecisionDecode.answer(q, scores, temperature)

    companion object {
        const val FAMILY = "deberta_decision"
        const val EMBEDS = "inputs_embeds"
        const val ATTENTION = "attention_mask"
        const val Q_ROUTING = "q_routing"
        const val O_ROUTING = "o_routing"
        const val OUTPUT = "logits"
        /** DeBERTa-v3-large's embedding row. */
        const val HIDDEN = 1024
        /** The author's schema: `noul` reads p(options[1]) of these two. */
        val NOUL_OPTIONS = listOf("no", "yes")

        /** The family rule's option strings: a choice's descriptions (the key when there is none), a score's levels, `no` / `yes`; checked as the author's `_question`. */
        fun options(q: Question, questionId: String = "q"): List<String> = when (q) {
            is Question.Choice -> {
                if (q.criteria.size > 255) throw ModelException(ErrorCode.INVALID_INPUT, "question '$questionId': a choice takes 2 to 255 options, got ${q.criteria.size}")
                q.criteria.map { (k, v) -> if (v.isNullOrEmpty()) k else v }
            }
            is Question.Score -> {
                if (q.criteria.size > 10) throw ModelException(ErrorCode.INVALID_INPUT, "question '$questionId': a score takes 2 to 10 ordered levels, got ${q.criteria.size}")
                q.criteria
            }
            is Question.Noul -> NOUL_OPTIONS
        }

        /** The family's handler_config: `window`, `hidden` 1024, `option_slots` 128, `state_tokens` 256, `temperature` 1.05, a token table; the publisher's tokenizer.json. */
        fun create(hc: JSONObject, tokenizerFile: File, window: Int, hidden: Int, languages: List<String>, host: PrepareHost): DebertaDecisionContract {
            if (hidden != HIDDEN) throw ModelException(ErrorCode.MANIFEST_INVALID, "handler_config.hidden $hidden: a deberta_decision graph takes $HIDDEN-wide embedding rows")
            val slots = hc.optInt("option_slots", 0).takeIf { it > 0 } ?: throw ModelException(ErrorCode.MANIFEST_INVALID, "handler_config.option_slots is missing")
            val stateTokens = hc.optInt("state_tokens", 0).takeIf { it > 0 } ?: throw ModelException(ErrorCode.MANIFEST_INVALID, "handler_config.state_tokens is missing")
            val temperature = hc.optDouble("temperature", Double.NaN).takeIf { it > 0.0 } ?: throw ModelException(ErrorCode.MANIFEST_INVALID, "handler_config.temperature is missing")
            val t0 = System.nanoTime()
            val tokenizer = try { UnigramTokenizer.load(tokenizerFile) } catch (e: ModelException) { throw e } catch (t: Throwable) {
                throw ModelException(ErrorCode.INITIALIZATION_FAILED, "tokenizer failed to load: ${t.javaClass.simpleName}: ${t.message}", details = mapOf("stage" to "tokenizer"), cause = t)
            }
            host.log.i("tokenizer ${tokenizerFile.name}: ${tokenizer.vocabularySize} tokens (Unigram), load_ms=${(System.nanoTime() - t0) / 1_000_000}")
            // No head budget: the state (cut to state_tokens), the questions and the options share the window.
            return DebertaDecisionContract(tokenizer, DecisionLimits(windowTokens = window, headTokens = window, maxOptions = slots, languages = languages), slots, stateTokens, temperature)
        }
    }
}

/**
 * The author's read-out (`typed_decisions/schema.py readout`, `decision_litert.py decide`): softmax of the logits /
 * the temperature in double precision (max-shifted), `choice` = the first maximum, `score` = the expected zero-based
 * level, `noul` = p(yes). `extras.max_probability` (choice and score) is the author's `confidence`, the top
 * probability; `confidence` is the SDK's (1 minus the normalized entropy; `max(p, 1-p)` for noul).
 */
internal object DebertaDecisionDecode {
    fun probabilities(logits: FloatArray, temperature: Double): DoubleArray {
        val scaled = DoubleArray(logits.size) { logits[it].toDouble() / temperature }
        val mx = scaled.max()
        val out = DoubleArray(scaled.size) { exp(scaled[it] - mx) }
        val sum = out.sum()
        for (i in out.indices) out[i] /= sum
        return out
    }

    fun answer(q: Question, logits: FloatArray, temperature: Double): Answer {
        val p = probabilities(logits, temperature)
        var best = 0
        for (i in 1 until p.size) if (p[i] > p[best]) best = i
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
