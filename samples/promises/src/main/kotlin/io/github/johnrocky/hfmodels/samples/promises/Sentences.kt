package io.github.johnrocky.hfmodels.samples.promises

/** One sentence of a conversation: who wrote it (shown on the screen, never sent to the model) and the text the model reads. */
data class Sentence(val sender: String?, val text: String)

/**
 * A conversation cut into sentences by fixed rules: one line at a time; a leading `[...]` (a chat app's time
 * stamp) is dropped; a line that starts with a name of up to three words and a colon followed by a space
 * (`Name: text`) has that name split off as the sender; the rest is cut after every `.`, `?` or `!` that a
 * space follows. An abbreviation with a period ("a.m. ", "Mr. ") is cut there too.
 */
object Sentences {
    /** At most this many sentences of one conversation are sorted; the screen says when more were left out. */
    const val MAX = 200

    private val STAMP = Regex("""^\[[^\]\n]{1,40}]\s*""")
    private val SENDER = Regex("""^([^:\s][^:\n]{0,31}?):\s+(\S.*)$""")
    private val BOUNDARY = Regex("""(?<=[.?!])\s+""")

    fun split(conversation: String): List<Sentence> {
        val out = ArrayList<Sentence>()
        for (raw in conversation.lines()) {
            val line = raw.trim().replaceFirst(STAMP, "").trim()
            if (line.isEmpty()) continue
            val m = SENDER.matchEntire(line)?.takeIf { it.groupValues[1].trim().split(' ').size <= 3 }
            val name = m?.groupValues?.get(1)?.trim()
            val text = m?.groupValues?.get(2) ?: line
            for (piece in text.split(BOUNDARY)) {
                val t = piece.trim()
                if (t.isNotEmpty()) out += Sentence(name, t)
            }
        }
        return out
    }
}
