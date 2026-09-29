package io.github.johnrocky.hfmodels.samples.pong

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Replays the reference trace (80 decisions, seed 7): from its first state, the recorded actions must
 * reproduce every later state, every event and the final counts. No serve happens inside the trace, so
 * the serve generator does not enter the comparison.
 */
class PhysicsReplayTest {
    @Suppress("UNCHECKED_CAST")
    private fun state(o: Map<String, Any?>): PongState {
        val ball = o["ball"] as List<Double>
        val v = o["v"] as List<Double>
        return PongState(ball[0], ball[1], v[0].toInt(), v[1].toInt(), (o["paddle"] as Double).toInt(), (o["left"] as Double).toInt())
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun replayMatchesTheTrace() {
        val trace = Fixtures.json("pong_trace_seed7.json") as Map<String, Any?>
        val steps = trace["steps"] as List<Map<String, Any?>>
        val summary = trace["summary"] as Map<String, Any?>
        assertEquals(80, steps.size)

        val game = PongGame(seed = 7)
        game.restore(state(steps[0]["state_before"] as Map<String, Any?>))
        for (row in steps) {
            val n = (row["step"] as Double).toInt()
            val expected = state(row["state_before"] as Map<String, Any?>)
            val actual = game.state()
            assertEquals("step $n ball x", expected.ballX, actual.ballX, 0.1)
            assertEquals("step $n ball y", expected.ballY, actual.ballY, 0.1)
            assertEquals("step $n vx", expected.vx, actual.vx)
            assertEquals("step $n vy", expected.vy, actual.vy)
            assertEquals("step $n paddle", expected.paddle, actual.paddle)
            assertEquals("step $n left", expected.left, actual.left)
            val event = game.step((row["action"] as Double).toInt())
            assertEquals("step $n event", row["event"], event?.label)
        }
        assertEquals((summary["hits"] as Double).toInt(), game.hits)
        assertEquals((summary["misses"] as Double).toInt(), game.misses)
        assertEquals((summary["left_hits"] as Double).toInt(), game.leftHits)
        assertEquals((summary["serves"] as Double).toInt(), game.serves)
        assertEquals(4, game.hits)
        assertEquals(0, game.misses)
        assertEquals(4, game.leftHits)
        assertEquals(1, game.serves)
        println("replay: 80 steps, hits ${game.hits}, misses ${game.misses}, left_hits ${game.leftHits}, left_misses ${game.leftMisses}, serves ${game.serves}")
    }
}
