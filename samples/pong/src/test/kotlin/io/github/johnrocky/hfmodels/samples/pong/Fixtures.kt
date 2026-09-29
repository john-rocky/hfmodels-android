package io.github.johnrocky.hfmodels.samples.pong

import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/** The reference files under src/test/resources/fixtures, drawn and resized by the reference pipeline. */
object Fixtures {
    fun bytes(name: String): ByteArray =
        requireNotNull(Fixtures::class.java.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }.use { it.readBytes() }

    fun text(name: String): String = bytes(name).toString(Charsets.UTF_8)

    fun json(name: String): Any? = MiniJson(text(name)).parse()

    /** The image's pixels as opaque ARGB, row by row. */
    fun pixels(name: String): Pair<IntArray, Int> {
        val img: BufferedImage = requireNotNull(ImageIO.read(bytes(name).inputStream())) { "cannot decode $name" }
        val out = IntArray(img.width * img.height)
        for (y in 0 until img.height) for (x in 0 until img.width) out[y * img.width + x] = img.getRGB(x, y) or (0xFF shl 24)
        return out to img.width
    }
}

/** Just enough JSON for the fixtures: objects, arrays, numbers, strings, true / false / null. */
class MiniJson(private val s: String) {
    private var i = 0

    fun parse(): Any? = value().also { ws(); require(i == s.length) { "trailing text at $i" } }

    private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

    private fun value(): Any? {
        ws()
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> lit("true", true)
            'f' -> lit("false", false)
            'n' -> lit("null", null)
            else -> if (c == '-' || c.isDigit()) num() else error("unexpected '$c' at $i")
        }
    }

    private fun lit(word: String, v: Any?): Any? { require(s.startsWith(word, i)); i += word.length; return v }

    private fun num(): Double {
        val start = i
        while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
        return s.substring(start, i).toDouble()
    }

    private fun str(): String {
        val b = StringBuilder(); i++
        while (s[i] != '"') {
            if (s[i] == '\\') {
                i++
                when (s[i]) {
                    'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r')
                    'u' -> { b.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                    else -> b.append(s[i])
                }
            } else b.append(s[i])
            i++
        }
        i++
        return b.toString()
    }

    private fun arr(): List<Any?> {
        val out = ArrayList<Any?>(); i++; ws()
        if (s[i] == ']') { i++; return out }
        while (true) {
            out += value(); ws()
            if (s[i++] == ']') return out
        }
    }

    private fun obj(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>(); i++; ws()
        if (s[i] == '}') { i++; return out }
        while (true) {
            ws(); val k = str(); ws(); require(s[i++] == ':'); out[k] = value(); ws()
            if (s[i++] == '}') return out
        }
    }
}
