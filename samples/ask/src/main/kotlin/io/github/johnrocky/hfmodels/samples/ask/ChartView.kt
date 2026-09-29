package io.github.johnrocky.hfmodels.samples.ask

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The chart on screen: the exact PNG bytes the model is sent, decoded and scaled up with
 * nearest-neighbour sampling at the largest whole factor that fits (3x is 768 px), centred. The
 * thin white frame is drawn outside the image, a dark gap away from it, so nothing but the chart is
 * inside the picture's bounds.
 */
class ChartView(context: Context, u: Float) : View(context) {
    private var bitmap: Bitmap? = null
    private val crisp = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val stroke = max(2f, 1.5f * u)
    private val gap = ceil(4f * u)
    private val margin = ceil(gap + stroke).toInt()
    private val framePaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = stroke; color = 0xFFFFFFFF.toInt() }
    private val dst = Rect()

    /** Shows [png], the bytes that go to the model. */
    fun show(png: ByteArray) {
        bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)
        invalidate()
    }

    /** Pixels of the screen per pixel of the chart for the view's current size. */
    fun factor(): Int = max(1, (min(width, height) - 2 * margin) / ChartFrame.SIDE)

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        val side = ChartFrame.SIDE * factor()
        val left = (width - side) / 2
        val top = (height - side) / 2
        dst.set(left, top, left + side, top + side)
        canvas.drawBitmap(bmp, null, dst, crisp)
        val o = gap + stroke / 2
        canvas.drawRect(left - o, top - o, left + side + o, top + side + o, framePaint)
    }
}
