package io.github.johnrocky.hfmodels.samples.look

import android.app.ActivityManager
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.text.InputFilter
import android.text.TextUtils
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
import android.widget.ScrollView
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
import io.github.johnrocky.hfmodels.PreparedModelInfo
import java.io.File
import java.security.MessageDigest
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
 * Ask a vision-language model about a photo, on the phone, and watch the answer arrive. Pick a photo (the system
 * photo picker, no permission) or an example, type a question or tap one of the example questions, and Ask: the
 * answer streams onto the screen, and the line under it says when its first token came, how fast the rest
 * followed, on which backend, and whether the load before it made any network request.
 *
 * The first question about a photo opens a conversation with the photo and the question in one turn; a further
 * question about the same photo is a text-only turn of that conversation. One loaded model holds one
 * conversation ([Looker]): after a new photo or a Stop, the next Ask loads the model again.
 *
 * Recording mode runs the Example button and the Ask button's own steps, over the lock screen, screen kept on:
 *
 *     adb shell am start -n io.github.johnrocky.hfmodels.samples.look/.MainActivity \
 *         --ez autostart true --ei delay 3 --es example bicycle --es question "'What is this a photo of?'" \
 *         --es backend cpu --es network offline --es variant vl_int8vis
 *
 * Every answer is written to getExternalFilesDir(null): look-result-<epoch s>.json and the exact JPEG bytes the
 * model got, frames-<epoch s>/000.jpg. Logcat tag `look`.
 */
class MainActivity : ComponentActivity() {
    private companion object {
        const val TAG = "look"

        const val BACKGROUND = 0xFF0E1116.toInt()
        const val CARD = 0xFF1B2028.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val DIM = 0xFF8A919C.toInt()
        const val PILL_IDLE = 0xFF5F6368.toInt()
        const val PILL_ASKING = 0xFFE53935.toInt()
        const val PILL_DONE = 0xFF2E7D32.toInt()
        const val REFUSED = 0xFFE57373.toInt()
    }

    private enum class Phase { IDLE, LOADING, READY, ASKING, DONE, STOPPED, FAILED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var models: HfModels
    private lateinit var looker: Looker
    private var job: Job? = null

    private var recording = false
    private var policyName = "auto"
    private var networkName = "auto"
    private var phase = Phase.IDLE
    private var detail = ""
    /** The photo on screen, the picture object the model's conversation is keyed on (a new photo is a new one), and where it came from. */
    private var photo: Photo? = null
    private var picture: Picture? = null
    private var photoSource = ""
    private var example: Example? = null
    /** Loads already shown in the footer and the log. */
    private var shownLoads = 0

    private var u = 1f
    private lateinit var pill: TextView
    private lateinit var status: TextView
    private lateinit var photoView: ImageView
    private lateinit var placeholder: TextView
    private lateinit var pick: Button
    private lateinit var exampleButton: Button
    private lateinit var questionBox: EditText
    private lateinit var chips: List<TextView>
    private lateinit var backend: Spinner
    private lateinit var ask: Button
    private lateinit var stop: Button
    private lateinit var answerScroll: ScrollView
    private lateinit var answer: TextView
    private lateinit var numbers: TextView
    private lateinit var footer: TextView

