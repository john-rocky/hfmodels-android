package io.github.johnrocky.hfmodels.samples.ask

/**
 * Draws a chart into a 256x256 buffer of opaque ARGB pixels (0xFFRRGGBB): white background, a black
 * baseline two rows thick, one filled bar per value standing on it. Nothing else is drawn: this buffer
 * is what the model is shown and what the screen shows, scaled.
 */
object ChartFrame {
    const val SIDE = 256
    /** The baseline's upper row; the bars end on the row above it. */
    const val BASE = SIDE - 24
    private const val GAP = 20

    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()
    val COLOURS: Map<String, Int> = linkedMapOf(
        "red" to 0xFFDC2828.toInt(),      // (220, 40, 40)
        "green" to 0xFF28AA3C.toInt(),    // (40, 170, 60)
        "blue" to 0xFF2850DC.toInt(),     // (40, 80, 220)
        "yellow" to 0xFFF0D21E.toInt(),   // (240, 210, 30)
        "purple" to 0xFF963CC8.toInt(),   // (150, 60, 200)
    )

    fun render(spec: ChartSpec, out: IntArray = IntArray(SIDE * SIDE)): IntArray {
        out.fill(WHITE)
        fill(out, GAP / 2, BASE, SIDE - GAP / 2, BASE + 1, BLACK)
        val n = spec.n
        val barWidth = (SIDE - GAP * (n + 1)).toDouble() / n
        for (i in 0 until n) {
            val x0 = GAP + i * (barWidth + GAP)
            fill(out, round(x0), BASE - spec.heights[i], round(x0 + barWidth), BASE - 1, COLOURS.getValue(spec.colours[i]))
        }
        return out
    }

    /** Rounds half to even, as the reference drawing's rounding does (no bar edge falls on .5 for 3, 4 or 5 bars). */
    private fun round(x: Double): Int = Math.rint(x).toInt()

    /** Fills x0..x1, y0..y1 (both ends included). */
    private fun fill(out: IntArray, x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        for (y in y0..y1) for (x in x0..x1) out[y * SIDE + x] = color
    }
}
