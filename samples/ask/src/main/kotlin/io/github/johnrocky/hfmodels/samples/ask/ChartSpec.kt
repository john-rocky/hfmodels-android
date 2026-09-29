package io.github.johnrocky.hfmodels.samples.ask

/**
 * One bar chart: bar [heights] in pixels and bar [colours] by name, left to right, and the pair of bars
 * the "taller" question compares ([tallerA] against [tallerB], bar indices). The picture, the questions
 * and the expected answers are all derived from this.
 */
data class ChartSpec(
    val id: String,
    val heights: List<Int>,
    val colours: List<String>,
    val context: String,
    val tallerA: Int,
    val tallerB: Int,
) {
    val n: Int get() = heights.size

    init {
        require(n in 3..5) { "$id: $n bars; the layout is for 3, 4 or 5" }
        require(colours.size == n) { "$id: ${colours.size} colours for $n bars" }
        require(colours.all { it in ChartFrame.COLOURS }) { "$id: unknown colour in $colours" }
        require(colours.toSet().size == n) { "$id: a colour names one bar only" }
        require(heights.all { it in 1..ChartFrame.BASE }) { "$id: heights $heights" }
        // "The tallest" and "the shortest" have one answer only when no two heights are equal.
        require(heights.toSet().size == n) { "$id: equal heights in $heights" }
        require(tallerA in heights.indices && tallerB in heights.indices && tallerA != tallerB) { "$id: taller pair $tallerA, $tallerB" }
    }
}

/** A multiple-choice question about a chart; [expected] is the index of the option the chart's data gives. */
data class Question(val id: String, val text: String, val options: List<String>, val expected: Int)

/** One entry of assets/charts.json: the chart and the questions stored with it. */
data class ChartEntry(val spec: ChartSpec, val questions: List<Question>)

/** Reads assets/charts.json (written by tools/make_charts_json.py). */
object ChartFile {
    fun parse(text: String): List<ChartEntry> {
        val rows = MiniJson(text).parse() as List<*>
        return rows.map { r ->
            val o = r as Map<*, *>
            val taller = o["taller"] as Map<*, *>
            val spec = ChartSpec(
                id = o["id"] as String,
                heights = (o["heights"] as List<*>).map { int(it) },
                colours = (o["colours"] as List<*>).map { it as String },
                context = o["context"] as String,
                tallerA = int(taller["a"]),
                tallerB = int(taller["b"]),
            )
            require(int(o["n"]) == spec.n) { "${spec.id}: n=${o["n"]} but ${spec.n} heights" }
            val questions = (o["questions"] as List<*>).map { q ->
                val m = q as Map<*, *>
                Question(m["id"] as String, m["question"] as String, (m["options"] as List<*>).map { it as String }, int(m["expected"]))
            }
            ChartEntry(spec, questions)
        }
    }

    private fun int(v: Any?): Int {
        val d = v as Double
        require(d == Math.floor(d)) { "not an integer: $d" }
        return d.toInt()
    }
}

/** Just enough JSON for charts.json: objects, arrays, numbers, strings, true / false / null. */
internal class MiniJson(private val s: String) {
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
                    'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r'); 'b' -> b.append('\b'); 'f' -> b.append('\u000C')
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
            when (s[i++]) { ']' -> return out; ',' -> {}; else -> error("expected ',' or ']' at ${i - 1}") }
        }
    }

    private fun obj(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>(); i++; ws()
        if (s[i] == '}') { i++; return out }
        while (true) {
            ws(); val k = str(); ws(); require(s[i++] == ':') { "expected ':' at ${i - 1}" }; out[k] = value(); ws()
            when (s[i++]) { '}' -> return out; ',' -> {}; else -> error("expected ',' or '}' at ${i - 1}") }
        }
    }
}
