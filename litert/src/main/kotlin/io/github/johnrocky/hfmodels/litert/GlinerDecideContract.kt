// Ported from litert-community/GLiNER2.5-Decide-LiteRT@db80197282d11373df084c0ceed67a54544cfa84, android/app/src/main/java/com/gliner25decide/{DecideInputs,DecideSchema,DecideDecoder}.kt (Apache-2.0, same author; gliner2 2.0.0's classification path), checked against its captured gliner2 batches (GlinerDecideParityTest).
package io.github.johnrocky.hfmodels.litert

import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.PrepareHost
import io.github.johnrocky.hfmodels.decide.Answer
import io.github.johnrocky.hfmodels.decide.DecisionLimits
import io.github.johnrocky.hfmodels.decide.Json
import io.github.johnrocky.hfmodels.decide.Question
import java.io.File
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.exp
import kotlin.math.max
import org.json.JSONObject

/** One gliner2 classification head as `classify_text` builds it from a tasks dict (the sequence-side fields of the card's `Task`). */
internal class GlinerTask(val name: String, val labels: List<String>, val prompt: String? = null, val labelDescriptions: Map<String, String>? = null)

/** An encoded request: the unpadded ids, each task's `[P]`-then-`[L]` positions (`batch.schema_special_indices[0]`), the `[L]` positions in request order. */
internal class GlinerEncoded(val ids: IntArray, val special: List<IntArray>, val labelPositions: IntArray)

/**
 * `gliner2_decide` (litert-community/GLiNER2.5-Decide-LiteRT): the DeBERTa-v3-large encoder and the
 * classification head of fastino/GLiNER2.5-Decide, one logit per label slot. The sequence is gliner2's:
 * per task `( [P] prompt_str ( [L] label … ) )`, tasks joined by `[SEP_STRUCT]`, then `[SEP_TEXT]` and the
 * lower-cased text words, every token tokenized on its own ([UnigramTokenizer]) with no CLS / SEP. The
 * graph takes the looked-up embeddings, the attention mask and a `[labelSlots, window]` routing matrix
 * (row j one-hot at the j-th `[L]`), assigned by shape because the converter names them `args_0..2`.
 *
 * A question is one task (the family rule, the form the demo sieve measured): the task `answer` with the
 * instructions as its prompt; the labels are a choice's descriptions (the key when there is none), a
 * score's levels, a noul's `false` / `true` descriptions or `no` / `yes`. Decoding is gliner2's
 * single-label rule at T=1 (float32 softmax, the first maximum). A request longer than the window, or
 * with more labels than the graph has slots, is refused, never cut.
 */
