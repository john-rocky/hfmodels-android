package io.github.johnrocky.hfmodels.samples.pong

import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
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
import io.github.johnrocky.hfmodels.litertlm.GenerationOptions
import io.github.johnrocky.hfmodels.litertlm.text
import java.io.File
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
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
 * Pong played by a vision-language model that looks at the game's own frames. Each decision renders
 * the 160x210 frame, resizes it to 256x256, and asks the model, in a new conversation, which of three
 * options to take; the letter it answers moves the right paddle by one step. A scripted paddle plays
 * the left side.
 *
 * Normal start: pick the backend, Load, Play (60 decisions), Stop. Recording mode loads, waits `delay`
 * seconds after READY and plays, drawn over the lock screen with the screen kept on:
 *
 *     adb shell am start -n io.github.johnrocky.hfmodels.samples.pong/.MainActivity \
 *         --ez autostart true --ei delay 3 --ei steps 60 --ei seed 7 --es backend gpu
 *
 * At DONE (or Stop) the run is written to getExternalFilesDir(null): pong-result-<epoch s>.json and the
 * exact PNG bytes the model got, frames-<epoch s>/NNN.png. Logcat tag `pong`.
 */
class MainActivity : ComponentActivity() {
    private companion object {
        const val TAG = "pong"
        const val REPO = "litert-community/decider-2b-vision-LiteRT"
        // The Hub commit the descriptor in assets was generated from.
        const val REVISION = "6c024e946bb8489f3faacb2514b0c61b355d1fdb"
        const val VARIANT = "int8"
        const val DESCRIPTOR_ASSET = "decider-2b-vision.hfmodels.json"
        const val SIDE = 256

        const val BACKGROUND = 0xFF0E1116.toInt()
        const val CARD = 0xFF1B2028.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val DIM = 0xFF8A919C.toInt()
        const val PILL_IDLE = 0xFF5F6368.toInt()
        const val PILL_PLAYING = 0xFFE53935.toInt()
        const val PILL_DONE = 0xFF2E7D32.toInt()
    }

    private enum class Phase { IDLE, LOADING, READY, PLAYING, DONE, FAILED }

    /** One answer from the model, with the times measured around it. */
    private class Decision(val text: String, val answerMs: Double?, val streamMs: Double, val conversationMs: Double, val closeMs: Double)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var models: HfModels
    private var chat: ChatModel? = null
    private var playJob: Job? = null

    private var recording = false
    private var steps = 60
    private var seed = 7
    private var policyName = "auto"
    private var phase = Phase.IDLE
    private var detail = ""
    private var done = 0
    private var lastAnswerMs: Double? = null
    private var loadMs = 0L
    private var loadDownloaded = false
    private var game: PongGame? = null
    private val prompt = Prompt.build()

