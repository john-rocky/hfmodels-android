// The colors, the card and the chips follow samples/decide's InboxViews.kt (branch sms-inbox-demo, c1c3cb6), itself the layout of the Core AI inbox example.
package io.github.johnrocky.hfmodels.samples.promises

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.max

/** The colors of the Core AI inbox example (InboxScreen.swift), as ARGB ints, and one color per answer. */
object PromisesStyle {
    const val BACKGROUND = 0xFF0E1116.toInt()
    const val LANE = 0xFF1B2028.toInt()
    const val PENDING = 0xFF6B7280.toInt()
    const val DIM = 0xFF3C424C.toInt()
    const val AXIS = 0xFF8A919C.toInt()
    const val LATENCY = 0xFFB8BEC6.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val ERROR = 0xFFFF8A80.toInt()
    const val PILL_IDLE = 0xFF5F6368.toInt()
    const val PILL_SORTING = 0xFFE53935.toInt()
    const val PILL_DONE = 0xFF2E7D32.toInt()

    /** Blue for a promise, yellow for a request, green for a plan, grey for nothing, red for a sentence too long for the window. */
    fun color(key: String): Int = when (key) {
        "promise" -> 0xFF4285F4.toInt()
        "request" -> 0xFFFBBC05.toInt()
        "plan" -> 0xFF34A853.toInt()
        Promises.TOO_LONG -> 0xFFE53935.toInt()
        else -> AXIS
    }

    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)

    /** A rounded rectangle with a ripple: the Paste / Sample / Add buttons. */
    fun button(context: Context, label: String, u: Float, stroke: Int, fill: Int = LANE): TextView = TextView(context).apply {
        text = label
        setTextColor(WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 12.5f * u)
        typeface = MEDIUM
        gravity = Gravity.CENTER
        val shape = GradientDrawable().apply { cornerRadius = 8f * u; setColor(fill); setStroke(max(1, (1.2f * u).toInt()), stroke) }
        background = RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), shape, null)
        setPadding((12f * u).toInt(), (5f * u).toInt(), (12f * u).toInt(), (5f * u).toInt())
        minHeight = (30f * u).toInt()
        isClickable = true
        isFocusable = true
    }
}

/**
 * One sentence large, so a viewer can read what goes in and what comes out: the sender, up to two lines of
 * the sentence, the question, then the answer as a chip with its probability. Before a conversation is
 * sorted it shows the question and its four options as the model reads them, in the model's order. The height never changes, so
 * the bundles below do not move when the sentence does. `u` is the screen width / 402.
 */
class SpotlightView(context: Context, private val u: Float) : View(context) {
    private var sender = ""
    private var text = ""
    /** An answer key, or null for the question and its options. */
    private var key: String? = null
    private var probability = 0.0
    private var body: StaticLayout? = null
    private var options: List<Pair<String, StaticLayout>>? = null

