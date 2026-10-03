// The request follows litert-community/GLiClass-Edge-v3.0-LiteRT@88c90950587eb951974c094eef91afa0fe3552c0, gliclass_litert.py and android/sample/app/src/main/java/com/gliclass/GliclassInputs.kt (Apache-2.0, same author; gliclass 0.1.20's uni-encoder pipeline), checked against its captured pipeline calls (GliclassParityTest).
package io.github.johnrocky.hfmodels.litert

import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.PrepareHost
import io.github.johnrocky.hfmodels.decide.Answer
import io.github.johnrocky.hfmodels.decide.DecisionLimits
import io.github.johnrocky.hfmodels.decide.Json
import io.github.johnrocky.hfmodels.decide.Question
import java.io.File
import org.json.JSONObject

/** An encoded GLiClass request: the string the pipeline tokenizes, its ids with `[CLS]` / `[SEP]`, the `<<LABEL>>` positions. */
internal class GliclassEncoded(val linearized: String, val ids: IntArray, val labelPositions: IntArray)

/**
 * `gliclass` (litert-community/GLiClass-Edge-v3.0-LiteRT): the ModernBERT encoder, the `[CLS]` and `<<LABEL>>`
 * read-outs and the scorer of knowledgator/gliclass-edge-v3.0, one logit per label slot. The request is
 * gliclass's: `<<LABEL>>label 1<<LABEL>>label 2…<<SEP>>` + prompt + text, nothing between the prompt and the text,
 * tokenized as one string with `[CLS]` / `[SEP]` ([ByteLevelBpeTokenizer]). The graph takes the looked-up
 * embeddings, the attention mask and `label_routing [1, labelSlots, window]` (row k one-hot at the k-th
 * `<<LABEL>>`).
 *
 * A question is one request (the round-1 sieve's form): the labels are a choice's descriptions (the key when there
 * is none), a score's levels, a noul's `false` / `true` descriptions or `no` / `yes`; the prompt is the
 * instructions followed by one space, so the text starts a word. Decoding is gliclass's single-label rule at T=1
 * (float32 softmax, the first maximum: [GlinerDecode], the same torch-shaped softmax). A request longer than the
 * window, or with more labels than the graph has slots, is refused, never cut.
 *
 * The publisher tokenizes the prompt and the text as one string, so the text is tokenized with each question's
 * request: [stateIds] keeps the serialized state's UTF-16 code units, and [Forward.stateTokens] counts the text's
 * tokens on their own.
 */