    /** The system photo picker: no storage permission, the user picks one image. */
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { importPhoto(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Creates /sdcard/Android/data/<package>/files/, where a model copy pushed with adb is picked up.
        getExternalFilesDir(null)
        models = HfModels(applicationContext)
        val descriptor = assets.open(Looker.DESCRIPTOR_ASSET).bufferedReader().use { it.readText() }
        // `--es variant <id>` overrides the choice by RAM (Variants).
        val variant = intent.getStringExtra("variant") ?: Variants.forMemory(totalMem())
        looker = Looker(models, descriptor, variant)
        val autostart = intent.getBooleanExtra("autostart", false)
        val delaySeconds = intent.getIntExtra("delay", 3)
        policyName = when (intent.getStringExtra("backend")) { "gpu" -> "gpu"; "cpu" -> "cpu"; else -> "auto" }
        // `--es network offline`: the load makes no request (OFFLINE_CACHE_MISS when the model is not on the phone);
        // `any`: the SDK may ask the Hub; by default the phone's store first, the network only for a missing file.
        networkName = when (intent.getStringExtra("network")) { "offline" -> "offline"; "any" -> "any"; else -> "auto" }
        looker.policy = policy()
        looker.network = when (networkName) { "offline" -> NetworkPolicy.Offline; "any" -> NetworkPolicy.Any; else -> null }
        looker.onStage = ::onStage
        looker.onEvent = ::onLoadEvent
        recording = autostart
        if (recording) {
            // The phone may sleep behind a secure lock screen: an activity under the keyguard is kept out of the
            // foreground (and off the big CPU cores), so recording mode shows over it and keeps the screen on.
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        buildScreen()
        if (recording) {
            window.insetsController?.let { it.hide(WindowInsets.Type.systemBars()); it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
        }
        render()
        if (autostart) {
            val ex = Examples.photo(intent.getStringExtra("example")) ?: Examples.PHOTOS.first()
            val question = intent.getStringExtra("question")
            startJob {
                if (!loadFirst()) return@startJob
                delay(delaySeconds * 1000L)
                showExample(ex)
                if (question != null) questionBox.setText(question)
                askNow()
            }
        }
    }

    /** Every model job goes through here: one at a time, and the buttons follow its end. */
    private fun startJob(block: suspend () -> Unit) {
        val j = scope.launch { block() }
        job = j
        j.invokeOnCompletion { runOnUiThread { render() } }
        render()
    }

    private fun busy() = job?.isActive == true

    private fun policy(): BackendPolicy = when (policyName) {
        "gpu" -> BackendPolicy.Require(BackendKind.GPU)
        "cpu" -> BackendPolicy.Require(BackendKind.CPU)
        else -> BackendPolicy.Auto
    }

    private fun totalMem(): Long = ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.totalMem

    private fun mb(bytes: Long) = String.format(Locale.US, "%,d MB", bytes shr 20)

    private fun seconds(ms: Number) = String.format(Locale.US, "%.1f s", ms.toDouble() / 1000.0)

    // ---- the model ----

    private fun onStage(stage: Looker.Stage) {
        when (stage) {
            Looker.Stage.CLOSING -> { phase = Phase.LOADING; detail = "Closing the model that held the last conversation" }
            Looker.Stage.LOADING -> { phase = Phase.LOADING; detail = "Resolving" }
            Looker.Stage.OPENING -> { showLoaded(); phase = Phase.ASKING; detail = "Opening a conversation" }
            Looker.Stage.ASKING -> { phase = Phase.ASKING; detail = "Waiting for the first token" }
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
        val i = looker.model?.info ?: return
        if (looker.loads == shownLoads) return
        shownLoads = looker.loads
        Log.i(TAG, "READY profile=${i.profileId} load_ms=${looker.loadMs} variant=${i.variantId} commit=${i.commit.take(8)} " +
            "downloaded=${looker.downloaded} network=${looker.loadNetwork} backend=${backendLabel(i)}")
        footer.text = listOf(Looker.REPO, i.variantId, i.profileId, Build.MODEL, "LiteRT-LM ${i.runtimeVersion}", "hfmodels ${i.sdkVersion}").joinToString(" · ")
    }

    /** The backend the language model was initialized on, and the vision encoder's when it differs. */
    private fun backendLabel(i: PreparedModelInfo): String {
        val language = i.components["language"]?.initialized?.name ?: "?"
        val vision = i.components["vision"]?.initialized?.name
        return if (vision == null || vision == language) language else "$language, vision $vision"
    }

    /** The backend chosen on screen applies from the next load; a loaded model on another backend is closed first. */
    private suspend fun applyBackend() {
        if (recording) return
        val chosen = backend.selectedItem as String
        if (chosen == policyName) return
        policyName = chosen
        looker.policy = policy()
        looker.close()
    }

    /** Recording mode's load before the delay; false (and FAILED on screen) when it fails. */
    private suspend fun loadFirst(): Boolean = try {
        val m = looker.load()
        showLoaded()
        phase = Phase.READY
        detail = String.format(Locale.US, "profile %s · loaded in %.1f s", m.info.profileId, looker.loadMs / 1000.0)
        render()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        fail(Looker.describe(e))
        false
    }

    private fun fail(reason: String) {
        Log.e(TAG, "FAILED $reason")
        phase = Phase.FAILED; detail = reason
        render()
    }

    // ---- the photo ----

    private fun onPick() {
        if (busy()) return
        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun importPhoto(uri: Uri) {
        if (busy()) return
        startJob { usePhoto("picker", null) { PhotoImport.load(contentResolver, uri) } }
    }

    private fun onExample() {
        if (busy()) return
        startJob { showExample(Examples.next(example)) }
    }

    /** The Example button's step: the example photo through the picker's steps, and its question if the box is empty. */
    private suspend fun showExample(ex: Example) {
        usePhoto("example:${ex.id}", ex) { PhotoImport.loadAsset(assets, ex.asset) }
        if (photo != null && questionBox.text.isBlank()) questionBox.setText(ex.question)
    }

    private suspend fun usePhoto(source: String, ex: Example?, read: () -> Photo) {
        try {
            val p = withContext(Dispatchers.IO) { read() }
            photo = p
            picture = Picture(p.bytes)
            photoSource = source
            example = ex
            photoView.setImageBitmap(BitmapFactory.decodeByteArray(p.bytes, 0, p.bytes.size))
            Log.i(TAG, "PHOTO source=$source w=${p.width} h=${p.height} from=${p.sourceWidth}x${p.sourceHeight} bytes=${p.bytes.size}")
            clearAnswer()
            if (looker.heldConversation) say("A new photo: the next Ask loads the model again first (one conversation per load).", DIM)
            if (phase != Phase.LOADING) { phase = if (looker.model != null) Phase.READY else Phase.IDLE; detail = "" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "PHOTO failed: $e")
            say("Could not read that photo: ${Looker.describe(e)}", REFUSED)
        }
        render()
    }

    // ---- asking ----

    private fun onAsk() {
        if (busy()) return
        startJob { askNow() }
    }

    /**
     * The Ask button's steps: check the photo and the question, send them, stream the answer onto the screen,
     * then the numbers line, and write the record.
     */
    private suspend fun askNow() {
        val typed = Typed.check(picture != null, questionBox.text.toString())
        if (typed is Typed.Result.Refused) {
            Log.i(TAG, "REFUSED ${typed.message}")
            say(typed.message, REFUSED)
            return
        }
        val question = (typed as Typed.Result.Ready).question
        val pic = picture ?: return
        val sent = photo
        applyBackend()
        clearAnswer()
        val thermalBefore = thermalStatus()
        val t0 = SystemClock.elapsedRealtime()
        var reply: Reply? = null
        var failure: String? = null
        var stopped = false
        try {
            reply = looker.ask(pic, question) { piece, ms ->
                runOnUiThread {
                    if (answer.text.isEmpty()) {
                        detail = "Answering"
                        numbers.text = String.format(Locale.US, "first token %.2f s", ms / 1000.0)
                        render()
                    }
                    answer.append(piece)
                    answerScroll.post { answerScroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        } catch (e: CancellationException) {
            stopped = true
            throw e
        } catch (e: Exception) {
            failure = Looker.describe(e)
        } finally {
            withContext(NonCancellable) {
                val info = looker.model?.info
                val offline = looker.loadNetwork == NetworkPolicy.Offline
                val r = reply
                val line = if (r != null && info != null) Stats.line(r.timing, backendLabel(info), offline) else ""
                when {
                    r != null -> {
                        phase = Phase.DONE
                        detail = if (r.firstTurn) {
                            val opened = r.conversationMs?.let { " · conversation opened in ${seconds(it)}" } ?: ""
                            val loaded = r.loadMs?.let { " · model loaded in ${seconds(it)}" } ?: ""
                            "The photo and the question in one turn$opened$loaded"
                        } else {
                            "A text-only turn about the same photo"
                        }
                        numbers.text = line
                        if (r.text.isEmpty()) say("The model wrote no text.", REFUSED)
                    }
                    stopped -> {
                        phase = Phase.STOPPED
                        detail = "Stopped. That conversation is closed; the next Ask loads the model again and sends the photo with the question."
                    }
                    else -> fail(failure ?: "unknown failure")
                }
                Log.i(TAG, "ASK first_turn=${r?.firstTurn} first_token_ms=${r?.timing?.firstTokenMs?.let { round1(it) }} chunks=${r?.timing?.chunks} " +
                    "chunks_per_s=${r?.timing?.chunksPerSecond?.let { round1(it) }} stream_ms=${r?.timing?.streamMs?.let { round1(it) }} " +
                    "conversation_ms=${r?.conversationMs?.let { round1(it) }} load_ms=${r?.loadMs} stopped=$stopped failure=${failure?.let { JSONObject.quote(it) }} " +
                    "line=${JSONObject.quote(line)} text=${JSONObject.quote(r?.text ?: "")}")
                val record = JSONObject()
                    .put("question", question)
                    .put("photo", JSONObject()
                        .put("source", photoSource)
                        .put("sha256", sent?.let { sha256(it.bytes) } ?: JSONObject.NULL)
                        .put("bytes", sent?.bytes?.size ?: JSONObject.NULL)
                        .put("width", sent?.width ?: JSONObject.NULL)
                        .put("height", sent?.height ?: JSONObject.NULL)
                        .put("source_width", sent?.sourceWidth ?: JSONObject.NULL)
                        .put("source_height", sent?.sourceHeight ?: JSONObject.NULL))
                    .put("answer", r?.text ?: JSONObject.NULL)
                    .put("answer_chars", r?.timing?.chars ?: JSONObject.NULL)
                    .put("line", line)
                    .put("first_token_ms", r?.timing?.firstTokenMs?.let { round1(it) } ?: JSONObject.NULL)
                    .put("stream_ms", r?.timing?.streamMs?.let { round1(it) } ?: JSONObject.NULL)
                    .put("chunks", r?.timing?.chunks ?: JSONObject.NULL)
                    .put("all_chunks", r?.timing?.allChunks ?: JSONObject.NULL)
                    .put("chunks_per_s", r?.timing?.chunksPerSecond?.let { round2(it) } ?: JSONObject.NULL)
                    .put("chunk_ms", JSONArray(r?.timing?.chunkMs?.map { round1(it) } ?: emptyList<Double>()))
                    .put("first_turn", r?.firstTurn ?: JSONObject.NULL)
                    .put("conversation_ms", r?.conversationMs?.let { round1(it) } ?: JSONObject.NULL)
                    .put("load_ms", r?.loadMs ?: JSONObject.NULL)
                    .put("load_downloaded", looker.downloaded)
                    .put("load_network", looker.loadNetwork?.name ?: JSONObject.NULL)
                    .put("stopped", stopped)
                    .put("failure", failure ?: JSONObject.NULL)
                    .put("model", info?.let { "${it.repoId}@${it.commit}" } ?: JSONObject.NULL)
                    .put("variant", info?.variantId ?: looker.variant)
                    .put("profile", info?.profileId ?: JSONObject.NULL)
                    .put("components", JSONObject().apply { info?.components?.forEach { (k, v) -> put(k, v.initialized?.name ?: JSONObject.NULL) } })
                    .put("backend_policy", policyName)
                    .put("network_policy", networkName)
                    .put("max_output_tokens", Looker.MAX_OUTPUT_TOKENS)
                    .put("sdk_version", info?.sdkVersion ?: JSONObject.NULL)
                    .put("runtime_version", info?.runtimeVersion ?: JSONObject.NULL)
                    .put("device", JSONObject().put("model", Build.MODEL).put("display", Build.DISPLAY).put("soc", Build.SOC_MODEL).put("total_mem", totalMem()))
                    .put("thermal_before", thermalBefore)
                    .put("thermal_after", thermalStatus())
                    .put("vmhwm_kb", vmHwmKb() ?: JSONObject.NULL)
                    .put("total_s", round1((SystemClock.elapsedRealtime() - t0) / 1000.0))
                val path = withContext(Dispatchers.IO) { runCatching { writeRecord(record, sent?.bytes) }.getOrElse { "not written ($it)" } }
                Log.i(TAG, "DONE json=$path")
                render()
            }
        }
    }

    private fun writeRecord(record: JSONObject, jpeg: ByteArray?): String {
        val dir = getExternalFilesDir(null) ?: filesDir
        var epoch = System.currentTimeMillis() / 1000
        while (File(dir, "look-result-$epoch.json").exists()) epoch++
        if (jpeg != null) File(File(dir, "frames-$epoch").apply { mkdirs() }, "000.jpg").writeBytes(jpeg)
        val file = File(dir, "look-result-$epoch.json")
        file.writeText(record.toString(2))
        return file.absolutePath
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun thermalStatus(): Int = getSystemService(PowerManager::class.java).currentThermalStatus

    /** The process's peak resident set so far (VmHWM in /proc/self/status), in kB. */
    private fun vmHwmKb(): Long? = runCatching {
        File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmHWM:") }?.filter { it.isDigit() }?.toLong()
    }.getOrNull()

    private fun round1(x: Double) = Math.round(x * 10) / 10.0
    private fun round2(x: Double) = Math.round(x * 100) / 100.0

    private fun clearAnswer() {
        answer.text = ""
        answer.setTextColor(WHITE)
        numbers.text = ""
    }

    /** A sentence in the answer area that is not the model's: a refusal, a failure to read a photo, a note. */
    private fun say(text: String, color: Int) {
        answer.text = text
        answer.setTextColor(color)
        numbers.text = ""
    }

    // ---- the screen ----

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
            // The keyboard's inset too: the question box stays above it while you type (the photo gives up the room).
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            content.setPadding(px(16f).toInt(), bars.top + px(8f).toInt(), px(16f).toInt(), bars.bottom + px(8f).toInt())
            footer.visibility = if (insets.isVisible(WindowInsets.Type.ime())) View.GONE else View.VISIBLE
            insets
        }

        // (1) state pill and what is happening
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        pill = label(15f, WHITE, bold = true).apply { setPadding(px(10f).toInt(), px(5f).toInt(), px(10f).toInt(), px(5f).toInt()) }
        status = label(14f, DIM).apply { maxLines = 2; gravity = Gravity.CENTER_VERTICAL }
        header.addView(pill)
        header.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = px(10f).toInt() })
        content.addView(header)

        // (2) the photo: the JPEG bytes the model gets
        val slot = FrameLayout(this)
        photoView = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        placeholder = label(20f, DIM, bold = true).apply {
            text = "Pick a photo"; gravity = Gravity.CENTER; background = rounded(CARD, 14f)
            setOnClickListener { onPick() }
        }
        slot.addView(photoView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        slot.addView(placeholder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        content.addView(slot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.1f).apply { topMargin = px(6f).toInt() })

        // (3) Pick a photo | Example
        val photoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pick = Button(this).apply { text = "Pick a photo"; setOnClickListener { onPick() } }
        exampleButton = Button(this).apply { text = "Example"; setOnClickListener { onExample() } }
        photoRow.addView(pick, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        photoRow.addView(exampleButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = px(8f).toInt() })
        content.addView(photoRow, wrapParams(top = 4f))

        // (4) the question box and the example questions (a tap puts one in the box)
        questionBox = EditText(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(17f)); setTextColor(WHITE); setHintTextColor(DIM)
            hint = "Your question about the photo"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            filters = arrayOf(InputFilter.LengthFilter(Typed.MAX_QUESTION_CHARS))
            background = rounded(CARD, 10f)
            setPadding(px(10f).toInt(), 0, px(10f).toInt(), 0)
        }
        content.addView(questionBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(42f).toInt()).apply { topMargin = px(4f).toInt() })
        chips = Examples.QUESTIONS.map { q ->
            label(14f, WHITE).apply {
                text = q; gravity = Gravity.CENTER_VERTICAL
                background = rounded(CARD, 100f)
                setPadding(px(12f).toInt(), 0, px(12f).toInt(), 0)
                setOnClickListener { if (!busy()) { questionBox.setText(q); questionBox.setSelection(q.length) } }
            }.also { chip ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(28f).toInt()))
                content.addView(row, wrapParams(top = 4f))
            }
        }

        // (5) backend, Ask, Stop
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        backend = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("auto", "gpu", "cpu"))
            setSelection(listOf("auto", "gpu", "cpu").indexOf(policyName))
        }
        ask = Button(this).apply { text = "Ask"; setOnClickListener { onAsk() } }
        stop = Button(this).apply { text = "Stop"; setOnClickListener { job?.cancel() } }
        controls.addView(backend, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(ask); controls.addView(stop)
        content.addView(controls, wrapParams(top = 4f))

        // (6) the answer as it streams, in large type
        answerScroll = ScrollView(this).apply { background = rounded(CARD, 14f) }
        answer = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(21f)); setTextColor(WHITE)
            setLineSpacing(0f, 1.1f)
            setPadding(px(12f).toInt(), px(10f).toInt(), px(12f).toInt(), px(10f).toInt())
        }
        answerScroll.addView(answer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(answerScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = px(6f).toInt() })