    private val pad = 14f * u
    private val captionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12.5f * u; typeface = PromisesStyle.MEDIUM; color = PromisesStyle.AXIS }
    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f * u; color = PromisesStyle.WHITE }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f * u; typeface = PromisesStyle.MEDIUM; color = PromisesStyle.WHITE }
    private val askPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12.5f * u; color = PromisesStyle.LATENCY }
    private val keyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * u; typeface = PromisesStyle.MEDIUM }
    private val optionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11.5f * u; color = PromisesStyle.LATENCY }
    private val answerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 17f * u; typeface = PromisesStyle.MEDIUM }
    private val probabilityPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f * u; color = PromisesStyle.LATENCY; fontFeatureSettings = "tnum" }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    // The sentence: caption, two lines, the question, the answer.
    private val bodyTop = pad + 18f * u + 4f * u
    private val askTop = bodyTop + 2 * bodyPaint.fontSpacing + 8f * u
    private val answerTop = askTop + 18f * u + 6f * u
    private val answerHeight = 28f * u
    private val cardHeight = answerTop + answerHeight + pad
    // The question: title, then one row per option (the key as a chip, its description beside it).
    private val questionTop = pad + 18f * u + 12f * u
    private val keyWidth = 70f * u
    private val optionChip = 20f * u
    private val optionGap = 6f * u

    fun showQuestion() {
        if (key == null && text.isEmpty()) return
        sender = ""; text = ""; key = null; body = null
        invalidate()
    }

    fun show(sender: String, text: String, key: String, probability: Double) {
        if (text != this.text) body = null
        this.sender = sender; this.text = text; this.key = key; this.probability = probability
        invalidate()
    }

    /** As tall as the taller of the two faces at this width, so switching between them never moves what is below. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val rows = optionLayouts(w - 2 * pad)
        val question = questionTop + rows.sumOf { rowHeight(it.second).toDouble() }.toFloat() - optionGap + pad
        setMeasuredDimension(w, max(cardHeight, question).toInt() + 1)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { body = null }

    override fun onDraw(canvas: Canvas) {
        fill.color = PromisesStyle.LANE
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, 12f * u, 12f * u, fill)
        val inner = width - 2 * pad
        val k = key
        if (k == null) {
            drawQuestion(canvas, inner)
            return
        }
        canvas.drawText(TextUtils.ellipsize(sender, captionPaint, inner, TextUtils.TruncateAt.END).toString(), pad, pad + 13f * u, captionPaint)
        val b = body ?: StaticLayout.Builder.obtain(text, 0, text.length, bodyPaint, inner.toInt().coerceAtLeast(1))
            .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build().also { body = it }
        canvas.save(); canvas.translate(pad, bodyTop); b.draw(canvas); canvas.restore()
        canvas.drawText(Promises.QUESTION.instructions, pad, askTop + 13f * u, askPaint)
        val right = chip(canvas, k, PromisesStyle.color(k), answerPaint, pad, answerTop, answerHeight, 11f * u)
        val note = if (k == Promises.TOO_LONG) "over 128 tokens with the question" else String.format(Locale.US, "%.2f", probability)
        val fm = probabilityPaint.fontMetrics
        canvas.drawText(note, right + 10f * u, answerTop + (answerHeight - fm.ascent - fm.descent) / 2, probabilityPaint)
    }

    /** The question as the model gets it: the instructions, then every option key with the description the model reads. */
    private fun drawQuestion(canvas: Canvas, inner: Float) {
        canvas.drawText(Promises.QUESTION.instructions, pad, pad + 18f * u, titlePaint)
        var y = questionTop
        for ((key, layout) in optionLayouts(inner)) {
            chip(canvas, key, PromisesStyle.color(key), keyPaint, pad, y, optionChip, 8f * u)
            canvas.save(); canvas.translate(pad + keyWidth + 8f * u, y + (optionChip - optionPaint.fontSpacing) / 2); layout.draw(canvas); canvas.restore()
            y += rowHeight(layout)
        }
    }

    private fun optionLayouts(inner: Float): List<Pair<String, StaticLayout>> {
        val textWidth = (inner - keyWidth - 8f * u).toInt().coerceAtLeast(1)
        options?.let { if (it.first().second.width == textWidth) return it }
        // The options in the order the model gets them (the question's own order).
        return Promises.QUESTION.criteria.map { (key, description) ->
            val d = description ?: key
            key to StaticLayout.Builder.obtain(d, 0, d.length, optionPaint, textWidth).setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
        }.also { options = it }
    }

    private fun rowHeight(layout: StaticLayout): Float = max(optionChip, (optionChip - optionPaint.fontSpacing) / 2 + layout.height) + optionGap

    private fun chip(canvas: Canvas, label: String, color: Int, paint: TextPaint, x: Float, top: Float, h: Float, padX: Float): Float {
        val w = paint.measureText(label) + 2 * padX
        fill.color = PromisesStyle.BACKGROUND
        rect.set(x, top, x + w, top + h)
        canvas.drawRoundRect(rect, 7f * u, 7f * u, fill)
        paint.color = color
        val fm = paint.fontMetrics
        canvas.drawText(label, x + padX, top + (h - fm.ascent - fm.descent) / 2, paint)
        return x + w
    }
}

/**
 * The three bundles, You promised / They asked you / Plans, with every sentence as written and an Add on each
 * row; under them the sentences that need nothing (and any too long for the window), counted and folded.
 * Rows arrive one by one while a conversation is sorted.
 */
class BundlesView(context: Context, private val u: Float, private val onAdd: (Promises.Verdict) -> Unit) : LinearLayout(context) {
    private class Section(val key: String, val title: String, val header: TextView, val rows: LinearLayout) { var count = 0 }

