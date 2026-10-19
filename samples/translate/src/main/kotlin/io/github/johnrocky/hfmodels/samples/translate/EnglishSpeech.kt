package io.github.johnrocky.hfmodels.samples.translate

import java.text.Normalizer

/**
 * An English sentence rewritten for the Kitten speaker before `SentenceSplitter.split`. The speaker's front-end
 * (hfmodels-litert `KittenG2P`) reads ASCII words, numbers and a few punctuation marks; `SentenceSplitter` cuts at every
 * `.!?` as KittenTTS 0.8.1 does, so "3:15 p.m." became "3:15 p," and "m," and "2.5" two chunks. The
 * translation model writes what a reader expects (Hy-MT2 wrote "I’m", "3:15", "a ATM"), so this turns it into what the
 * speaker can say:
 *  - typographic apostrophes and quotes to ASCII (the G2P's words are `[A-Za-z']+`: "I’m" was "I" and "m");
 *  - letters with accents to their base letter ("Andrés" -> "Andres": the G2P drops a letter it does not know);
 *  - times: "3:15 p.m." -> "three fifteen PM", "9 a.m." -> "nine AM", "9:00" -> "nine o'clock", "10:05" -> "ten oh five";
 *  - money and percent: "€23.50" / "23,50 €" -> "23 euros 50", "$5" -> "5 dollars", "£3" -> "3 pounds", "20%" -> "20 percent";
 *  - ordinals and decimals: "21st" -> "twenty first", "2.5" -> "2 point 5";
 *  - a few abbreviations whose dot `SentenceSplitter` would cut at: Mr., Mrs., Ms., Dr., St., No. 4, e.g., i.e., etc., vs.;
 *  - "&" -> "and".
 * A sentence that ended in `.!?` still ends in one (a rewritten "a.m." at the end keeps the full stop).
 */
internal object EnglishSpeech {
    private val AM_PM = Regex("""\b(\d{1,2})(?::(\d{2}))?\s*([AaPp])\.?\s?[Mm]\b\.?""")
    private val CLOCK = Regex("""\b(\d{1,2}):(\d{2})\b""")
    // The amount: digits with thousands separators, then two decimals (a dot or, in Spanish style, a comma).
    private val EURO_BEFORE = Regex("""€\s?(\d{1,3}(?:,\d{3})+|\d+)(?:[.,](\d{2}))?(?!\d)""")
    private val EURO_AFTER = Regex("""\b(\d{1,3}(?:\.\d{3})+|\d+)(?:,(\d{2}))?\s?€""")
    private val DOLLAR = Regex("\\$\\s?(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.(\\d{2}))?(?!\\d)")
    private val POUND = Regex("""£\s?(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d{2}))?(?!\d)""")
    private val PERCENT = Regex("""(\d+(?:\.\d+)?)\s?%""")
    private val ORDINAL = Regex("""\b(\d{1,4})(st|nd|rd|th)\b""", RegexOption.IGNORE_CASE)
    private val DECIMAL = Regex("""\b(\d+)\.(\d+)\b""")
    private val NUMBER_SIGN = Regex("""\bNo\.\s?(?=\d)""")
    private val SAINT = Regex("""\bSt\.\s(?=[A-Z])""")
    private val ABBREVIATIONS = listOf(
        Regex("""\bMr\.""") to "Mister", Regex("""\bMrs\.""") to "Missus", Regex("""\bMs\.""") to "Miz", Regex("""\bDr\.""") to "Doctor",
        Regex("""\bSt\.""") to "Street", Regex("""\be\.g\.""") to "for example", Regex("""\bi\.e\.""") to "that is",
        Regex("""\betc\.""") to "et cetera", Regex("""\bvs\.""") to "versus",
    )
    private val MARKS = Regex("""[\p{Mn}]+""")
    private val SPACES = Regex("""\s+""")