        // (7) the numbers: first token, speed, backend, whether the load made a request; one line, the type shrinks to fit
        numbers = label(16f, WHITE, bold = true).apply {
            setAutoSizeTextTypeUniformWithConfiguration(px(10f).toInt(), px(16f).toInt(), 1, TypedValue.COMPLEX_UNIT_PX)
            gravity = Gravity.CENTER_VERTICAL
        }
        content.addView(numbers, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(24f).toInt()).apply { topMargin = px(8f).toInt() })

        // (8) model, variant, profile, phone, runtime and SDK versions (filled in once loaded)
        footer = label(11f, DIM).apply { maxLines = 2; text = "${Looker.REPO} · ${looker.variant} · ${Build.MODEL}" }
        content.addView(footer, wrapParams(top = 6f))

        setContentView(frame)
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
        status.text = when {
            detail.isNotEmpty() -> detail
            photo == null -> "Pick a photo or an example, then ask about it"
            else -> "Type a question or tap an example, then Ask"
        }
        photoView.visibility = if (photo != null) View.VISIBLE else View.GONE
        placeholder.visibility = if (photo == null) View.VISIBLE else View.GONE
        val busy = busy()
        backend.isEnabled = !busy && !recording
        pick.isEnabled = !busy
        exampleButton.isEnabled = !busy
        ask.isEnabled = !busy
        stop.isEnabled = busy && (phase == Phase.LOADING || phase == Phase.ASKING)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        looker.closeNow()
        models.close()
    }
}
