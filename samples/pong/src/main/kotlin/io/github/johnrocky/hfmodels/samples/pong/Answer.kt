package io.github.johnrocky.hfmodels.samples.pong

/** Reads the option letter from the streamed text: its first non-whitespace character. */
object Answer {
    const val UNPARSED = -1

    /** 0 for A, 1 for B, 2 for C; [UNPARSED] for anything else, including no text. */
    fun parse(text: String): Int = when (text.firstOrNull { !it.isWhitespace() }) {
        'A' -> 0
        'B' -> 1
        'C' -> 2
        else -> UNPARSED
    }

    /** The letter shown and recorded: "A", "B", "C", or "?" when the text did not parse. */
    fun letter(choice: Int): String = if (choice == UNPARSED) "?" else ('A' + choice).toString()
}
