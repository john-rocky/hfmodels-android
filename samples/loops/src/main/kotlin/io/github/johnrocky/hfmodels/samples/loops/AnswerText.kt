package io.github.johnrocky.hfmodels.samples.loops

/**
 * The answer as a column shows it. The model writes Markdown (`**bold**`, `*` and `-` bullets, `### ` headings) and, in
 * its working, LaTeX: `\[ … \]` or `$$ … $$` on lines of their own, `\( … \)` or `$ … $` inside a sentence, with
 * `\frac`, `\times`, `\text{…}` and the like. The column drops the marks, sets the bold parts in bold and writes the math
 * in plain symbols (`\frac{88{,}000}{11}` as `88,000/11`, `5 \times 8` as `5 × 8`, `a_{16}` as `a₁₆`), so the answer
 * reads as text; the run's record keeps what the model wrote. A `$` pair is math only when it encloses math (`$x$`,
 * `$75\text{ g}$`), never two prices (`$5 per week × 8 weeks = $40`). A ``` fence is left as written, and so is a
 * `#### <number>` line, the GSM8K examples' final answer.
 *
 * While the answer streams ([render]'s `partial`), what may be half of a mark is held back (a lone `*`, a `\` command or
 * a `{` argument still on its way), so a mark never shows and is then taken away. No Android types: the JVM tests run it.
 */
object AnswerText {
    /** The text to show and the ranges to set in bold. */
    data class Shown(val text: String, val bold: List<IntRange>)

    private val HEADING = Regex("^#{1,3} ")
    private val BULLET = Regex("^(\\s*)[*-]\\s+")
    private val NUMBERED = Regex("^(\\s*\\d+\\.)\\s{2,}")
    private val TAIL_COMMAND = Regex("\\\\+[A-Za-z]*$")
    private val SPACES = Regex(" {2,}")
    /** In a `$` pair with a space inside, one of these makes it math (`$x + 20 = 25$`); words alone do not. */
    private const val MATH_SIGNS = "\\^_=+-×÷*/<>≤≥"

    fun render(raw: String, partial: Boolean = false): Shown {
        val src = if (partial) held(raw) else raw
        val out = StringBuilder()
        val bold = ArrayList<IntRange>()
        var open = -1
        var fence = false
        var display = false
        var shownLines = 0
        val lines = src.split('\n')
        for ((i, whole) in lines.withIndex()) {
            val t = whole.trim()
            if (!fence && (t == "\\[" || t == "\\]" || t == "\$\$")) {
                // A display formula's own line: dropped; the lines between are math.
                display = if (t == "\$\$") !display else t == "\\["
                continue
            }
            if (shownLines++ > 0) out.append('\n')
            if (t.startsWith("```")) {
                fence = !fence
                out.append(whole)
                continue
            }
            if (fence) {
                out.append(whole)
                continue
            }
            // Trailing spaces end a line (Markdown's line break); the last line may still be streaming.
            var line = if (i < lines.lastIndex) whole.trimEnd(' ', '\t') else whole
            if (display) {
                val close = listOf(line.indexOf("\\]"), line.indexOf("\$\$")).filter { it >= 0 }.minOrNull()
                val body = if (close == null) line else line.substring(0, close)
                val indent = body.length - body.trimStart().length
                out.append(body, 0, indent).append(math(body.substring(indent)))
                if (close != null) {
                    display = false
                    open = inline(line.substring(close + 2), out, bold, open)
                }
                continue
            }
            var heading = false
            HEADING.find(line)?.let { line = line.substring(it.value.length); heading = true }
            if (line.startsWith("> ")) line = line.substring(2)
            BULLET.find(line)?.let { line = it.groupValues[1] + "• " + line.substring(it.value.length) }
            NUMBERED.find(line)?.let { line = it.groupValues[1] + " " + line.substring(it.value.length) }
            val start = out.length
            open = inline(line, out, bold, open)
            if (heading && out.length > start) bold += start until out.length
        }
        if (open >= 0 && out.length > open) bold += open until out.length
        val n = out.length
        return Shown(out.toString(), bold.mapNotNull { r -> if (r.first >= n) null else r.first..minOf(r.last, n - 1) })
    }

    /** LaTeX math as plain text: `\frac{a}{b}` as `a/b`, `\times` as `×`, `x^{2}` as `x²`, `\text{…}` as its words. */
    fun math(tex: String): String = SPACES.replace(Tex(tex, 0, math = true).rest(), " ").replace("( ", "(").replace(" )", ")").trim()

