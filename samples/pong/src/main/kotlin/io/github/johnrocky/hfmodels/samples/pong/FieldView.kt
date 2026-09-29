package io.github.johnrocky.hfmodels.samples.pong

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The game field: the 160x210 frame scaled up with nearest-neighbour sampling at the largest whole
 * factor that fits, so every game pixel is the same size. The paddle labels sit above the field's
 * corners, outside the frame. Between two game states the ball and the paddles glide for
 * [GLIDE_MS]; that motion is on screen only, the model is only ever sent the states themselves.
 */
class FieldView(context: Context, private val u: Float) : View(context) {
    companion object {
        const val GLIDE_MS = 350L
    }

    private var from: PongState? = null
    private var to: PongState? = null
    private var jumpBall = false
    private var glideStart = 0L

    private val pixels = IntArray(PongFrame.WIDTH * PongFrame.HEIGHT)
    private val bitmap = Bitmap.createBitmap(PongFrame.WIDTH, PongFrame.HEIGHT, Bitmap.Config.ARGB_8888)
    private val crisp = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val dst = Rect()
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 15f * u
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val labelStrip = 24f * u

    /** Shows [s] at rest. */
    fun show(s: PongState) {
        from = s; to = s; jumpBall = false; glideStart = 0L
        invalidate()
    }

    /** Glides from the state on screen to [s]; [jumpBall] puts the ball there at once (a new serve). */
    fun glideTo(s: PongState, jumpBall: Boolean) {
        from = to ?: s; to = s; this.jumpBall = jumpBall
        glideStart = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    /** Milliseconds until the running glide ends (0 at rest). */
    fun remainingGlideMs(): Long = max(0L, glideStart + GLIDE_MS - SystemClock.uptimeMillis())

    override fun onDraw(canvas: Canvas) {
        val a = from ?: return
        val b = to ?: return
        val t = if (glideStart == 0L) 1f else min(1f, (SystemClock.uptimeMillis() - glideStart).toFloat() / GLIDE_MS)
        fun lerp(x: Double, y: Double) = (x + (y - x) * t).roundToInt()
        val ballX = if (jumpBall) b.ballX.roundToInt() else lerp(a.ballX, b.ballX)
        val ballY = if (jumpBall) b.ballY.roundToInt() else lerp(a.ballY, b.ballY)
        PongFrame.render(ballX, ballY, lerp(a.paddle.toDouble(), b.paddle.toDouble()), lerp(a.left.toDouble(), b.left.toDouble()), pixels)
        bitmap.setPixels(pixels, 0, PongFrame.WIDTH, 0, 0, PongFrame.WIDTH, PongFrame.HEIGHT)

        val scale = max(1, min(width / PongFrame.WIDTH, ((height - labelStrip) / PongFrame.HEIGHT).toInt()))
        val w = PongFrame.WIDTH * scale
        val h = PongFrame.HEIGHT * scale
        val left = (width - w) / 2
        val top = max(0, (height - labelStrip.roundToInt() - h) / 2) + labelStrip.roundToInt()
        dst.set(left, top, left + w, top + h)
        canvas.drawBitmap(bitmap, null, dst, crisp)

        val baseline = top - 6f * u
        labelPaint.color = PongFrame.LEFT_PADDLE
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("script", left.toFloat(), baseline, labelPaint)
        labelPaint.color = PongFrame.RIGHT_PADDLE
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("model", (left + w).toFloat(), baseline, labelPaint)

        if (t < 1f) postInvalidateOnAnimation()
    }
}