    private var u = 1f
    private lateinit var pill: TextView
    private lateinit var count: TextView
    private lateinit var lastMs: TextView
    private lateinit var field: FieldView
    private lateinit var question: TextView
    private lateinit var answerMs: TextView
    private lateinit var optionRows: List<TextView>
    private lateinit var thumbnail: ImageView
    private lateinit var scoreModel: TextView
    private lateinit var scoreScript: TextView
    private lateinit var footer: TextView
    private lateinit var backend: Spinner
    private lateinit var load: Button
    private lateinit var play: Button
    private lateinit var stop: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        models = HfModels(applicationContext)
        val autostart = intent.getBooleanExtra("autostart", false)
        val delaySeconds = intent.getIntExtra("delay", 3)
        steps = intent.getIntExtra("steps", 60).coerceAtLeast(1)
        seed = intent.getIntExtra("seed", 7)
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
        field.show(PongGame(seed).state())
        render()
        if (autostart) {
            scope.launch {
                val model = loadModel() ?: return@launch
                delay(delaySeconds * 1000L)
                play(model)
            }
        }
    }

    private fun policy(): BackendPolicy = when (policyName) {
        "gpu" -> BackendPolicy.Require(BackendKind.GPU)
        "cpu" -> BackendPolicy.Require(BackendKind.CPU)
        else -> BackendPolicy.Auto
    }

    private fun mb(bytes: Long) = String.format(Locale.US, "%,d MB", bytes shr 20)

    /** Loads the model with the descriptor from assets; null (and FAILED on screen) when the load fails. */
    private suspend fun loadModel(): ChatModel? {
        chat?.let { return it }
        phase = Phase.LOADING; detail = "Resolving"; render()
        val descriptor = assets.open(DESCRIPTOR_ASSET).bufferedReader().use { it.readText() }
        val t0 = SystemClock.elapsedRealtime()
        return try {
            val model = models.fromPretrained(
                ModelRef(REPO, revision = REVISION, variant = VARIANT),
                Tasks.Chat,
                LoadOptions(descriptorJson = descriptor, backendPolicy = policy()),
            ) { e ->
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
            loadMs = SystemClock.elapsedRealtime() - t0
            chat = model
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

    private fun fail(reason: String) {
        Log.e(TAG, "FAILED $reason")
        phase = Phase.FAILED; detail = reason
        render()
    }

    /** One request: a new conversation, the image then the question, one output token, then close. */
    private suspend fun decide(model: ChatModel, png: ByteArray): Decision {
        val t0 = SystemClock.elapsedRealtimeNanos()
        val session = model.createConversation(ConversationConfig())
        val t1 = SystemClock.elapsedRealtimeNanos()
        val text = StringBuilder()
        var first = 0L
        var t2 = 0L
        try {
            session.stream(Contents.of(Content.ImageBytes(png), Content.Text(prompt)), GenerationOptions(maxOutputTokens = 1)).collect { m ->
                val piece = m.text
                if (piece.isNotEmpty() && first == 0L) first = SystemClock.elapsedRealtimeNanos()
                text.append(piece)
            }
            t2 = SystemClock.elapsedRealtimeNanos()
        } finally {
            withContext(NonCancellable) { session.closeAndJoin() }
        }
        val t3 = SystemClock.elapsedRealtimeNanos()
        return Decision(
            text = text.toString(),
            answerMs = if (first == 0L) null else (first - t1) / 1e6,
            streamMs = (t2 - t1) / 1e6,
            conversationMs = (t1 - t0) / 1e6,
            closeMs = (t3 - t2) / 1e6,
        )
    }

    private suspend fun play(model: ChatModel) {
        val g = PongGame(seed)
        game = g
        done = 0; lastAnswerMs = null
        val log = JSONArray()
        val frames = ArrayList<ByteArray>()
        val answers = ArrayList<Double>()
        val conversations = ArrayList<Double>()
        var unparsed = 0
        var stopped = true
        var failure: String? = null
        val thermalBefore = thermalStatus()
        val t0 = SystemClock.elapsedRealtime()
        phase = Phase.PLAYING; detail = ""
        field.show(g.state())
        showAnswer(null, null, null)
        render()
        try {
            for (n in 0 until steps) {
                val before = g.state()
                val png = withContext(Dispatchers.Default) {
                    Png.encode(Bicubic.resize(PongFrame.render(before), PongFrame.WIDTH, PongFrame.HEIGHT, SIDE, SIDE), SIDE, SIDE)
                }
                val d = withContext(Dispatchers.Default) { decide(model, png) }
                frames += png
                val choice = Answer.parse(d.text)
                if (choice == Answer.UNPARSED) unparsed++
                val action = if (choice == Answer.UNPARSED) PongGame.STAY else choice
                d.answerMs?.let { answers += it }
                conversations += d.conversationMs
                // The field finishes gliding into the state the model was shown, then the answer and the move appear together.
                delay(field.remainingGlideMs())
                lastAnswerMs = d.answerMs
                showAnswer(choice, d.answerMs, png)
                val event = g.step(action)
                field.glideTo(g.state(), jumpBall = event == PongEvent.MISS || event == PongEvent.LEFT_MISS)
                log.put(
                    JSONObject()
                        .put("step", n)
                        .put("letter", Answer.letter(choice))
                        .put("raw_text", d.text)
                        .put("action", action)
                        .put("answer_ms", d.answerMs?.let { round1(it) } ?: JSONObject.NULL)
                        .put("stream_ms", round1(d.streamMs))
                        .put("conversation_ms", round1(d.conversationMs))
                        .put("close_ms", round1(d.closeMs))
                        .put("state_before", stateJson(before))
                        .put("event", event?.label ?: JSONObject.NULL),
                )
                Log.i(TAG, "STEP $n letter=${Answer.letter(choice)} action=$action answer_ms=${d.answerMs?.let { round1(it) }} conv_ms=${round1(d.conversationMs)} event=${event?.label}")
                done = n + 1
                render()
            }
            stopped = false
        } catch (e: ModelException) {
            stopped = false
            failure = "${e.code}: ${e.reason}"
            Log.e(TAG, "FAILED $failure")
        } finally {
            val totalS = (SystemClock.elapsedRealtime() - t0) / 1000.0
            val thermalAfter = thermalStatus()
            withContext(NonCancellable) {
                val record = JSONObject()
                    .put("steps", steps)
                    .put("completed_steps", log.length())
                    .put("stopped", stopped)
                    .put("failure", failure ?: JSONObject.NULL)
                    .put("seed", seed)
                    .put("backend_policy", policyName)
                    .put("profile", model.info.profileId)
                    .put("variant", model.info.variantId)
                    .put("model", "${model.info.repoId}@${model.info.commit}")
                    .put("sdk_version", model.info.sdkVersion)
                    .put("runtime_version", model.info.runtimeVersion)
                    .put("device", JSONObject().put("model", Build.MODEL).put("display", Build.DISPLAY))
                    .put("load_ms", loadMs)
                    .put("load_downloaded", loadDownloaded)
                    .put("answer_ms_median", stat(answers) { median(it) })
                    .put("answer_ms_p90", stat(answers) { p90(it) })
                    .put("conversation_ms_median", stat(conversations) { median(it) })
                    .put("hits", g.hits)
                    .put("misses", g.misses)
                    .put("left_hits", g.leftHits)
                    .put("left_misses", g.leftMisses)
                    .put("serves", g.serves)
                    .put("unparsed", unparsed)
                    .put("thermal_before", thermalBefore)
                    .put("thermal_after", thermalAfter)
                    .put("total_s", round1(totalS))
                    .put("prompt", prompt)
                    .put("steps_log", log)
                val path = withContext(Dispatchers.IO) { writeRecord(record, frames) }
                Log.i(TAG, "DONE json=$path")
                phase = if (failure != null) Phase.FAILED else Phase.DONE
                detail = failure ?: if (stopped) "stopped" else ""
                render()
            }
        }
    }

    private fun writeRecord(record: JSONObject, frames: List<ByteArray>): String {
        val dir = getExternalFilesDir(null) ?: filesDir
        val epoch = System.currentTimeMillis() / 1000
        val framesDir = File(dir, "frames-$epoch").apply { mkdirs() }
        frames.forEachIndexed { i, bytes -> File(framesDir, String.format(Locale.US, "%03d.png", i)).writeBytes(bytes) }
        val file = File(dir, "pong-result-$epoch.json")
        file.writeText(record.toString(2))
        return file.absolutePath
    }

    private fun stateJson(s: PongState) = JSONObject()
        .put("ball", JSONArray().put(s.ballX).put(s.ballY))
        .put("v", JSONArray().put(s.vx).put(s.vy))
        .put("paddle", s.paddle)
        .put("left", s.left)

    private fun thermalStatus(): Int = getSystemService(PowerManager::class.java).currentThermalStatus
    private fun round1(x: Double) = Math.round(x * 10) / 10.0
    private fun stat(xs: List<Double>, f: (List<Double>) -> Double): Any = if (xs.isEmpty()) JSONObject.NULL else round1(f(xs))
    private fun median(xs: List<Double>): Double = xs.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }
    private fun p90(xs: List<Double>): Double = xs.sorted().let { s -> s[(ceil(0.9 * s.size).toInt() - 1).coerceIn(0, s.size - 1)] }

    // ---- the screen ----

    private fun px(v: Float) = v * u

    private fun label(size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size)); setTextColor(color); includeFontPadding = false
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
        fontFeatureSettings = "tnum"
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }

    /** One line that shrinks its type (down to [minSize]) rather than cut the text. */
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

        // (1) state pill, decision count, last answer time
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        pill = label(15f, WHITE, bold = true).apply { setPadding(px(10f).toInt(), px(6f).toInt(), px(10f).toInt(), px(6f).toInt()) }
        count = label(15f, WHITE, bold = true)
        lastMs = label(15f, DIM, bold = true).apply { gravity = Gravity.END }
        header.addView(pill)
        header.addView(count, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = px(10f).toInt() })
        header.addView(lastMs)
        content.addView(header)

        // (2) the field, with the paddle labels above its corners
        field = FieldView(this, u)
        content.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = px(6f).toInt() })

        // (3) the decision card: the question, the three options, the answer time, the image the model got
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 14f)
            setPadding(px(12f).toInt(), px(10f).toInt(), px(12f).toInt(), px(12f).toInt())
        }
        val qRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        question = fitted(label(15f, WHITE, bold = true).apply { text = Prompt.QUESTION }, 11f, 15f)
        answerMs = label(28f, WHITE, bold = true).apply { gravity = Gravity.END }
        qRow.addView(question, LinearLayout.LayoutParams(0, px(30f).toInt(), 1f))
        qRow.addView(answerMs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(8f).toInt() })
        card.addView(qRow)
        val aRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val options = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        optionRows = Prompt.OPTIONS.mapIndexed { i, o ->
            label(16f, DIM).apply {
                text = "(${'A' + i}) $o"
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(10f).toInt(), 0, px(10f).toInt(), 0)
                options.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(32f).toInt()).apply { topMargin = if (i == 0) 0 else px(2f).toInt() })
            }
        }
        aRow.addView(options, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        thumbnail = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY; setBackgroundColor(BACKGROUND) }
        aRow.addView(thumbnail, LinearLayout.LayoutParams(px(96f).toInt(), px(96f).toInt()).apply { leftMargin = px(10f).toInt() })
        card.addView(aRow, wrapParams(top = 8f))
        content.addView(card, wrapParams(top = 8f))

        // (4) the score line
        val scores = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scoreModel = fitted(label(13.5f, WHITE), 10f, 13.5f)
        scoreScript = fitted(label(13.5f, WHITE).apply { gravity = Gravity.END }, 10f, 13.5f)
        scores.addView(scoreModel, LinearLayout.LayoutParams(0, px(20f).toInt(), 1f))
        scores.addView(scoreScript, LinearLayout.LayoutParams(0, px(20f).toInt(), 1f).apply { leftMargin = px(8f).toInt() })
        content.addView(scores, wrapParams(top = 10f))

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
        play = Button(this).apply { text = "Play"; setOnClickListener { onPlay() } }
        stop = Button(this).apply { text = "Stop"; setOnClickListener { playJob?.cancel() } }
        controls.addView(backend, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(load); controls.addView(play); controls.addView(stop)
        if (!recording) content.addView(controls, wrapParams(top = 6f))

        setContentView(frame)
    }

    private fun onLoad() {
        policyName = backend.selectedItem as String
        scope.launch { loadModel() }
    }

    private fun onPlay() {
        val model = chat ?: return
        if (playJob?.isActive == true) return
        playJob = scope.launch { play(model) }
    }

    /** The card after an answer: the chosen row highlighted, the time, the exact image that was sent. */
    private fun showAnswer(choice: Int?, ms: Double?, png: ByteArray?) {
        optionRows.forEachIndexed { i, row ->
            val chosen = choice == i
            row.background = if (chosen) rounded(PongFrame.RIGHT_PADDLE, 8f) else null
            row.setTextColor(if (chosen) BACKGROUND else DIM)
            row.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(if (chosen) 19f else 16f))
            row.typeface = if (chosen) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val msText = ms?.let { String.format(Locale.US, "%,d ms", Math.round(it)) } ?: if (choice == null) "" else "no text"
        answerMs.text = if (choice == Answer.UNPARSED) "? · $msText" else msText
        if (png != null) {
            val bmp = BitmapFactory.decodeByteArray(png, 0, png.size)
            thumbnail.setImageDrawable(BitmapDrawable(resources, bmp).apply { isFilterBitmap = false })
        } else {
            thumbnail.setImageDrawable(null)
        }
    }

    private fun scoreText(name: String, color: Int, returned: Int, missed: Int): CharSequence = SpannableStringBuilder().apply {
        append(name, ForegroundColorSpan(color), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(StyleSpan(Typeface.BOLD), 0, name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        append("  returned $returned · missed $missed")
    }

    private fun render() {
        val (badge, color) = when (phase) {
            Phase.IDLE -> "IDLE" to PILL_IDLE
            Phase.LOADING -> "LOADING" to PILL_IDLE
            Phase.READY -> "READY" to PILL_IDLE
            Phase.PLAYING -> "● PLAYING" to PILL_PLAYING
            Phase.DONE -> "DONE" to PILL_DONE
            Phase.FAILED -> "FAILED" to PILL_PLAYING
        }
        pill.text = badge; pill.background = rounded(color, 100f)
        count.text = when (phase) {
            Phase.PLAYING, Phase.DONE -> "$done / $steps" + if (detail.isEmpty()) "" else " · $detail"
            Phase.IDLE -> "choose a backend, then Load"
            else -> detail
        }
        lastMs.text = lastAnswerMs?.let { String.format(Locale.US, "%,d ms", Math.round(it)) } ?: ""
        val g = game
        scoreModel.text = scoreText("model", PongFrame.RIGHT_PADDLE, g?.hits ?: 0, g?.misses ?: 0)
        scoreScript.text = scoreText("script", PongFrame.LEFT_PADDLE, g?.leftHits ?: 0, g?.leftMisses ?: 0)
        if (!recording) {
            load.isEnabled = phase == Phase.IDLE || phase == Phase.FAILED
            backend.isEnabled = load.isEnabled
            play.isEnabled = chat != null && phase != Phase.PLAYING && phase != Phase.LOADING
            stop.isEnabled = phase == Phase.PLAYING
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        chat?.close()
        models.close()
    }
}
