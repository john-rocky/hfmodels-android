package io.github.johnrocky.hfmodels.samples.translate

import org.json.JSONObject

/**
 * Token ids to text for parakeet-tdt-0.6b-v3, from the model's own `tokenizer.json` (nvidia/parakeet-tdt-0.6b-v3: BPE,
 * 8,192 pieces, a Metaspace decoder with U+2581 and prepend_scheme "always", no byte pieces). The TDT decoder gives ids,
 * so the text is the pieces joined, U+2581 read as a space, trimmed: no tokenizer library is needed.
 *
 * Skipped: the special added tokens (`<unk>`, `<pad>`, `<blank>`) and the `<|...|>` control tokens. The digits 0 to 9
 * are added tokens too, with special false, and stay: skipping every added token drops the numbers ("Son  euros" for
 * "Son 23 euros", the first Mac run of 2026-10-19).
 */
internal class ParakeetVocab(private val pieces: Array<String?>, private val skip: BooleanArray) {
    val size: Int get() = pieces.size

    fun text(ids: IntArray): String {
        val b = StringBuilder()
        for (id in ids) {
            if (id !in pieces.indices || skip[id]) continue
            pieces[id]?.let { b.append(it) }
        }
        return b.toString().replace(METASPACE, ' ').trim()
    }

    companion object {
        const val METASPACE = '▁'

        /** `model.vocab` ({piece: id}) and `added_tokens` ([{id, content, special}]) of a tokenizer.json. */
        fun parse(json: String): ParakeetVocab {
            val root = JSONObject(json)
            val model = root.getJSONObject("model")
            val vocab = model.getJSONObject("vocab")
            var n = 0
            for (k in vocab.keys()) n = maxOf(n, vocab.getInt(k) + 1)
            val added = root.optJSONArray("added_tokens")
            if (added != null) for (i in 0 until added.length()) n = maxOf(n, added.getJSONObject(i).getInt("id") + 1)
            val pieces = arrayOfNulls<String>(n)
            for (k in vocab.keys()) pieces[vocab.getInt(k)] = k
            val skip = BooleanArray(n)
            if (added != null) for (i in 0 until added.length()) {
                val a = added.getJSONObject(i)
                val id = a.getInt("id")
                val content = a.getString("content")
                if (pieces[id] == null) pieces[id] = content
                if (a.optBoolean("special", false) || (content.startsWith("<|") && content.endsWith("|>"))) skip[id] = true
            }
            return ParakeetVocab(pieces, skip)
        }
    }
}

/**
 * Audio longer than the graph's window goes in pieces, each cut at the quietest 20 ms frame of the last [searchMs] before
 * the window's end, so that every piece starts at a pause as an utterance does. Standalone windows that start inside
 * speech can come back empty (36.9 % of the later 5 s windows with NeMo itself, edge-llm-bench 2026-09-25); a pause is
 * the closest a cut gets to an utterance's own start. Returns [start, end) sample ranges.
 */
internal fun pieces(pcm: FloatArray, windowSamples: Int, rate: Int = 16000, searchMs: Int = 2500, frameMs: Int = 20): List<IntRange> {
    if (pcm.size <= windowSamples) return listOf(0 until pcm.size)
    val frame = rate * frameMs / 1000
    val search = minOf(rate * searchMs / 1000, windowSamples - frame)
    val out = ArrayList<IntRange>()
    var start = 0
    while (pcm.size - start > windowSamples) {
        val lo = start + windowSamples - search
        val hi = start + windowSamples - frame
        var best = lo
        var bestEnergy = Double.MAX_VALUE
        var f = lo
        while (f <= hi) {
            var e = 0.0
            for (i in f until f + frame) e += pcm[i].toDouble() * pcm[i]
            if (e < bestEnergy) { bestEnergy = e; best = f }
            f += frame
        }
        val cut = best + frame / 2
        out += start until cut
        start = cut
    }
    out += start until pcm.size
    return out
}
