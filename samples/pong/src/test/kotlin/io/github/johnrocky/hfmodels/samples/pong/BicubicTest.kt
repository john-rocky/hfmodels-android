package io.github.johnrocky.hfmodels.samples.pong

import java.util.Locale
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 256x256 image the model gets, against the reference resize of the same frame. */
class BicubicTest {
    @Test
    fun resizeMatchesTheReference() {
        FrameTest.states().forEachIndexed { n, s ->
            val (expected, width) = Fixtures.pixels("state${n}_256_bicubic.png")
            assertEquals(256, width)
            val actual = Bicubic.resize(PongFrame.render(s), PongFrame.WIDTH, PongFrame.HEIGHT, 256, 256)
            var max = 0
            var sum = 0L
            for (i in expected.indices) {
                for (shift in intArrayOf(16, 8, 0)) {
                    val d = abs(((expected[i] shr shift) and 0xFF) - ((actual[i] shr shift) and 0xFF))
                    if (d > max) max = d
                    sum += d
                }
            }
            val mean = sum.toDouble() / (expected.size * 3)
            println(String.format(Locale.US, "state%d 256x256 bicubic: max abs channel diff %d, mean abs diff %.6f", n, max, mean))
            assertTrue("state$n max $max", max <= 3)
            assertTrue("state$n mean $mean", mean <= 0.5)
        }
    }
}
