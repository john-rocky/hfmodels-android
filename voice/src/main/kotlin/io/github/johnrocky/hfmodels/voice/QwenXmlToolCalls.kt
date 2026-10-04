// Ported from john-rocky/edge-agent-lab 2d2f12c, android/phone-agent/.../QwenXmlToolCalls.kt (Apache-2.0, the same author).
package io.github.johnrocky.hfmodels.voice

/**
 * Qwen's XML tool calls in the model's text (Qwen3-Coder and its fine-tunes such as Agents-A1):
 * `<tool_call><function=NAME><parameter=KEY>VALUE</parameter>...</function></tool_call>`. The runtime
 * does not parse this form, so the app does. A block is executed only when it is complete and well
 * formed; anything else stays in the text and is reported by [hasUnparsedMarkup], never run.
 */
object QwenXmlToolCalls {
    data class Call(val name: String, val args: Map<String, String>, val raw: String)

    private data class Match(val call: Call, val start: Int, val end: Int)

    const val OPEN = "<tool_call>"
    private const val CLOSE = "</tool_call>"

    fun parse(text: String): List<Call> = matches(text).map { it.call }

    /** The text with the valid calls removed; malformed markup stays visible. */
    fun withoutCalls(text: String): String {
        val out = StringBuilder()
        var cursor = 0
        for (match in matches(text)) {
            out.append(text, cursor, match.start)
            cursor = match.end
        }
        return out.append(text.substring(cursor)).toString().trim()
    }

    /** True when markup is left after the valid calls are removed: an incomplete or malformed call. */
    fun hasUnparsedMarkup(text: String): Boolean {
        val rest = withoutCalls(text)
        return listOf("<tool_call", "</tool_call", "<function=", "</function", "<parameter=", "</parameter").any { rest.contains(it) }
    }

    private fun matches(text: String): List<Match> {
        val out = ArrayList<Match>()
        var cursor = 0
        while (true) {
            val start = text.indexOf(OPEN, cursor)
            if (start < 0) break
            val close = text.indexOf(CLOSE, start + OPEN.length)
            if (close < 0) break
            val nested = text.indexOf(OPEN, start + OPEN.length)
            if (nested in (start + OPEN.length) until close) {
                // Recover the next complete block, but leave the broken prefix visible.
                cursor = nested
                continue
            }
            val end = close + CLOSE.length
            parseBody(text.substring(start + OPEN.length, close), text.substring(start, end))?.let { out += Match(it, start, end) }
            cursor = end
        }
        return out
    }

    // A small hand scanner: no regex differences between Android ICU and the JVM, no XML entity or DTD expansion.
    private fun parseBody(source: String, raw: String): Call? {
        val body = source.trim()
        if (!body.startsWith("<function=")) return null
        val nameEnd = body.indexOf('>')
        if (nameEnd < 0) return null
        val name = body.substring("<function=".length, nameEnd)
        if (!identifier(name)) return null
        val args = LinkedHashMap<String, String>()
        var cursor = nameEnd + 1
        while (true) {
            while (cursor < body.length && body[cursor].isWhitespace()) cursor++
            if (body.startsWith("</function>", cursor)) {
                if (body.substring(cursor + "</function>".length).isNotBlank()) return null
                return Call(name, args, raw)
            }
            if (!body.startsWith("<parameter=", cursor)) return null
            val keyEnd = body.indexOf('>', cursor)
            if (keyEnd < 0) return null
            val key = body.substring(cursor + "<parameter=".length, keyEnd)
            if (!identifier(key) || args.containsKey(key)) return null
            val valueEnd = body.indexOf("</parameter>", keyEnd + 1)
            if (valueEnd < 0) return null
            val value = body.substring(keyEnd + 1, valueEnd).trim()
            if (listOf("<parameter=", "<function=", "</function>", OPEN, CLOSE).any { value.contains(it) }) return null
            args[key] = value
            cursor = valueEnd + "</parameter>".length
        }
    }

    private fun identifier(s: String): Boolean = s.isNotEmpty() &&
        (s[0] in 'A'..'Z' || s[0] in 'a'..'z' || s[0] == '_') &&
        s.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "_.-" }
}