    fun rewrite(sentence: String): String {
        var s = sentence.replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"').replace(' ', ' ')
        s = MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").let { Normalizer.normalize(it, Normalizer.Form.NFC) }
        val ended = s.trimEnd().lastOrNull()?.let { it in ".!?" } == true
        s = AM_PM.replace(s) { m ->
            val (h, mm, ap) = m.destructured
            clock(h.toInt(), mm.toIntOrNull(), withOClock = false) + if (ap.equals("a", true)) " AM" else " PM"
        }
        s = CLOCK.replace(s) { m -> clock(m.groupValues[1].toInt(), m.groupValues[2].toInt(), withOClock = true) }
        s = EURO_BEFORE.replace(s) { m -> money(m.groupValues[1], m.groupValues[2], "euro") }
        s = EURO_AFTER.replace(s) { m -> money(m.groupValues[1].replace(".", ""), m.groupValues[2], "euro") }
        s = DOLLAR.replace(s) { m -> money(m.groupValues[1], m.groupValues[2], "dollar") }
        s = POUND.replace(s) { m -> money(m.groupValues[1], m.groupValues[2], "pound") }
        s = PERCENT.replace(s) { m -> m.groupValues[1] + " percent" }
        s = ORDINAL.replace(s) { m -> NumberWords.ordinal(m.groupValues[1].toInt()) }
        s = DECIMAL.replace(s) { m -> m.groupValues[1] + " point " + m.groupValues[2].toCharArray().joinToString(" ") }
        s = NUMBER_SIGN.replace(s, "number ")
        s = SAINT.replace(s, "Saint ")
        for ((re, word) in ABBREVIATIONS) s = re.replace(s, word)
        s = s.replace("&", " and ")
        s = SPACES.replace(s, " ").trim()
        if (ended && s.isNotEmpty() && s.last() !in ".!?") s += "."
        return s
    }

    /** 3:15 -> "three fifteen", 9:00 -> "nine o'clock" (or "nine" before AM / PM), 10:05 -> "ten oh five". */
    private fun clock(hour: Int, minute: Int?, withOClock: Boolean): String {
        val h = NumberWords.cardinal(hour)
        return when {
            minute == null || minute == 0 -> if (withOClock) "$h o'clock" else h
            minute < 10 -> "$h oh ${NumberWords.cardinal(minute)}"
            else -> "$h ${NumberWords.cardinal(minute)}"
        }
    }

    /** "23", "50", "euro" -> "23 euros 50"; "1" -> "1 euro". Thousands separators go (the speaker reads 1500 as words). */
    private fun money(whole: String, cents: String, unit: String): String {
        val w = whole.replace(",", "")
        val plural = if (w == "1") unit else unit + "s"
        return if (cents.isNotEmpty() && cents != "00") "$w $plural $cents" else "$w $plural"
    }
}

/** English number words for the rewrite (the speaker reads digits itself; these are the forms it would not get right). */
internal object NumberWords {
    private val ONES = arrayOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val TENS = arrayOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val ORDINAL_OF = mapOf(
        "one" to "first", "two" to "second", "three" to "third", "five" to "fifth", "eight" to "eighth", "nine" to "ninth", "twelve" to "twelfth",
        "twenty" to "twentieth", "thirty" to "thirtieth", "forty" to "fortieth", "fifty" to "fiftieth", "sixty" to "sixtieth",
        "seventy" to "seventieth", "eighty" to "eightieth", "ninety" to "ninetieth",
    )

    /** 0 to 9,999 in words ("twenty three", "one thousand two hundred five"); larger as digits. */
    fun cardinal(n: Int): String {
        if (n < 0 || n > 9999) return n.toString()
        if (n < 20) return ONES[n]
        val words = ArrayList<String>()
        var r = n
        if (r >= 1000) { words += ONES[r / 1000]; words += "thousand"; r %= 1000 }
        if (r >= 100) { words += ONES[r / 100]; words += "hundred"; r %= 100 }
        if (r >= 20) { words += TENS[r / 10]; r %= 10 }
        if (r > 0 || words.isEmpty()) words += ONES[r]
        return words.joinToString(" ")
    }

