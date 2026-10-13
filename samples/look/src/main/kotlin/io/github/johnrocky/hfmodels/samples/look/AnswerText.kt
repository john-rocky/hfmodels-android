package io.github.johnrocky.hfmodels.samples.look

/**
 * The answer as the screen shows it. The model writes Markdown (`**bold**`, `- ` bullets, `### ` headings, a line
 * break as two trailing spaces); the screen drops the marks and sets the bold parts in bold, so the answer reads as
 * text. The record keeps what the model wrote. It runs on every streamed piece, so a `**` that has not closed yet is
 * bold to the end and a lone `*` at the very end (half of a `**` still on its way) is held back.
 * No Android types: the JVM tests run it.
 */
object AnswerText {
    /** The text to show and the ranges to set in bold. */
    data class Shown(val text: String, val bold: List<IntRange>)

    private val HEADING = Regex("^#{1,6} ")

    fun render(raw: String): Shown {
        val out = StringBuilder()
        val bold = ArrayList<IntRange>()
        var open = -1
        val lines = raw.split('\n')
        for ((i, whole) in lines.withIndex()) {
            if (i > 0) out.append('\n')
            // Trailing spaces end a line (Markdown's line break); the last line may still be streaming.
            var line = if (i < lines.lastIndex) whole.trimEnd(' ', '\t') else whole
            var heading = false
            HEADING.find(line)?.let { line = line.substring(it.value.length); heading = true }
            if (line.startsWith("> ")) line = line.substring(2)
            if (line.startsWith("- ") || line.startsWith("* ")) line = "• " + line.substring(2)
            val start = out.length
            var j = 0
            while (j < line.length) {
                if (line.startsWith("**", j)) {
                    if (open < 0) {
                        open = out.length
                    } else {
                        if (out.length > open) bold += open until out.length
                        open = -1
                    }
                    j += 2
                    continue
                }
                out.append(line[j])
                j++
            }
            if (heading && out.length > start) bold += start until out.length
        }
        if (raw.endsWith("*") && !raw.endsWith("**") && out.endsWith("*")) out.setLength(out.length - 1)
        if (open >= 0 && out.length > open) bold += open until out.length
        val n = out.length
        return Shown(out.toString(), bold.mapNotNull { r -> if (r.first >= n) null else r.first..minOf(r.last, n - 1) })
    }
}
