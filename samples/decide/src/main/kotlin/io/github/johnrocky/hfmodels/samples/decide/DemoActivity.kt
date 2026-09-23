package io.github.johnrocky.hfmodels.samples.decide

import android.animation.LayoutTransition
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.util.Log
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import io.github.johnrocky.hfmodels.BackendKind
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.decide.Answer
import io.github.johnrocky.hfmodels.decide.Question
import io.github.johnrocky.hfmodels.decide.TypedDecisions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A scripted demo of typed decisions for a screen recording: a stream of utterances the model
 * gates (only a question or a request wakes the assistant), then a question ranked against
 * five passages. Every decision is a real call on the loaded model; the milliseconds on screen
 * are the SDK's own timing. Start: `adb shell am start -n <applicationId>/.DemoActivity
 * --es variant en_s256_fp32 --es backend gpu`; the script begins on the first tap after `ready`.
 */
class DemoActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var decisions: DecisionModels
    private var model: TypedDecisions? = null
    private var questionsAsked = 0
    private var questionMsSum = 0.0

    private lateinit var root: LinearLayout
    private lateinit var counter: TextView
    private lateinit var title: TextView
    private lateinit var stage: LinearLayout
    private lateinit var history: LinearLayout
    private lateinit var footer: TextView
    private var started = false

    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * density + 0.5f).toInt()

    private fun text(size: Float, color: Int, bold: Boolean = false, mono: Boolean = false): TextView = TextView(this).apply {
        textSize = size; setTextColor(color)
        typeface = if (mono) Typeface.MONOSPACE else if (bold) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
        includeFontPadding = false
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG; window.navigationBarColor = BG
        decisions = DecisionModels(this)
        val frame = FrameLayout(this).apply { setBackgroundColor(BG) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(44), dp(24), dp(36))
            layoutTransition = LayoutTransition().apply { enableTransitionType(LayoutTransition.CHANGING) }
        }
        root.addView(text(11f, DIM).apply { letterSpacing = 0.16f; text = "ON-DEVICE  ·  GALAXY S26  ·  GPU  ·  LiteRT" })
        title = text(26f, FG, bold = true).apply { text = "Loading the model…"; setPadding(0, dp(26), 0, dp(20)) }
        root.addView(title)
        stage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = rounded(CARD, 22); setPadding(dp(22), dp(22), dp(22), dp(22))
            layoutTransition = LayoutTransition().apply { enableTransitionType(LayoutTransition.CHANGING) }
        }
        root.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        history = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(18), 0, 0); layoutTransition = LayoutTransition() }
        root.addView(history, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        counter = text(17f, ACCENT, mono = true).apply { text = ""; setPadding(0, 0, 0, dp(10)) }
        root.addView(counter)
        footer = text(12f, DIM).apply { text = "laya (Apache-2.0) on LiteRT 2.2.0  ·  no network after the download" }
        root.addView(footer)
        frame.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(frame)
        // After setContentView: the decor view exists only then. Hide both bars for a clean recording.
        window.insetsController?.let { it.hide(WindowInsets.Type.systemBars()); it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
        frame.setOnClickListener { if (model != null && !started) { started = true; scope.launch { runScript() } } }

        val variant = intent.getStringExtra("variant") ?: "en_s256_fp32"
        val backend = when (intent.getStringExtra("backend")) { "cpu" -> BackendKind.CPU; "auto" -> null; else -> BackendKind.GPU }
        scope.launch {
            try {
                val m = decisions.load(variant, backend) { }
                model = m
                title.text = "ready  ·  tap to start"
                stageLine("${m.info.variantId} on ${m.info.profileId}, window ${m.limits.windowTokens} tokens", DIM, 15f)
                if (intent.getBooleanExtra("autostart", false)) { started = true; runScript() }
            } catch (e: ModelException) { title.text = "${e.code}: ${e.reason}" }
        }
    }

    private fun stageLine(s: String, color: Int, size: Float): TextView = text(size, color).apply { text = s; stage.addView(this) }

    private fun bump(d: io.github.johnrocky.hfmodels.decide.Decisions) {
        questionsAsked += d.timing.questionMs.size; questionMsSum += d.timing.questionMs.sum()
        counter.text = "$questionsAsked questions  ·  ${"%.0f".format(questionMsSum / questionsAsked)} ms each"
    }

    private suspend fun runScript() {
        val m = model ?: return
        // 1. the gate: only a question or a request opens it
        title.text = "Only a real request wakes the assistant"
        stage.removeAllViews()
        val line = stageLine("", FG, 27f)
        val verdictRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(18), 0, 0) }
        val pillRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val pill = text(24f, Color.WHITE, bold = true).apply { setPadding(dp(18), dp(9), dp(18), dp(9)) }
        val msView = text(17f, ACCENT, mono = true)
        pillRow.addView(pill); pillRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)); pillRow.addView(msView)
        val kindView = text(15f, DIM).apply { setPadding(0, dp(12), 0, 0) }
        verdictRow.addView(pillRow); verdictRow.addView(kindView)
        stage.addView(verdictRow)
        verdictRow.alpha = 0f
        for (utterance in GATE_LINES) {
            verdictRow.alpha = 0f
            line.alpha = 0f; line.text = "“$utterance”"; line.animate().alpha(1f).setDuration(220).start()
            delay(500)
            val d = m.decide(mapOf("utterance" to utterance), MainActivity.GATE_QUESTIONS)
            bump(d)
            Log.i("demo", "gate \"$utterance\" -> ${d.answers} ${"%.0f".format(d.timing.totalMs)} ms")
            val kind = d.answers.getValue("kind") as Answer.Choice
            val need = d.answers.getValue("needs_response") as Answer.Noul
            val open = kind.choice == "question" || kind.choice == "request_or_command" || need.noul >= 0.5
            pill.text = if (open) "OPEN" else "closed"
            pill.background = rounded(if (open) GREEN else GRAY, 14)
            kindView.text = "${kind.choice.replace('_', ' ')}  ·  ${"%.2f".format(kind.probabilities.getValue(kind.choice))}"
            msView.text = "${"%.0f".format(d.timing.totalMs)} ms"
            verdictRow.animate().alpha(1f).setDuration(180).start()
            delay(1900)
            val h = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
            h.addView(text(11f, Color.WHITE, bold = true).apply { text = if (open) "OPEN" else "closed"; background = rounded(if (open) GREEN else GRAY, 8); setPadding(dp(8), dp(3), dp(8), dp(3)) })
            h.addView(text(14f, DIM).apply { text = utterance; setPadding(dp(12), 0, dp(8), 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            h.addView(text(12f, ACCENT, mono = true).apply { text = "${"%.0f".format(d.timing.totalMs)} ms" })
            history.addView(h, 0)
            if (history.childCount > 6) history.removeViewAt(history.childCount - 1)
        }
        delay(900)
        // 2. ranking: which passage answers the question
        history.removeAllViews()
        for ((query, passages) in RANK_SETS) {
            title.text = "Which passage answers it?"
            stage.removeAllViews()
            stageLine("“$query”", FG, 24f)
            val rows = ArrayList<Triple<LinearLayout, View, TextView>>()
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(14), 0, 0); layoutTransition = LayoutTransition().apply { enableTransitionType(LayoutTransition.CHANGING) } }
            stage.addView(list)
            for (p in passages) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(9), 0, dp(9)) }
                val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                top.addView(text(15f, FG).apply { text = p }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val num = text(14f, ACCENT, mono = true).apply { text = "" }
                top.addView(num)
                row.addView(top)
                val bar = View(this).apply { background = rounded(ACCENT, 3) }
                row.addView(bar, LinearLayout.LayoutParams(0, dp(6)).apply { topMargin = dp(6) })
                list.addView(row)
                rows += Triple(row, bar, num)
            }
            delay(700)
            var modelMs = 0.0
            val scored = ArrayList<Pair<Int, Double>>()
            for ((i, p) in passages.withIndex()) {
                val d = m.decide(linkedMapOf("query" to query, "passage" to p), MainActivity.RANK_QUESTIONS)
                bump(d); modelMs += d.timing.totalMs
                val a = d.answers.getValue("answers") as Answer.Noul
                Log.i("demo", "rank \"$query\" / \"$p\" -> ${"%.2f".format(a.noul)} ${"%.0f".format(d.timing.totalMs)} ms")
                scored += i to a.noul
                val (_, bar, num) = rows[i]
                num.text = "%.2f".format(a.noul)
                val full = list.width - dp(0)
                ValueAnimator.ofInt(0, (full * a.noul).toInt().coerceAtLeast(dp(4))).apply {
                    duration = 380; interpolator = DecelerateInterpolator()
                    addUpdateListener { bar.layoutParams = (bar.layoutParams as LinearLayout.LayoutParams).apply { width = it.animatedValue as Int }; bar.requestLayout() }
                }.start()
                delay(120)
            }
            val total = modelMs
            delay(700)
            // reorder: best first
            val order = scored.sortedByDescending { it.second }.map { it.first }
            list.removeAllViews()
            for ((rank, i) in order.withIndex()) {
                val (row, bar, num) = rows[i]
                list.addView(row)
                val win = rank == 0
                ((row.getChildAt(0) as LinearLayout).getChildAt(0) as TextView).setTextColor(if (win) FG else DIM)
                bar.background = rounded(if (win) GREEN else ACCENT, 3)
                num.setTextColor(if (win) GREEN else ACCENT)
            }
            stageLine("${passages.size} passages, ${passages.size * 2} questions  ·  ${"%.0f".format(total)} ms", ACCENT, 14f).apply { typeface = Typeface.MONOSPACE; setPadding(0, dp(12), 0, 0) }
            delay(2600)
        }
        // 3. end card
        stage.removeAllViews()
        title.text = "Every answer came from the phone"
        stageLine("$questionsAsked questions", ACCENT, 30f).apply { typeface = Typeface.MONOSPACE }
        stageLine("${"%.0f".format(questionMsSum / questionsAsked)} ms each", ACCENT, 22f).apply { typeface = Typeface.MONOSPACE; setPadding(0, dp(6), 0, 0) }
        stageLine("Write your own questions about any text.\nThe model picks the answer and says how sure it is.\nNo server, no network.", FG, 16f).apply { setPadding(0, dp(18), 0, 0) }
        stageLine("github.com/john-rocky/hfmodels-android\nhfmodels-litert 0.1.2 · Maven Central", DIM, 11.5f).apply { typeface = Typeface.MONOSPACE; setPadding(0, dp(16), 0, 0) }
    }

    override fun onDestroy() {
        super.onDestroy()
        val d = decisions
        scope.cancel()
        CoroutineScope(Dispatchers.IO).launch { withContext(NonCancellable) { d.release(); d.models.closeAndJoin() } }
    }

    companion object {
        private const val BG = 0xFF0B0F14.toInt()
        private const val CARD = 0xFF141B24.toInt()
        private const val FG = 0xFFF2F4F7.toInt()
        private const val DIM = 0xFF8A97A6.toInt()
        private const val ACCENT = 0xFF22D3EE.toInt()
        private const val GREEN = 0xFF1FAD66.toInt()
        private const val GRAY = 0xFF3A4653.toInt()
        val GATE_LINES = listOf(
            "so we finally watched that movie last night",
            "it was fine, a bit long though",
            "hey, what time does the pharmacy close today?",
            "set a timer for ten minutes",
            "um so yeah anyway",
            "can you turn off the kitchen lights",
            "I think it's going to rain tomorrow",
        )
        val RANK_SETS: List<Pair<String, List<String>>> = listOf(
            "When does the store close on Sundays?" to listOf(
                "Our store hours are 9 to 6 on weekdays and 10 to 5 on Sundays.",
                "The parking lot is free for the first two hours.",
                "On Sundays we close at 5 pm; the pharmacy counter closes at 4.",
                "Returns are accepted within 30 days with a receipt.",
                "We were founded in 1998 in a small garage.",
            ),
            "Is parking free?" to listOf(
                "Our store hours are 9 to 6 on weekdays and 10 to 5 on Sundays.",
                "The parking lot is free for the first two hours.",
                "On Sundays we close at 5 pm; the pharmacy counter closes at 4.",
                "Returns are accepted within 30 days with a receipt.",
                "We were founded in 1998 in a small garage.",
            ),
        )
    }
}