    /** One line outside a display formula: bold marks, code spans, inline math and commands. Returns the open bold's start. */
    private fun inline(line: String, out: StringBuilder, bold: MutableList<IntRange>, openAt: Int): Int {
        var open = openAt
        var j = 0
        while (j < line.length) {
            val c = line[j]
            when {
                c == '`' -> {
                    // A code span, as written.
                    val k = line.indexOf('`', j + 1)
                    val end = if (k < 0) line.length else k + 1
                    out.append(line, j, end)
                    j = end
                }
                line.startsWith("**", j) -> {
                    if (open < 0) {
                        open = out.length
                    } else {
                        if (out.length > open) bold += open until out.length
                        open = -1
                    }
                    j += 2
                }
                line.startsWith("\\(", j) || line.startsWith("\\[", j) || line.startsWith("\$\$", j) -> {
                    val close = when (line[j + 1]) { '(' -> "\\)"; '[' -> "\\]"; else -> "\$\$" }
                    val k = line.indexOf(close, j + 2)
                    out.append(math(line.substring(j + 2, if (k < 0) line.length else k)))
                    j = if (k < 0) line.length else k + 2
                }
                // A closer whose opener was on a line before.
                line.startsWith("\\)", j) || line.startsWith("\\]", j) -> j += 2
                c == '$' -> {
                    val k = closer(line, j)
                    if (k < 0) {
                        out.append('$')
                        j++
                    } else {
                        out.append(math(line.substring(j + 1, k)))
                        j = k + 1
                    }
                }
                c == '\\' -> {
                    // Outside math only a command this file knows changes (`\times`, `\frac{…}{…}`); any other stays as written.
                    val tex = Tex(line, j, math = false)
                    out.append(tex.command())
                    j = tex.i
                }
                else -> {
                    out.append(c)
                    j++
                }
            }
        }
        return open
    }

    /**
     * The `$` that closes the math pair opened at [open], or -1 when that `$` is a dollar sign: the pair must hug its
     * content (`$x$`, not `$ x $`), the closing `$` must not start a price (`$5-$10`), and content with a space in it
     * must carry a math sign (`$5 and the ratio ($` is two dollar signs).
     */
    private fun closer(line: String, open: Int): Int {
        if (open + 1 >= line.length || line[open + 1].isWhitespace()) return -1
        var k = open + 1
        while (k < line.length) {
            when (line[k]) {
                '\\' -> k++
                '$' -> {
                    val content = line.substring(open + 1, k)
                    val hugs = !line[k - 1].isWhitespace() && (k + 1 >= line.length || !line[k + 1].isDigit())
                    val isMath = content.none { it.isWhitespace() } || content.any { it in MATH_SIGNS }
                    return if (hugs && isMath) k else -1
                }
            }
            k++
        }
        return -1
    }

    /** [raw] without the end that may be half of a mark: `\fra`, `^`, `$`, a lone `*`, an open argument (`\frac{88{,}0`). */
    private fun held(raw: String): String {
        var s = TAIL_COMMAND.find(raw)?.let { raw.substring(0, it.range.first) } ?: raw
        while (s.isNotEmpty() && (s.last() in "^_$" || (s.last() == '*' && !s.endsWith("**")))) s = s.dropLast(1)
        return s.substring(0, openArgument(s))
    }

    /** Where an argument still open on the last line starts with its command (`\frac{a}{b` from `\frac`); s.length when none. */
    private fun openArgument(s: String): Int {
        val from = s.lastIndexOf('\n') + 1
        val opens = ArrayList<Int>()
        var k = from
        while (k < s.length) {
            when (s[k]) {
                '\\' -> k++
                '{' -> opens += k
                '}' -> opens.removeLastOrNull()
            }
            k++
        }
        var p = opens.firstOrNull() ?: return s.length
        while (p > from && s[p - 1] == '}') p = matching(s, p - 1, from) ?: break
        var q = p
        while (q > from && s[q - 1].isLetter()) q--
        return when {
            q < p && q > from && s[q - 1] == '\\' -> q - 1
            p > from && s[p - 1] in "^_" -> p - 1
            // `88{` or `88{,`: the thousands mark on its way.
            p >= s.length - 2 -> p
            else -> s.length
        }
    }

    /** The `{` that the `}` at [close] closes, looking back no further than [from]. */
    private fun matching(s: String, close: Int, from: Int): Int? {
        var depth = 0
        for (k in close downTo from) {
            when (s[k]) {
                '}' -> depth++
                '{' -> if (--depth == 0) return k
            }
        }
        return null
    }

