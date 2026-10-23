package io.github.johnrocky.hfmodels.samples.loops

/** The final `#### <number>` line of an answer, read against a GSM8K question's known answer. */
object Marks {
    sealed class Mark {
        /** The answer has no `####` line with a number (it was cut, or the model wrote the number another way). */
        object NoLine : Mark()
        data class Match(val value: String) : Mark()
        data class Mismatch(val value: String, val gold: String) : Mark()
    }

    private val line = Regex("^\\s*####\\s*(.*?)\\s*$", RegexOption.MULTILINE)
    private val number = Regex("-?\\d[\\d,]*(?:\\.\\d+)?")

    /** The number on the last `####` line, normalized; null when there is none. */
    fun finalValue(text: String): String? {
        val last = line.findAll(text).lastOrNull() ?: return null
        val n = number.find(last.groupValues[1].replace("\$", "")) ?: return null
        return normalize(n.value)
    }

    /** `8,000` -> `8000`, `60.00` -> `60`, `2.50` -> `2.5`; the digits of a whole number are never touched. */
    fun normalize(value: String): String {
        var v = value.replace(",", "")
        if ('.' in v) v = v.trimEnd('0').trimEnd('.')
        return v
    }

    fun mark(text: String, gold: String): Mark {
        val v = finalValue(text) ?: return Mark.NoLine
        return if (v == normalize(gold)) Mark.Match(v) else Mark.Mismatch(v, normalize(gold))
    }
}
