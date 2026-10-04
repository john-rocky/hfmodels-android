// The dark palette and the chip shapes follow samples/promises (PromisesViews.kt).
package io.github.johnrocky.hfmodels.samples.finder

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max

object FinderStyle {
    const val BACKGROUND = 0xFF0E1116.toInt()
    const val LANE = 0xFF1B2028.toInt()
    const val OUTLINE = 0xFF5F6368.toInt()
    const val AXIS = 0xFF8A919C.toInt()
    const val LATENCY = 0xFFB8BEC6.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val ERROR = 0xFFFF8A80.toInt()
    const val INK = 0xFF0E1116.toInt()
    const val FIND = 0xFF8AB4F8.toInt()

    /** One color per filter: the set chip's fill, and the card parts that satisfy that filter. */
    fun color(f: Facet): Int = when (f) {
        Facet.MEAL -> 0xFF8AB4F8.toInt()     // blue
        Facet.DIET -> 0xFF81C995.toInt()     // green
        Facet.TIME -> 0xFFFDD663.toInt()     // yellow
        Facet.WITHOUT -> 0xFFC58AF9.toInt()  // purple
        Facet.SPICE -> 0xFFF28B82.toInt()    // red
    }

    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)

    fun rounded(fill: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(fill)
        if (stroke > 0) setStroke(stroke, strokeColor)
    }
}

/** Children left to right, wrapping to the next row when one does not fit. */
class FlowLayout(context: Context, private val gapX: Int, private val gapY: Int) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var row = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            c.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > width) { x = 0; y += row + gapY; row = 0 }
            x += c.measuredWidth + gapX
            row = max(row, c.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), y + row + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var row = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (x > 0 && x + c.measuredWidth > width) { x = 0; y += row + gapY; row = 0 }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + gapX
            row = max(row, c.measuredHeight)
        }
    }
}

/**
 * One filter chip: `Meal: any` outlined in grey while unset, `Meal: dinner` filled with the filter's color once set. The
 * text changes at once; the colors cross-fade in [FADE_MS]. A tap, set or not, goes to [onTap] (the menu of the filter's values).
 */
class FilterChip(context: Context, val facet: Facet, private val u: Float, private val onTap: (FilterChip) -> Unit) : TextView(context) {
    private val shape = GradientDrawable().apply { cornerRadius = 100f }
    private val stroke = max(1, (1.2f * u).toInt())
    private var set = false
    private var progress = 0f
    private var fade: ValueAnimator? = null
    private val argb = ArgbEvaluator()

    init {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 14f * u)
        typeface = FinderStyle.MEDIUM
        gravity = Gravity.CENTER
        maxLines = 1
        setPadding((13f * u).toInt(), (6f * u).toInt(), (13f * u).toInt(), (6f * u).toInt())
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, null)
        setOnClickListener { onTap(this) }
        paint(0f)
    }

    /** Shows [key] (the facet's unset key, or a set one); [animate] cross-fades the colors when the state changes. */
    fun show(key: String, animate: Boolean) {
        text = "${facet.label}: ${Finder.chipText(facet, key)}"
        val nowSet = key != facet.unset
        contentDescription = "${facet.label} filter: ${if (nowSet) Finder.chipText(facet, key) else "not set"}. Tap to choose."
        if (nowSet == set) return
        set = nowSet
        fade?.cancel()
        val target = if (set) 1f else 0f
        if (!animate) { paint(target); return }
        fade = ValueAnimator.ofFloat(progress, target).apply {
            duration = FADE_MS
            addUpdateListener { paint(it.animatedValue as Float) }
            start()
        }
    }

    private fun paint(p: Float) {
        progress = p
        val color = FinderStyle.color(facet)
        shape.setColor(argb.evaluate(p, 0x00000000, color) as Int)
        shape.setStroke(stroke, argb.evaluate(p, FinderStyle.OUTLINE, color) as Int)
        setTextColor(argb.evaluate(p, FinderStyle.AXIS, FinderStyle.INK) as Int)
    }

    companion object {
        const val FADE_MS = 150L
    }
}

/** One recipe: its name large, and the attribute line with the parts that satisfy a set filter in that filter's color. */
class RecipeCard(context: Context, val recipe: Recipe, private val u: Float) : LinearLayout(context) {
    private val line: TextView

    init {
        orientation = VERTICAL
        background = FinderStyle.rounded(FinderStyle.LANE, 10f * u)
        setPadding((12f * u).toInt(), (8f * u).toInt(), (12f * u).toInt(), (9f * u).toInt())
        addView(TextView(context).apply {
            text = recipe.name
            setTextColor(FinderStyle.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 17f * u)
            typeface = FinderStyle.MEDIUM
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        line = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 13f * u)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, (2f * u).toInt(), 0, 0)
        }
        addView(line)
    }

    fun show(f: Filters) {
        line.text = SpannableStringBuilder().apply {
            for ((i, part) in RecipeLine.parts(recipe, f).withIndex()) {
                if (i > 0) append(RecipeLine.SEPARATOR, ForegroundColorSpan(FinderStyle.OUTLINE), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val start = length
                append(part.text)
                val facet = part.facet
                setSpan(ForegroundColorSpan(if (facet != null) FinderStyle.color(facet) else FinderStyle.AXIS), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (facet != null) setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
}

/** The small grey links under the input: `Try:` and one sentence per line. */
fun tryRow(context: Context, u: Float, sentences: List<String>, onTap: (String) -> Unit): View = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    addView(TextView(context).apply {
        text = "Try:"
        setTextColor(FinderStyle.AXIS)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 12.5f * u)
        typeface = FinderStyle.MEDIUM
        setPadding(0, (3f * u).toInt(), (8f * u).toInt(), 0)
    })
    addView(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        for (s in sentences) addView(TextView(context).apply {
            text = s
            setTextColor(FinderStyle.LATENCY)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 12.5f * u)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, (3f * u).toInt(), 0, (3f * u).toInt())
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, FinderStyle.rounded(FinderStyle.WHITE, 6f * u))
            isClickable = true
            setOnClickListener { onTap(s) }
        })
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
}