internal class GlinerDecideContract(
    private val tokenizer: UnigramTokenizer,
    override val limits: DecisionLimits,
    private val labelSlots: Int,
) : DecisionContract {
    override val family: String get() = FAMILY
    override val padId: Int get() = tokenizer.padId
    override val head: Head? get() = null
    override val notes: List<String> = listOf(
        "one gliner2 task per question (task 'answer', the instructions as its prompt, the options as labels); a request longer than the ${limits.windowTokens}-token window or with more than $labelSlots labels is refused, never cut; " +
            "probabilities are gliner2's single-label float32 softmax of the label logits (T=1)",
    )
    private val window = limits.windowTokens

    /** The text words' ids: "." appended unless the text ends a sentence, gliner2's words lower-cased, each tokenized on its own. */
    override fun stateIds(state: Any): IntArray {
        val text = collateText(if (state is String) state else Json.dumps(state))
        val ids = IntList(256)
        for (w in splitWords(text)) ids.addAll(tokenizer.encodeWord(w.text))
        return ids.toArray()
    }

    /** The family rule: one question = one task `answer`, the instructions as its prompt. */
    fun task(q: Question): GlinerTask = GlinerTask(TASK, labels(q), q.instructions)

    override fun forward(questionId: String, q: Question, stateIds: IntArray): Forward = forward(listOf(task(q)), stateIds, questionId)

    /**
     * Several tasks in one sequence, the way gliner2 encodes a tasks dict: the parity checks replay the
     * publisher's captured requests through it. decide() sends one task per forward, so gliner2's rule that
     * resolves a head's settings from its prompt string (a task name that prefixes another's) never applies.
     */
    fun forward(tasks: List<GlinerTask>, textIds: IntArray, questionId: String = "q"): Forward {
        if (tasks.isEmpty() || tasks.any { it.labels.isEmpty() }) throw ModelException(ErrorCode.INVALID_INPUT, "every task needs at least one label")
        val count = tasks.sumOf { it.labels.size }
        if (count > labelSlots) throw ModelException(
            ErrorCode.INVALID_INPUT, "question '$questionId' has $count options; this model scores at most $labelSlots per forward",
            details = mapOf("question" to questionId, "options" to count.toString()),
        )
        val e = encode(tasks, textIds)
        if (e.ids.size > window) throw ModelException(
            ErrorCode.CONTEXT_LIMIT_EXCEEDED, "question '$questionId' does not fit the $window-token window: the instructions, options and text take ${e.ids.size} tokens; this model refuses instead of cutting (a variant with a larger window holds up to 512)",
            details = mapOf("question" to questionId, "window" to window.toString(), "tokens" to e.ids.size.toString()),
        )
        val routing = FloatArray(labelSlots * window)
        e.labelPositions.forEachIndexed { row, p -> routing[row * window + p] = 1f }
        return Forward(e.ids, routing, IntArray(count) { it }, textIds.size, false)
    }

    /** gliner2 2.0.0 `_collate_batch` + `_format_input_with_mapping` for classification heads, with the text words already tokenized. */
    fun encode(tasks: List<GlinerTask>, textIds: IntArray): GlinerEncoded {
        val schemas = tasks.map { schemaTokens(it) }
        val combined = ArrayList<String>()
        for (s in schemas) { combined.addAll(s); combined.add(SEP_STRUCT) }
        if (combined.isNotEmpty()) combined.removeAt(combined.size - 1)
        combined.add(SEP_TEXT)
        // Only structural slots are routed: [P] at offset+1 and the [L] slots 4, 6, … before ") )".
        // Prompt or label text that itself tokenizes to a marker id is never counted as a marker.
        val markerIndices = HashSet<Int>()
        var offset = 0
        for (s in schemas) {
            if (s.size > 1) markerIndices.add(offset + 1)
            for (index in 4 until s.size - 2 step 2) markerIndices.add(offset + index)
            offset += s.size + 1
        }
        val ids = IntList(window)
        val special = List(tasks.size) { ArrayList<Int>() }
        var currentSchema = 0
        var foundSeparator = false
        for ((originalIndex, token) in combined.withIndex()) {
            var schemaIndex = -1
            if (token == SEP_TEXT) foundSeparator = true
            else if (!foundSeparator) {
                schemaIndex = currentSchema
                if (token == SEP_STRUCT) currentSchema++
            }
            val position = ids.size
            ids.addAll(tokenizer.encodeWord(token))
            if (schemaIndex >= 0 && originalIndex in markerIndices) {
                if (schemaIndex >= tasks.size) throw markerText()
                special[schemaIndex].add(position)
            }
        }
        // A label or prompt equal to "[SEP_STRUCT]" / "[SEP_TEXT]" shifts gliner2's segment bookkeeping; refuse instead of routing labels to the wrong head.
        tasks.forEachIndexed { i, t -> if (special[i].size != t.labels.size + 1) throw markerText() }
        ids.addAll(textIds)
        return GlinerEncoded(ids.toArray(), special.map { it.toIntArray() }, special.flatMap { it.drop(1) }.toIntArray())
    }

    override fun signature(hostLookup: Boolean, inputShape: (String) -> List<Int>?, outputShape: (String) -> List<Int>?): Signature {
        val shapes = INPUTS.associateWith(inputShape)
        fun named(shape: List<Int>): String = shapes.filterValues { it == shape }.keys.singleOrNull()
            ?: throw IllegalStateException("the graph has no single input of shape $shape (inputs $shapes); not a GLiNER2.5-Decide graph for window $window")
        val out = outputShape(OUTPUT)
        if (out != listOf(1, 1, 1, labelSlots)) throw IllegalStateException("output $OUTPUT has shape $out, not [1, 1, 1, $labelSlots]")
        return Signature(named(listOf(1, window, HIDDEN)), named(listOf(1, window)), named(listOf(1, labelSlots, window)), listOf(OUTPUT))
    }

    override fun scores(f: Forward, output: (String) -> FloatArray): FloatArray {
        val logits = output(OUTPUT)
        return FloatArray(f.reads.size) { logits[f.reads[it]] }
    }

    override fun decode(q: Question, scores: FloatArray, headLogits: FloatArray?): Answer = GlinerDecode.answer(q, scores)

    companion object {
        const val FAMILY = "gliner2_decide"
        /** The task a question becomes; the card's "question over a passage" form. */
        const val TASK = "answer"
        const val P_TOKEN = "[P]"
        const val L_TOKEN = "[L]"
        const val DESC_TOKEN = "[DESCRIPTION]"
        const val SEP_STRUCT = "[SEP_STRUCT]"
        const val SEP_TEXT = "[SEP_TEXT]"
        /** The converter's input names; their roles are assigned by shape. */
        val INPUTS = listOf("args_0", "args_1", "args_2")
        const val OUTPUT = "output_0"
        /** DeBERTa-v3-large's embedding row. */
        const val HIDDEN = 1024

        /** The option labels of a question: a choice's descriptions (the key when there is none), a score's levels, a noul's two sides. */
        fun labels(q: Question): List<String> = when (q) {
            is Question.Choice -> q.criteria.map { (k, v) -> if (v.isNullOrEmpty()) k else v }
            is Question.Score -> q.criteria
            is Question.Noul -> listOf(
                q.criteria?.get("false")?.takeIf { it.isNotEmpty() } ?: "no",
                q.criteria?.get("true")?.takeIf { it.isNotEmpty() } ?: "yes",
            )
        }

        /** `prompt_str`: the task name, `": " + prompt` when set, then `" [DESCRIPTION] label: description"` per described label. One schema token. */
        fun promptString(t: GlinerTask): String {
            val b = StringBuilder(t.name)
            if (!t.prompt.isNullOrEmpty()) b.append(": ").append(t.prompt)
            t.labelDescriptions?.forEach { (label, description) -> if (label in t.labels) b.append(' ').append(DESC_TOKEN).append(' ').append(label).append(": ").append(description) }
            return b.toString()
        }

        /** `( [P] prompt_str ( [L] label1 [L] label2 … ) )`, one entry per gliner2 schema token. */
        fun schemaTokens(t: GlinerTask): List<String> {
            val tokens = arrayListOf("(", P_TOKEN, promptString(t), "(")
            for (label in t.labels) { tokens.add(L_TOKEN); tokens.add(label) }
            tokens.add(")"); tokens.add(")")
            return tokens
        }

        /** gliner2 `_collate_batch`: append "." unless the text ends with ".", "!" or "?". */
        fun collateText(text: String): String = when {
            text.isEmpty() -> "."
            text.endsWith(".") || text.endsWith("!") || text.endsWith("?") -> text
            else -> "$text."
        }

        /** A `WhitespaceTokenSplitter` token: lower-cased [text]; half-open [start] / [end] count code points of the original string. */
        class Word(val text: String, val start: Int, val end: Int)

        /**
         * gliner2 2.0.0 `processing/word_splitter.py:WhitespaceTokenSplitter.__call__`. Python's Unicode word / space
         * classes are spelled out (Java's `\w` includes combining marks and its default `\s` is ASCII-only); only the
         * token is lower-cased, so the offsets survive characters whose lower case changes length.
         */
        fun splitWords(text: String): List<Word> {
            val result = ArrayList<Word>()
            val m = WORD_PATTERN.matcher(text)
            while (m.find()) result.add(Word(m.group().lowercase(Locale.ROOT), text.codePointCount(0, m.start()), text.codePointCount(0, m.end())))
            return result
        }

        private const val SPACE = "\\x{09}-\\x{0d}\\x{1c}-\\x{20}\\x{85}\\x{a0}\\x{1680}\\x{2000}-\\x{200a}\\x{2028}\\x{2029}\\x{202f}\\x{205f}\\x{3000}"
        private const val WORD = "\\p{L}\\p{N}_"
        private val WORD_PATTERN = Pattern.compile(
            "(?:https?://[^$SPACE]+|www\\.[^$SPACE]+)" +
                "|[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}" +
                "|@[a-z0-9_]+" +
                "|[$WORD]+(?:[-_][$WORD]+)*" +
                "|[^$SPACE]",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE,
        )

        private fun markerText() = ModelException(ErrorCode.INVALID_INPUT, "an option or the instructions equal a gliner2 structural token ($SEP_STRUCT / $SEP_TEXT)")

        /** The family's handler_config: `window`, `hidden` 1024, `label_slots` 32, a token table; the publisher's tokenizer.json. */
        fun create(hc: JSONObject, tokenizerFile: File, window: Int, hidden: Int, languages: List<String>, host: PrepareHost): GlinerDecideContract {
            if (hidden != HIDDEN) throw ModelException(ErrorCode.MANIFEST_INVALID, "handler_config.hidden $hidden: a gliner2_decide graph takes $HIDDEN-wide embedding rows")
            val slots = hc.optInt("label_slots", 0).takeIf { it > 0 } ?: throw ModelException(ErrorCode.MANIFEST_INVALID, "handler_config.label_slots is missing")
            val t0 = System.nanoTime()
            val tokenizer = try { UnigramTokenizer.load(tokenizerFile) } catch (e: ModelException) { throw e } catch (t: Throwable) {
                throw ModelException(ErrorCode.INITIALIZATION_FAILED, "tokenizer failed to load: ${t.javaClass.simpleName}: ${t.message}", details = mapOf("stage" to "tokenizer"), cause = t)
            }
            host.log.i("tokenizer ${tokenizerFile.name}: ${tokenizer.vocabularySize} tokens (Unigram), load_ms=${(System.nanoTime() - t0) / 1_000_000}")
            // No head budget: the schema and the text share the window, and nothing is cut.
            return GlinerDecideContract(tokenizer, DecisionLimits(windowTokens = window, headTokens = window, maxOptions = slots, languages = languages), slots)
        }
    }
}

