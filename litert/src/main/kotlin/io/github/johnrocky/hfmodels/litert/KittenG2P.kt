// Ported from google-ai-edge/litert-samples c18346a8, samples/litert/text_to_speech_streaming/.../KittenG2P.kt (Apache-2.0).
package io.github.johnrocky.hfmodels.litert

import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import java.io.Closeable
import java.io.File
import java.util.zip.GZIPInputStream
import org.json.JSONObject

/**
 * Text to KittenTTS symbol ids without espeak at run time, as the LiteRT text_to_speech_streaming
 * sample does it: an espeak en-us IPA dictionary first (`g2p_dict.txt.gz`, word<TAB>ipa), [oov]
 * (the DeepPhonemizer graph, [KittenNeuralG2P]) for words it lacks. Host normalization: a run of two
 * or more capitals is spelled letter by letter ("GPU" -> "gee pee you"), numbers are read as words
 * ("4090" -> "four thousand ninety"), the word "I" is espeak's letter name (`ˈaɪ`), and punctuation
 * is its own space-separated token, as the pip package's `basic_english_tokenize` leaves it. The IPA
 * maps one character to one symbol of the 178-symbol table; characters outside the table are dropped.
 */
internal class KittenG2P(
    private val dictionary: Map<String, String>,
    private val symbolToId: Map<Char, Int>,
    private val oov: (String) -> String,
) {
    /** The text as space-separated IPA tokens. */
    fun ipa(text: String): String {
        val out = StringBuilder()
        fun append(phonemes: String) {
            if (phonemes.isEmpty()) return
            if (out.isNotEmpty()) out.append(' ')
            out.append(phonemes)
        }
        for (match in TOKEN.findAll(text)) {
            val token = match.value
            when {
                ACRONYM.matches(token) -> append(token.lowercase().mapNotNull { LETTER_IPA[it] }.joinToString(""))
                // The pronoun: the dictionary has no "i", and the graph's answer for it is not ours to rely on.
                token == "I" -> append(LETTER_IPA.getValue('i'))
                token[0].isDigit() -> for (word in numberToWords(token)) append(dictionary[word] ?: oov(word))
                WORD.matches(token) -> token.lowercase().let { append(dictionary[it] ?: oov(it)) }
                else -> append(token)
            }
        }
        return out.toString()
    }

    /** [ipa] as symbol ids, with the 0 the model expects at each end. */
    fun ids(text: String): IntArray {
        val ipa = ipa(text)
        val ids = ArrayList<Int>(ipa.length + 2)
        ids += 0
        for (ch in ipa) symbolToId[ch]?.let { ids += it }
        ids += 0
        return ids.toIntArray()
    }

    companion object {
        /** Case-aware tokens: ACRONYM (2+ caps) | NUMBER | word | punctuation. */
        private val TOKEN = Regex("[A-Z]{2,}|\\d[\\d,]*(?:\\.\\d+)?|[A-Za-z']+|[.,!?;:—…\"]")
        private val ACRONYM = Regex("[A-Z]{2,}")
        private val WORD = Regex("[A-Za-z']+")

        /** espeak letter-name IPA: acronyms are spelled out (e.g. "GPU" -> dʒˈiːpˈiːjˈuː). */
        private val LETTER_IPA = mapOf(
            'a' to "ˈeɪ", 'b' to "bˈiː", 'c' to "sˈiː", 'd' to "dˈiː", 'e' to "ˈiː",
            'f' to "ˈɛf", 'g' to "dʒˈiː", 'h' to "ˈeɪtʃ", 'i' to "ˈaɪ", 'j' to "dʒˈeɪ",
            'k' to "kˈeɪ", 'l' to "ˈɛl", 'm' to "ˈɛm", 'n' to "ˈɛn", 'o' to "ˈoʊ",
            'p' to "pˈiː", 'q' to "kjˈuː", 'r' to "ˈɑːɹ", 's' to "ˈɛs", 't' to "tˈiː",
            'u' to "jˈuː", 'v' to "vˈiː", 'w' to "dˈʌbəljˌuː", 'x' to "ˈɛks",
            'y' to "wˈaɪ", 'z' to "zˈiː",
        )
        private val ONES = arrayOf(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
        )
        private val TENS = arrayOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
        private val SCALES = arrayOf("", "thousand", "million", "billion", "trillion")

        /** "1,234.5" -> [one, thousand, two, hundred, thirty, four, point, five]. */
        fun numberToWords(raw: String): List<String> {
            val token = raw.replace(",", "")
            if (token.contains('.')) {
                val parts = token.split('.', limit = 2)
                val words = (if (parts[0].isNotEmpty()) integerToWords(parts[0].toLongOrNull() ?: 0L) else listOf("zero")).toMutableList()
                words += "point"
                for (digit in parts[1]) if (digit.isDigit()) words += ONES[digit - '0']
                return words
            }
            return integerToWords(token.toLongOrNull() ?: return emptyList())
        }

        private fun wordsUnderThousand(value: Int): List<String> {
            var n = value
            val words = ArrayList<String>(4)
            if (n >= 100) { words += ONES[n / 100]; words += "hundred"; n %= 100 }
            if (n >= 20) { words += TENS[n / 10]; n %= 10 }
            if (n > 0) words += ONES[n]
            return words
        }

        private fun integerToWords(value: Long): List<String> {
            if (value == 0L) return listOf("zero")
            if (value < 0) return listOf("minus") + integerToWords(-value)
            val groups = ArrayList<Int>()
            var n = value
            while (n > 0) { groups += (n % 1000).toInt(); n /= 1000 }
            // Too big to name: digit by digit.
            if (groups.size > SCALES.size) return value.toString().map { ONES[it - '0'] }
            val words = ArrayList<String>()
            for (i in groups.indices.reversed()) {
                if (groups[i] == 0) continue
                words += wordsUnderThousand(groups[i])
                if (SCALES[i].isNotEmpty()) words += SCALES[i]
            }
            return words
        }

        /** `g2p_dict.txt.gz`: word<TAB>ipa per line, read gzipped. */
        fun readDictionary(file: File): HashMap<String, String> {
            val dictionary = HashMap<String, String>(400_000)
            GZIPInputStream(file.inputStream().buffered(1 shl 16), 1 shl 16).bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    val tab = line.indexOf('\t')
                    if (tab > 0) dictionary[line.substring(0, tab)] = line.substring(tab + 1)
                }
            }
            return dictionary
        }

        /** `symbols.json` (`{"symbols": [...]}`): symbol -> id = index. A symbol listed twice keeps its last index, as the pip package's dict does. */
        fun readSymbols(json: String): Map<Char, Int> {
            val symbols = JSONObject(json).getJSONArray("symbols")
            val out = HashMap<Char, Int>()
            for (i in 0 until symbols.length()) {
                val s = symbols.getString(i)
                if (s.length == 1) out[s[0]] = i
            }
            return out
        }
    }
}

