// Ported from litert-community/GLiClass-Edge-v3.0-LiteRT@88c90950587eb951974c094eef91afa0fe3552c0, android/sample/app/src/main/java/com/gliclass/GliclassTokenizer.kt (Apache-2.0, same author): JSON read with JsonStream and errors as ModelException; added-token matching, normalization, pre-tokenization and BPE unchanged.
package io.github.johnrocky.hfmodels.litert

import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.ModelException
import java.io.File
import java.text.Normalizer
import java.util.PriorityQueue
import java.util.regex.Pattern

/**
 * A byte-level BPE `tokenizer.json` whose pre-tokenizer adds a prefix space (ModernBERT / Ettin as GLiClass-Edge
 * v3.0 ships it), without JNI. [encode] returns `[CLS] … [SEP]` like `Tokenizer.encode(text)` with special tokens,
 * in the library's order:
 * 1. split the raw text on the added tokens marked `normalized: false` (`<<LABEL>>`, `<<SEP>>`, `[CLS]`, …;
 *    `[MASK]` also takes the whitespace on its left);
 * 2. NFC-normalize each remaining segment, then split it on the added tokens marked `normalized: true` (the
 *    2–24 space runs, `[unused0]`–`[unused82]`, `|||…|||`);
 * 3. per remaining split: prepend one space unless it starts with U+0020 (`add_prefix_space` applies to every
 *    split, so the text after `<<SEP>>` starts with `Ġ`), split with the GPT-2 regex, map UTF-8 bytes to GPT-2's
 *    byte characters and apply the BPE merges by rank;
 * 4. add `[CLS]` and `[SEP]` (the template `[CLS] $A [SEP]`, checked at load).
 * [HfTokenizer] covers byte-level BPE without the prefix space and matches every added token in the raw text in
 * one pass; this class is the pipeline that differs. Added tokens are matched leftmost-longest. The regex spells
 * out the whitespace class because Java's `\s` is ASCII-only and Android rejects `UNICODE_CHARACTER_CLASS`; the
 * class is onig's Unicode `\s` (Unicode White_Space).
 */