/**
 * gliner2 2.0.0 `_extract_classification_result` for a single-label head at T=1, as the publisher's host
 * ports it (`DecideDecoder.kt`): float32 softmax shaped like torch's CPU kernel, the first index of the
 * maximum like `torch.argmax`. `choice` is the winning key, `score` the expected level index, `noul`
 * P(true side); probabilities unrounded. `confidence` is the SDK's field (1 minus the normalized entropy;
 * `max(p, 1-p)` for noul); gliner2 has no such field.
 */
internal object GlinerDecode {
    /** torch's CPU float32 softmax shape: max-shift, exp, sum, multiply by the reciprocal. */
    fun softmax(logits: FloatArray): FloatArray {
        var mx = logits[0]
        for (v in logits) if (v > mx) mx = v
        val out = FloatArray(logits.size) { exp((logits[it] - mx).toDouble()).toFloat() }
        var sum = 0f
        for (v in out) sum += v
        val inverse = 1f / sum
        for (i in out.indices) out[i] *= inverse
        return out
    }

    /** First index of the maximum, as `torch.argmax`. */
    fun argmax(values: FloatArray): Int {
        var best = 0
        for (i in 1 until values.size) if (values[i] > values[best]) best = i
        return best
    }

    fun answer(q: Question, logits: FloatArray): Answer {
        val p32 = softmax(logits)
        val p = DoubleArray(p32.size) { p32[it].toDouble() }
        val best = argmax(p32)
        return when (q) {
            is Question.Choice -> {
                val keys = q.criteria.keys.toList()
                Answer.Choice(keys[best], LinkedHashMap(keys.indices.associate { keys[it] to p[it] }), LayaDecode.confidence(p))
            }
            is Question.Score -> {
                var s = 0.0
                for (i in p.indices) s += i * p[i]
                Answer.Score(s, LinkedHashMap(q.criteria.indices.associate { it.toString() to q.criteria[it] }), LinkedHashMap(p.indices.associate { it.toString() to p[it] }), LayaDecode.confidence(p))
            }
            is Question.Noul -> Answer.Noul(p[1], max(p[1], 1.0 - p[1]))
        }
    }
}
