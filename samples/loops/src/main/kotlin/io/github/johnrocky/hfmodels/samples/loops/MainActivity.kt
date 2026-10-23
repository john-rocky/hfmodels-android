package io.github.johnrocky.hfmodels.samples.loops

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import io.github.johnrocky.hfmodels.HfModels
import io.github.johnrocky.hfmodels.HfModelsVersion
import io.github.johnrocky.hfmodels.LoadEvent
import io.github.johnrocky.hfmodels.NetworkPolicy
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
 * One question, two files of the same model, side by side. NVIDIA's Dory1.0-4B applies a shared block of layers
 * several times per token; its LiteRT-LM repo has the int4 weights in two graphs, two loops (`int4`, NVIDIA's default)
 * and one loop (`int4_1loop`). Type a question or pick an example, choose 1 loop, 2 loops or Both, and Run: each file
 * answers in turn ([Loops] closes one engine before it loads the other), and each column shows its answer, the time to
 * the first token, the tokens per second and the whole answer's time. A GSM8K example's final line is marked against
 * its known answer.
 *
 * Recording mode runs one spec on its own and shows over the lock screen with the screen kept on:
 *
 *     adb shell am start -n io.github.johnrocky.hfmodels.samples.loops/.MainActivity \
 *         --ez autostart true --ei delay 3 --es examples all --es run both --es order 12 --ez think false
 *
 * `question` (your text) | `example` (one id) | `examples` (`all` or ids with commas); `run` both | 1 | 2; `order` 12 | 21;
 * `think`; `network` any | offline; `stop_after_mb` N (the harness presses Stop once N MB of a download are on the
 * phone). `--ez record true` alone shows the screen over the lock screen for a run driven by taps. Every run writes
 * `loops-run-<epoch s>.json` to getExternalFilesDir(null); logcat tag `loops`.
 */
class MainActivity : ComponentActivity() {
    private companion object {
        const val TAG = "loops"
        const val MAX_QUESTION_CHARS = 4000

        const val BACKGROUND = 0xFF0E1116.toInt()
        const val CARD = 0xFF1B2028.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val DIM = 0xFF8A919C.toInt()
        const val PILL_IDLE = 0xFF5F6368.toInt()
        const val PILL_BUSY = 0xFFE53935.toInt()
        const val PILL_DONE = 0xFF2E7D32.toInt()
        const val MATCH = 0xFF5CBA5C.toInt()
        const val MISMATCH = 0xFFE53935.toInt()
        val ACCENT = mapOf(Loop.ONE to 0xFF4FC3F7.toInt(), Loop.TWO to 0xFFFFB74D.toInt())
    }

    private enum class Phase { READY, LOADING, ANSWERING, DONE, STOPPED, FAILED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var models: HfModels
    private lateinit var loops: Loops
    private var job: Job? = null
    private var recording = false
    private var phase = Phase.READY
    private var detail = ""
    /** The run in progress: its Stop-after-MB is read on every download event. */
    private var running: RunSpec? = null
    private val states = HashMap<Loop, FileState>()

    private var u = 1f
    private lateinit var pill: TextView
    private lateinit var headline: TextView
    private lateinit var questionBox: EditText
    private lateinit var examples: Spinner
    private lateinit var runGroup: RadioGroup
    private lateinit var think: CheckBox
    private lateinit var download: CheckBox
    private lateinit var run: Button
    private lateinit var stop: Button
    private lateinit var footer: TextView
    private val panes = LinkedHashMap<Loop, Pane>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Creates /sdcard/Android/data/<package>/files/, where a model copy pushed with adb is picked up (README).
        val pushDir = getExternalFilesDir(null)
        models = HfModels(applicationContext)
        loops = Loops(models, pushDir)
        loops.onStage = { loop, stage -> runOnUiThread { onStage(loop, stage) } }
        loops.onEvent = { loop, e -> runOnUiThread { onLoadEvent(loop, e) } }

        val autostart = intent.getBooleanExtra("autostart", false)
        recording = autostart || intent.getBooleanExtra("record", false)
        if (recording) {
            // A phone asleep behind a secure lock screen keeps a started app in the background (slower, off the network):
            // recording mode shows over the lock screen and keeps the screen on.
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        buildScreen()
        if (autostart) window.insetsController?.let { it.hide(WindowInsets.Type.systemBars()); it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
        intent.getStringExtra("network")?.let { download.isChecked = it != "offline" }
        render()

        if (autostart) {
            val spec = try {
                RunSpec(
                    questions = RunSpec.questions(intent.getStringExtra("question"), intent.getStringExtra("example"), intent.getStringExtra("examples")),
                    loops = RunSpec.loops(intent.getStringExtra("run"), intent.getStringExtra("order")),
                    think = intent.getBooleanExtra("think", false),
                    offline = intent.getStringExtra("network") == "offline",
                    stopAfterMb = intent.getIntExtra("stop_after_mb", -1).takeIf { it > 0 },
                )
            } catch (e: IllegalArgumentException) {
                fail(e.message ?: "$e")
                null
            }
            val delaySeconds = intent.getIntExtra("delay", 3)
            if (spec != null) startJob {
                refreshFiles()
                spec.questions.firstOrNull()?.let { questionBox.setText(it.text) }
                selectRun(spec.loops); think.isChecked = spec.think; download.isChecked = !spec.offline
                delay(delaySeconds * 1000L)
                runSpec(spec)
            }
        } else {
            startJob { refreshFiles() }
        }
    }

    /** Every job goes through here: one at a time, and the controls follow its end. */
    private fun startJob(block: suspend () -> Unit) {
        val j = scope.launch { block() }
        job = j
        j.invokeOnCompletion { runOnUiThread { render() } }
        render()
    }

    // ---- the files ----

    /** Each column's file line: where the file is now (no request is made). */
    private suspend fun refreshFiles() {
        for (loop in Loop.values()) {
            val s = try { loops.fileState(loop) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            if (s != null) states[loop] = s
            panes.getValue(loop).file.text = fileLine(loop, s)
        }
        Log.i(TAG, "FILES " + Loop.values().joinToString(" ") { "${it.variant}=${states[it]?.javaClass?.simpleName ?: "unknown"}" })
    }

    private fun fileLine(loop: Loop, s: FileState?): String {
        val where = when (s) {
            is FileState.OnPhone -> "on the phone"
            is FileState.Pushed -> "pushed copy, checked at its first run"
            is FileState.Missing -> "not on the phone: ${Loops.gb(s.bytes)} to download"
            null -> "not checked"
        }
        return "${loop.variant} · ${s?.let { Loops.gb(it.bytes) } ?: "?"} · $where"
    }

    // ---- a run ----

    private fun onRun() {
        if (job?.isActive == true) return
        Loops.refusal(questionBox.text.toString())?.let { r -> detail = r; phase = Phase.READY; render(); Log.i(TAG, "REFUSED $r"); return }
        val spec = RunSpec(listOf(RunSpec.question(questionBox.text.toString())), chosenLoops(), think.isChecked, offline = !download.isChecked)
        // The keyboard goes away so both columns have the room while they answer.
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(questionBox.windowToken, 0)
        questionBox.clearFocus()
        startJob { runSpec(spec) }
    }

    /**
     * Every question on the first file, then every question on the second (one load per file), each in a conversation
     * of its own. A file whose load fails (not on the phone with downloads off, a download stopped or failed) ends that
     * file's part with a sentence and the run goes on with the other file; Stop ends the run.
     */
    private suspend fun runSpec(spec: RunSpec) {
        running = spec
        loops.network = if (spec.offline) NetworkPolicy.Offline else NetworkPolicy.Any
        val answers = HashMap<Loop, HashMap<Int, Answer>>()
        val rows = JSONArray()
        val events = JSONArray()
        val loads = JSONArray()
        val t0 = SystemClock.elapsedRealtime()
        val started = System.currentTimeMillis()
        val thermalStart = thermalStatus()
        var stopped = false
        for (loop in Loop.values()) panes.getValue(loop).clear(if (loop in spec.loops) "Waiting" else "Not in this run")
        Log.i(TAG, "RUN loops=${spec.loops.joinToString(",") { it.variant }} questions=${spec.questions.size} think=${spec.think} offline=${spec.offline} stop_after_mb=${spec.stopAfterMb} thermal=$thermalStart")
        try {
            outer@ for (loop in spec.loops) {
                val pane = panes.getValue(loop)
                for ((k, q) in spec.questions.withIndex()) {
                    if (spec.questions.size > 1) {
                        questionBox.setText(q.text)
                        // The column of the file that ran first shows its answer to this question beside the one coming.
                        for ((other, done) in answers) if (other != loop) done[k]?.let { panes.getValue(other).show(it, q.example) }
                    }
                    pane.clear(if (loops.loaded == loop) "Answering" else "Loading")
                    val thermalBefore = thermalStatus()
                    val loadsBefore = loops.loads
                    try {
                        val a = loops.answer(loop, q.text, spec.think) { text, thoughtChars -> runOnUiThread { pane.streaming(text, thoughtChars) } }
                        if (loops.loads != loadsBefore) loads.put(loadRow(loop))
                        answers.getOrPut(loop) { HashMap() }[k] = a
                        pane.show(a, q.example)
                        rows.put(answerRow(a, k, q, thermalBefore))
                        Log.i(TAG, "ANSWER loop=${loop.passes} variant=${loop.variant} q=${q.example?.id ?: "own"} " + String.format(Locale.US,
                            "first_token_ms=%.1f tok_s=%.2f total_ms=%.1f chunks=%d thought_chars=%d load_ms=%s backend=%s mark=%s text=%s",
                            a.timing.firstTokenMs ?: -1.0, a.timing.tokensPerSecond ?: -1.0, a.timing.totalMs, a.timing.chunks, a.thought.length,
                            a.loadMs?.toString() ?: "none", a.backend, markWord(a, q.example), JSONObject.quote(a.text)))
                    } catch (e: CancellationException) {
                        val s = loops.sentence(loop, e)
                        pane.stopped(s, loops.partialText)
                        events.put(eventRow("stopped", loop, k, q, s))
                        Log.i(TAG, "STOPPED loop=${loop.passes} q=${q.example?.id ?: "own"} sentence=${JSONObject.quote(s)}")
                        for (rest in spec.loops.dropWhile { it != loop }.drop(1)) panes.getValue(rest).clear("Not run: stopped.")
                        stopped = true
                        break@outer
                    } catch (e: Exception) {
                        val s = loops.sentence(loop, e)
                        pane.failed(s)
                        events.put(eventRow("failed", loop, k, q, s))
                        Log.w(TAG, "FAILED loop=${loop.passes} q=${q.example?.id ?: "own"} sentence=${JSONObject.quote(s)}", e)
                        // A file that did not load cannot answer the next questions either: on to the other file.
                        if (loops.loaded != loop) continue@outer
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                running = null
                phase = when {
                    stopped -> Phase.STOPPED
                    events.length() > 0 && rows.length() == 0 -> Phase.FAILED
                    else -> Phase.DONE
                }
                detail = summary(spec, answers, stopped, events.length())
                val record = JSONObject()
                    .put("app", "samples/loops")
                    .put("model", "${Loops.REPO}@${Loops.REVISION}")
                    .put("spec", JSONObject()
                        .put("loops", JSONArray(spec.loops.map { it.variant }))
                        .put("questions", JSONArray(spec.questions.map { JSONObject().put("id", it.example?.id ?: "own").put("text", it.text) }))
                        .put("think", spec.think).put("offline", spec.offline).put("stop_after_mb", spec.stopAfterMb ?: JSONObject.NULL))
                    .put("answers", rows)
                    .put("events", events)
                    .put("loads", loads)
                    .put("stopped", stopped)
                    .put("sdk_version", loops.model?.info?.sdkVersion ?: HfModelsVersion.SDK_VERSION)
                    .put("runtime_version", loops.model?.info?.runtimeVersion ?: JSONObject.NULL)
                    .put("device", JSONObject().put("model", Build.MODEL).put("display", Build.DISPLAY))
                    .put("started_epoch_ms", started)
                    .put("total_s", round1((SystemClock.elapsedRealtime() - t0) / 1000.0))
                    .put("thermal_start", thermalStart)
                    .put("thermal_end", thermalStatus())
                val path = withContext(Dispatchers.IO) { runCatching { writeRecord(record) }.getOrElse { "not written ($it)" } }
                Log.i(TAG, "DONE phase=$phase answers=${rows.length()} events=${events.length()} json=$path")
                refreshFiles()
                render()
            }
        }
    }

    private fun summary(spec: RunSpec, answers: Map<Loop, Map<Int, Answer>>, stopped: Boolean, failures: Int): String {
        if (stopped) return "Stopped"
        if (spec.questions.size == 1) {
            val parts = spec.loops.mapNotNull { l -> answers[l]?.get(0)?.let { "${l.label} ${String.format(Locale.US, "%.1f s", it.timing.totalMs / 1000.0)}" } }
            return if (parts.isEmpty()) "No answer" else parts.joinToString(" · ")
        }
        val n = answers.values.sumOf { it.size }
        return "$n answers to ${spec.questions.size} questions" + if (failures > 0) " · $failures not answered" else ""
    }

    /** The harness's Stop: the same cancel as the button, once a download has the asked-for MB on the phone. */
    private fun onLoadEvent(loop: Loop, e: LoadEvent) {
        val pane = panes.getValue(loop)
        pane.status.text = when (e) {
            is LoadEvent.Resolving -> "Preparing the file"
            is LoadEvent.DownloadStarted -> "Downloading ${Loops.mb(e.totalBytes)} MB"
            is LoadEvent.Downloading -> {
                val from = loops.resumedFrom?.takeIf { it > 0 }?.let { " (continued from ${Loops.mb(it)} MB)" } ?: ""
                "Downloading ${Loops.mb(e.bytes)} / ${Loops.mb(e.totalBytes)} MB$from"
            }
            is LoadEvent.Verifying -> "Checking the file's sha256"
            is LoadEvent.Initializing -> "Starting the engine (${e.profileId})"
            is LoadEvent.Fallback -> "Fallback: ${e.reason}"
            is LoadEvent.Ready -> "Loaded"
        }
        if (e is LoadEvent.Downloading) {
            detail = "Downloading ${loop.label}: ${Loops.mb(e.bytes)} / ${Loops.mb(e.totalBytes)} MB"
            running?.stopAfterMb?.let { mb -> if (e.bytes >= mb.toLong() shl 20) { Log.i(TAG, "HARNESS stop at ${e.bytes} bytes"); job?.cancel() } }
        } else {
            Log.i(TAG, "load ${loop.variant} ${pane.status.text}")
        }
        render()
    }

    private fun onStage(loop: Loop, stage: Loops.Stage) {
        when (stage) {
            Loops.Stage.CLOSING -> { phase = Phase.LOADING; detail = "Closing the ${loop.label} engine" }
            Loops.Stage.LOADING -> { phase = Phase.LOADING; detail = "Loading ${loop.label}"; panes.getValue(loop).status.text = "Loading" }
            Loops.Stage.ANSWERING -> {
                phase = Phase.ANSWERING; detail = "${loop.label} answering"
                val p = panes.getValue(loop)
                p.loadLine.text = loops.answerLoadMs?.let { "load ${seconds(it)}" } ?: "already loaded"
                p.status.text = "Answering"
                showFooter()
            }
        }
        render()
    }

    private fun fail(reason: String) {
        Log.e(TAG, "FAILED $reason")
        phase = Phase.FAILED; detail = reason
        render()
    }

    // ---- the record ----

    private fun answerRow(a: Answer, k: Int, q: RunSpec.Question, thermalBefore: Int): JSONObject {
        val row = JSONObject()
            .put("loop", a.loop.passes).put("variant", a.loop.variant)
            .put("question_index", k).put("question_id", q.example?.id ?: "own").put("question", q.text)
            .put("text", a.text).put("thought_chars", a.thought.length)
            .put("chunks", a.timing.chunks)
            .put("first_token_ms", a.timing.firstTokenMs?.let { round1(it) } ?: JSONObject.NULL)
            .put("first_text_ms", a.firstTextMs?.let { round1(it) } ?: JSONObject.NULL)
            .put("tok_s", a.timing.tokensPerSecond?.let { Math.round(it * 100) / 100.0 } ?: JSONObject.NULL)
            .put("total_ms", round1(a.timing.totalMs))
            .put("load_ms", a.loadMs ?: JSONObject.NULL)
            .put("backend", a.backend)
            .put("thermal_before", thermalBefore).put("thermal_after", thermalStatus())
        q.example?.gold?.let { gold -> row.put("gold", gold).put("final_value", Marks.finalValue(a.text) ?: JSONObject.NULL).put("mark", markWord(a, q.example)) }
        return row
    }

    private fun loadRow(loop: Loop): JSONObject {
        val i = loops.model?.info
        return JSONObject().put("loop", loop.passes).put("variant", loop.variant).put("load_ms", loops.loadMs)
            .put("download_bytes", loops.downloadBytes ?: JSONObject.NULL).put("resumed_from", loops.resumedFrom ?: JSONObject.NULL)
            .put("profile", i?.profileId ?: JSONObject.NULL)
            .put("initialized", i?.components?.entries?.joinToString(" ") { "${it.key}=${it.value.initialized}" } ?: JSONObject.NULL)
            .put("commit", i?.commit ?: JSONObject.NULL)
    }

    private fun eventRow(kind: String, loop: Loop, k: Int, q: RunSpec.Question, sentence: String): JSONObject =
        JSONObject().put("kind", kind).put("loop", loop.passes).put("variant", loop.variant).put("question_index", k)
            .put("question_id", q.example?.id ?: "own").put("sentence", sentence)
            .put("partial_chunks", loops.partialChunks)
            .put("download_bytes", loops.downloadBytes ?: JSONObject.NULL).put("download_total", loops.downloadTotal ?: JSONObject.NULL)
            .put("resumed_from", loops.resumedFrom ?: JSONObject.NULL)

    private fun markWord(a: Answer, example: Example?): String {
        val gold = example?.gold ?: return "none"
        return when (Marks.mark(a.text, gold)) {
            is Marks.Mark.Match -> "match"
            is Marks.Mark.Mismatch -> "mismatch"
            Marks.Mark.NoLine -> "no_line"
        }
    }

    private fun writeRecord(record: JSONObject): String {
        val dir = getExternalFilesDir(null) ?: filesDir
        val file = File(dir, "loops-run-${System.currentTimeMillis() / 1000}.json")
        file.writeText(record.toString(2))
        return file.absolutePath
    }

    private fun thermalStatus(): Int = getSystemService(PowerManager::class.java).currentThermalStatus
    private fun round1(x: Double) = Math.round(x * 10) / 10.0
    private fun seconds(ms: Number) = String.format(Locale.US, "%.1f s", ms.toDouble() / 1000.0)

    // ---- the screen ----

    /** One file's column: its name and where the file is, what it is doing, the answer, the mark and the times. */
    private inner class Pane(val loop: Loop) {
        val root = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 12f)
            setPadding(px(10f).toInt(), px(8f).toInt(), px(10f).toInt(), px(8f).toInt())
        }
        val title = label(19f, ACCENT.getValue(loop), bold = true).apply { text = loop.label }
        val file = label(11f, DIM).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val status = label(12f, DIM).apply { maxLines = 4; ellipsize = TextUtils.TruncateAt.END; id = if (loop == Loop.ONE) R.id.status_1 else R.id.status_2 }
        val scroll = ScrollView(this@MainActivity)
        val answer = TextView(this@MainActivity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(13f)); setTextColor(WHITE)
            setLineSpacing(0f, 1.1f)
            id = if (loop == Loop.ONE) R.id.answer_1 else R.id.answer_2
        }
        val mark = label(13f, WHITE, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val timing = label(15f, WHITE, bold = true).apply { maxLines = 1; id = if (loop == Loop.ONE) R.id.timing_1 else R.id.timing_2 }
        val firstToken = label(12f, DIM)
        val loadLine = label(11f, DIM)

        init {
            scroll.addView(answer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            root.addView(title, wrapParams())
            root.addView(file, wrapParams(top = 2f))
            root.addView(status, wrapParams(top = 4f))
            root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = px(6f).toInt() })
            root.addView(mark, wrapParams(top = 6f))
            root.addView(timing, wrapParams(top = 4f))
            root.addView(firstToken, wrapParams(top = 2f))
            root.addView(loadLine, wrapParams(top = 2f))
        }

        fun clear(statusText: String) {
            status.text = statusText; status.setTextColor(DIM)
            answer.text = ""; mark.text = ""; timing.text = ""; firstToken.text = ""; loadLine.text = ""
        }

        /** While the model writes: the answer so far, at the bottom of the column; the reasoning's length while it reasons. */
        fun streaming(text: String, thoughtChars: Int) {
            if (thoughtChars > 0) status.text = if (text.isEmpty()) "Reasoning: $thoughtChars characters" else "Answering after $thoughtChars characters of reasoning"
            answer.text = text
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }

        fun show(a: Answer, example: Example?) {
            status.setTextColor(DIM)
            status.text = if (a.thought.isNotEmpty()) "${a.thought.length} characters of reasoning kept out of the answer · ${a.backend}" else "Answered on the ${a.backend}"
            answer.text = a.text
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
            val gold = example?.gold
            if (gold == null) {
                mark.text = ""
            } else when (val m = Marks.mark(a.text, gold)) {
                is Marks.Mark.Match -> { mark.setTextColor(MATCH); mark.text = "#### ${m.value} · GSM8K's answer" }
                is Marks.Mark.Mismatch -> { mark.setTextColor(MISMATCH); mark.text = "#### ${m.value} · GSM8K's answer is ${m.gold}" }
                Marks.Mark.NoLine -> { mark.setTextColor(MISMATCH); mark.text = "no #### line · GSM8K's answer is $gold" }
            }
            val t = a.timing
            timing.text = String.format(Locale.US, "%.1f s", t.totalMs / 1000.0) + (t.tokensPerSecond?.let { String.format(Locale.US, " · %.1f tok/s", it) } ?: "")
            firstToken.text = t.firstTokenMs?.let { String.format(Locale.US, "first token %.2f s · %d tokens", it / 1000.0, t.chunks) } ?: "no token"
            loadLine.text = a.loadMs?.let { "load ${seconds(it)}" } ?: "already loaded"
        }

        fun stopped(sentence: String, partial: String) {
            status.setTextColor(WHITE); status.text = sentence
            answer.text = partial
        }

        fun failed(sentence: String) {
            status.setTextColor(MISMATCH); status.text = sentence
        }
    }

    private fun px(v: Float) = v * u

    private fun label(size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size)); setTextColor(color); includeFontPadding = false
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
        fontFeatureSettings = "tnum"
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
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
            // The keyboard's inset too: the boxes stay above it while you type (the columns give up the room).
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            content.setPadding(px(16f).toInt(), bars.top + px(8f).toInt(), px(16f).toInt(), bars.bottom + px(8f).toInt())
            footer.visibility = if (insets.isVisible(WindowInsets.Type.ime())) View.GONE else View.VISIBLE
            insets
        }

        // (1) state pill and what is happening
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        pill = label(15f, WHITE, bold = true).apply { setPadding(px(10f).toInt(), px(5f).toInt(), px(10f).toInt(), px(5f).toInt()); id = R.id.state }
        headline = label(15f, WHITE, bold = true).apply {
            setAutoSizeTextTypeUniformWithConfiguration(px(10f).toInt(), px(15f).toInt(), 1, TypedValue.COMPLEX_UNIT_PX)
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(pill)
        header.addView(headline, LinearLayout.LayoutParams(0, px(26f).toInt(), 1f).apply { leftMargin = px(10f).toInt() })
        content.addView(header)

        // (2) the question: your own text, or an example's
        questionBox = EditText(this).apply {
            id = R.id.question
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(15f)); setTextColor(WHITE); setHintTextColor(DIM)
            hint = "Type a question, or pick an example"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3; maxLines = 5; gravity = Gravity.TOP or Gravity.START
            isVerticalScrollBarEnabled = true
            filters = arrayOf(InputFilter.LengthFilter(MAX_QUESTION_CHARS))
            background = rounded(CARD, 10f)
            setPadding(px(10f).toInt(), px(8f).toInt(), px(10f).toInt(), px(8f).toInt())
        }
        content.addView(questionBox, wrapParams(top = 8f))

        // (3) the examples: picking one fills the question box
        examples = Spinner(this).apply {
            id = R.id.examples
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Examples…") + Examples.ALL.map { it.title })
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position == 0) return
                    questionBox.setText(Examples.ALL[position - 1].text)
                    setSelection(0)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        content.addView(examples, wrapParams(top = 2f))

        // (4) which file, Think, downloads, Run and Stop
        runGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        for ((rid, text) in listOf(R.id.run_1 to "1 loop", R.id.run_2 to "2 loops", R.id.run_both to "Both")) {
            runGroup.addView(RadioButton(this).apply { id = rid; this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_PX, px(14f)) })
        }
        runGroup.check(R.id.run_both)
        think = CheckBox(this).apply { id = R.id.think; text = "Think"; isChecked = false; setTextSize(TypedValue.COMPLEX_UNIT_PX, px(14f)) }
        val choose = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        choose.addView(runGroup, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        choose.addView(think)
        content.addView(choose, wrapParams(top = 2f))

        download = CheckBox(this).apply { id = R.id.download; text = "Download missing files"; isChecked = true; setTextSize(TypedValue.COMPLEX_UNIT_PX, px(13f)) }
        run = Button(this).apply { id = R.id.run; text = "Run"; setOnClickListener { onRun() } }
        stop = Button(this).apply { id = R.id.stop; text = "Stop"; setOnClickListener { job?.cancel() } }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        actions.addView(download, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(run); actions.addView(stop)
        content.addView(actions, wrapParams())

        // (5) the two columns, 1 loop on the left
        val columns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (loop in Loop.values()) {
            val pane = Pane(loop)
            panes[loop] = pane
            pane.file.text = fileLine(loop, null)
            pane.clear("")
            columns.addView(pane.root, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (loop != Loop.ONE) leftMargin = px(8f).toInt() })
        }
        content.addView(columns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = px(6f).toInt() })

        // (6) footer: the model, its commit, the phone, the runtime and SDK versions (the variant once one is loaded)
        footer = label(11f, DIM).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; text = "${Loops.REPO}@${Loops.REVISION.take(8)} · ${Build.MODEL}" }
        content.addView(footer, wrapParams(top = 6f))

        setContentView(frame)
    }

    private fun showFooter() {
        val i = loops.model?.info ?: return
        footer.text = listOf("${i.repoId}@${i.commit.take(8)}", "${i.variantId}/${i.profileId}", Build.MODEL, "LiteRT-LM ${i.runtimeVersion}", "hfmodels ${i.sdkVersion}").joinToString(" · ")
    }

    private fun chosenLoops(): List<Loop> = when (runGroup.checkedRadioButtonId) {
        R.id.run_1 -> listOf(Loop.ONE)
        R.id.run_2 -> listOf(Loop.TWO)
        else -> listOf(Loop.ONE, Loop.TWO)
    }

    private fun selectRun(order: List<Loop>) = runGroup.check(if (order.size == 2) R.id.run_both else if (order[0] == Loop.ONE) R.id.run_1 else R.id.run_2)

    private fun render() {
        val (badge, color) = when (phase) {
            Phase.READY -> "READY" to PILL_IDLE
            Phase.LOADING -> "LOADING" to PILL_BUSY
            Phase.ANSWERING -> "● ANSWERING" to PILL_BUSY
            Phase.DONE -> "DONE" to PILL_DONE
            Phase.STOPPED -> "STOPPED" to PILL_IDLE
            Phase.FAILED -> "FAILED" to PILL_BUSY
        }
        pill.text = badge; pill.background = rounded(color, 100f)
        headline.text = detail.ifEmpty { "Type a question or pick an example, then Run" }
        val busy = job?.isActive == true && phase != Phase.READY || running != null
        questionBox.isEnabled = !busy
        examples.isEnabled = !busy
        for (i in 0 until runGroup.childCount) runGroup.getChildAt(i).isEnabled = !busy
        think.isEnabled = !busy
        download.isEnabled = !busy
        run.isEnabled = !busy
        stop.isEnabled = busy
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        loops.closeNow()
        models.close()
    }
}
