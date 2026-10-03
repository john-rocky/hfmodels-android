// Ported from litert-community/GLiNER2.5-Decide-LiteRT@db80197282d11373df084c0ceed67a54544cfa84, android/app/src/main/java/com/gliner25decide/GlinerTokenizer.kt (Apache-2.0, same author): JSON read with JsonStream and errors as ModelException; normalizer, added-token matching and lattice unchanged.
package io.github.johnrocky.hfmodels.litert

import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.ModelException
import java.io.File
import java.text.Normalizer
import java.util.regex.Pattern

/**
 * A SentencePiece Unigram `tokenizer.json` (DeBERTa-v3's), without JNI, for encoders that tokenize
 * every word on its own (gliner2 2.0.0 `SchemaTransformer._format_input_with_mapping`): [encodeWord]
 * returns one word's ids with no CLS / SEP. The published normalizer (Replace `\s{2,}|[\n\r\t]` -> " ",
 * NFC, Strip right) and pre-tokenizer (Metaspace, `prepend_scheme: always`, split) are checked at load
 * and reproduced; anything else is refused. Lattice scores stay doubles to keep Hugging Face
 * tokenizers' path selection; there is no byte fallback (a character outside the vocabulary is the
 * unknown token, consecutive unknowns fused). Added tokens marked `normalized: false` are matched in the
 * raw text first; those marked `normalized: true` (GLiNER2.5-Decide's `[UNK]`) are matched in each
 * normalized segment, the way `AddedVocabulary::extract_and_normalize` does it.
 */
