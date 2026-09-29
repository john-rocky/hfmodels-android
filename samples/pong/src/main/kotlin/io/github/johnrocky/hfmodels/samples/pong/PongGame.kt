package io.github.johnrocky.hfmodels.samples.pong

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** What happened at the end of one step; `null` from [PongGame.step] means the ball just moved. */
enum class PongEvent(val label: String) {
    /** The right paddle (the model) returned the ball. */
    HIT("hit"),
    /** The right paddle missed; the ball is served again toward it. */
    MISS("miss"),
    /** The scripted left paddle missed; the ball is served toward it. */
    LEFT_MISS("left_miss"),
}

/** The discrete state the model is shown: ball position and velocity, both paddles' top edges. */
data class PongState(val ballX: Double, val ballY: Double, val vx: Int, val vy: Int, val paddle: Int, val left: Int)

/**
 * Pong on the 160x210 Atari frame, one step per decision. The right paddle is moved by the model's
 * answer, the left paddle follows the ball on its own, then the ball advances by one velocity step.
 * Field walls at y = 34 and y = 194; left paddle x 16..20, right paddle x 140..144, paddles 16 tall;
 * the ball is 2 x 4.
 */
class PongGame(seed: Int) {
    companion object {
        const val TOP = 34
        const val BOT = 194
        const val PADDLE_HEIGHT = 16
        const val PADDLE_STEP = 10
        const val VX = 12
        const val VY_MAX = 8
        const val RIGHT_X = 140
        const val LEFT_EDGE = 20
        const val UP = 0
        const val DOWN = 1
        const val STAY = 2
    }

    private val random = Random(seed)

    var ballX = 0.0; private set
    var ballY = 0.0; private set
    var vx = 0; private set
    var vy = 0; private set
    /** Top edge of the right paddle (the model's). */
    var paddle = 90; private set
    /** Top edge of the left paddle (scripted). */
    var left = 90; private set

    var hits = 0; private set
    var misses = 0; private set
    var leftHits = 0; private set
    var leftMisses = 0; private set
    var serves = 0; private set

    init {
        serve(towardRight = true)
    }

    fun state() = PongState(ballX, ballY, vx, vy, paddle, left)

    /** Puts the ball and both paddles where [s] says; the counts and the serve generator are left as they are. */
    fun restore(s: PongState) {
        ballX = s.ballX; ballY = s.ballY; vx = s.vx; vy = s.vy; paddle = s.paddle; left = s.left
    }

    private fun serve(towardRight: Boolean) {
        serves++
        ballX = if (towardRight) 40.0 else 118.0
        ballY = random.nextInt(TOP + 10, BOT - 14 + 1).toDouble()
        vx = if (towardRight) VX else -VX
        vy = (if (random.nextBoolean()) -1 else 1) * random.nextInt(2, VY_MAX + 1)
    }

    /** Applies [action] ([UP], [DOWN] or [STAY]) to the right paddle, moves the left paddle, then the ball. */
    fun step(action: Int): PongEvent? {
        if (action == UP) paddle = max(TOP, paddle - PADDLE_STEP)
        else if (action == DOWN) paddle = min(BOT - PADDLE_HEIGHT, paddle + PADDLE_STEP)
        // The scripted left paddle moves toward the ball's centre, at most PADDLE_STEP per step.
        val target = ballY + 2 - 8
        val d = max(-PADDLE_STEP.toDouble(), min(PADDLE_STEP.toDouble(), target - left))
        left = max(TOP.toDouble(), min((BOT - PADDLE_HEIGHT).toDouble(), left + d)).toInt()

        var nx = ballX + vx
        var ny = ballY + vy
        if (ny < TOP) { ny = TOP + (TOP - ny); vy = -vy }
        if (ny + 4 > BOT) { ny = BOT - 4 - (ny + 4 - BOT); vy = -vy }
        var event: PongEvent? = null
        if (vx > 0 && nx + 2 >= RIGHT_X) {
            // Hit when the ball's centre is within the paddle, with 4 px of grace at either end.
            if (paddle - 4 <= ny + 2 && ny + 2 <= paddle + 20) {
                hits++; event = PongEvent.HIT
                nx = RIGHT_X - 2 - (nx + 2 - RIGHT_X); vx = -vx
                // The farther from the paddle's centre, the steeper the return (truncated toward zero).
                vy = (vy + ((ny + 2) - (paddle + 8)) / 4.0).coerceIn(-VY_MAX.toDouble(), VY_MAX.toDouble()).toInt()
            } else {
                misses++
                ballX = nx; ballY = ny
                serve(towardRight = true)
                return PongEvent.MISS
            }
        } else if (vx < 0 && nx <= LEFT_EDGE) {
            if (left - 4 <= ny + 2 && ny + 2 <= left + 20) {
                leftHits++
                nx = LEFT_EDGE + (LEFT_EDGE - nx); vx = -vx
            } else {
                leftMisses++
                ballX = nx; ballY = ny
                serve(towardRight = false)
                return PongEvent.LEFT_MISS
            }
        }
        ballX = nx; ballY = ny
        return event
    }
}
