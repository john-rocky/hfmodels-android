package io.github.johnrocky.hfmodels.samples.decide

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** The colors and the pill of the Core AI inbox example (InboxScreen.swift), as ARGB ints. */
object InboxStyle {
    const val BACKGROUND = 0xFF0E1116.toInt()
    const val LANE = 0xFF1B2028.toInt()
    const val PENDING = 0xFF6B7280.toInt()
    const val DIM = 0xFF3C424C.toInt()
    const val AXIS = 0xFF8A919C.toInt()
    const val LATENCY = 0xFFB8BEC6.toInt()
    const val ACTION = 0xFF4285F4.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val PILL_IDLE = 0xFF5F6368.toInt()
    const val PILL_SORTING = 0xFFE53935.toInt()
    const val PILL_DONE = 0xFF2E7D32.toInt()

    /** One color per option, in InboxPanel.OPTIONS order; grey for "nothing". */
    val OPTION_COLORS = intArrayOf(AXIS, 0xFF4285F4.toInt(), 0xFFFBBC05.toInt(), 0xFF34A853.toInt(), 0xFFAB47BC.toInt())
}

/**
 * The inbox, drawn row by row: the sender, two lines of text (grey until sorted), then the answer as a
 * chip. Every row has the same height, so answers arriving never move the list; while sorting the list
 * glides so the text being sorted sits in the lower third. `u` is the screen width / 402, the unit the
 * Core AI screen is laid out in.
 */
class InboxListView(context: Context, private val u: Float) : View(context) {
    class Row(val sender: String, val text: String) {
        /** Index into InboxPanel.OPTIONS; -1 until sorted. */
        var bin = -1
        internal var grey: StaticLayout? = null
        internal var white: StaticLayout? = null
        internal var layoutWidth = 0
    }

    var rows: List<Row> = emptyList()
        set(v) { field = v; scroll = 0f; target = 0f; invalidate() }
    /** The row being sorted (highlighted and followed), or -1. */
    var current = -1
        set(v) { field = v; if (v >= 0) follow = true; invalidate(); tick() }
    /** Room under the last row for the button over the list, kept while sorting so the list never jumps at DONE. */
    private val bottomRoom = 64f * u

    private val pitch = 88f * u
    private val rowHeight = 86f * u
    private var scroll = 0f
    private var target = 0f
    private var follow = false
    private var lastFrameNanos = 0L
    private val scroller = OverScroller(context)

    private val senderPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * u; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f * u }
    private val chipPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.4f * u; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private fun maxScroll() = max(0f, rows.size * pitch + bottomRoom - height)

    private fun layoutsOf(r: Row, width: Int) {
        if (r.layoutWidth == width && r.grey != null) return
        fun build(color: Int) = StaticLayout.Builder.obtain(r.text, 0, r.text.length, TextPaint(textPaint).apply { this.color = color }, width)
            .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
        r.grey = build(InboxStyle.PENDING); r.white = build(InboxStyle.WHITE); r.layoutWidth = width
    }

    override fun onDraw(canvas: Canvas) {
        advance()
        val first = max(0, (scroll / pitch).toInt())
        val last = min(rows.size - 1, ((scroll + height) / pitch).toInt())
        val pad = 8f * u
        val textWidth = (width - 2 * pad).toInt().coerceAtLeast(1)
        for (i in first..last) {
            val r = rows[i]
            val top = i * pitch - scroll
            if (i == current) {
                fill.color = InboxStyle.LANE
                rect.set(0f, top, width.toFloat(), top + rowHeight)
                canvas.drawRoundRect(rect, 8f * u, 8f * u, fill)
            }
            val sorted = r.bin >= 0
            senderPaint.color = if (sorted || i == current) InboxStyle.AXIS else InboxStyle.DIM
            canvas.drawText(TextUtils.ellipsize(r.sender, senderPaint, width - 2 * pad, TextUtils.TruncateAt.END).toString(), pad, top + 6f * u + 11f * u, senderPaint)
            layoutsOf(r, textWidth)
            canvas.save()
            canvas.translate(pad, top + 6f * u + 17f * u)
            (if (sorted) r.white else r.grey)!!.draw(canvas)
            canvas.restore()
            if (sorted) chip(canvas, InboxPanel.SHORT[r.bin], InboxStyle.OPTION_COLORS[r.bin], InboxStyle.LANE, pad, top + 6f * u + 17f * u + 36f * u + 4f * u)
        }
        if (animating()) postInvalidateOnAnimation()
    }

    private fun chip(canvas: Canvas, label: String, textColor: Int, bg: Int, x: Float, top: Float): Float {
        val w = chipPaint.measureText(label) + 14f * u
        fill.color = bg
        rect.set(x, top, x + w, top + 18f * u)
        canvas.drawRoundRect(rect, 6f * u, 6f * u, fill)
        chipPaint.color = textColor
        canvas.drawText(label, x + 7f * u, top + 13f * u, chipPaint)
        return x + w
    }

    // Follow the current row with a short easing (about 80 ms), as the Core AI list does with its 0.08 s animation.
    private fun advance() {
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0.016 else ((now - lastFrameNanos) / 1e9).coerceIn(0.0, 0.1)
        lastFrameNanos = now
        if (scroller.computeScrollOffset()) {
            scroll = scroller.currY.toFloat(); target = scroll
        } else if (follow && current >= 0) {
            target = (current * pitch + rowHeight / 2 - 0.72f * height).coerceIn(0f, maxScroll())
            scroll += ((target - scroll) * (1 - exp(-dt / 0.08))).toFloat()
            if (abs(target - scroll) < 0.5f) scroll = target
        }
        scroll = scroll.coerceIn(0f, maxScroll())
    }

    private fun animating() = !scroller.isFinished || (follow && abs(target - scroll) >= 0.5f)

    private fun tick() { lastFrameNanos = 0L; postInvalidateOnAnimation() }

    // Touch: drag and fling when the list is not being followed.
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { scroller.forceFinished(true); follow = false; return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            scroll = (scroll + dy).coerceIn(0f, maxScroll()); target = scroll; invalidate(); return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            scroller.fling(0, scroll.toInt(), 0, -vy.toInt(), 0, 0, 0, maxScroll().toInt()); tick(); return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event) || super.onTouchEvent(event)
}

