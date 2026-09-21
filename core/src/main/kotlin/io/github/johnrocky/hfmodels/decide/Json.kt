package io.github.johnrocky.hfmodels.decide

import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.ModelException

/**
 * A small JSON reader / writer that keeps object key order and writes the way Python's
 * `json.dumps(value, ensure_ascii=False)` does (`", "` and `": "` separators, `/` unescaped,
 * non-ASCII kept). Decision models serialize a structured state into their prompt with exactly
 * that form, so the bytes a phone tokenizes must match the bytes the publisher's own code produced.
 * `org.json` cannot be used here: it escapes `/` and, on the JVM, loses key order.
 *
 * Values: `Map<String, Any?>` (ordered), `List<Any?>`, `String`, `Boolean`, `Long`, `Double`, null.
 */
object Json {
    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.skipWs()
        if (p.i != text.length) throw invalid("trailing characters at ${p.i}")
        return v
    }

    fun parseObject(text: String): Map<String, Any?> {
        val m = parse(text) as? Map<*, *> ?: throw invalid("not a JSON object")
        @Suppress("UNCHECKED_CAST")
        return m as Map<String, Any?>
    }

    /** Python `json.dumps(value, ensure_ascii=False)` with the default separators. */
    fun dumps(value: Any?): String = StringBuilder().also { write(value, it) }.toString()

    private fun write(v: Any?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null")
            is String -> writeString(v, sb)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Float -> writeDouble(v.toDouble(), sb)
            is Double -> writeDouble(v, sb)
            is Number -> sb.append(v.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(", ")
                    first = false
                    writeString(k.toString(), sb)
                    sb.append(": ")
                    write(x, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(", ")
                    first = false
                    write(x, sb)
                }
                sb.append(']')
            }
            is Array<*> -> write(v.asList(), sb)
            else -> throw ModelException(ErrorCode.INVALID_INPUT, "cannot serialize ${v.javaClass.name} into a state")
        }
    }

    /** Python's `float.__repr__`: shortest round-trip digits, `1.0`, `1e-05`, `1e+16`. */
    private fun writeDouble(d: Double, sb: StringBuilder) {
        if (d.isNaN() || d.isInfinite()) throw ModelException(ErrorCode.INVALID_INPUT, "non-finite number in a state")
        if (d == Math.rint(d) && Math.abs(d) < 1e16) { sb.append(d.toLong()); sb.append(".0"); return }
        val s = d.toString()   // shortest round-trip on the JVM as well, but the exponent form differs
        val e = s.indexOf('E')
        if (e < 0) { sb.append(s); return }
        val mantissa = s.substring(0, e).removeSuffix(".0")
        val exp = s.substring(e + 1).toInt()
        val absExp = Math.abs(exp)
        if (exp in -4..15) {
            // Python prints these positionally.
            sb.append(java.math.BigDecimal(s).toPlainString().let { if (it.contains('.')) it.trimEnd('0').trimEnd('.').let { t -> if (t.contains('.')) t else "$t.0" } else "$it.0" })
            return
        }
        sb.append(mantissa).append('e').append(if (exp < 0) '-' else '+').append(if (absExp < 10) "0$absExp" else absExp.toString())
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private fun invalid(msg: String) = ModelException(ErrorCode.INVALID_INPUT, "invalid JSON: $msg")

    private class Parser(val s: String) {
        var i = 0
        fun skipWs() { while (i < s.length && s[i].let { it == ' ' || it == '\n' || it == '\r' || it == '\t' }) i++ }
        fun value(): Any? {
            skipWs()
            if (i >= s.length) throw invalid("unexpected end")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++ // {
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                skipWs()
                if (i >= s.length || s[i] != '"') throw invalid("expected a key at $i")
                val k = str()
                skipWs()
                if (i >= s.length || s[i] != ':') throw invalid("expected ':' at $i")
                i++
                m[k] = value()
                skipWs()
                if (i >= s.length) throw invalid("unterminated object")
                when (s[i]) { ',' -> i++; '}' -> { i++; return m }; else -> throw invalid("expected ',' or '}' at $i") }
            }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                l.add(value())
                skipWs()
                if (i >= s.length) throw invalid("unterminated array")
                when (s[i]) { ',' -> i++; ']' -> { i++; return l }; else -> throw invalid("expected ',' or ']' at $i") }
            }
        }
        fun str(): String {
            i++ // "
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw invalid("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw invalid("bad escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> { if (i + 4 > s.length) throw invalid("bad \\u escape"); sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> throw invalid("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw invalid("unexpected token at $i")
            i += word.length
            return v
        }
        fun num(): Any {
            val start = i
            if (i < s.length && s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            val t = s.substring(start, i)
            if (t.isEmpty()) throw invalid("unexpected character at $start")
            return if (t.any { it == '.' || it == 'e' || it == 'E' }) t.toDoubleOrNull() ?: throw invalid("bad number '$t'")
            else t.toLongOrNull() ?: (t.toDoubleOrNull() ?: throw invalid("bad number '$t'"))
        }
    }
}
