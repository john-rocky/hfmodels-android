package io.github.johnrocky.hfmodels.samples.ask

import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.text.Editable
import android.text.InputFilter
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import io.github.johnrocky.hfmodels.BackendKind
import io.github.johnrocky.hfmodels.BackendPolicy
import io.github.johnrocky.hfmodels.HfModels
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
 * Ask a vision-language model about your own photo, on the phone. Pick a photo (the system photo picker,
 * no permission), type a question and two to five options, lettered A to E on screen, and Ask: the model
 * answers with one letter, and the chosen option lights up with the time it took.
 *
 * The first question about a photo opens a conversation with the photo and the question in one turn; a
 * further question about the same photo is a text-only turn of that conversation. One loaded model holds
 * one conversation ([Asker]): after a new photo, a Stop or the Demo, the next Ask loads the model again.
 *
 * Demo: the app draws one of its 24 bar charts and asks five questions about it, marking each answer
 * against the data it drew. Recording mode runs the Demo alone, over the lock screen, screen kept on:
 *
 *     adb shell am start -n io.github.johnrocky.hfmodels.samples.ask/.MainActivity \
 *         --ez autostart true --ei delay 3 --ei chart 0 --es backend gpu --ei pictures 1
 *
 * Each Demo picture's run is written to getExternalFilesDir(null): ask-result-<epoch s>.json and the exact
 * PNG bytes the model got, frames-<epoch s>/000.png. Logcat tag `ask`.
 */
class MainActivity : ComponentActivity() {
    private companion object {
        const val TAG = "ask"
        const val CHARTS_ASSET = "charts.json"
        /** The question is on screen this long before it is sent. */
        const val SHOW_MS = 800L
        /** The answer stays on screen this long before the next question. */
        const val HOLD_MS = 1_200L
        const val MAX_QUESTION_CHARS = 200
        const val MAX_OPTION_CHARS = 80

        const val BACKGROUND = 0xFF0E1116.toInt()
        const val CARD = 0xFF1B2028.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val DIM = 0xFF8A919C.toInt()
        const val PILL_IDLE = 0xFF5F6368.toInt()
        const val PILL_ASKING = 0xFFE53935.toInt()
        const val PILL_DONE = 0xFF2E7D32.toInt()
        const val MATCH = 0xFF5CBA5C.toInt()       // the model paddle's green in samples/pong
        const val MISMATCH = 0xFFE53935.toInt()
        const val MAX_OPTIONS = Typed.MAX_OPTIONS
    }

    private enum class Phase { IDLE, LOADING, READY, ASKING, DONE, STOPPED, FAILED }

    /** What the picture slot and the card show: your photo and your question, or the chart demo. */
    private enum class Pane { PHOTO, DEMO }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var models: HfModels
    private lateinit var asker: Asker
    private lateinit var charts: List<ChartSpec>
    private var askJob: Job? = null

    private var recording = false
    private var pane = Pane.PHOTO
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
    /** The picked photo, and the picture object the model's conversation is keyed on (a new pick is a new one). */
    private var photo: Photo? = null
    private var photoPicture: Picture? = null
    /** Loads already shown in the footer and the log. */
    private var shownLoads = 0

    private var u = 1f
    private lateinit var pill: TextView
    private lateinit var count: TextView
    private lateinit var lastMs: TextView
    // the picture slot
    private lateinit var photoView: ImageView
    private lateinit var placeholder: TextView
    private lateinit var chartView: ChartView
    private lateinit var paneRow: LinearLayout
    private lateinit var pick: Button
    private lateinit var demo: Button
    // the photo card: your question and options
    private lateinit var photoCard: LinearLayout
    private lateinit var questionBox: EditText
    private lateinit var boxRows: List<LinearLayout>
    private lateinit var boxLetters: List<TextView>
    private lateinit var boxes: List<EditText>
    private lateinit var boxMs: List<TextView>
    private lateinit var note: TextView
    // the demo card: the chart's question and options, the mark, the score
    private lateinit var demoCard: LinearLayout
    private lateinit var question: TextView
    private lateinit var optionRows: List<LinearLayout>
    private lateinit var optionTexts: List<TextView>
    private lateinit var optionMs: List<TextView>
    private lateinit var mark: TextView
    private lateinit var score: TextView
    private lateinit var footer: TextView
    private lateinit var controls: LinearLayout
    private lateinit var backend: Spinner
    private lateinit var ask: Button
    private lateinit var stop: Button

