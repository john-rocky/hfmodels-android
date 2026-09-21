package io.github.johnrocky.hfmodels.litert

import java.io.Reader

/**
 * A forward-only JSON token reader for large files (a 34 MB `tokenizer.json` with 580,000 merges
 * must not be materialized as objects). Pure JVM so the same code runs in unit tests.
 */
internal class JsonStream(private val reader: Reader) {
    enum class Token { BEGIN_OBJECT, END_OBJECT, BEGIN_ARRAY, END_ARRAY, NAME, STRING, NUMBER, BOOLEAN, NULL, END }

    private val buf = CharArray(1 shl 16)
    private var len = 0
    private var pos = 0
    private var eof = false
    /** The text of the last NAME / STRING / NUMBER / BOOLEAN token. */
    var text: String = ""
        private set
    private val sb = StringBuilder()
    private val ctx = ArrayList<Char>()          // 'o' object / 'a' array
    private val keyNext = ArrayList<Boolean>()   // for 'o': the next string is a key

    private fun peek(): Int {
        if (pos >= len) fill()
        return if (pos >= len) -1 else buf[pos].code
    }
    private fun read(): Int { val c = peek(); if (c >= 0) pos++; return c }
    private fun fill() {
        if (eof) return
        val n = reader.read(buf, 0, buf.size)
        if (n < 0) { eof = true; len = 0 } else len = n
        pos = 0
    }
    private fun skipWs() {
        while (true) {
            val c = peek()
            if (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code || c == ','.code || c == ':'.code) pos++ else return
        }
    }
    private fun push(c: Char) { ctx.add(c); keyNext.add(c == 'o') }
    private fun pop() { ctx.removeAt(ctx.size - 1); keyNext.removeAt(keyNext.size - 1) }
    private fun inObject() = ctx.isNotEmpty() && ctx[ctx.size - 1] == 'o'
    private fun afterValue() { if (inObject()) keyNext[keyNext.size - 1] = true }

    fun next(): Token {
        skipWs()
        val c = read()
        if (c < 0) return Token.END
        return when (c.toChar()) {
            '{' -> { push('o'); Token.BEGIN_OBJECT }
            '}' -> { pop(); afterValue(); Token.END_OBJECT }
            '[' -> { push('a'); Token.BEGIN_ARRAY }
            ']' -> { pop(); afterValue(); Token.END_ARRAY }
            '"' -> {
                readString()
                if (inObject() && keyNext[keyNext.size - 1]) { keyNext[keyNext.size - 1] = false; Token.NAME } else { afterValue(); Token.STRING }
            }
            't' -> { skip(3); text = "true"; afterValue(); Token.BOOLEAN }
            'f' -> { skip(4); text = "false"; afterValue(); Token.BOOLEAN }
            'n' -> { skip(3); text = "null"; afterValue(); Token.NULL }
            else -> { readNumber(c.toChar()); afterValue(); Token.NUMBER }
        }
    }

    private fun skip(n: Int) { repeat(n) { read() } }

    private fun readString() {
        sb.setLength(0)
        while (true) {
            val c = read()
            if (c < 0) throw IllegalStateException("unterminated string")
            when (c.toChar()) {
                '"' -> { text = sb.toString(); return }
                '\\' -> when (val e = read().toChar()) {
                    '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                    'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                    'u' -> { var v = 0; repeat(4) { v = v * 16 + Character.digit(read().toChar(), 16) }; sb.append(v.toChar()) }
                    else -> throw IllegalStateException("bad escape \\$e")
                }
                else -> sb.append(c.toChar())
            }
        }
    }

    private fun readNumber(first: Char) {
        sb.setLength(0); sb.append(first)
        while (true) {
            val c = peek()
            if (c < 0) break
            val ch = c.toChar()
            if (ch.isDigit() || ch == '.' || ch == 'e' || ch == 'E' || ch == '+' || ch == '-') { sb.append(ch); pos++ } else break
        }
        text = sb.toString()
    }

    /** Skips the rest of the value whose opening token was just returned; a no-op for scalars. */
    fun skipValue(t: Token) {
        var depth = when (t) { Token.BEGIN_OBJECT, Token.BEGIN_ARRAY -> 1; else -> return }
        while (depth > 0) {
            when (next()) { Token.BEGIN_OBJECT, Token.BEGIN_ARRAY -> depth++; Token.END_OBJECT, Token.END_ARRAY -> depth--; Token.END -> throw IllegalStateException("unexpected end"); else -> {} }
        }
    }

    /** Reads a scalar value token and returns its text; throws for a container. */
    fun scalar(): String {
        val t = next()
        if (t == Token.BEGIN_OBJECT || t == Token.BEGIN_ARRAY) throw IllegalStateException("expected a scalar, got $t")
        return text
    }
}
