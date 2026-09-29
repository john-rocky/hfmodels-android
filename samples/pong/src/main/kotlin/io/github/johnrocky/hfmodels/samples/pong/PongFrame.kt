package io.github.johnrocky.hfmodels.samples.pong

import kotlin.math.roundToInt

/**
 * Draws the game into a 160x210 buffer of opaque ARGB pixels (0xFFRRGGBB), the Atari Pong look:
 * brown field, white walls above and below, the scripted paddle on the left, the model's on the right,
 * the white ball drawn last. Nothing else is drawn: this buffer is what the model is shown.
 */
object PongFrame {
    const val WIDTH = 160
    const val HEIGHT = 210

    const val BACKGROUND = 0xFF904811.toInt()      // (144, 72, 17)
    const val WHITE = 0xFFECECEC.toInt()           // (236, 236, 236)
    const val LEFT_PADDLE = 0xFFD5824A.toInt()     // (213, 130, 74)
    const val RIGHT_PADDLE = 0xFF5CBA5C.toInt()    // (92, 186, 92)

    fun render(s: PongState, out: IntArray = IntArray(WIDTH * HEIGHT)): IntArray =
        render(s.ballX.roundToInt(), s.ballY.roundToInt(), s.paddle, s.left, out)

    /** Ball at ([ballX], [ballY]) (its top-left pixel), paddles by their top edges. */
    fun render(ballX: Int, ballY: Int, paddle: Int, left: Int, out: IntArray = IntArray(WIDTH * HEIGHT)): IntArray {
        out.fill(BACKGROUND)
        fill(out, 0, 24, 159, 33, WHITE)
        fill(out, 0, 194, 159, 209, WHITE)
        fill(out, 16, left, 19, left + 15, LEFT_PADDLE)
        fill(out, 140, paddle, 143, paddle + 15, RIGHT_PADDLE)
        fill(out, ballX, ballY, ballX + 1, ballY + 3, WHITE)
        return out
    }

    /** Fills x0..x1, y0..y1 (both ends included), clipped to the frame. */
    private fun fill(out: IntArray, x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        for (y in maxOf(y0, 0)..minOf(y1, HEIGHT - 1)) {
            for (x in maxOf(x0, 0)..minOf(x1, WIDTH - 1)) out[y * WIDTH + x] = color
        }
    }
}