    /** 1 -> "first", 21 -> "twenty first", 12 -> "twelfth", 40 -> "fortieth". */
    fun ordinal(n: Int): String {
        val words = cardinal(n).split(' ').toMutableList()
        val last = words.last()
        words[words.size - 1] = ORDINAL_OF[last] ?: if (last.all { it.isLetter() }) last + "th" else last
        return words.joinToString(" ")
    }
}

/**
 * The translation's text as it streams in, cut into sentences for the speaker: [add] returns the sentences that are
 * complete, [flush] the rest at the end of the stream. A sentence ends at a run of `.!?` (and closing quotes or
 * brackets) followed by white space and the next character; not after a single letter or a dotted abbreviation ("U.S.",
 * "a.m.") or one of [ABBREVIATIONS] (Mr., Dr., St., No., e.g., etc.), whose dot does not end a sentence.
 * (hfmodels-voice keeps its own SentenceStream internal; this one also knows the abbreviations.)
 */
internal class SentenceCutter {
    private val pending = StringBuilder()

    fun add(delta: String): List<String> {
        pending.append(delta)
        val out = ArrayList<String>()
        while (true) {
            val cut = firstCut(pending)?.second ?: break
            val sentence = pending.substring(0, cut).trim()
            pending.delete(0, cut)
            if (sentence.isNotEmpty()) out += sentence
        }
        return out
    }

    fun flush(): List<String> {
        val rest = pending.toString().trim()
        pending.setLength(0)
        return if (rest.isEmpty()) emptyList() else listOf(rest)
    }

    companion object {
        private const val ENDS = ".!?"
        private const val CLOSERS = "\"')]”’"
        /** "U.S" before the final dot, "a.m", "e.g". */
        private val DOTTED = Regex("""([A-Za-z]\.)+[A-Za-z]""")
        val ABBREVIATIONS = setOf("Mr", "Mrs", "Ms", "Dr", "St", "No", "Ave", "vs", "etc", "approx", "Jr", "Sr")

        /**
         * The first sentence end of [text] with a character after it: the index of its first mark and the index after
         * the white space that follows it; null when there is none yet.
         */
        fun firstCut(text: CharSequence): Pair<Int, Int>? {
            var i = 0
            while (i < text.length) {
                if (text[i] in ENDS) {
                    var j = i
                    while (j < text.length && text[j] in ENDS) j++
                    while (j < text.length && text[j] in CLOSERS) j++
                    var k = j
                    while (k < text.length && text[k].isWhitespace()) k++
                    if (k > j && k < text.length && !abbreviationBefore(text, i)) return i to k
                    i = j
                } else i++
            }
            return null
        }

        /** Whether the word that the mark at [at] ends is an abbreviation or an initial, whose dot does not end a sentence. */
        private fun abbreviationBefore(text: CharSequence, at: Int): Boolean {
            if (text[at] != '.') return false
            var s = at
            while (s > 0 && !text[s - 1].isWhitespace()) s--
            val word = text.substring(s, at)
            if (word.isEmpty()) return false
            if (word.length == 1 && word[0].isLetter()) return true
            if (DOTTED.matches(word)) return true
            return word.trimStart('(', '"', '\'') in ABBREVIATIONS
        }
    }
}

/**
 * What the speaker says of the streamed translation, piece by piece: the first piece ends at the first `,` `;` or `:`
 * (with white space after it) that comes after at least [minWords] words, so the voice starts before the first sentence
 * is finished, unless a sentence ends before such a mark; every later piece is a whole sentence ([SentenceCutter]).
 * [sentencesEnded] counts the sentences finished so far (the first one may be said in two pieces).
 */