    private val SYMBOLS = mapOf(
        "times" to "×", "div" to "÷", "cdot" to "·", "pm" to "±", "mp" to "∓", "approx" to "≈", "sim" to "~",
        "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥", "ne" to "≠", "neq" to "≠", "lt" to "<", "gt" to ">",
        "dots" to "…", "ldots" to "…", "cdots" to "⋯", "infty" to "∞", "to" to "→", "rightarrow" to "→",
        "Rightarrow" to "⇒", "implies" to "⇒", "leftarrow" to "←", "iff" to "⇔", "therefore" to "∴", "circ" to "∘",
        "degree" to "°", "percent" to "%", "quad" to " ", "qquad" to " ", "alpha" to "α", "beta" to "β", "gamma" to "γ",
        "delta" to "δ", "Delta" to "Δ", "theta" to "θ", "lambda" to "λ", "mu" to "μ", "pi" to "π", "sigma" to "σ",
        "Sigma" to "Σ", "omega" to "ω",
    )
    /** Commands whose argument is words: shown as the words. */
    private val TEXT = setOf("text", "textrm", "textbf", "textit", "mathrm", "mathbf", "mathit", "mathsf", "operatorname", "boxed", "mbox")
    /** Delimiter sizes (`\left(`): the delimiter after them stays, `.` is none. */
    private val SIZES = setOf("left", "right", "bigl", "bigr", "Bigl", "Bigr", "big", "Big")
    private val DROPPED = setOf("displaystyle", "textstyle", "limits", "nolimits")
    private const val OPERATORS = "+-−×÷·=/<>≤≥≈"
    private val SUP = "0123456789+-=()nix∘°".zip("⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾ⁿⁱˣ°°").toMap()
    private val SUB = "0123456789+-=()aeoxhklmnpstijruv".zip("₀₁₂₃₄₅₆₇₈₉₊₋₌₍₎ₐₑₒₓₕₖₗₘₙₚₛₜᵢⱼᵣᵤᵥ").toMap()

    /** A fraction's side: as it is when it is one term, in parentheses when it has a space or an operator. */
    private fun side(x: String) = if (x.isNotEmpty() && x.none { it.isWhitespace() || it in OPERATORS }) x else "($x)"

    /**
     * `^…` as superscript characters (`_…` as subscript) when every character has one; else the mark and the text
     * (`v_original`), an expression in parentheses (`e^(-kt)`).
     */
    private fun script(x: String, sup: Boolean): String {
        val map = if (sup) SUP else SUB
        if (x.isNotEmpty() && x.all { it in map }) return x.map { map.getValue(it) }.joinToString("")
        val mark = if (sup) "^" else "_"
        return if (x.all { it.isLetterOrDigit() }) mark + x else "$mark($x)"
    }

    /** A LaTeX reader from [i]: in [math], groups, `^`, `_` and `&` count; outside it only commands do. */
    private class Tex(private val s: String, var i: Int, private val math: Boolean) {
        fun rest(): String {
            val b = StringBuilder()
            while (i < s.length) b.append(unit())
            return b.toString()
        }

        private fun unit(): String {
            val c = s[i]
            return when {
                c == '\\' -> command()
                !math -> { i++; c.toString() }
                c == '{' -> Tex(group(), 0, math = true).rest()
                c == '}' || c == '&' -> { i++; "" }
                c == '^' || c == '_' -> { i++; script(arg(), c == '^') }
                else -> { i++; c.toString() }
            }
        }

        /** The text between the `{` at [i] and its `}`; [i] moves past the `}`. */
        private fun group(): String {
            val start = ++i
            var depth = 1
            while (i < s.length) {
                when (s[i]) {
                    '\\' -> i++
                    '{' -> depth++
                    '}' -> if (--depth == 0) return s.substring(start, i++)
                }
                i++
            }
            i = s.length
            return s.substring(start)
        }

        /** A command's or a script's argument, as math: a group, a command or one character. */
        private fun arg(): String {
            while (i < s.length && s[i] == ' ') i++
            if (i >= s.length) return ""
            return when (s[i]) {
                '{' -> Tex(group(), 0, math = true).rest()
                '\\' -> command()
                else -> s[i++].toString()
            }
        }

        /** The command at [i] (a `\`) with its arguments. */
        fun command(): String {
            val start = i++
            if (i >= s.length) return "\\"
            if (!s[i].isLetter()) {
                val c = s[i++]
                return when {
                    c in "$%&#" -> c.toString()
                    !math -> s.substring(start, i)
                    c in ",;: " -> " "
                    c == '!' -> ""
                    c == '\\' -> "\n"
                    else -> c.toString()
                }
            }
            while (i < s.length && s[i].isLetter()) i++
            val name = s.substring(start + 1, i)
            SYMBOLS[name]?.let { return it }
            return when (name) {
                "frac", "dfrac", "tfrac" -> { val a = arg(); val b = arg(); side(a) + "/" + side(b) }
                in TEXT -> {
                    while (i < s.length && s[i] == ' ') i++
                    if (i < s.length && s[i] == '{') Tex(group(), 0, math = false).rest() else arg()
                }
                "sqrt" -> {
                    if (i < s.length && s[i] == '[') i = s.indexOf(']', i).let { if (it < 0) s.length else it + 1 }
                    "√" + side(arg())
                }
                "overline" -> arg().map { "$it\u0305" }.joinToString("")
                // Outside math any other command stays as written (`C:\big` is a path).
                else -> when {
                    !math -> s.substring(start, i)
                    name in SIZES -> { if (i < s.length && s[i] == '.') i++; "" }
                    name in DROPPED -> ""
                    name == "begin" || name == "end" -> { arg(); "" }
                    else -> name
                }
            }
        }
    }
}
