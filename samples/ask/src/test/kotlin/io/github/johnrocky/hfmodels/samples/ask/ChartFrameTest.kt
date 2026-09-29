package io.github.johnrocky.hfmodels.samples.ask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 256x256 chart drawn here equals the reference drawing of the same chart, pixel for pixel. */
class ChartFrameTest {
    @Test
    fun chartsMatchTheReferenceDrawing() {
        val charts = Fixtures.charts()
        val reference = Fixtures.referenceCharts()
        assertEquals(3, reference.size)
        reference.forEachIndexed { k, ref ->
            val spec = charts[k].spec
            // The asset's chart is the chart the reference drew.
            assertEquals(ref["id"], spec.id)
            assertEquals((ref["heights"] as List<*>).map { (it as Double).toInt() }, spec.heights)
            assertEquals(ref["colours"], spec.colours)
            val (expected, width) = Fixtures.pixels("${spec.id}.png")
            assertEquals(ChartFrame.SIDE, width)
            assertEquals(ChartFrame.SIDE * ChartFrame.SIDE, expected.size)
            val actual = ChartFrame.render(spec)
            var differing = 0
            for (i in expected.indices) if (expected[i] != actual[i]) differing++
            println("${spec.id} (${spec.n} bars): $differing of ${expected.size} pixels differ")
            assertEquals("${spec.id}: differing pixels", 0, differing)
        }
    }

    @Test
    fun everyChartInTheAssetRenders() {
        val charts = Fixtures.charts()
        assertEquals(24, charts.size)
        for (c in charts) {
            val px = ChartFrame.render(c.spec)
            // Each bar's colour appears, and the baseline row is black from x 10 to 246.
            for (name in c.spec.colours) assertTrue("${c.spec.id}: no $name pixel", px.contains(ChartFrame.COLOURS.getValue(name)))
            for (x in 0 until ChartFrame.SIDE) {
                val want = if (x in 10..246) ChartFrame.BLACK else ChartFrame.WHITE
                assertEquals("${c.spec.id} baseline x=$x", want, px[ChartFrame.BASE * ChartFrame.SIDE + x])
                assertEquals("${c.spec.id} baseline x=$x", want, px[(ChartFrame.BASE + 1) * ChartFrame.SIDE + x])
            }
        }
    }
}
