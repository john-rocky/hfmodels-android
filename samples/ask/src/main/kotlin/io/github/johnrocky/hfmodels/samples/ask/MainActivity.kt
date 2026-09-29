package io.github.johnrocky.hfmodels.samples.ask

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import io.github.johnrocky.hfmodels.BackendKind
import io.github.johnrocky.hfmodels.BackendPolicy
import io.github.johnrocky.hfmodels.HfModels
import io.github.johnrocky.hfmodels.LoadEvent
import io.github.johnrocky.hfmodels.LoadOptions
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.ModelRef
import io.github.johnrocky.hfmodels.Tasks
import io.github.johnrocky.hfmodels.litertlm.ChatModel
import io.github.johnrocky.hfmodels.litertlm.ChatSession
import io.github.johnrocky.hfmodels.litertlm.GenerationOptions
import io.github.johnrocky.hfmodels.litertlm.text
import java.io.File
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * One bar chart, drawn by the app, and five questions about it, answered on the phone by a
 * vision-language model. The chart goes to the model once, in the first turn of one conversation;
 * every further question is a text-only turn of the same conversation. The app knows the data it
 * drew, so it marks each answer as matching the chart or not.
 *
 * One loaded model holds one conversation. On this model family a new conversation on the same
 * loaded model starts from the previous conversation's state (google-ai-edge/LiteRT-LM#3165), so a
 * second picture closes the model and loads it again.
 *
 * Normal start: pick the backend, Load, Ask, Stop. Recording mode loads, waits `delay` seconds after
 * READY and asks, drawn over the lock screen with the screen kept on:
 *
 *     adb shell am start -n io.github.johnrocky.hfmodels.samples.ask/.MainActivity \
 *         --ez autostart true --ei delay 3 --ei chart 0 --es backend gpu --ei pictures 1
 *
 * Each picture's run is written to getExternalFilesDir(null): ask-result-<epoch s>.json and the exact
 * PNG bytes the model got, frames-<epoch s>/000.png. Logcat tag `ask`.
 */
class MainActivity : ComponentActivity() {
    private companion object {
        const val TAG = "ask"
        const val REPO = "litert-community/decider-2b-vision-LiteRT"
        // The Hub commit the descriptor in assets was generated from.
        const val REVISION = "6c024e946bb8489f3faacb2514b0c61b355d1fdb"
        const val VARIANT = "int8"
        const val DESCRIPTOR_ASSET = "decider-2b-vision.hfmodels.json"
        const val CHARTS_ASSET = "charts.json"
        /** The question is on screen this long before it is sent. */
        const val SHOW_MS = 800L
        /** The answer stays on screen this long before the next question. */
        const val HOLD_MS = 1_200L

        const val BACKGROUND = 0xFF0E1116.toInt()
        const val CARD = 0xFF1B2028.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val DIM = 0xFF8A919C.toInt()
        const val PILL_IDLE = 0xFF5F6368.toInt()
        const val PILL_ASKING = 0xFFE53935.toInt()
        const val PILL_DONE = 0xFF2E7D32.toInt()
        const val MATCH = 0xFF5CBA5C.toInt()       // the model paddle's green in samples/pong
        const val MISMATCH = 0xFFE53935.toInt()
        const val MAX_OPTIONS = 5
    }

    private enum class Phase { IDLE, LOADING, READY, ASKING, DONE, FAILED }

    /** One turn's answer with the times measured around it. */
    private class Turn(val text: String, val answerMs: Double?, val streamMs: Double)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var models: HfModels
    private lateinit var charts: List<ChartSpec>
    private var chat: ChatModel? = null
    /** True once [chat] has held a conversation; the next picture then needs a new load. */
    private var chatUsed = false
    private var askJob: Job? = null

    private var recording = false
    private var pictures = 1
    private var picture = 0
    private var nextChart = 0
    private var policyName = "auto"
    private var phase = Phase.IDLE
    private var detail = ""
    private var answered = 0
    private var matched = 0
    private var total = Questions.IDS.size
    private var lastAnswerMs: Double? = null
    private var loadMs = 0L
    private var loadDownloaded = false

