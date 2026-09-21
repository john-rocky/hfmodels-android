package io.github.johnrocky.hfmodels.litert

import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.decide.Json
import java.io.File
import java.text.Normalizer

/**
 * A Hugging Face `tokenizer.json` BPE tokenizer, without JNI, for the two pipelines the
 * catalogued decision encoders use:
 *  - byte-level BPE (GPT-2 style: NFC, the GPT-2 regex, bytes mapped to printable characters), and
 *  - SentencePiece-style BPE (`Replace " " -> "▁"`, `Metaspace` with `prepend_scheme: always`,
 *    `byte_fallback`, `fuse_unk`).
 * Added tokens are matched leftmost-longest on the raw text before anything else, with `lstrip` /
 * `rstrip`. `encode` never adds the template's special tokens; the sequence builder places them.
 * Parity with the upstream tokenizers is checked by `HfTokenizerTest` against ids the publisher's
 * own tokenizer produced.
 */
internal class HfTokenizer private constructor(
    private val kind: Kind,
    private val vocab: HashMap<String, Int>,
    /** (leftId shl 32 | rightId) -> (rank shl 32 | mergedId) */
    private val merges: HashMap<Long, Long>,
    private val added: List<Added>,
    private val nfc: Boolean,
    private val byteFallback: Boolean,
    private val fuseUnk: Boolean,
    private val unkId: Int,
    val clsId: Int,
    val sepId: Int,
    val maskId: Int,
    val maskToken: String,
    val padId: Int,
    /** byte value -> vocab id of its byte-level character (byte-level BPE only). */
    private val byteToId: IntArray?,
) {
    enum class Kind { BYTE_LEVEL, METASPACE }

    class Added(val content: String, val id: Int, val lstrip: Boolean, val rstrip: Boolean)

    val vocabSize: Int get() = vocab.size

    /** Token ids of `text`, no special tokens added. */
    fun encode(text: String): IntArray {
        if (text.isEmpty()) return IntArray(0)
        val out = IntList()
        var i = 0
        var segStart = 0
        while (i < text.length) {
            val m = matchAdded(text, i)
            if (m == null) { i++; continue }
            var start = i
            var end = i + m.content.length
            if (m.lstrip) while (start > segStart && Character.isWhitespace(text[start - 1])) start--
            if (m.rstrip) while (end < text.length && Character.isWhitespace(text[end])) end++
            if (start > segStart) encodePlain(text.substring(segStart, start), out)
            out.add(m.id)
            i = end
            segStart = end
        }
        if (segStart < text.length) encodePlain(text.substring(segStart), out)
        return out.toArray()
    }

    private val addedByFirst: Map<Char, List<Added>> = added.groupBy { it.content[0] }.mapValues { (_, v) -> v.sortedByDescending { it.content.length } }

    private fun matchAdded(text: String, at: Int): Added? {
        val cands = addedByFirst[text[at]] ?: return null
        for (a in cands) if (text.startsWith(a.content, at)) return a
        return null
    }

    private fun encodePlain(seg: String, out: IntList) {
        when (kind) {
            Kind.BYTE_LEVEL -> {
                val s = if (nfc) Normalizer.normalize(seg, Normalizer.Form.NFC) else seg
                val table = byteToId!!
                gpt2Pieces(s) { piece ->
                    val bytes = piece.toByteArray(Charsets.UTF_8)
                    val syms = IntArray(bytes.size) { k -> table[bytes[k].toInt() and 0xFF].let { if (it < 0) unkId else it } }
                    bpe(syms, out)
                }
            }
            Kind.METASPACE -> {
                var s = seg.replace(' ', '▁')
                if (s.isEmpty()) return
                if (!s.startsWith('▁')) s = "▁$s"
                // Split before every "▁" (MergedWithNext): each piece starts with one.
                var start = 0
                for (k in 1 until s.length) if (s[k] == '▁') { pieceMetaspace(s.substring(start, k), out); start = k }
                pieceMetaspace(s.substring(start), out)
            }
        }
    }

    private val byteTokenIds: IntArray? = if (byteFallback) IntArray(256) { b -> vocab[String.format("<0x%02X>", b)] ?: -1 }.takeIf { arr -> arr.all { it >= 0 } } else null
    private val charIdCache = HashMap<Int, Int>()

    private fun pieceMetaspace(piece: String, out: IntList) {
        // Symbols per Unicode code point; a code point outside the vocab becomes its UTF-8 bytes
        // (`<0xNN>` tokens) or the unknown token (consecutive unknowns fused).
        val syms = IntList()
        var pendingUnk = false
        var i = 0
        while (i < piece.length) {
            val cp = piece.codePointAt(i)
            val n = Character.charCount(cp)
            val cached = charIdCache[cp]
            val id = cached ?: (vocab[piece.substring(i, i + n)] ?: -1).also { charIdCache[cp] = it }
            if (id >= 0) {
                if (pendingUnk) { syms.add(unkId); pendingUnk = false }
                syms.add(id)
            } else if (byteTokenIds != null) {
                if (pendingUnk) { syms.add(unkId); pendingUnk = false }
                for (b in piece.substring(i, i + n).toByteArray(Charsets.UTF_8)) syms.add(byteTokenIds[b.toInt() and 0xFF])
            } else {
                if (pendingUnk && !fuseUnk) syms.add(unkId)
                pendingUnk = true
            }
            i += n
        }
        if (pendingUnk) syms.add(unkId)
        bpe(syms.toArray(), out)
    }

    /** Merges by rank, leftmost first among equals, exactly the upstream `Word::merge_all` order. */
    private fun bpe(symbols: IntArray, out: IntList) {
        var ids = symbols
        var n = ids.size
        while (n >= 2) {
            var best = -1
            var bestRank = Int.MAX_VALUE
            var bestMerged = 0
            for (i in 0 until n - 1) {
                val m = merges[(ids[i].toLong() shl 32) or (ids[i + 1].toLong() and 0xFFFFFFFFL)] ?: continue
                val rank = (m ushr 32).toInt()
                if (rank < bestRank) { bestRank = rank; best = i; bestMerged = (m and 0xFFFFFFFFL).toInt() }
            }
            if (best < 0) break
            ids[best] = bestMerged
            System.arraycopy(ids, best + 2, ids, best + 1, n - best - 2)
            n--
        }
        for (i in 0 until n) out.add(ids[i])
    }

    companion object {
        /**
         * GPT-2's pre-tokenization, `'s|'t|'re|'ve|'m|'ll|'d| ?\p{L}+| ?\p{N}+| ?[^\s\p{L}\p{N}]+|\s+(?!\S)|\s+`,
         * as a scanner over code points (Android's regex engine lacks the Unicode class flag the
         * JVM needs for `\s`, and the two must agree with each other and with the upstream).
         */
        internal fun gpt2Pieces(s: String, emit: (String) -> Unit) {
            val n = s.length
            var i = 0
            while (i < n) {
                if (s[i] == '\'') {
                    val c = CONTRACTIONS.firstOrNull { s.startsWith(it, i) }
                    if (c != null) { emit(s.substring(i, i + c.length)); i += c.length; continue }
                }
                val j = if (s[i] == ' ') i + 1 else i
                if (j < n) {
                    val cp = s.codePointAt(j)
                    val cls = classOf(cp)
                    if (cls != WHITE) {
                        var k = j
                        while (k < n) { val c2 = s.codePointAt(k); if (classOf(c2) != cls) break; k += Character.charCount(c2) }
                        emit(s.substring(i, k)); i = k; continue
                    }
                }
                // whitespace run; if followed by a non-space, leave its last character for the next piece
                var k = i
                while (k < n) { val c2 = s.codePointAt(k); if (classOf(c2) != WHITE) break; k += Character.charCount(c2) }
                if (k < n && k - i > 1) { emit(s.substring(i, k - 1)); i = k - 1 } else { emit(s.substring(i, k)); i = k }
            }
        }
        private val CONTRACTIONS = listOf("'s", "'t", "'re", "'ve", "'m", "'ll", "'d")
        private const val LETTER = 1; private const val NUMBER = 2; private const val OTHER = 3; private const val WHITE = 4
        private fun classOf(cp: Int): Int = when {
            isWhiteSpace(cp) -> WHITE
            Character.isLetter(cp) -> LETTER
            Character.getType(cp).let { it == Character.DECIMAL_DIGIT_NUMBER.toInt() || it == Character.LETTER_NUMBER.toInt() || it == Character.OTHER_NUMBER.toInt() } -> NUMBER
            else -> OTHER
        }
        /** Unicode `White_Space`, what `\s` means in the upstream regex. */
        private fun isWhiteSpace(cp: Int): Boolean = cp in 0x9..0xD || cp == 0x20 || cp == 0x85 || cp == 0xA0 || cp == 0x1680 || cp in 0x2000..0x200A || cp == 0x2028 || cp == 0x2029 || cp == 0x202F || cp == 0x205F || cp == 0x3000
        private val BYTE_TO_CHAR: CharArray = CharArray(256).also { t ->
            val direct = (33..126) + (161..172) + (174..255)
            var n = 0
            for (b in 0 until 256) t[b] = if (b in direct) b.toChar() else (256 + n++).toChar()
        }
        /** Loads `tokenizer.json` (+ `tokenizer_config.json` for the special token names). */
        fun load(tokenizerJson: File, tokenizerConfig: File): HfTokenizer {
            val cfg = Json.parseObject(tokenizerConfig.readText())
            fun tok(key: String) = (cfg[key] as? String) ?: ((cfg[key] as? Map<*, *>)?.get("content") as? String) ?: throw invalid("tokenizer_config.json has no '$key'")
            val clsTok = tok("cls_token"); val sepTok = tok("sep_token"); val maskTok = tok("mask_token"); val padTok = tok("pad_token")
            val unkTok = (cfg["unk_token"] as? String) ?: ((cfg["unk_token"] as? Map<*, *>)?.get("content") as? String)

            var kind: Kind? = null
            var nfc = false
            var byteFallback = false
            var fuseUnk = false
            var modelUnk: String? = null
            val vocab = HashMap<String, Int>(65536)
            val mergeList = ArrayList<String>()   // "a b" per merge in rank order
            val added = ArrayList<Added>()
            val addedIds = HashMap<String, Int>()
            tokenizerJson.bufferedReader().use { r ->
                val js = JsonStream(r)
                if (js.next() != JsonStream.Token.BEGIN_OBJECT) throw invalid("tokenizer.json is not an object")
                while (true) {
                    val t = js.next()
                    if (t == JsonStream.Token.END_OBJECT || t == JsonStream.Token.END) break
                    if (t != JsonStream.Token.NAME) throw invalid("unexpected $t")
                    when (js.text) {
                        "normalizer" -> {
                            val v = js.next()
                            if (v == JsonStream.Token.BEGIN_OBJECT) {
                                val types = ArrayList<String>()
                                readObjectStrings(js, "type", types)
                                for (ty in types) when (ty) {
                                    "NFC" -> nfc = true
                                    "Replace" -> {}   // the Metaspace pre-tokenizer applies the same replacement
                                    "Sequence" -> {}
                                    else -> throw invalid("normalizer '$ty' is not supported")
                                }
                            } else js.skipValue(v)
                        }
                        "pre_tokenizer" -> {
                            val v = js.next()
                            if (v == JsonStream.Token.BEGIN_OBJECT) {
                                val types = ArrayList<String>()
                                readObjectStrings(js, "type", types)
                                kind = when {
                                    "ByteLevel" in types -> Kind.BYTE_LEVEL
                                    "Metaspace" in types -> Kind.METASPACE
                                    else -> throw invalid("pre_tokenizer $types is not supported (ByteLevel or Metaspace)")
                                }
                            } else throw invalid("tokenizer.json has no pre_tokenizer")
                        }
                        "added_tokens" -> {
                            if (js.next() != JsonStream.Token.BEGIN_ARRAY) throw invalid("added_tokens is not an array")
                            while (true) {
                                val e = js.next()
                                if (e == JsonStream.Token.END_ARRAY) break
                                if (e != JsonStream.Token.BEGIN_OBJECT) throw invalid("added token is not an object")
                                var content = ""; var id = -1; var lstrip = false; var rstrip = false
                                while (true) {
                                    val f = js.next()
                                    if (f == JsonStream.Token.END_OBJECT) break
                                    when (js.text) {
                                        "content" -> content = js.scalar()
                                        "id" -> id = js.scalar().toInt()
                                        "lstrip" -> lstrip = js.scalar() == "true"
                                        "rstrip" -> rstrip = js.scalar() == "true"
                                        else -> js.skipValue(js.next())
                                    }
                                }
                                if (content.isNotEmpty() && id >= 0) { added += Added(content, id, lstrip, rstrip); addedIds[content] = id }
                            }
                        }
                        "model" -> {
                            if (js.next() != JsonStream.Token.BEGIN_OBJECT) throw invalid("model is not an object")
                            while (true) {
                                val f = js.next()
                                if (f == JsonStream.Token.END_OBJECT) break
                                when (js.text) {
                                    "type" -> if (js.scalar() != "BPE") throw invalid("model type '${js.text}' is not supported (BPE)")
                                    "byte_fallback" -> byteFallback = js.scalar() == "true"
                                    "fuse_unk" -> fuseUnk = js.scalar() == "true"
                                    "unk_token" -> { val v = js.next(); if (v == JsonStream.Token.STRING) modelUnk = js.text else js.skipValue(v) }
                                    "vocab" -> {
                                        if (js.next() != JsonStream.Token.BEGIN_OBJECT) throw invalid("vocab is not an object")
                                        while (true) {
                                            val k = js.next()
                                            if (k == JsonStream.Token.END_OBJECT) break
                                            val token = js.text
                                            vocab[token] = js.scalar().toInt()
                                        }
                                    }
                                    "merges" -> {
                                        if (js.next() != JsonStream.Token.BEGIN_ARRAY) throw invalid("merges is not an array")
                                        while (true) {
                                            val m = js.next()
                                            if (m == JsonStream.Token.END_ARRAY) break
                                            if (m == JsonStream.Token.STRING) mergeList += js.text
                                            else if (m == JsonStream.Token.BEGIN_ARRAY) {
                                                js.next(); val a = js.text; js.next(); val b = js.text
                                                if (js.next() != JsonStream.Token.END_ARRAY) throw invalid("merge pair with more than two parts")
                                                mergeList += "$a\u0000$b"
                                            } else throw invalid("merge entry $m")
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
            val k = kind ?: throw invalid("tokenizer.json has no pre_tokenizer")
            if (vocab.isEmpty()) throw invalid("empty vocab")
            val merges = HashMap<Long, Long>(mergeList.size * 2)
            for ((rank, m) in mergeList.withIndex()) {
                val a: String; val b: String
                if (m.contains('\u0000')) { a = m.substringBefore('\u0000'); b = m.substringAfter('\u0000') }
                else { val sp = m.indexOf(' '); if (sp < 0) continue; a = m.substring(0, sp); b = m.substring(sp + 1) }
                val ia = vocab[a] ?: continue
                val ib = vocab[b] ?: continue
                val iab = vocab[a + b] ?: continue
                merges[(ia.toLong() shl 32) or (ib.toLong() and 0xFFFFFFFFL)] = (rank.toLong() shl 32) or (iab.toLong() and 0xFFFFFFFFL)
            }
            // A byte-level vocab may lack the bytes that never occur in valid UTF-8 (0xC0, 0xC1, 0xF5-0xFF); they map to unk.
            val byteToId = if (k == Kind.BYTE_LEVEL) IntArray(256) { b -> vocab[BYTE_TO_CHAR[b].toString()] ?: -1 } else null
            fun idOf(token: String) = addedIds[token] ?: vocab[token] ?: throw invalid("special token '$token' is not in the vocab")
            val unkId = (unkTok ?: modelUnk)?.let { addedIds[it] ?: vocab[it] } ?: -1
            return HfTokenizer(k, vocab, merges, added, nfc, byteFallback, fuseUnk, unkId, idOf(clsTok), idOf(sepTok), idOf(maskTok), maskTok, idOf(padTok), byteToId)
        }

        /** Collects every string value under `key` in the object just opened (recursing into nested objects/arrays). */
        private fun readObjectStrings(js: JsonStream, key: String, out: MutableList<String>) {
            var depth = 1
            var lastName = ""
            while (depth > 0) {
                when (val t = js.next()) {
                    JsonStream.Token.BEGIN_OBJECT, JsonStream.Token.BEGIN_ARRAY -> depth++
                    JsonStream.Token.END_OBJECT, JsonStream.Token.END_ARRAY -> depth--
                    JsonStream.Token.NAME -> lastName = js.text
                    JsonStream.Token.STRING -> { if (lastName == key) out += js.text; lastName = "" }
                    JsonStream.Token.END -> throw invalid("unexpected end")
                    else -> { if (t == JsonStream.Token.NUMBER || t == JsonStream.Token.BOOLEAN || t == JsonStream.Token.NULL) lastName = "" }
                }
            }
        }

        private fun invalid(msg: String) = ModelException(ErrorCode.MANIFEST_INVALID, "tokenizer: $msg")
    }
}

/** A growable int array (no boxing). */
internal class IntList(capacity: Int = 64) {
    private var a = IntArray(capacity)
    var size = 0
        private set
    fun add(v: Int) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
    fun addAll(v: IntArray) { for (x in v) add(x) }
    operator fun get(i: Int) = a[i]
    fun toArray(): IntArray = a.copyOf(size)
}
