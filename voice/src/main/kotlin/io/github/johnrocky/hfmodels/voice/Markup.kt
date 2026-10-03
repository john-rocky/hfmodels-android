package io.github.johnrocky.hfmodels.voice

/**
 * The index of [opener] in [text], or of a trailing piece of it that the next chunk may complete; the
 * text's length when neither is there. (Its own file: the JVM tests load it without LiteRT-LM's classes.)
 */
internal fun beforeMarkup(text: String, opener: String): Int {
    val at = text.indexOf(opener)
    if (at >= 0) return at
    for (k in minOf(opener.length - 1, text.length) downTo 1) if (text.regionMatches(text.length - k, opener, 0, k)) return text.length - k
    return text.length
}

/**
 * The openers of the call markup the runtime parses for the model types it knows (Qwen's `<tool_call>`, Gemma 4's
 * `<|tool_call>`, FunctionGemma's `<start_function_call>`) and LFM2's `<|tool_call_start|>`, which it does not: in the
 * text of a [ToolFormat.Runtime] turn any of them is a call the runtime left unparsed.
 */
internal val RUNTIME_MARKUP = listOf("<tool_call", "<|tool_call", "<start_function_call>")

/**
 * The part of [said] (a finished turn's text without call markup, trimmed) that the first [shown] characters of
 * [text] (the raw turn, streamed up to the first markup) did not show yet; empty when nothing is left.
 */
internal fun unshownText(text: String, shown: Int, said: String): String {
    val before = text.substring(0, shown).trimStart()
    return if (said.length > before.length && said.startsWith(before)) said.substring(before.length) else ""
}

private val LINE_MARK = Regex("(?m)^[ \\t]*(?:[-*] +|#+[ \\t]*)")
private val MARKDOWN_MARKS = Regex("[*_#`]")

/**
 * [text] without the markdown a chat model may still write in a spoken reply: a `- ` or `* ` bullet or a `#` heading
 * mark at the start of a line, and every `*`, `_`, `#` and `` ` `` (emphasis, code). What is left goes to the speaker;
 * the model's text as it was written stays in [VoiceLoop.TurnTiming.reply].
 */
internal fun stripMarkdown(text: String): String = text.replace(LINE_MARK, "").replace(MARKDOWN_MARKS, "")
