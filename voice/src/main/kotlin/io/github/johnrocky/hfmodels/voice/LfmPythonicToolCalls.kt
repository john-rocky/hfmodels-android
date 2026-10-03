package io.github.johnrocky.hfmodels.voice

/**
 * LFM2's tool calls in the model's text: `<|tool_call_start|>[set_alarm(hour=7, minute=30, label='Wake up'), get_current_datetime()]<|tool_call_end|>`,
 * a Python list of calls with keyword arguments (LFM2 / LFM2.5 Instruct; LiteRT-LM 0.16.1 leaves them in the text
 * of a `generic_model` bundle). Values are Python literals: numbers, quoted strings, True / False / None. A block
 * is run only when it is complete and well formed; anything else stays in the text and is reported by
 * [hasUnparsedMarkup], never run.
 */
object LfmPythonicToolCalls {
    data class Call(val name: String, val args: Map<String, Any?>)

    const val OPEN = "<|tool_call_start|>"
    const val CLOSE = "<|tool_call_end|>"

    private class Block(val calls: List<Call>, val start: Int, val end: Int)

    fun parse(text: String): List<Call> = blocks(text).flatMap { it.calls }

    /** The text with the valid blocks removed; malformed markup stays visible. */
    fun withoutCalls(text: String): String {
        val out = StringBuilder()
        var cursor = 0
        for (b in blocks(text)) {
            out.append(text, cursor, b.start)
            cursor = b.end
        }
        return out.append(text.substring(cursor)).toString().trim()
    }

    /** True when a marker is left after the valid blocks are removed: an incomplete or malformed call. */
    fun hasUnparsedMarkup(text: String): Boolean = withoutCalls(text).let { it.contains(OPEN) || it.contains(CLOSE) }

    private fun blocks(text: String): List<Block> {
        val out = ArrayList<Block>()
        var cursor = 0
        while (true) {
            val start = text.indexOf(OPEN, cursor)
            if (start < 0) break
            val close = text.indexOf(CLOSE, start + OPEN.length)
            if (close < 0) break
            val end = close + CLOSE.length
            Scanner(text.substring(start + OPEN.length, close)).calls()?.let { out += Block(it, start, end) }
            cursor = end
        }
        return out
    }

    /** `[call, ...]` (the brackets may be missing for one call); call = `name(key=value, ...)`. Null when malformed. */
    private class Scanner(private val s: String) {
        private var i = 0

        fun calls(): List<Call>? {
            skip()
            val bracket = peek('[')
            if (bracket) i++
            val calls = ArrayList<Call>()
            skip()
            if (!(bracket && peek(']'))) {
                while (true) {
                    calls += call() ?: return null
                    skip()
                    if (peek(',')) { i++; continue }
                    break
                }
            }
            if (bracket) { skip(); if (!peek(']')) return null; i++ }
            skip()
            return if (i == s.length && calls.isNotEmpty()) calls else null
        }

        private fun call(): Call? {
            skip()
            val name = identifier() ?: return null
            skip()
            if (!peek('(')) return null
            i++
            val args = LinkedHashMap<String, Any?>()
            skip()
            if (peek(')')) { i++; return Call(name, args) }
            while (true) {
                skip()
                val key = identifier() ?: return null
                skip()
                if (!peek('=') || args.containsKey(key)) return null
                i++
                skip()
                val v = value() ?: return null
                args[key] = if (v === NONE) null else v
                skip()
                when {
                    peek(',') -> i++
                    peek(')') -> { i++; return Call(name, args) }
                    else -> return null
                }
            }
        }

        /** A literal; [NONE] stands for Python's None so that null can mean "malformed". */
        private fun value(): Any? {
            if (i >= s.length) return null
            val c = s[i]
            if (c == '\'' || c == '"') return string(c)
            val start = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] in "+-._")) i++
            val word = s.substring(start, i)
            return when {
                word == "True" || word == "true" -> true
                word == "False" || word == "false" -> false
                word == "None" || word == "null" -> NONE
                word.toLongOrNull() != null -> word.toLong()
                word.toDoubleOrNull() != null -> word.toDouble()
                else -> null
            }
        }

        private fun string(quote: Char): String? {
            i++
            val b = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when {
                    c == quote -> return b.toString()
                    c == '\\' && i < s.length -> {
                        val e = s[i++]
                        b.append(when (e) { 'n' -> '\n'; 't' -> '\t'; else -> e })
                    }
                    else -> b.append(c)
                }
            }
            return null
        }

        private fun identifier(): String? {
            val start = i
            if (i < s.length && (s[i].isLetter() || s[i] == '_')) i++ else return null
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '.')) i++
            return s.substring(start, i)
        }

        private fun peek(c: Char) = i < s.length && s[i] == c
        private fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
    }

    /** Python's None while scanning; [Call.args] holds it as null. */
    private val NONE = Any()
}
