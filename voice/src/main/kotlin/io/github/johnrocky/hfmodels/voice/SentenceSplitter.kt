// Ported from google-ai-edge/litert-samples c18346a8, samples/litert/text_to_speech_streaming/.../SentenceChunker.kt (Apache-2.0).
package io.github.johnrocky.hfmodels.voice

/**
 * Splits text into chunks for `Speaker.synthesize`, the way `chunk_text` of the KittenTTS 0.8.1 pip
 * package does, with the Japanese `。！？` as sentence ends too: cut at every run of `.!?。！？` (the
 * marks are dropped), trim, skip empty pieces, end each chunk with `,` unless it already ends in one
 * of `.!?,;:`, and cut a piece longer than [split]'s `maxChars` at spaces.
 *
 * 0.8.1 is the release this model (nano 0.8) shipped with and the form the LiteRT sample ported (it
 * keeps the quirks on purpose: the model's prosody was tuned against this front-end). KittenTTS main
 * of 2026-10 (`kittenml/preprocess.py`) no longer cuts at common abbreviations; this keeps the 0.8.1
 * form and is not smarter: "Dr. Smith" is cut after "Dr", "7.30" into "7" and "30", "p.m." into "p"
 * and "m". One difference: the added comma counts against `maxChars` (0.8.1 returns 401 characters
 * for a 400-character sentence), so every chunk fits `Speaker.maxChars`; a sentence that already ends
 * in punctuation gets no comma and may use all of `maxChars`; a run without spaces longer than the
 * limit is cut where it reaches it.
 */
object SentenceSplitter {
    /** The sentence marks: every run of them ends a sentence. */
    internal const val MARKS = ".!?。！？"
    private val SENTENCE_END = Regex("[$MARKS]+")
    private val SPACES = Regex("\\s+")
    private const val PUNCTUATION = ".!?,;:"

    fun split(text: String, maxChars: Int = 400): List<String> {
        require(maxChars >= 2) { "maxChars must be at least 2 (one character and the comma)" }
        val budget = maxChars - 1
        val chunks = ArrayList<String>()
        for (sentence in SENTENCE_END.split(text)) {
            val trimmed = sentence.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.length <= (if (trimmed.last() in PUNCTUATION) maxChars else budget)) { chunks += punctuate(trimmed); continue }
            val b = StringBuilder()
            for (word in trimmed.split(SPACES)) {
                var w = word
                if (b.isNotEmpty() && b.length + 1 + w.length > budget) { chunks += punctuate(b.toString()); b.setLength(0) }
                while (w.length > budget) {
                    // A run without spaces longer than a chunk; never cut a surrogate pair.
                    val cut = if (Character.isLowSurrogate(w[budget])) budget - 1 else budget
                    chunks += punctuate(w.substring(0, cut))
                    w = w.substring(cut)
                }
                if (b.isNotEmpty()) b.append(' ')
                b.append(w)
            }
            if (b.isNotEmpty()) chunks += punctuate(b.toString())
        }
        return chunks
    }

    private fun punctuate(chunk: String): String = if (chunk.last() in PUNCTUATION) chunk else "$chunk,"
}

/**
 * [SentenceSplitter] over text that streams in: [add] returns the chunks that are final (everything up to the last
 * run of sentence marks that another character has followed), [flush] the rest. The chunks of a whole stream equal
 * `SentenceSplitter.split` of its whole text, except that a [flush] in the middle (the end of a model turn) also
 * ends a sentence.
 */
internal class SentenceStream(private val maxChars: Int) {
    private val pending = StringBuilder()

    fun add(delta: String): List<String> {
        pending.append(delta)
        var cut = -1
        for (i in pending.length - 1 downTo 1) {
            if (pending[i] !in SentenceSplitter.MARKS && pending[i - 1] in SentenceSplitter.MARKS) { cut = i; break }
        }
        if (cut < 0) return emptyList()
        val done = pending.substring(0, cut)
        pending.delete(0, cut)
        return SentenceSplitter.split(done, maxChars)
    }

    fun flush(): List<String> {
        val rest = pending.toString()
        pending.setLength(0)
        return SentenceSplitter.split(rest, maxChars)
    }
}