    private var u = 1f
    private lateinit var pill: TextView
    private lateinit var count: TextView
    private lateinit var lastMs: TextView
    private lateinit var chartView: ChartView
    private lateinit var question: TextView
    private lateinit var optionRows: List<LinearLayout>
    private lateinit var optionTexts: List<TextView>
    private lateinit var optionMs: List<TextView>
    private lateinit var mark: TextView
    private lateinit var score: TextView
    private lateinit var footer: TextView
    private lateinit var backend: Spinner
    private lateinit var load: Button
    private lateinit var ask: Button
    private lateinit var stop: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        models = HfModels(applicationContext)
        charts = ChartFile.parse(assets.open(CHARTS_ASSET).bufferedReader().use { it.readText() }).map { it.spec }
        val autostart = intent.getBooleanExtra("autostart", false)
        val delaySeconds = intent.getIntExtra("delay", 3)
        nextChart = Math.floorMod(intent.getIntExtra("chart", 0), charts.size)
        pictures = intent.getIntExtra("pictures", 1).coerceAtLeast(1)
        policyName = when (intent.getStringExtra("backend")) { "gpu" -> "gpu"; "cpu" -> "cpu"; else -> "auto" }
        recording = autostart
        if (recording) {
            // The phone may sleep behind a secure lock screen: an activity under the keyguard is kept off the
            // network and out of the foreground, so recording mode shows over it and keeps the screen on.
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        buildScreen()
        if (recording) {
            window.insetsController?.let { it.hide(WindowInsets.Type.systemBars()); it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
        }
        chartView.show(chartPng(charts[nextChart]))
        showQuestion(null)
        render()
        if (autostart) {
            scope.launch {
                loadModel() ?: return@launch
                delay(delaySeconds * 1000L)
                askAll()
            }
        }
    }

    private fun policy(): BackendPolicy = when (policyName) {
        "gpu" -> BackendPolicy.Require(BackendKind.GPU)
        "cpu" -> BackendPolicy.Require(BackendKind.CPU)
        else -> BackendPolicy.Auto
    }

    private fun mb(bytes: Long) = String.format(Locale.US, "%,d MB", bytes shr 20)

    /** The chart as the PNG bytes the model gets; the screen shows these same bytes. */
    private fun chartPng(spec: ChartSpec): ByteArray = Png.encode(ChartFrame.render(spec), ChartFrame.SIDE, ChartFrame.SIDE)

    /** Loads the model with the descriptor from assets; null (and FAILED on screen) when the load fails. */
    private suspend fun loadModel(): ChatModel? {
        phase = Phase.LOADING; detail = "Resolving"; loadDownloaded = false; render()
        val descriptor = assets.open(DESCRIPTOR_ASSET).bufferedReader().use { it.readText() }
        val t0 = SystemClock.elapsedRealtime()
        return try {
            val model = models.fromPretrained(
                ModelRef(REPO, revision = REVISION, variant = VARIANT),
                Tasks.Chat,
                LoadOptions(descriptorJson = descriptor, backendPolicy = policy()),
            ) { e ->
                // Initializing, Fallback and Downloading arrive on the SDK's worker threads; the views are touched on the main thread only.
                runOnUiThread {
                    detail = when (e) {
                        is LoadEvent.Resolving -> "Resolving"
                        is LoadEvent.DownloadStarted -> { loadDownloaded = true; "Downloading ${mb(e.totalBytes)}" }
                        is LoadEvent.Downloading -> "Downloading ${mb(e.bytes)} / ${mb(e.totalBytes)}"
                        is LoadEvent.Verifying -> "Verifying sha256"
                        is LoadEvent.Initializing -> "Initializing profile ${e.profileId}"
                        is LoadEvent.Fallback -> "Fallback: ${e.reason}"
                        is LoadEvent.Ready -> "Ready: profile ${e.info.profileId}"
                    }
                    if (e !is LoadEvent.Downloading) Log.i(TAG, "load $detail")
                    render()
                }
            }
            loadMs = SystemClock.elapsedRealtime() - t0
            chat = model
            chatUsed = false
            val i = model.info
            Log.i(TAG, "READY profile=${i.profileId} load_ms=$loadMs variant=${i.variantId} commit=${i.commit.take(8)} downloaded=$loadDownloaded")
            footer.text = listOf(REPO, i.variantId, i.profileId, Build.MODEL, "LiteRT-LM ${i.runtimeVersion}", "hfmodels ${i.sdkVersion}").joinToString(" · ")
            phase = Phase.READY
            detail = String.format(Locale.US, "profile %s · loaded in %.1f s", i.profileId, loadMs / 1000.0)
            render()
            model
        } catch (e: ModelException) {
            fail("${e.code}: ${e.reason}")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail("Failed: $e")
            null
        }
    }

    /**
     * A model that has not held a conversation yet: the loaded one, or, after it answered a picture,
     * a new load. The used model is closed first (one model per HfModels client).
     */
    private suspend fun freshModel(): ChatModel? {
        val loaded = chat
        if (loaded != null && !chatUsed) return loaded
        if (loaded != null) {
            chat = null
            val closed = withContext(NonCancellable) { runCatching { loaded.closeAndJoin() } }
            closed.exceptionOrNull()?.let { fail("closing the model before the next load: $it"); return null }
        }
        return loadModel()
    }

    private fun fail(reason: String) {
        Log.e(TAG, "FAILED $reason")
        phase = Phase.FAILED; detail = reason
        render()
    }

    /** [pictures] charts from [nextChart] on, each on a model that has held no conversation before. */
    private suspend fun askAll() {
        for (p in 1..pictures) {
            if (p > 1) delay(HOLD_MS)
            val spec = charts[nextChart]
            nextChart = (nextChart + 1) % charts.size
            val model = freshModel() ?: return
            picture = p
            if (!askPicture(model, spec)) return
        }
    }

    /** One stream on [session]: the letter, the time to it and the time to the end of the stream. */
    private suspend fun turn(session: ChatSession, contents: Contents): Turn {
        val text = StringBuilder()
        var first = 0L
        val t0 = SystemClock.elapsedRealtimeNanos()
        session.stream(contents, GenerationOptions(maxOutputTokens = 1)).collect { m ->
            val piece = m.text
            if (piece.isNotEmpty() && first == 0L) first = SystemClock.elapsedRealtimeNanos()
            text.append(piece)
        }
        val t1 = SystemClock.elapsedRealtimeNanos()
        return Turn(text.toString(), if (first == 0L) null else (first - t0) / 1e6, (t1 - t0) / 1e6)
    }

    /**
     * One picture: one conversation, the image and the first question in turn 1, each further question
     * as a text-only turn of the same conversation, then close. False when it failed or was stopped.
     */
    private suspend fun askPicture(model: ChatModel, spec: ChartSpec): Boolean {
        val questions = Questions.of(spec)
        val turns = Prompt.turns(spec, questions)
        val png = withContext(Dispatchers.Default) { chartPng(spec) }
        chartView.show(png)
        answered = 0; matched = 0; total = questions.size; lastAnswerMs = null
        showQuestion(null)
        val log = JSONArray()
        var stopped = true
        var failure: String? = null
        var session: ChatSession? = null
        var closeMs: Double? = null
        val thermalBefore = thermalStatus()
        val t0 = SystemClock.elapsedRealtime()
        phase = Phase.ASKING; detail = ""
        render()
        Log.i(TAG, "PICTURE $picture/$pictures chart=${spec.id} bars=${spec.n} scale=${chartView.factor()}")
        try {
            chatUsed = true
            val c0 = SystemClock.elapsedRealtimeNanos()
            val s = withContext(Dispatchers.Default) { model.createConversation(ConversationConfig()) }
            session = s
            val conversationMs = (SystemClock.elapsedRealtimeNanos() - c0) / 1e6
            for ((k, q) in questions.withIndex()) {
                if (k > 0) delay(HOLD_MS)
                showQuestion(q)
                delay(SHOW_MS)
                val contents = if (k == 0) Contents.of(Content.ImageBytes(png), Content.Text(turns[k])) else Contents.of(Content.Text(turns[k]))
                val t = withContext(Dispatchers.Default) { turn(s, contents) }
                val choice = Answer.parse(t.text, q.options.size)
                val ok = choice == q.expected
                answered = k + 1
                if (ok) matched++
                lastAnswerMs = t.answerMs
                showAnswer(choice, t.answerMs, ok)
                val row = JSONObject()
                    .put("turn", k + 1)
                    .put("id", q.id)
                    .put("question", q.text)
                    .put("options", JSONArray(q.options))
                    .put("letter", Answer.letter(choice))
                    .put("raw_text", t.text)
                    .put("idx", choice)
                    .put("expected", q.expected)
                    .put("ok", ok)
                    .put("answer_ms", t.answerMs?.let { round1(it) } ?: JSONObject.NULL)
                    .put("stream_ms", round1(t.streamMs))
                if (k == 0) row.put("conversation_ms", round1(conversationMs))
                log.put(row)
                Log.i(TAG, "TURN ${k + 1} id=${q.id} letter=${Answer.letter(choice)} ok=$ok answer_ms=${t.answerMs?.let { round1(it) }}")
                render()
            }
            stopped = false
        } catch (e: ModelException) {
            stopped = false
            failure = "${e.code}: ${e.reason}"
            Log.e(TAG, "FAILED $failure")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            stopped = false
            failure = "Failed: $e"
            Log.e(TAG, "FAILED $failure", e)
        } finally {
            val totalS = (SystemClock.elapsedRealtime() - t0) / 1000.0
            withContext(NonCancellable) {
                session?.let { s ->
                    val c0 = SystemClock.elapsedRealtimeNanos()
                    runCatching { s.closeAndJoin() }.onFailure { Log.w(TAG, "session close: ${it.message}") }
                    closeMs = (SystemClock.elapsedRealtimeNanos() - c0) / 1e6
                }
                val record = JSONObject()
                    .put("picture", picture)
                    .put("pictures", pictures)
                    .put("chart", JSONObject().put("id", spec.id).put("n", spec.n).put("heights", JSONArray(spec.heights)).put("colours", JSONArray(spec.colours)))
                    .put("chart_scale", chartView.factor())
                    .put("questions", log)
                    .put("completed_turns", log.length())
                    .put("matches", matched)
                    .put("stopped", stopped)
                    .put("failure", failure ?: JSONObject.NULL)
                    .put("load_ms", loadMs)
                    .put("load_downloaded", loadDownloaded)
                    .put("close_ms", closeMs?.let { round1(it) } ?: JSONObject.NULL)
                    .put("profile", model.info.profileId)
                    .put("backend_policy", policyName)
                    .put("variant", model.info.variantId)
                    .put("model", "${model.info.repoId}@${model.info.commit}")
                    .put("sdk_version", model.info.sdkVersion)
                    .put("runtime_version", model.info.runtimeVersion)
                    .put("device", JSONObject().put("model", Build.MODEL).put("display", Build.DISPLAY))
                    .put("thermal_before", thermalBefore)
                    .put("thermal_after", thermalStatus())
                    .put("total_s", round1(totalS))
                    .put("prompt_turn1", turns[0])
                val path = withContext(Dispatchers.IO) { runCatching { writeRecord(record, png) }.getOrElse { "not written ($it)" } }
                Log.i(TAG, "DONE json=$path")
                phase = if (failure != null) Phase.FAILED else Phase.DONE
                detail = failure ?: if (stopped) "stopped" else ""
                render()
            }
        }
        return failure == null && !stopped
    }

    private fun writeRecord(record: JSONObject, png: ByteArray): String {
        val dir = getExternalFilesDir(null) ?: filesDir
        val epoch = System.currentTimeMillis() / 1000
        val framesDir = File(dir, "frames-$epoch").apply { mkdirs() }
        File(framesDir, "000.png").writeBytes(png)
        val file = File(dir, "ask-result-$epoch.json")
        file.writeText(record.toString(2))
        return file.absolutePath
    }

    private fun thermalStatus(): Int = getSystemService(PowerManager::class.java).currentThermalStatus
    private fun round1(x: Double) = Math.round(x * 10) / 10.0

    // ---- the screen ----

    private fun px(v: Float) = v * u

    private fun label(size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size)); setTextColor(color); includeFontPadding = false
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
        fontFeatureSettings = "tnum"
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }

    /** Text that shrinks its type (down to [minSize]) rather than cut it. */
    private fun fitted(tv: TextView, minSize: Float, maxSize: Float) = tv.apply {
        setAutoSizeTextTypeUniformWithConfiguration(px(minSize).toInt(), px(maxSize).toInt(), 1, TypedValue.COMPLEX_UNIT_PX)
        gravity = gravity or Gravity.CENTER_VERTICAL
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = px(radius) }

    private fun wrapParams(top: Float = 0f) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(top).toInt() }

    private fun buildScreen() {
        // Laid out in units of the screen width / 402, so a recording scaled to any width keeps its proportions.
        u = resources.displayMetrics.widthPixels / 402f
        val frame = FrameLayout(this).apply { setBackgroundColor(BACKGROUND) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        frame.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            content.setPadding(px(16f).toInt(), bars.top + px(8f).toInt(), px(16f).toInt(), bars.bottom + px(8f).toInt())
            insets
        }

        // (1) state pill, answer count, last answer time
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        pill = label(16f, WHITE, bold = true).apply { setPadding(px(10f).toInt(), px(6f).toInt(), px(10f).toInt(), px(6f).toInt()) }
        count = fitted(label(16f, WHITE, bold = true), 11f, 16f)
        lastMs = label(16f, DIM, bold = true).apply { gravity = Gravity.END }
        header.addView(pill)
        header.addView(count, LinearLayout.LayoutParams(0, px(28f).toInt(), 1f).apply { leftMargin = px(10f).toInt() })
        header.addView(lastMs)
        content.addView(header)

        // (2) the chart: the picture the model gets, scaled by a whole factor, framed outside its bounds
        chartView = ChartView(this, u)
        content.addView(chartView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = px(6f).toInt() })

        // (3) the question card: the question, the options (the chosen one highlighted, its answer time on the right), the mark
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 14f)
            setPadding(px(12f).toInt(), px(12f).toInt(), px(12f).toInt(), px(12f).toInt())
        }
        question = fitted(label(23f, WHITE, bold = true).apply { maxLines = 2; ellipsize = null; gravity = Gravity.CENTER_VERTICAL }, 16f, 23f)
        card.addView(question, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(58f).toInt()))
        val rows = ArrayList<LinearLayout>()
        val texts = ArrayList<TextView>()
        val times = ArrayList<TextView>()
        for (i in 0 until MAX_OPTIONS) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(px(10f).toInt(), 0, px(10f).toInt(), 0)
            }
            val t = label(19f, DIM).apply { gravity = Gravity.CENTER_VERTICAL }
            val ms = label(19f, BACKGROUND, bold = true).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
            row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            row.addView(ms, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { leftMargin = px(8f).toInt() })
            card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(34f).toInt()).apply { topMargin = px(if (i == 0) 6f else 3f).toInt() })
            rows += row; texts += t; times += ms
        }
        optionRows = rows; optionTexts = texts; optionMs = times
        mark = label(19f, MATCH, bold = true).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(10f).toInt(), 0, 0, 0) }
        card.addView(mark, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(30f).toInt()).apply { topMargin = px(6f).toInt() })
        content.addView(card, wrapParams(top = 10f))

        // (4) the score line
        score = fitted(label(17f, WHITE, bold = true), 12f, 17f)
        content.addView(score, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(24f).toInt()).apply { topMargin = px(10f).toInt() })

        // (5) footer: model, variant, profile, phone, runtime and SDK versions (filled in at READY)
        footer = label(12f, DIM).apply { maxLines = 2; text = "$REPO · $VARIANT · ${Build.MODEL}" }
        content.addView(footer, wrapParams(top = 8f))

        // Controls for a normal start; recording mode runs on its own.
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        backend = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("auto", "gpu", "cpu"))
            setSelection(listOf("auto", "gpu", "cpu").indexOf(policyName))
        }
        load = Button(this).apply { text = "Load"; setOnClickListener { onLoad() } }
        ask = Button(this).apply { text = "Ask"; setOnClickListener { onAsk() } }
        stop = Button(this).apply { text = "Stop"; setOnClickListener { askJob?.cancel() } }
        controls.addView(backend, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(load); controls.addView(ask); controls.addView(stop)
        if (!recording) content.addView(controls, wrapParams(top = 6f))

        setContentView(frame)
    }

    private fun onLoad() {
        policyName = backend.selectedItem as String
        scope.launch { loadModel() }
    }

    private fun onAsk() {
        if (askJob?.isActive == true) return
        askJob = scope.launch { askAll() }
    }

    /** The card before an answer: the question and its options, nothing chosen. Null clears the card. */
    private fun showQuestion(q: Question?) {
        question.text = q?.text ?: ""
        optionRows.forEachIndexed { i, row ->
            val option = q?.options?.getOrNull(i)
            row.visibility = if (option == null) View.INVISIBLE else View.VISIBLE
            row.background = null
            optionTexts[i].text = option?.let { "(${'A' + i}) $it" } ?: ""
            optionTexts[i].setTextColor(DIM)
            optionTexts[i].typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            optionMs[i].text = ""
        }
        mark.text = ""
    }

    /** The card after an answer: the chosen row highlighted with the answer time on its right, then the mark. */
    private fun showAnswer(choice: Int, ms: Double?, ok: Boolean) {
        val msText = ms?.let { String.format(Locale.US, "%,d ms", Math.round(it)) } ?: "no text"
        optionRows.forEachIndexed { i, row ->
            val chosen = choice == i
            row.background = if (chosen) rounded(WHITE, 8f) else null
            optionTexts[i].setTextColor(if (chosen) BACKGROUND else DIM)
            optionTexts[i].typeface = if (chosen) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
            optionMs[i].text = if (chosen) msText else ""
        }
        mark.setTextColor(if (ok) MATCH else MISMATCH)
        mark.text = when {
            ok -> "matches the chart"
            choice == Answer.UNPARSED -> "does not match the chart (no option letter, $msText)"
            else -> "does not match the chart"
        }
    }

    private fun render() {
        val (badge, color) = when (phase) {
            Phase.IDLE -> "IDLE" to PILL_IDLE
            Phase.LOADING -> "LOADING" to PILL_IDLE
            Phase.READY -> "READY" to PILL_IDLE
            Phase.ASKING -> "● ASKING" to PILL_ASKING
            Phase.DONE -> "DONE" to PILL_DONE
            Phase.FAILED -> "FAILED" to PILL_ASKING
        }
        pill.text = badge; pill.background = rounded(color, 100f)
        val pictureText = if (pictures > 1 && picture > 0) " · picture $picture of $pictures" else ""
        count.text = when (phase) {
            Phase.ASKING, Phase.DONE -> "$answered / $total$pictureText" + if (detail.isEmpty()) "" else " · $detail"
            Phase.IDLE -> "choose a backend, then Load"
            else -> detail
        }
        lastMs.text = lastAnswerMs?.let { String.format(Locale.US, "%,d ms", Math.round(it)) } ?: ""
        score.text = if (answered == 0) "" else "$matched of $answered answers match the chart"
        if (!recording) {
            load.isEnabled = chat == null && (phase == Phase.IDLE || phase == Phase.FAILED)
            backend.isEnabled = load.isEnabled
            ask.isEnabled = chat != null && phase != Phase.ASKING && phase != Phase.LOADING
            stop.isEnabled = phase == Phase.ASKING
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        chat?.close()
        models.close()
    }
}