    private val sections = LinkedHashMap<String, Section>()
    private val restHeader: TextView
    private val restRows: LinearLayout
    private var nothing = 0
    private var tooLong = 0
    private var expanded = false
    private val buttons = HashMap<Promises.Verdict, View>()

    init {
        orientation = VERTICAL
        for ((key, title) in Promises.BUNDLES) {
            val header = headerView()
            val rows = LinearLayout(context).apply { orientation = VERTICAL }
            addView(header)
            addView(rows)
            sections[key] = Section(key, title, header, rows).also { renderHeader(it) }
        }
        restHeader = headerView().apply {
            isClickable = true
            setOnClickListener { expanded = !expanded; restRows.visibility = if (expanded) VISIBLE else GONE; renderRest() }
        }
        restRows = LinearLayout(context).apply { orientation = VERTICAL; visibility = GONE }
        addView(restHeader)
        addView(restRows)
        renderRest()
    }

    fun clear() {
        for (s in sections.values) { s.rows.removeAllViews(); s.count = 0; renderHeader(s) }
        restRows.removeAllViews()
        nothing = 0; tooLong = 0
        buttons.clear()
        renderRest()
    }

    fun add(v: Promises.Verdict) {
        val s = sections[v.key]
        if (s != null) {
            s.count++
            renderHeader(s)
            s.rows.addView(row(v, s.key))
            return
        }
        if (v.key == Promises.TOO_LONG) tooLong++ else nothing++
        restRows.addView(foldedRow(v))
        renderRest()
    }

    /** The Add of a row, for the recording mode's tap. */
    fun buttonFor(v: Promises.Verdict): View? = buttons[v]

    private fun headerView() = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 14f * u)
        typeface = PromisesStyle.MEDIUM
        setTextColor(PromisesStyle.WHITE)
        setPadding(0, (8f * u).toInt(), 0, (3f * u).toInt())
    }

    private fun renderHeader(s: Section) {
        s.header.text = SpannableStringBuilder().apply {
            append("●  ", ForegroundColorSpan(PromisesStyle.color(s.key)), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(s.title)
            append("   ${s.count}", ForegroundColorSpan(PromisesStyle.LATENCY), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun renderRest() {
        restHeader.text = SpannableStringBuilder().apply {
            append("●  ", ForegroundColorSpan(PromisesStyle.AXIS), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append("Nothing to do", ForegroundColorSpan(PromisesStyle.AXIS), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append("   $nothing", ForegroundColorSpan(PromisesStyle.LATENCY), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (tooLong > 0) append("   too long $tooLong", ForegroundColorSpan(PromisesStyle.color(Promises.TOO_LONG)), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (nothing + tooLong > 0) append(if (expanded) "   ▾" else "   ▸", ForegroundColorSpan(PromisesStyle.AXIS), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun sentenceText(v: Promises.Verdict, color: Int) = SpannableStringBuilder().apply {
        v.sentence.sender?.let { name ->
            val start = length
            append(name)
            setSpan(ForegroundColorSpan(PromisesStyle.AXIS), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(AbsoluteSizeSpan((11.5f * u).toInt()), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append("   ")
        }
        append(v.sentence.text, ForegroundColorSpan(color), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun row(v: Promises.Verdict, key: String): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, (3f * u).toInt(), 0, (3f * u).toInt())
        addView(TextView(context).apply {
            text = sentenceText(v, PromisesStyle.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 13.5f * u)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val add = PromisesStyle.button(context, "Add", u, PromisesStyle.color(key)).apply {
            contentDescription = "Add to calendar: ${v.sentence.text}"
            setOnClickListener { onAdd(v) }
        }
        buttons[v] = add
        addView(add, LayoutParams((56f * u).toInt(), LayoutParams.WRAP_CONTENT).apply { marginStart = (8f * u).toInt() })
    }

    private fun foldedRow(v: Promises.Verdict): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, (3f * u).toInt(), 0, (3f * u).toInt())
        addView(TextView(context).apply {
            text = sentenceText(v, PromisesStyle.PENDING)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 13f * u)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(context).apply {
            text = v.key
            setTextColor(PromisesStyle.color(v.key))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 11f * u)
            typeface = PromisesStyle.MEDIUM
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = (8f * u).toInt() })
    }
}
