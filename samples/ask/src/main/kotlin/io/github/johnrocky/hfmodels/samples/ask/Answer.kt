package io.github.johnrocky.hfmodels.samples.ask

/** Reads the option letter from the streamed text: its first non-whitespace character. */
object Answer {
    const val UNPARSED = -1

    /** 0 for A, 1 for B, … up to [options] - 1; [UNPARSED] for anything else, including no text. */
    fun parse(text: String, options: Int): Int {
        val c = text.firstOrNull { !it.isWhitespace() } ?: return UNPARSED
        val k = c - 'A'
        return if (k in 0 until options) k else UNPARSED
    }

    /** The letter shown and recorded: "A", "B", …, or "?" when the text did not parse. */
    fun letter(choice: Int): String = if (choice == UNPARSED) "?" else ('A' + choice).toString()
}