internal class UnigramTokenizer private constructor(
    private val vocabulary: HashMap<String, Piece>,
    /** Matched in the raw text, before normalization (`normalized: false`). */
    private val addedTokens: HashMap<String, Int>,
    /** Matched in each normalized segment (`normalized: true`), keyed by the normalized content. */
    private val normalizedAddedTokens: HashMap<String, Int>,
    private val maxPieceLength: Int,
    private val unknownScore: Double,
    private val unknownId: Int,
    /** `[PAD]`, the right padding (a host-lookup graph looks its row up too). */
    val padId: Int,
    /** Token ids: the Unigram vocabulary plus the added tokens. */
    val vocabularySize: Int,
) {
    private class Piece(val id: Int, val score: Double)

    /** One word's ids, no special tokens added. Added tokens are identified before normalization, even inside a word. */
    fun encodeWord(text: String): IntArray {
        if (text.isEmpty()) return IntArray(0)
        addedTokens[text]?.let { return intArrayOf(it) }
        val result = ArrayList<Int>()
        var from = 0
        while (from < text.length) {
            val (next, match) = leftmostLongest(text, from, addedTokens.keys)
            if (next > from) encodeNormalized(text.substring(from, next), result)
            val special = match ?: break
            result.add(addedTokens.getValue(special))
            from = next + special.length
        }
        return result.toIntArray()
    }

    private fun encodeNormalized(text: String, result: MutableList<Int>) {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return
        // Normalized added tokens split the normalized segment; its pieces are not normalized again.
        var from = 0
        while (from < normalized.length) {
            val (next, match) = leftmostLongest(normalized, from, normalizedAddedTokens.keys)
            if (next > from) encodePreTokenized(normalized.substring(from, next), result)
            val special = match ?: break
            result.add(normalizedAddedTokens.getValue(special))
            from = next + special.length
        }
    }

    private fun encodePreTokenized(normalized: String, result: MutableList<Int>) {
        val escaped = normalized.replace(' ', '▁')
        val prefixed = if (escaped.startsWith('▁')) escaped else "▁$escaped"
        // Metaspace split=true starts a new pre-token at every replacement.
        var start = 0
        for (i in 1 until prefixed.length) {
            if (prefixed[i] == '▁') {
                result.addAll(viterbi(prefixed.substring(start, i)))
                start = i
            }
        }
        result.addAll(viterbi(prefixed.substring(start)))
    }

    private fun viterbi(text: String): List<Int> {
        val best = DoubleArray(text.length + 1) { Double.NEGATIVE_INFINITY }
        val backPosition = IntArray(text.length + 1)
        val backId = IntArray(text.length + 1)
        best[0] = 0.0
        var position = 0
        while (position < text.length) {
            val characterLength = Character.charCount(text.codePointAt(position))
            var hasSingleCharacter = false
            if (best[position] != Double.NEGATIVE_INFINITY) {
                val limit = minOf(text.length, position + maxPieceLength)
                for (end in position + 1..limit) {
                    val piece = vocabulary[text.substring(position, end)] ?: continue
                    if (end - position == characterLength) hasSingleCharacter = true
                    val score = best[position] + piece.score
                    if (score > best[end]) {
                        best[end] = score
                        backPosition[end] = position
                        backId[end] = piece.id
                    }
                }
                // Hugging Face Unigram adds UNK only when there is no vocabulary piece for this single Unicode scalar.
                if (!hasSingleCharacter) {
                    val end = position + characterLength
                    val score = best[position] + unknownScore
                    if (score > best[end]) {
                        best[end] = score
                        backPosition[end] = position
                        backId[end] = unknownId
                    }
                }
            }
            position += characterLength
        }
        val reversed = ArrayList<Int>()
        position = text.length
        while (position > 0) {
            val id = backId[position]
            // Unigram fuses consecutive unknown characters into one UNK.
            if (id != unknownId || reversed.lastOrNull() != unknownId) reversed.add(id)
            val previous = backPosition[position]
            check(previous < position) { "Unigram lattice has no path" }
            position = previous
        }
        reversed.reverse()
        return reversed
    }

    companion object {
        // Android rejects UNICODE_CHARACTER_CLASS; spelling out Unicode White_Space keeps the published
        // normalizer identical on Android's ICU regex engine and the desktop JVM.
        private val REPEATED_WHITESPACE = Pattern.compile(
            "[\\x{09}-\\x{0d}\\x{20}\\x{85}\\x{a0}\\x{1680}\\x{2000}-\\x{200a}\\x{2028}\\x{2029}\\x{202f}\\x{205f}\\x{3000}]{2,}|[\\n\\r\\t]",
        )

        /** The published normalizer: Replace(`\s{2,}|[\n\r\t]` -> " "), NFC, Strip(right). */
        private fun normalize(text: String): String {
            val replaced = REPEATED_WHITESPACE.matcher(text).replaceAll(" ")
            return Normalizer.normalize(replaced, Normalizer.Form.NFC).trimEnd { isUnicodeWhitespace(it.code) }
        }

        /**
         * Aho-Corasick LeftmostLongest, as Hugging Face tokenizers matches added tokens: the earliest start wins,
         * then the longest token at that start. Returns (text.length, null) when none.
         */
        private fun leftmostLongest(text: String, from: Int, tokens: Set<String>): Pair<Int, String?> {
            var next = text.length
            var match: String? = null
            for (token in tokens) {
                val position = text.indexOf(token, from)
                if (position >= 0 && (position < next || (position == next && token.length > (match?.length ?: 0)))) {
                    next = position
                    match = token
                }
            }
            return next to match
        }

        /** Unicode White_Space, used by Rust's char::is_whitespace for Strip. */
        private fun isUnicodeWhitespace(codePoint: Int): Boolean =
            codePoint in 0x09..0x0d || codePoint == 0x20 || codePoint == 0x85 || codePoint == 0xa0 || codePoint == 0x1680 ||
                codePoint in 0x2000..0x200a || codePoint == 0x2028 || codePoint == 0x2029 || codePoint == 0x202f || codePoint == 0x205f || codePoint == 0x3000

        /** Loads `tokenizer.json`; the vocabulary is streamed, the small sections are read whole and checked against the published contract. */
        fun load(tokenizerJson: File): UnigramTokenizer {
            val vocabulary = HashMap<String, Piece>(170_000)
            var vocabSize = 0
            var minScore = Double.POSITIVE_INFINITY
            var maxLength = 0
            var modelType: String? = null
            var unkId = -1
            var byteFallback = false
            var normalizer: Any? = null
            var preTokenizer: Any? = null
            var added: Any? = null
            tokenizerJson.bufferedReader().use { r ->
                val js = JsonStream(r)
                if (js.next() != JsonStream.Token.BEGIN_OBJECT) throw invalid("tokenizer.json is not an object")
                while (true) {
                    val t = js.next()
                    if (t == JsonStream.Token.END_OBJECT || t == JsonStream.Token.END) break
                    if (t != JsonStream.Token.NAME) throw invalid("unexpected $t")
                    when (js.text) {
                        "normalizer" -> normalizer = value(js, js.next())
                        "pre_tokenizer" -> preTokenizer = value(js, js.next())
                        "added_tokens" -> added = value(js, js.next())
                        "model" -> {
                            if (js.next() != JsonStream.Token.BEGIN_OBJECT) throw invalid("model is not an object")
                            while (true) {
                                val f = js.next()
                                if (f == JsonStream.Token.END_OBJECT) break
                                when (js.text) {
                                    "type" -> modelType = js.scalar()
                                    "unk_id" -> { val v = js.next(); if (v == JsonStream.Token.NUMBER) unkId = js.text.toInt() else js.skipValue(v) }
                                    "byte_fallback" -> byteFallback = js.scalar() == "true"
                                    "vocab" -> {
                                        if (js.next() != JsonStream.Token.BEGIN_ARRAY) throw invalid("vocab is not an array")
                                        while (true) {
                                            val e = js.next()
                                            if (e == JsonStream.Token.END_ARRAY) break
                                            if (e != JsonStream.Token.BEGIN_ARRAY || js.next() != JsonStream.Token.STRING) throw invalid("vocab entry $vocabSize is not [piece, score]")
                                            val text = js.text
                                            if (js.next() != JsonStream.Token.NUMBER) throw invalid("vocab entry $vocabSize has no score")
                                            val score = js.text.toDouble()
                                            if (js.next() != JsonStream.Token.END_ARRAY) throw invalid("vocab entry $vocabSize has more than two parts")
                                            vocabulary[text] = Piece(vocabSize, score)
                                            minScore = minOf(minScore, score)
                                            maxLength = maxOf(maxLength, text.length)
                                            vocabSize++
                                        }
                                    }
                                    else -> js.skipValue(js.next())
                                }
                            }
                        }
                        else -> js.skipValue(js.next())
                    }
                }
            }
            if (modelType != "Unigram") throw invalid("model type '$modelType' is not supported (Unigram)")
            if (byteFallback) throw invalid("byte-fallback Unigram tokenizers are not supported")
            if (vocabSize == 0 || unkId !in 0 until vocabSize) throw invalid("no vocabulary or no unk_id")
            checkNormalization(normalizer, preTokenizer)
            val addedTokens = HashMap<String, Int>()
            val normalizedAdded = HashMap<String, Int>()
            var size = vocabSize
            for (a in added as? List<*> ?: throw invalid("added_tokens is not an array")) {
                val token = a as? Map<*, *> ?: throw invalid("added token is not an object")
                if (token["lstrip"] == true || token["rstrip"] == true || token["single_word"] == true) throw invalid("added-token matching policy of '${token["content"]}' is not supported")
                val content = token["content"] as? String ?: throw invalid("added token without content")
                val id = (token["id"] as? Double)?.toInt() ?: throw invalid("added token '$content' has no id")
                if (token["normalized"] == true) {
                    val normalized = normalize(content)
                    if (normalized.isEmpty()) throw invalid("added token normalizes to nothing: $content")
                    normalizedAdded[normalized] = id
                } else addedTokens[content] = id
                size = maxOf(size, id + 1)
            }
            val padId = addedTokens["[PAD]"] ?: throw invalid("no [PAD] among the added tokens")
            return UnigramTokenizer(vocabulary, addedTokens, normalizedAdded, maxLength, minScore - 10.0, unkId, padId, size)
        }

        private fun checkNormalization(normalizer: Any?, preTokenizer: Any?) {
            val ns = (normalizer as? Map<*, *>)?.get("normalizers") as? List<*>
            val n0 = ns?.getOrNull(0) as? Map<*, *>
            val n2 = ns?.getOrNull(2) as? Map<*, *>
            val normalizerOk = ns != null && ns.size == 3 &&
                n0?.get("type") == "Replace" && (n0["pattern"] as? Map<*, *>)?.get("Regex") == "\\s{2,}|[\\n\\r\\t]" && n0["content"] == " " &&
                (ns[1] as? Map<*, *>)?.get("type") == "NFC" &&
                n2?.get("type") == "Strip" && n2["strip_left"] == false && n2["strip_right"] == true
            if (!normalizerOk) throw invalid("normalizer differs from the published DeBERTa-v3 contract (Replace, NFC, Strip right)")
            val ps = (preTokenizer as? Map<*, *>)?.get("pretokenizers") as? List<*>
            val p0 = ps?.getOrNull(0) as? Map<*, *>
            val preOk = ps != null && ps.size == 1 && p0?.get("type") == "Metaspace" && p0["replacement"] == "▁" && p0["prepend_scheme"] == "always" && p0["split"] == true
            if (!preOk) throw invalid("pre-tokenizer differs from the published DeBERTa-v3 contract (Metaspace, prepend_scheme always, split)")
        }

        /** The value whose first token was just read, as maps (key order kept), lists, strings, doubles, booleans and nulls. */
        private fun value(js: JsonStream, t: JsonStream.Token): Any? = when (t) {
            JsonStream.Token.BEGIN_OBJECT -> LinkedHashMap<String, Any?>().also { m ->
                while (true) {
                    val k = js.next()
                    if (k == JsonStream.Token.END_OBJECT) break
                    if (k != JsonStream.Token.NAME) throw invalid("unexpected $k in an object")
                    val name = js.text
                    m[name] = value(js, js.next())
                }
            }
            JsonStream.Token.BEGIN_ARRAY -> ArrayList<Any?>().also { a ->
                while (true) {
                    val e = js.next()
                    if (e == JsonStream.Token.END_ARRAY) break
                    a += value(js, e)
                }
            }
            JsonStream.Token.STRING -> js.text
            JsonStream.Token.NUMBER -> js.text.toDouble()
            JsonStream.Token.BOOLEAN -> js.text == "true"
            JsonStream.Token.NULL -> null
            else -> throw invalid("unexpected $t")
        }

        private fun invalid(msg: String) = ModelException(ErrorCode.MANIFEST_INVALID, "tokenizer: $msg")
    }
}