internal class SpeechCutter(private val minWords: Int = MIN_WORDS) {
    private val sentences = SentenceCutter()
    /** The text before the first piece is out; null after. */
    private var head: StringBuilder? = StringBuilder()

    var sentencesEnded = 0
        private set

    fun add(delta: String): List<String> {
        val h = head ?: return sentences.add(delta).also { sentencesEnded += it.size }
        h.append(delta)
        val sentence = SentenceCutter.firstCut(h)
        val clause = clauseCut(h)
        if (clause != null && (sentence == null || clause.first < sentence.first)) {
            val piece = h.substring(0, clause.first + 1).trim()
            val rest = h.substring(clause.second)
            head = null
            return listOf(piece) + sentences.add(rest).also { sentencesEnded += it.size }
        }
        if (sentence == null) return emptyList()
        head = null
        return sentences.add(h.toString()).also { sentencesEnded += it.size }
    }

    fun flush(): List<String> {
        val h = head
        if (h != null) {
            head = null
            val rest = h.toString().trim()
            if (rest.isEmpty()) return emptyList()
            sentencesEnded++
            return listOf(rest)
        }
        return sentences.flush().also { sentencesEnded += it.size }
    }

    /** The first `,` `;` `:` of [text] with white space after it and at least [minWords] words before it: its index and the index after the white space. */
    private fun clauseCut(text: CharSequence): Pair<Int, Int>? {
        var words = 0
        var inWord = false
        for (i in text.indices) {
            val c = text[i]
            if (c.isLetterOrDigit()) {
                if (!inWord) { words++; inWord = true }
                continue
            }
            if (c.isWhitespace()) inWord = false
            if (c in CLAUSE_MARKS && words >= minWords && i + 1 < text.length && text[i + 1].isWhitespace()) {
                var k = i + 1
                while (k < text.length && text[k].isWhitespace()) k++
                return i to k
            }
        }
        return null
    }

    companion object {
        /** Words before the first piece's mark (the lane's decision before any number: 4). */
        const val MIN_WORDS = 4
        private const val CLAUSE_MARKS = ",;:"
    }
}

/**
 * Whether an utterance holds speech before it is transcribed: at least [MIN_VOICED_MS] of 20 ms frames at or above the
 * endpointer's start level, and an RMS over the whole utterance at or above [floorRms]. Below either it is "Nothing
 * heard". The floor is provisional (0.005, what the EmbeddingGemma demo chose for a voice take): it is set from a silent
 * recording made through this app's microphone on the phone, whose RMS every TURN line logs.
 */
internal object InputGate {
    const val MIN_VOICED_MS = 250
    const val DEFAULT_FLOOR_RMS = 0.005f

    class Verdict(val heard: Boolean, val rms: Double, val voicedMs: Int, val reason: String)

    fun check(pcm: FloatArray, rate: Int, startRms: Float, floorRms: Float = DEFAULT_FLOOR_RMS, frameMs: Int = 20): Verdict {
        val frame = rate * frameMs / 1000
        var all = 0.0
        var voiced = 0
        var f = 0
        val start = startRms.toDouble() * startRms
        while (f + frame <= pcm.size) {
            var e = 0.0
            for (i in f until f + frame) e += pcm[i].toDouble() * pcm[i]
            all += e
            if (e / frame >= start) voiced++
            f += frame
        }
        for (i in f until pcm.size) all += pcm[i].toDouble() * pcm[i]
        val rms = if (pcm.isEmpty()) 0.0 else kotlin.math.sqrt(all / pcm.size)
        val voicedMs = voiced * frameMs
        return when {
            voicedMs < MIN_VOICED_MS -> Verdict(false, rms, voicedMs, "too short ($voicedMs ms of voice)")
            rms < floorRms -> Verdict(false, rms, voicedMs, "too quiet (rms ${"%.4f".format(java.util.Locale.US, rms)})")
            else -> Verdict(true, rms, voicedMs, "")
        }
    }
}