/** The options as growing bars with their counts (the Core AI intent bars), grown against a quarter of the inbox. */
class InboxBinsView(context: Context, private val u: Float) : View(context) {
    var total = 0
        set(v) { field = v; invalidate() }
    val counts = IntArray(InboxPanel.OPTIONS.size)
    private val shown = FloatArray(InboxPanel.OPTIONS.size)
    private var lastFrameNanos = 0L

    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11.5f * u; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) }
    private val countPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11.5f * u; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); color = InboxStyle.WHITE; textAlign = Paint.Align.RIGHT; fontFeatureSettings = "tnum" }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    val rowPitch = 20f * u

    fun update() { lastFrameNanos = 0L; postInvalidateOnAnimation() }

    fun reset() { counts.fill(0); shown.fill(0f); invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (rowPitch * counts.size).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0.016 else ((now - lastFrameNanos) / 1e9).coerceIn(0.0, 0.1)
        lastFrameNanos = now
        val scale = max(max(total / 4f, (counts.maxOrNull() ?: 0).toFloat()), 1f)
        val labelW = 104f * u
        val countW = 36f * u
        val trackX = labelW + 8f * u
        val trackW = width - trackX - 8f * u - countW
        var moving = false
        for (i in counts.indices) {
            val y = i * rowPitch
            val color = InboxStyle.OPTION_COLORS[i]
            labelPaint.color = color
            canvas.drawText(InboxPanel.SHORT[i], 0f, y + 13f * u, labelPaint)
            // Bars ease toward their count in about 0.1 s, like the Core AI bars.
            shown[i] += ((counts[i] - shown[i]) * (1 - exp(-dt / 0.1))).toFloat()
            if (abs(counts[i] - shown[i]) < 0.01f) shown[i] = counts[i].toFloat() else moving = true
            val top = y + 4.5f * u
            fill.color = InboxStyle.LANE
            rect.set(trackX, top, trackX + trackW, top + 9f * u)
            canvas.drawRoundRect(rect, 3f * u, 3f * u, fill)
            val w = trackW * min(1f, shown[i] / scale)
            if (w > 0.5f) {
                fill.color = color
                rect.set(trackX, top, trackX + w, top + 9f * u)
                canvas.drawRoundRect(rect, 3f * u, 3f * u, fill)
            }
            canvas.drawText(String.format(Locale.US, "%,d", counts[i]), width.toFloat(), y + 13f * u, countPaint)
        }
        if (moving) postInvalidateOnAnimation()
    }
}