/**
 * The sample's out-of-dictionary G2P: a DeepPhonemizer forward transformer (OpenPhonemizer's
 * espeak-IPA checkpoint) as a fixed-shape graph, one word per run. Input: `<en_us>`, each character
 * repeated `char_repeats` times, `<end>`, zero-padded to `MAXT`, as floats; output: argmax per
 * position, repeats collapsed, special tokens, blanks and `-` dropped. CompiledModel on the CPU;
 * every call on the LiteRT thread.
 */
internal class KittenNeuralG2P(
    private val meta: Meta,
    private val model: CompiledModel,
    private val inputs: List<TensorBuffer>,
    private val outputs: List<TensorBuffer>,
) : Closeable {
    class Meta(
        private val charToIndex: Map<Char, Int>,
        private val indexToPhoneme: Map<Int, String>,
        private val special: Set<String>,
        private val charRepeats: Int,
        private val startId: Int,
        private val endId: Int,
        val maxTokens: Int,
        val numPhonemes: Int,
    ) {
        /** The graph's input row for [word] and the number of positions that carry it. */
        fun encode(word: String): Pair<FloatArray, Int> {
            val ids = ArrayList<Int>(maxTokens)
            ids += startId
            for (ch in word) charToIndex[ch]?.let { id -> repeat(charRepeats) { ids += id } }
            ids += endId
            val length = minOf(ids.size, maxTokens)
            return FloatArray(maxTokens) { if (it < length) ids[it].toFloat() else 0f } to length
        }

        /** [logits]: `maxTokens * numPhonemes`, row-major. */
        fun decode(logits: FloatArray, length: Int): String {
            val out = StringBuilder()
            var previous = -1
            for (t in 0 until length) {
                var best = 0
                var bestScore = logits[t * numPhonemes]
                for (k in 1 until numPhonemes) {
                    val score = logits[t * numPhonemes + k]
                    if (score > bestScore) { bestScore = score; best = k }
                }
                if (best == previous) continue
                previous = best
                val phoneme = indexToPhoneme[best] ?: continue
                if (phoneme in special || best == 0) continue
                for (ch in phoneme) if (ch != '-') out.append(ch)
            }
            return out.toString()
        }

        companion object {
            /** `g2p_meta.json`: char2idx, idx2ph, char_repeats, start, end, MAXT, n_phonemes, special. */
            fun parse(json: String): Meta {
                val o = JSONObject(json)
                val c2i = o.getJSONObject("char2idx")
                val charToIndex = HashMap<Char, Int>()
                for (k in c2i.keys()) if (k.length == 1) charToIndex[k[0]] = c2i.getInt(k)
                val i2p = o.getJSONObject("idx2ph")
                val indexToPhoneme = HashMap<Int, String>()
                for (k in i2p.keys()) indexToPhoneme[k.toInt()] = i2p.getString(k)
                val sp = o.getJSONArray("special")
                return Meta(
                    charToIndex, indexToPhoneme, (0 until sp.length()).map { sp.getString(it) }.toSet(),
                    o.getInt("char_repeats"), o.getInt("start"), o.getInt("end"), o.getInt("MAXT"), o.getInt("n_phonemes"),
                )
            }
        }
    }

    /** One word to espeak-style IPA. On the LiteRT thread. */
    fun word(word: String): String {
        val (input, length) = meta.encode(word)
        inputs[0].writeFloat(input)
        model.run(inputs, outputs)
        return meta.decode(outputs[0].readFloat(), length)
    }

    override fun close() {
        (inputs + outputs).forEach { runCatching { it.close() } }
        model.close()
    }

    companion object {
        /** On the LiteRT thread. Throws the runtime's exception unchanged; the handler maps it. */
        fun open(file: File, meta: Meta, options: CompiledModel.Options, env: Environment): KittenNeuralG2P {
            val model = CompiledModel.create(file.absolutePath, options, env)
            var ins: List<TensorBuffer> = emptyList()
            var outs: List<TensorBuffer> = emptyList()
            try {
                ins = model.createInputBuffers()
                outs = model.createOutputBuffers()
                val inSize = ins.singleOrNull()?.readFloat()?.size
                val outSize = outs.singleOrNull()?.readFloat()?.size
                check(inSize == meta.maxTokens && outSize == meta.maxTokens * meta.numPhonemes) {
                    "the g2p graph does not match g2p_meta.json: expected one input of ${meta.maxTokens} and one output of ${meta.maxTokens} x ${meta.numPhonemes} floats, found inputs ${ins.map { it.readFloat().size }}, outputs ${outs.map { it.readFloat().size }}"
                }
                return KittenNeuralG2P(meta, model, ins, outs)
            } catch (t: Throwable) {
                (ins + outs).forEach { runCatching { it.close() } }
                runCatching { model.close() }
                throw t
            }
        }
    }
}