    /** The system photo picker: no storage permission, the user picks one image. */
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { importPhoto(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Creates /sdcard/Android/data/<package>/files/, where a model copy pushed with adb is picked up (README).
        getExternalFilesDir(null)
        models = HfModels(applicationContext)
        val descriptor = assets.open(Asker.DESCRIPTOR_ASSET).bufferedReader().use { it.readText() }
        asker = Asker(models, descriptor)
        charts = ChartFile.parse(assets.open(CHARTS_ASSET).bufferedReader().use { it.readText() }).map { it.spec }
        val autostart = intent.getBooleanExtra("autostart", false)
        val delaySeconds = intent.getIntExtra("delay", 3)
        nextChart = Math.floorMod(intent.getIntExtra("chart", 0), charts.size)
        pictures = intent.getIntExtra("pictures", 1).coerceAtLeast(1)
        policyName = when (intent.getStringExtra("backend")) { "gpu" -> "gpu"; "cpu" -> "cpu"; else -> "auto" }
        // `--es network offline`: the load makes no request (OFFLINE_CACHE_MISS when the model is not on the phone).
        asker.network = if (intent.getStringExtra("network") == "offline") NetworkPolicy.Offline else NetworkPolicy.Any
        asker.policy = policy()
        asker.onStage = ::onStage
        asker.onEvent = ::onLoadEvent
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
            showPane(Pane.DEMO)
        } else {
            showPane(Pane.PHOTO)
        }
        chartView.show(chartPng(charts[nextChart]))
        showQuestion(null)
        render()
        if (autostart) {
            startJob {
                if (!loadForDemo()) return@startJob
                delay(delaySeconds * 1000L)
                askAll()
            }
        }
    }

    /** Every model job goes through here: one at a time, and the buttons follow its end. */
    private fun startJob(block: suspend () -> Unit) {
        val job = scope.launch { block() }
        askJob = job
        job.invokeOnCompletion { runOnUiThread { render() } }
        render()
    }

    private fun policy(): BackendPolicy = when (policyName) {
        "gpu" -> BackendPolicy.Require(BackendKind.GPU)
        "cpu" -> BackendPolicy.Require(BackendKind.CPU)
        else -> BackendPolicy.Auto
    }

    private fun mb(bytes: Long) = String.format(Locale.US, "%,d MB", bytes shr 20)

    private fun seconds(ms: Number) = String.format(Locale.US, "%.1f s", ms.toDouble() / 1000.0)

    /** The chart as the PNG bytes the model gets; the screen shows these same bytes. */
    private fun chartPng(spec: ChartSpec): ByteArray = Png.encode(ChartFrame.render(spec), ChartFrame.SIDE, ChartFrame.SIDE)

    // ---- the model ----

    private fun onStage(stage: Asker.Stage) {
        when (stage) {
            Asker.Stage.CLOSING -> { phase = Phase.LOADING; detail = "Closing the model that held the last conversation" }
            Asker.Stage.LOADING -> { phase = Phase.LOADING; detail = "Resolving" }
            Asker.Stage.OPENING -> { showLoaded(); phase = Phase.ASKING; detail = "Opening a conversation" }
            Asker.Stage.ASKING -> { phase = Phase.ASKING; detail = "" }
        }
        Log.i(TAG, "stage $stage")
        render()
    }