internal class GliclassContract(
    private val tokenizer: ByteLevelBpeTokenizer,
    override val limits: DecisionLimits,
    private val labelSlots: Int,
) : DecisionContract {
    override val family: String get() = FAMILY
    override val padId: Int get() = tokenizer.padId
    override val head: Head? get() = null
    override val notes: List<String> = listOf(
        "one gliclass request per question: labels = a choice's descriptions (the key when there is none), a score's levels, a noul's false / true descriptions or no / yes; " +
            "prompt = the instructions plus one space, right before the text; a request longer than the ${limits.windowTokens}-token window or with more than $labelSlots labels is refused, never cut; " +
            "probabilities are gliclass's single-label float32 softmax of the label logits (T=1)",
    )
    private val window = limits.windowTokens
    private val labelId = tokenizer.tokenId(LABEL_TOKEN) ?: throw ModelException(ErrorCode.MANIFEST_INVALID, "tokenizer.json has no $LABEL_TOKEN")

    init {
        if (tokenizer.tokenId(SEP_TOKEN) == null) throw ModelException(ErrorCode.MANIFEST_INVALID, "tokenizer.json has no $SEP_TOKEN")
    }

    /** The serialized state's UTF-16 code units (not token ids: the text is tokenized with each request, glued to the prompt). */
    override fun stateIds(state: Any): IntArray {
        val text = if (state is String) state else Json.dumps(state)
        return IntArray(text.length) { text[it].code }
    }

    override fun forward(questionId: String, q: Question, stateIds: IntArray): Forward {
        val text = String(CharArray(stateIds.size) { stateIds[it].toChar() })
        val f = forward(text, labels(q), prompt(q), questionId)
        return Forward(f.ids, f.routing, f.reads, tokenizer.encode(text).size - 2, false)
    }

    /** One request from its parts, the way the pipeline and the card's host take it (the parity checks replay the captured calls through it). */
    fun forward(text: String, labels: List<String>, prompt: String?, questionId: String = "q"): Forward {
        val e = encode(text, labels, prompt, questionId)
        if (e.ids.size > window) throw ModelException(
            ErrorCode.CONTEXT_LIMIT_EXCEEDED, "question '$questionId' does not fit the $window-token window: the labels, the instructions and the text take ${e.ids.size} tokens; this model refuses instead of cutting (a variant with a larger window holds up to 256)",
            details = mapOf("question" to questionId, "window" to window.toString(), "tokens" to e.ids.size.toString()),
        )
        val routing = FloatArray(labelSlots * window)
        e.labelPositions.forEachIndexed { row, p -> routing[row * window + p] = 1f }
        return Forward(e.ids, routing, IntArray(labels.size) { it }, e.ids.size, false)
    }

    /** gliclass `prepare_input` (labels first, `prompt_first`) and the tokenizer call; every `<<LABEL>>` must be one of ours. */
    fun encode(text: String, labels: List<String>, prompt: String?, questionId: String = "q"): GliclassEncoded {
        if (labels.isEmpty()) throw ModelException(ErrorCode.INVALID_INPUT, "question '$questionId' has no labels")
        if (labels.size > labelSlots) throw ModelException(
            ErrorCode.INVALID_INPUT, "question '$questionId' has ${labels.size} options; this model scores at most $labelSlots per forward",
            details = mapOf("question" to questionId, "options" to labels.size.toString()),
        )
        val linearized = linearize(text, labels, prompt)
        val ids = tokenizer.encode(linearized)
        val positions = ids.indices.filter { ids[it] == labelId }.toIntArray()
        if (positions.size != labels.size) throw ModelException(ErrorCode.INVALID_INPUT, "question '$questionId': the text, the instructions or an option contains $LABEL_TOKEN")
        return GliclassEncoded(linearized, ids, positions)
    }

    override fun signature(hostLookup: Boolean, inputShape: (String) -> List<Int>?, outputShape: (String) -> List<Int>?): Signature {
        if (!hostLookup) throw IllegalStateException("a gliclass graph takes inputs_embeds: the variant needs files.table")
        val expect = mapOf(EMBEDS to listOf(1, window, HIDDEN), ATTENTION to listOf(1, window), ROUTING to listOf(1, labelSlots, window))
        for ((name, shape) in expect) if (inputShape(name) != shape) throw IllegalStateException("input $name has shape ${inputShape(name)}, not $shape; not a GLiClass-Edge graph for window $window")
        if (outputShape(OUTPUT) != listOf(1, 1, 1, labelSlots)) throw IllegalStateException("output $OUTPUT has shape ${outputShape(OUTPUT)}, not [1, 1, 1, $labelSlots]")
        return Signature(EMBEDS, ATTENTION, ROUTING, listOf(OUTPUT))
    }

    override fun scores(f: Forward, output: (String) -> FloatArray): FloatArray {
        val logits = output(OUTPUT)
        return FloatArray(f.reads.size) { logits[f.reads[it]] }
    }

    override fun decode(q: Question, scores: FloatArray, headLogits: FloatArray?): Answer = GlinerDecode.answer(q, scores)

    companion object {
        const val FAMILY = "gliclass"
        const val LABEL_TOKEN = "<<LABEL>>"
        const val SEP_TOKEN = "<<SEP>>"
        const val EMBEDS = "inputs_embeds"
        const val ATTENTION = "attention_mask"
        const val ROUTING = "label_routing"
        const val OUTPUT = "logits"
        /** ModernBERT (ettin-encoder-32m)'s embedding row. */
        const val HIDDEN = 384

        /** The pipeline's input string: the labels, `<<SEP>>`, the prompt (if any), then the text. */
        fun linearize(text: String, labels: List<String>, prompt: String?): String {
            val b = StringBuilder()
            for (label in labels) b.append(LABEL_TOKEN).append(label)
            return b.append(SEP_TOKEN).append(prompt ?: "").append(text).toString()
        }

        /** The family rule's labels: a choice's descriptions (the key when there is none), a score's levels, a noul's two sides. */
        fun labels(q: Question): List<String> = GlinerDecideContract.labels(q)

        /** The family rule's prompt: the instructions and one space (the round-1 sieve's form; the card's host puts nothing between prompt and text). */
        fun prompt(q: Question): String = q.instructions + " "

        /** The family's handler_config: `window`, `hidden` 384, `label_slots` 25, a token table; the publisher's tokenizer.json, its special tokens optionally declared. */
        fun create(hc: JSONObject, tokenizerFile: File, window: Int, hidden: Int, languages: List<String>, host: PrepareHost): GliclassContract {
            if (hidden != HIDDEN) throw ModelException(ErrorCode.MANIFEST_INVALID, "handler_config.hidden $hidden: a gliclass graph takes $HIDDEN-wide embedding rows")
            val slots = hc.optInt("label_slots", 0).takeIf { it > 0 } ?: throw ModelException(ErrorCode.MANIFEST_INVALID, "handler_config.label_slots is missing")
            val t0 = System.nanoTime()
            val tokenizer = try { ByteLevelBpeTokenizer.load(tokenizerFile) } catch (e: ModelException) { throw e } catch (t: Throwable) {
                throw ModelException(ErrorCode.INITIALIZATION_FAILED, "tokenizer failed to load: ${t.javaClass.simpleName}: ${t.message}", details = mapOf("stage" to "tokenizer"), cause = t)
            }
            hc.optJSONObject("special_tokens")?.let { s ->
                for ((key, id) in listOf("cls" to tokenizer.clsId, "sep" to tokenizer.sepId, "pad" to tokenizer.padId, "mask" to null)) {
                    val token = s.optString(key, "").takeIf { it.isNotEmpty() } ?: continue
                    val found = tokenizer.tokenId(token)
                    if (found == null || (id != null && found != id)) throw ModelException(ErrorCode.MANIFEST_INVALID, "handler_config.special_tokens.$key '$token' is not the tokenizer's ${if (id == null) "token" else "id $id"}")
                }
            }
            host.log.i("tokenizer ${tokenizerFile.name}: ${tokenizer.vocabularySize} tokens (byte-level BPE, prefix space), load_ms=${(System.nanoTime() - t0) / 1_000_000}")
            // No head budget: the labels, the prompt and the text share the window, and nothing is cut.
            return GliclassContract(tokenizer, DecisionLimits(windowTokens = window, headTokens = window, maxOptions = slots, languages = languages), slots)
        }
    }
}
