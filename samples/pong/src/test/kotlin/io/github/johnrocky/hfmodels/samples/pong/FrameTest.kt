package io.github.johnrocky.hfmodels.samples.pong

import org.junit.Assert.assertEquals
import org.junit.Test

/** The 160x210 frame drawn here equals the reference drawing of the same state, pixel for pixel. */
class FrameTest {
    companion object {
        /** states.json: ball (x, y), right_y, left_y per state, in fixture order. */
        fun states(): List<PongState> {
            @Suppress("UNCHECKED_CAST")
            val rows = Fixtures.json("states.json") as List<Map<String, Any?>>
            return rows.map { r ->
                val ball = r["ball"] as List<*>
                PongState((ball[0] as Double), (ball[1] as Double), 0, 0, (r["right_y"] as Double).toInt(), (r["left_y"] as Double).toInt())
            }
        }
    }

    @Test
    fun framesMatchTheReferenceDrawing() {
        val states = states()
        assertEquals(3, states.size)
        states.forEachIndexed { n, s ->
            val (expected, width) = Fixtures.pixels("state${n}_160x210.png")
            assertEquals(PongFrame.WIDTH, width)
            val actual = PongFrame.render(s)
            var differing = 0
            for (i in expected.indices) if (expected[i] != actual[i]) differing++
            println("state$n 160x210: $differing of ${expected.size} pixels differ")
            assertEquals("state$n: differing pixels", 0, differing)
        }
    }
}