    private fun onLoadEvent(e: LoadEvent) {
        // Delivered on the caller's dispatcher (the SDK's promise); runOnUiThread keeps the views safe regardless.
        runOnUiThread {
            detail = when (e) {
                is LoadEvent.Resolving -> "Resolving"
                is LoadEvent.DownloadStarted -> "Downloading ${mb(e.totalBytes)}"
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

    /** The footer and the READY log line, once per load. */
    private fun showLoaded() {
        val i = asker.model?.info ?: return
        if (asker.loads == shownLoads) return
        shownLoads = asker.loads
        Log.i(TAG, "READY profile=${i.profileId} load_ms=${asker.loadMs} variant=${i.variantId} commit=${i.commit.take(8)} downloaded=${asker.downloaded}")
        footer.text = listOf(Asker.REPO, i.variantId, i.profileId, Build.MODEL, "LiteRT-LM ${i.runtimeVersion}", "hfmodels ${i.sdkVersion}").joinToString(" · ")
    }

    /** The backend chosen on screen applies from the next load; a loaded model on another backend is closed first. */
    private suspend fun applyBackend() {
        if (recording) return
        val chosen = backend.selectedItem as String
        if (chosen == policyName) return
        policyName = chosen
        asker.policy = policy()
        asker.close()
    }

    /** Recording mode's load before the delay; false (and FAILED on screen) when it fails. */
    private suspend fun loadForDemo(): Boolean = try {
        val m = asker.load()
        showLoaded()
        phase = Phase.READY
        detail = String.format(Locale.US, "profile %s · loaded in %.1f s", m.info.profileId, asker.loadMs / 1000.0)
        render()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        fail(Asker.describe(e))
        false
    }

    private fun fail(reason: String) {
        Log.e(TAG, "FAILED $reason")
        phase = Phase.FAILED; detail = reason
        if (pane == Pane.PHOTO) note(reason, MISMATCH)
        render()
    }

    // ---- your photo ----

    private fun importPhoto(uri: Uri) {
        if (askJob?.isActive == true) return
        startJob {
            try {
                val p = withContext(Dispatchers.IO) { PhotoImport.load(contentResolver, uri) }
                photo = p
                photoPicture = Picture(p.bytes)
                photoView.setImageBitmap(BitmapFactory.decodeByteArray(p.bytes, 0, p.bytes.size))
                Log.i(TAG, "PHOTO w=${p.width} h=${p.height} source=${p.sourceWidth}x${p.sourceHeight} bytes=${p.bytes.size}")
                clearAnswer()
                val reload = if (asker.heldConversation) " The next Ask loads the model again first: one conversation per load." else ""
                note(String.format(Locale.US, "Photo %d x %d (from %d x %d). Type a question and options, then Ask.%s", p.width, p.height, p.sourceWidth, p.sourceHeight, reload), DIM)
                if (phase != Phase.LOADING) { phase = if (asker.model != null) Phase.READY else Phase.IDLE; detail = "" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "PHOTO failed: $e")
                note("Could not read that photo: ${Asker.describe(e)}", MISMATCH)
            }
            showPane(Pane.PHOTO)
            render()
        }
    }

    private fun onAsk() {
        if (askJob?.isActive == true) return
        showPane(Pane.PHOTO)
        val typed = Typed.check(photo != null, questionBox.text.toString(), boxes.map { it.text.toString() })
        if (typed is Typed.Result.Refused) {
            Log.i(TAG, "REFUSED ${typed.message}")
            note(typed.message, MISMATCH)
            return
        }
        val ready = typed as Typed.Result.Ready
        val pic = photoPicture ?: return
        clearAnswer()
        lastAnswerMs = null
        startJob {
            try {
                applyBackend()
                note("", DIM)
                val r = asker.ask(pic, ready.question, ready.options)
                lastAnswerMs = r.answerMs
                showPhotoAnswer(r, ready.options.size)
                phase = Phase.DONE
                detail = if (r.choice == Answer.UNPARSED) "no option letter" else "answer ${Answer.letter(r.choice)}"
                Log.i(TAG, "ASK first_turn=${r.firstTurn} letter=${Answer.letter(r.choice)} raw=${JSONObject.quote(r.text)} answer_ms=${r.answerMs?.let { round1(it) }} " +
                    "stream_ms=${round1(r.streamMs)} conversation_ms=${r.conversationMs?.let { round1(it) }} load_ms=${r.loadMs} options=${ready.options.size}")
            } catch (e: CancellationException) {
                phase = Phase.STOPPED; detail = "stopped"
                Log.i(TAG, "STOPPED")
                note("Stopped. That conversation is closed; the next Ask loads the model again and sends the photo with the question.", DIM)
                render()
                throw e
            } catch (e: Exception) {
                fail(Asker.describe(e))
            }
            render()
        }
    }

    /** The chosen row lit with its time on the right; the line under the options says what the turn carried. */
    private fun showPhotoAnswer(r: Reply, options: Int) {
        val msText = r.answerMs?.let { String.format(Locale.US, "%,d ms", Math.round(it)) } ?: "no text"
        for (i in 0 until MAX_OPTIONS) lightRow(i, i == r.choice, if (i == r.choice) msText else "")
        val what = if (r.firstTurn) {
            val opened = r.conversationMs?.let { " · conversation opened in ${seconds(it)}" } ?: ""
            val loaded = r.loadMs?.let { " · model loaded in ${seconds(it)}" } ?: ""
            "The photo and the question in one turn$opened$loaded."
        } else {
            "A text-only turn about the same photo."
        }
        if (r.choice == Answer.UNPARSED) {
            note("No option letter (the model wrote ${JSONObject.quote(r.text)}, $msText). $what", MISMATCH)
        } else {
            note("${Answer.letter(r.choice)} of A to ${'A' + options - 1} in $msText. $what", WHITE)
        }
    }

    private fun lightRow(i: Int, lit: Boolean, ms: String) {
        boxRows[i].background = if (lit) rounded(WHITE, 10f) else null
        boxes[i].background = rounded(if (lit) 0 else CARD, 10f)
        boxes[i].setTextColor(if (lit) BACKGROUND else WHITE)
        boxes[i].typeface = if (lit) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
        boxLetters[i].setTextColor(if (lit) BACKGROUND else DIM)
        boxMs[i].text = ms
    }

    private fun clearAnswer() {
        for (i in 0 until MAX_OPTIONS) lightRow(i, false, "")
    }

    private fun note(text: String, color: Int) {
        note.text = text
        note.setTextColor(color)
    }

    // ---- the chart demo ----

    private fun onDemo() {
        if (askJob?.isActive == true) return
        showPane(Pane.DEMO)
        startJob {
            applyBackend()
            askAll()
        }
    }

    /** [pictures] charts from [nextChart] on, each in a conversation of its own (so each on a fresh load). */
    private suspend fun askAll() {
        for (p in 1..pictures) {
            if (p > 1) delay(HOLD_MS)
            val spec = charts[nextChart]
            nextChart = (nextChart + 1) % charts.size
            picture = p
            if (!askPicture(spec)) return
        }
    }

    /**
     * One chart: one conversation, the image and the first question in turn 1, each further question as a
     * text-only turn of the same conversation, then close. False when it failed or was stopped.
     */
    private suspend fun askPicture(spec: ChartSpec): Boolean {
        val questions = Questions.of(spec)
        val turns = Prompt.turns(spec, questions)
        val png = withContext(Dispatchers.Default) { chartPng(spec) }
        val pic = Picture(png, spec.context)
        chartView.show(png)
        answered = 0; matched = 0; total = questions.size; lastAnswerMs = null
        showQuestion(null)
        val log = JSONArray()
        var stopped = true
        var failure: String? = null
        var closeMs: Double? = null
        val thermalBefore = thermalStatus()
        var t0 = SystemClock.elapsedRealtime()
        phase = Phase.ASKING; detail = ""
        render()
        try {
            // Opened before the first question shows (a new load first when the model has held a conversation);
            // total_s counts from the start of the opening, as it did before the photo screen.
            val openMs = asker.open(pic)
            t0 = SystemClock.elapsedRealtime() - Math.round(openMs)
            Log.i(TAG, "PICTURE $picture/$pictures chart=${spec.id} bars=${spec.n} scale=${chartView.factor()}")
            for ((k, q) in questions.withIndex()) {
                if (k > 0) delay(HOLD_MS)
                showQuestion(q)
                delay(SHOW_MS)
                val t = asker.ask(pic, q.text, q.options)
                val ok = t.choice == q.expected
                answered = k + 1
                if (ok) matched++
                lastAnswerMs = t.answerMs
                showAnswer(t.choice, t.answerMs, ok)
                val row = JSONObject()
                    .put("turn", k + 1)
                    .put("id", q.id)
                    .put("question", q.text)
                    .put("options", JSONArray(q.options))
                    .put("letter", Answer.letter(t.choice))
                    .put("raw_text", t.text)
                    .put("idx", t.choice)
                    .put("expected", q.expected)
                    .put("ok", ok)
                    .put("answer_ms", t.answerMs?.let { round1(it) } ?: JSONObject.NULL)
                    .put("stream_ms", round1(t.streamMs))
                if (k == 0) row.put("conversation_ms", t.conversationMs?.let { round1(it) } ?: JSONObject.NULL)
                log.put(row)
                Log.i(TAG, "TURN ${k + 1} id=${q.id} letter=${Answer.letter(t.choice)} ok=$ok answer_ms=${t.answerMs?.let { round1(it) }}")
                render()
            }
            stopped = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            stopped = false
            failure = Asker.describe(e)
            Log.e(TAG, "FAILED $failure", e)
        } finally {
            val totalS = (SystemClock.elapsedRealtime() - t0) / 1000.0
            withContext(NonCancellable) {
                val c0 = SystemClock.elapsedRealtimeNanos()
                if (asker.sessionState != null) {
                    asker.endConversation()
                    closeMs = (SystemClock.elapsedRealtimeNanos() - c0) / 1e6
                }
                val info = asker.model?.info
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
                    .put("load_ms", asker.loadMs)
                    .put("load_downloaded", asker.downloaded)
                    .put("close_ms", closeMs?.let { round1(it) } ?: JSONObject.NULL)
                    .put("profile", info?.profileId ?: JSONObject.NULL)
                    .put("backend_policy", policyName)
                    .put("variant", info?.variantId ?: JSONObject.NULL)
                    .put("model", info?.let { "${it.repoId}@${it.commit}" } ?: JSONObject.NULL)
                    .put("sdk_version", info?.sdkVersion ?: JSONObject.NULL)
                    .put("runtime_version", info?.runtimeVersion ?: JSONObject.NULL)
                    .put("device", JSONObject().put("model", Build.MODEL).put("display", Build.DISPLAY))
                    .put("thermal_before", thermalBefore)
                    .put("thermal_after", thermalStatus())
                    .put("total_s", round1(totalS))
                    .put("prompt_turn1", turns[0])
                val path = withContext(Dispatchers.IO) { runCatching { writeRecord(record, png) }.getOrElse { "not written ($it)" } }
                Log.i(TAG, "DONE json=$path")
                phase = when { failure != null -> Phase.FAILED; stopped -> Phase.STOPPED; else -> Phase.DONE }
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

    private fun box(hint: String, maxChars: Int): EditText = EditText(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, px(17f)); setTextColor(WHITE); setHintTextColor(DIM)
        this.hint = hint
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_NEXT
        filters = arrayOf(InputFilter.LengthFilter(maxChars))
        background = rounded(CARD, 10f)
        setPadding(px(10f).toInt(), 0, px(10f).toInt(), 0)
        // Editing after an answer clears the lit row: the answer was to the text as it was.
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { if (askJob?.isActive != true) clearAnswer() }
        })
    }

    private fun buildScreen() {
        // Laid out in units of the screen width / 402, so a recording scaled to any width keeps its proportions.
        u = resources.displayMetrics.widthPixels / 402f
        val frame = FrameLayout(this).apply { setBackgroundColor(BACKGROUND) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        frame.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.setOnApplyWindowInsetsListener { _, insets ->
            // The keyboard's inset too: the boxes stay above it while you type (the picture slot gives up the room).
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            content.setPadding(px(16f).toInt(), bars.top + px(8f).toInt(), px(16f).toInt(), bars.bottom + px(8f).toInt())
            footer.visibility = if (insets.isVisible(WindowInsets.Type.ime())) View.GONE else View.VISIBLE
            insets
        }

        // (1) state pill, what is happening, last answer time
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        pill = label(16f, WHITE, bold = true).apply { setPadding(px(10f).toInt(), px(6f).toInt(), px(10f).toInt(), px(6f).toInt()) }
        count = fitted(label(16f, WHITE, bold = true), 11f, 16f)
        lastMs = label(16f, DIM, bold = true).apply { gravity = Gravity.END }
        header.addView(pill)
        header.addView(count, LinearLayout.LayoutParams(0, px(28f).toInt(), 1f).apply { leftMargin = px(10f).toInt() })
        header.addView(lastMs)
        content.addView(header)

        // (2) the picture slot: your photo (the JPEG bytes the model gets), or the chart (the PNG bytes it gets)
        val slot = FrameLayout(this)
        photoView = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        placeholder = label(20f, DIM, bold = true).apply {
            text = "Pick a photo"; gravity = Gravity.CENTER; background = rounded(CARD, 14f)
            setOnClickListener { onPick() }
        }
        chartView = ChartView(this, u)
        slot.addView(photoView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        slot.addView(placeholder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        slot.addView(chartView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        content.addView(slot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = px(6f).toInt() })

        // (3) Pick a photo | Demo
        paneRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pick = Button(this).apply { text = "Pick a photo"; setOnClickListener { onPick() } }
        demo = Button(this).apply { text = "Demo"; setOnClickListener { onDemo() } }
        paneRow.addView(pick, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        paneRow.addView(demo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = px(8f).toInt() })
        content.addView(paneRow, wrapParams(top = 4f))

        // (4a) the photo card: the question box, five option boxes lettered A to E (the chosen one lit, its time on the right), a note
        photoCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        questionBox = box("Question, e.g. What is this a photo of?", MAX_QUESTION_CHARS)
        photoCard.addView(questionBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(42f).toInt()))
        val rows = ArrayList<LinearLayout>(); val letters = ArrayList<TextView>(); val edits = ArrayList<EditText>(); val times = ArrayList<TextView>()
        for (i in 0 until MAX_OPTIONS) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val letter = label(18f, DIM, bold = true).apply { text = "${'A' + i}"; gravity = Gravity.CENTER }
            val edit = box(if (i < Typed.MIN_OPTIONS) "Option ${'A' + i}" else "Option ${'A' + i} (optional)", MAX_OPTION_CHARS)
            if (i == MAX_OPTIONS - 1) edit.imeOptions = EditorInfo.IME_ACTION_DONE
            val ms = label(17f, BACKGROUND, bold = true).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
            row.addView(letter, LinearLayout.LayoutParams(px(28f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
            row.addView(edit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            row.addView(ms, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { leftMargin = px(6f).toInt(); rightMargin = px(10f).toInt() })
            photoCard.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(38f).toInt()).apply { topMargin = px(4f).toInt() })
            rows += row; letters += letter; edits += edit; times += ms
        }
        boxRows = rows; boxLetters = letters; boxes = edits; boxMs = times
        note = label(14f, DIM).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER_VERTICAL }
        photoCard.addView(note, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(40f).toInt()).apply { topMargin = px(4f).toInt() })
        content.addView(photoCard, wrapParams(top = 6f))

        // (4b) the demo card: the question, the options (the chosen one highlighted, its answer time on the right), the mark; the score line
        demoCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 14f)
            setPadding(px(12f).toInt(), px(12f).toInt(), px(12f).toInt(), px(12f).toInt())
        }
        question = fitted(label(23f, WHITE, bold = true).apply { maxLines = 2; ellipsize = null; gravity = Gravity.CENTER_VERTICAL }, 16f, 23f)
        card.addView(question, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(58f).toInt()))
        val optRows = ArrayList<LinearLayout>(); val texts = ArrayList<TextView>(); val optTimes = ArrayList<TextView>()
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
            optRows += row; texts += t; optTimes += ms
        }
        optionRows = optRows; optionTexts = texts; optionMs = optTimes
        mark = label(19f, MATCH, bold = true).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(10f).toInt(), 0, 0, 0) }
        card.addView(mark, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(30f).toInt()).apply { topMargin = px(6f).toInt() })
        demoCard.addView(card, wrapParams())
        score = fitted(label(17f, WHITE, bold = true), 12f, 17f)
        demoCard.addView(score, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(24f).toInt()).apply { topMargin = px(10f).toInt() })
        content.addView(demoCard, wrapParams(top = 10f))

        // (5) footer: model, variant, profile, phone, runtime and SDK versions (filled in once loaded)
        footer = label(12f, DIM).apply { maxLines = 2; text = "${Asker.REPO} · ${Asker.VARIANT} · ${Build.MODEL}" }
        content.addView(footer, wrapParams(top = 8f))

        // Controls for a normal start; recording mode runs on its own.
        controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        backend = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("auto", "gpu", "cpu"))
            setSelection(listOf("auto", "gpu", "cpu").indexOf(policyName))
        }
        ask = Button(this).apply { text = "Ask"; setOnClickListener { onAsk() } }
        stop = Button(this).apply { text = "Stop"; setOnClickListener { askJob?.cancel() } }
        controls.addView(backend, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(ask); controls.addView(stop)
        if (!recording) {
            content.addView(controls, wrapParams(top = 6f))
        } else {
            paneRow.visibility = View.GONE
        }

        setContentView(frame)
    }

    private fun onPick() {
        if (askJob?.isActive == true) return
        showPane(Pane.PHOTO)
        render()
        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun showPane(p: Pane) {
        pane = p
        val photoPane = p == Pane.PHOTO
        photoView.visibility = if (photoPane && photo != null) View.VISIBLE else View.GONE
        placeholder.visibility = if (photoPane && photo == null) View.VISIBLE else View.GONE
        chartView.visibility = if (photoPane) View.GONE else View.VISIBLE
        photoCard.visibility = if (photoPane) View.VISIBLE else View.GONE
        demoCard.visibility = if (photoPane) View.GONE else View.VISIBLE
    }

    /** The demo card before an answer: the question and its options, nothing chosen. Null clears the card. */
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

    /** The demo card after an answer: the chosen row highlighted with the answer time on its right, then the mark. */
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
            Phase.STOPPED -> "STOPPED" to PILL_IDLE
            Phase.FAILED -> "FAILED" to PILL_ASKING
        }
        pill.text = badge; pill.background = rounded(color, 100f)
        val pictureText = if (pictures > 1 && picture > 0) " · picture $picture of $pictures" else ""
        count.text = when {
            pane == Pane.DEMO && (phase == Phase.ASKING || phase == Phase.DONE) -> "$answered / $total$pictureText" + if (detail.isEmpty()) "" else " · $detail"
            detail.isNotEmpty() -> detail
            phase == Phase.ASKING -> "asking"
            photo == null -> "pick a photo, type a question, Ask"
            else -> "type a question and options, then Ask"
        }
        lastMs.text = lastAnswerMs?.let { String.format(Locale.US, "%,d ms", Math.round(it)) } ?: ""
        score.text = if (answered == 0) "" else "$matched of $answered answers match the chart"
        if (!recording) {
            val busy = askJob?.isActive == true
            backend.isEnabled = !busy
            pick.isEnabled = !busy
            demo.isEnabled = !busy
            ask.isEnabled = !busy
            stop.isEnabled = busy && (phase == Phase.LOADING || phase == Phase.ASKING)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        asker.closeNow()
        models.close()
    }
}
