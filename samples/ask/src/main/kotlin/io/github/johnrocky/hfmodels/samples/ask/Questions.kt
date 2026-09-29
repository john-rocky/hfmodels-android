package io.github.johnrocky.hfmodels.samples.ask

/** The five questions asked about every chart, in the order they are asked, built from the chart alone. */
object Questions {
    val IDS = listOf("tallest", "shortest", "count", "taller", "leftmost")
    val COUNT_OPTIONS = listOf("2", "3", "4", "5", "6")
    val YES_NO = listOf("no", "yes")

    fun of(spec: ChartSpec): List<Question> {
        val bars = spec.colours.map { "the $it bar" }
        val h = spec.heights
        val a = spec.tallerA
        val b = spec.tallerB
        return listOf(
            Question("tallest", "Which bar is the tallest?", bars, h.indices.maxBy { h[it] }),
            Question("shortest", "Which bar is the shortest?", bars, h.indices.minBy { h[it] }),
            Question("count", "How many bars are in the chart?", COUNT_OPTIONS, COUNT_OPTIONS.indexOf(spec.n.toString())),
            Question("taller", "Is the ${spec.colours[a]} bar taller than the ${spec.colours[b]} bar?", YES_NO, if (h[a] > h[b]) 1 else 0),
            Question("leftmost", "Which bar is on the far left?", bars, 0),
        )
    }
}