internal class ByteLevelBpeTokenizer private constructor(
    private val merges: HashMap<Long, Merge>,
    private val addedIds: HashMap<String, Int>,
    /** `normalized: false`: matched in the raw text. */
    private val rawTokens: TrieNode,
    /** `normalized: true`: matched in each NFC-normalized segment. */
    private val normalizedTokens: TrieNode,
    /** Vocabulary id of each byte's single GPT-2 character; -1 if the vocabulary has none. */
    private val byteIds: IntArray,
    /** `[CLS]`, added first. */
    val clsId: Int,
    /** `[SEP]`, added last. */
    val sepId: Int,
    /** `[PAD]`, the right padding (its embedding row is looked up too). */
    val padId: Int,
    /** Token ids: the BPE vocabulary plus the added tokens. */
    val vocabularySize: Int,
    private val vocabulary: HashMap<String, Int>,
) {
    private class AddedToken(val id: Int, val leftStrip: Boolean)

    private class TrieNode {
        val children = HashMap<Char, TrieNode>()
        var token: AddedToken? = null
    }

    private class Merge(val rank: Int, val id: Int)

    private class Candidate(val rank: Int, val left: Int, val right: Int, val leftId: Int, val rightId: Int, val id: Int)

    /** The id of an added token or vocabulary entry, or null. */
    fun tokenId(token: String): Int? = addedIds[token] ?: vocabulary[token]

    /** `[CLS]` + the token ids of [text] + `[SEP]`, as `Tokenizer.encode(text).ids`. */
    fun encode(text: String): IntArray {
        val ids = ArrayList<Int>(text.length / 3 + 2)
        ids.add(clsId)
        split(text, rawTokens, { segment -> split(normalize(segment), normalizedTokens, { piece -> byteLevel(piece, ids) }, { ids.add(it) }) }) { ids.add(it) }
        ids.add(sepId)
        return ids.toIntArray()
    }

    /**
     * Splits [text] on the tokens of [root] (leftmost-longest, non-overlapping) and reports the non-empty text
     * between them and each token's id in order. A token with `lstrip` also takes the whitespace to its left,
     * but never text before the previous token.
     */
    private fun split(text: String, root: TrieNode, onText: (String) -> Unit, onToken: (Int) -> Unit) {
        var unprocessed = 0
        var position = 0
        while (position < text.length) {
            var node = root
            var end = position
            var matched: AddedToken? = null
            var matchedEnd = position
            while (end < text.length) {
                node = node.children[text[end]] ?: break
                end++
                node.token?.let { matched = it; matchedEnd = end }
            }
            val token = matched
            if (token == null) { position++; continue }
            var begin = position
            if (token.leftStrip) {
                while (begin > unprocessed && isWhitespace(text.codePointBefore(begin))) begin -= Character.charCount(text.codePointBefore(begin))
            }
            if (begin > unprocessed) onText(text.substring(unprocessed, begin))
            onToken(token.id)
            position = matchedEnd
            unprocessed = matchedEnd
        }
        if (unprocessed < text.length) onText(text.substring(unprocessed))
    }

    /** ByteLevel pre-tokenizer (prefix space, GPT-2 regex) followed by BPE on every piece. */
    private fun byteLevel(split: String, output: MutableList<Int>) {
        val text = if (split.startsWith(' ')) split else " $split"
        val matcher = SPLIT_PATTERN.matcher(text)
        var last = 0
        while (matcher.find()) {
            if (matcher.start() > last) bpe(text.substring(last, matcher.start()), output)
            bpe(matcher.group(), output)
            last = matcher.end()
        }
        if (last < text.length) bpe(text.substring(last), output)
    }

    /**
     * BPE on the UTF-8 bytes of one piece: start from each byte's character, then repeatedly apply the lowest-rank
     * merge, leftmost first, as `tokenizers`' `Word::merge_all`. A byte without a vocabulary character is dropped
     * (the model has no unknown token); valid UTF-8 never has one.
     */
    private fun bpe(piece: String, output: MutableList<Int>) {
        val bytes = piece.toByteArray(Charsets.UTF_8)
        val symbols = ArrayList<Int>(bytes.size)
        for (byte in bytes) {
            val id = byteIds[byte.toInt() and 0xff]
            if (id >= 0) symbols.add(id)
        }
        if (symbols.size < 2) { output.addAll(symbols); return }
        val ids = symbols.toIntArray()
        val previous = IntArray(ids.size) { it - 1 }
        val next = IntArray(ids.size) { if (it + 1 < ids.size) it + 1 else -1 }
        val alive = BooleanArray(ids.size) { true }
        val queue = PriorityQueue<Candidate>(compareBy<Candidate> { it.rank }.thenBy { it.left })
        fun offer(left: Int) {
            if (left < 0 || !alive[left]) return
            val right = next[left]
            if (right < 0) return
            val merge = merges[key(ids[left], ids[right])] ?: return
            queue.add(Candidate(merge.rank, left, right, ids[left], ids[right], merge.id))
        }
        for (index in 0 until ids.size - 1) offer(index)
        while (queue.isNotEmpty()) {
            val candidate = queue.remove()
            val left = candidate.left
            val right = candidate.right
            // Skip entries made stale by an earlier merge on either side.
            if (!alive[left] || !alive[right] || next[left] != right || ids[left] != candidate.leftId || ids[right] != candidate.rightId) continue
            ids[left] = candidate.id
            alive[right] = false
            next[left] = next[right]
            if (next[right] >= 0) previous[next[right]] = left
            offer(previous[left])
            offer(left)
        }
        for (index in ids.indices) if (alive[index]) output.add(ids[index])
    }

    companion object {
        // onig's Unicode \s = Unicode White_Space; also Rust's char::is_whitespace for lstrip.
        private const val WHITESPACE = "\\x{09}-\\x{0d}\\x{20}\\x{85}\\x{a0}\\x{1680}\\x{2000}-\\x{200a}\\x{2028}\\x{2029}\\x{202f}\\x{205f}\\x{3000}"

        /** GPT-2's split regex with `\s` / `\S` spelled out for java.util.regex and Android ICU. */
        private val SPLIT_PATTERN: Pattern = Pattern.compile(
            "'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^$WHITESPACE\\p{L}\\p{N}]+|[$WHITESPACE]+(?![^$WHITESPACE])|[$WHITESPACE]+",
        )

        private fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)

        private fun key(left: Int, right: Int): Long = (left.toLong() shl 32) or (right.toLong() and 0xffffffffL)

        private fun isWhitespace(codePoint: Int): Boolean =
            codePoint in 0x09..0x0d || codePoint == 0x20 || codePoint == 0x85 || codePoint == 0xa0 || codePoint == 0x1680 ||
                codePoint in 0x2000..0x200a || codePoint == 0x2028 || codePoint == 0x2029 || codePoint == 0x202f || codePoint == 0x205f || codePoint == 0x3000

        /** GPT-2 `bytes_to_unicode`: every byte maps to one printable character. */
        private fun byteCharacters(): CharArray {
            val printable = (0x21..0x7e) + (0xa1..0xac) + (0xae..0xff)
            val map = CharArray(256)
            var extra = 0
            for (byte in 0 until 256) map[byte] = if (byte in printable) byte.toChar() else (256 + extra++).toChar()
            return map
        }

        private fun insert(root: TrieNode, content: String, token: AddedToken) {
            if (content.isEmpty()) throw invalid("empty added token")
            var node = root
            for (character in content) node = node.children.getOrPut(character) { TrieNode() }
            node.token = token
        }

        /** Loads `tokenizer.json`; the vocabulary and the merges are streamed, the small sections are read whole and checked against the contract above. */
        fun load(tokenizerJson: File): ByteLevelBpeTokenizer {
            val vocabulary = HashMap<String, Int>(70_000)
            val mergePairs = ArrayList<String>(60_000)   // "left\u0000right" in rank order
            val model = LinkedHashMap<String, Any?>()
            var normalizer: Any? = null
            var preTokenizer: Any? = null
            var postProcessor: Any? = null
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
                        "post_processor" -> postProcessor = value(js, js.next())
                        "added_tokens" -> added = value(js, js.next())
                        "model" -> {
                            if (js.next() != JsonStream.Token.BEGIN_OBJECT) throw invalid("model is not an object")
                            while (true) {
                                val f = js.next()
                                if (f == JsonStream.Token.END_OBJECT) break
                                when (val name = js.text) {
                                    "vocab" -> {
                                        if (js.next() != JsonStream.Token.BEGIN_OBJECT) throw invalid("vocab is not an object")
                                        while (true) {
                                            if (js.next() == JsonStream.Token.END_OBJECT) break
                                            val token = js.text
                                            vocabulary[token] = js.scalar().toInt()
                                        }
                                    }
                                    "merges" -> {
                                        if (js.next() != JsonStream.Token.BEGIN_ARRAY) throw invalid("merges is not an array")
                                        while (true) {
                                            when (js.next()) {
                                                JsonStream.Token.END_ARRAY -> break
                                                JsonStream.Token.STRING -> {
                                                    val space = js.text.indexOf(' ', 1)
                                                    if (space <= 0) throw invalid("merge ${mergePairs.size} is not a pair: ${js.text}")
                                                    mergePairs += js.text.substring(0, space) + "\u0000" + js.text.substring(space + 1)
                                                }
                                                JsonStream.Token.BEGIN_ARRAY -> {
                                                    if (js.next() != JsonStream.Token.STRING) throw invalid("merge ${mergePairs.size} is not a pair")
                                                    val left = js.text
                                                    if (js.next() != JsonStream.Token.STRING) throw invalid("merge ${mergePairs.size} is not a pair")
                                                    val right = js.text
                                                    if (js.next() != JsonStream.Token.END_ARRAY) throw invalid("merge ${mergePairs.size} has more than two parts")
                                                    mergePairs += left + "\u0000" + right
                                                }
                                                else -> throw invalid("merge entry ${mergePairs.size}")
                                            }
                                        }
                                    }
                                    else -> model[name] = value(js, js.next())
                                }
                            }
                        }
                        else -> js.skipValue(js.next())
                    }
                }
            }
            val modelOk = model["type"] == "BPE" && model["dropout"] == null && model["unk_token"] == null && model["continuing_subword_prefix"] == null &&
                model["end_of_word_suffix"] == null && model["byte_fallback"] != true && model["ignore_merges"] != true
            if (!modelOk) throw invalid("model differs from the byte-level BPE contract (BPE, no dropout / unk / prefixes / byte fallback / ignore_merges)")
            if ((normalizer as? Map<*, *>)?.get("type") != "NFC") throw invalid("normalizer is not NFC")
            val pre = preTokenizer as? Map<*, *>
            if (pre?.get("type") != "ByteLevel" || pre["add_prefix_space"] != true || pre["use_regex"] != true) throw invalid("pre-tokenizer differs from ByteLevel(add_prefix_space, use_regex)")
            if (vocabulary.isEmpty()) throw invalid("empty vocab")

            val merges = HashMap<Long, Merge>(mergePairs.size * 4 / 3 + 1)
            for ((rank, pair) in mergePairs.withIndex()) {
                val left = pair.substringBefore('\u0000')
                val right = pair.substringAfter('\u0000')
                val leftId = vocabulary[left] ?: throw invalid("merge $rank: unknown token $left")
                val rightId = vocabulary[right] ?: throw invalid("merge $rank: unknown token $right")
                val merged = vocabulary[left + right] ?: throw invalid("merge $rank: no token $left$right")
                // As tokenizers' merge map: a repeated pair keeps its last rank.
                merges[key(leftId, rightId)] = Merge(rank, merged)
            }
            val chars = byteCharacters()
            val byteIds = IntArray(256) { vocabulary[chars[it].toString()] ?: -1 }

            var size = (vocabulary.values.maxOrNull() ?: -1) + 1
            val addedIds = HashMap<String, Int>()
            val rawTokens = TrieNode()
            val normalizedTokens = TrieNode()
            for (a in added as? List<*> ?: throw invalid("added_tokens is not an array")) {
                val entry = a as? Map<*, *> ?: throw invalid("added token is not an object")
                val content = entry["content"] as? String ?: throw invalid("added token without content")
                if (entry["single_word"] == true || entry["rstrip"] == true) throw invalid("added-token matching policy of '$content' is not supported")
                val id = (entry["id"] as? Double)?.toInt() ?: throw invalid("added token '$content' has no id")
                val token = AddedToken(id, entry["lstrip"] == true)
                if (entry["normalized"] == true) insert(normalizedTokens, normalize(content), token) else insert(rawTokens, content, token)
                addedIds[content] = id
                size = maxOf(size, id + 1)
            }
            val clsId = addedIds["[CLS]"] ?: throw invalid("no [CLS] among the added tokens")
            val sepId = addedIds["[SEP]"] ?: throw invalid("no [SEP] among the added tokens")
            val padId = addedIds["[PAD]"] ?: throw invalid("no [PAD] among the added tokens")
            checkTemplate(postProcessor, clsId, sepId)
            return ByteLevelBpeTokenizer(merges, addedIds, rawTokens, normalizedTokens, byteIds, clsId, sepId, padId, size, vocabulary)
        }

        /** The post-processor must be the template `[CLS] $A [SEP]` with the added tokens' ids. */
        private fun checkTemplate(post: Any?, clsId: Int, sepId: Int) {
            val p = post as? Map<*, *>
            if (p?.get("type") != "TemplateProcessing") throw invalid("post-processor is not TemplateProcessing")
            val pieces = (p["single"] as? List<*>)?.map { item ->
                item as Map<*, *>
                ((item["SpecialToken"] as? Map<*, *>)?.get("id") as? String) ?: ("$" + ((item["Sequence"] as? Map<*, *>)?.get("id") as? String))
            }
            if (pieces != listOf("[CLS]", "\$A", "[SEP]")) throw invalid("template is not [CLS] \$A [SEP]: $pieces")
            val special = p["special_tokens"] as? Map<*, *>
            fun firstId(token: String) = ((special?.get(token) as? Map<*, *>)?.get("ids") as? List<*>)?.firstOrNull()?.let { (it as Double).toInt() }
            if (firstId("[CLS]") != clsId || firstId("[SEP]") != sepId) throw invalid("template special-token ids differ from the added tokens")
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
