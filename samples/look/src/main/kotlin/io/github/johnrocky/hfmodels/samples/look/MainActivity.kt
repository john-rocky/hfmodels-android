package io.github.johnrocky.hfmodels.samples.look

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.text.InputFilter
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.StyleSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
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
 * Ask a vision-language model about a photo, on the phone, and watch the answer arrive. Take a photo, pick one (the
 * system photo picker, no permission) or tap one of the examples under the photo; type a question or tap one of the
 * example questions; Ask. The answer streams onto the screen, and the line under it says when its first token came,
 * how fast the rest followed, on which backend, and whether the load before it made any network request.
 *
 * The model loads when the app starts, and a conversation is opened as soon as it has: READY means the next question
 * goes straight to the model. The first question about a photo is that conversation's first turn (the photo and the
 * text); a further question about the same photo is a text-only turn. One loaded model holds one conversation
 * ([Looker]): after a new photo or a Stop the app closes the model, loads it again and opens a new conversation, and
 * the state line says so.
 *
 * Take mode is the screen for a recording driven by taps (adb `input tap` goes through the same code as a finger): it
 * shows over the lock screen with the screen kept on and the status bar visible, nothing runs by itself, and
 * everything from the status bar down to the numbers line sits in the top 9:16 of the screen (1080 x 1920 on a
 * 1080-pixel-wide phone); the line with the model and the versions is below it, without the phone's name.
 *
 *     adb shell am start -n io.github.johnrocky.hfmodels.samples.look/.MainActivity \
 *         --ez take true --es backend gpu --es variant vl_int8 --es network offline
 *
 * Recording mode runs one example and its question by itself (a check, not a take): over the lock screen, system
 * bars hidden, screen kept on, it waits `delay` seconds after READY, then does what a tap on the example and a tap
 * on Ask do:
 *
 *     adb shell am start -n io.github.johnrocky.hfmodels.samples.look/.MainActivity \
 *         --ez autostart true --ei delay 3 --es example sign --es question "'What does this sign say?'" \
 *         --es backend gpu --es network offline --es variant vl_int8
 *
 * `backend` is auto, gpu, cpu or a profile id of the descriptor (gpu_vision_cpu). Every answer is written to
 * getExternalFilesDir(null): look-result-<epoch s>.json and the exact JPEG bytes the model got,
 * frames-<epoch s>/000.jpg. Logcat tag `look`: READY, BOUNDS, PHOTO, ASK and DONE lines.
 */
class MainActivity : ComponentActivity() {
    private companion object {
        const val TAG = "look"

        const val BACKGROUND = 0xFF0E1116.toInt()
        const val CARD = 0xFF1B2028.toInt()
        const val BUTTON = 0xFF2A313C.toInt()
        const val ACCENT = 0xFF2F6FED.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val DIM = 0xFF8A919C.toInt()
        const val PILL_IDLE = 0xFF5F6368.toInt()
        const val PILL_ASKING = 0xFFE53935.toInt()
        const val PILL_DONE = 0xFF2E7D32.toInt()
        const val REFUSED = 0xFFE57373.toInt()

        /** Where the camera app writes its shot: the app's cache, shared through the FileProvider of the manifest. */
        const val CAMERA_FILE = "camera/look-camera.jpg"
        const val STATE_CAMERA_URI = "camera_uri"
        val BACKENDS = listOf("auto", "gpu", "cpu")
        const val FETCH_HINT = "The example photos are not in this build: run samples/look/tools/fetch_examples.py, then build again."
    }

    private enum class Phase { IDLE, LOADING, READY, ASKING, DONE, STOPPED, FAILED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var models: HfModels
    private lateinit var looker: Looker
    /** The model's work (load and open, or a question): one job at a time. */
    private var job: Job? = null
    /** True while [job] is a question. */
    private var asking = false
    /** Set when Stop ends a question: once that job has ended, the model is loaded again for the next one. */
    private var reloadAfterStop = false

    private var recording = false
    private var take = false
    private var policyName = "auto"
    private var networkName = "auto"
    private var phase = Phase.IDLE
    private var detail = ""
    /** Why the model is loading (shown before the SDK's load events); empty once it is ready. */
    private var why = ""
    /** The photo on screen, the picture object the model's conversation is keyed on (a new photo is a new one), and where it came from. */
    private var photo: Photo? = null
    private var picture: Picture? = null
    private var photoSource = ""
    private var cameraUri: Uri? = null
    private var examplesMissing = false
    /** The answer as the model writes it; the screen shows [AnswerText.render] of it. */
    private val raw = StringBuilder()

    private var u = 1f
    private lateinit var pill: TextView
    private lateinit var status: TextView
    private lateinit var slot: FrameLayout
    private lateinit var photoView: ImageView
    private lateinit var placeholder: TextView
    private lateinit var thumbs: List<ImageView>
    private lateinit var pick: TextView
    private lateinit var camera: TextView
    private lateinit var questionBox: EditText
    private lateinit var chips: List<TextView>
    private lateinit var backend: Spinner
    private lateinit var ask: TextView
    private lateinit var stop: TextView
    private lateinit var answerScroll: ScrollView
    private lateinit var answer: TextView
    private lateinit var numbers: TextView
    private lateinit var footer: TextView

    /** The system photo picker: no storage permission, the user picks one image. */
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { choosePhoto("picker") { PhotoImport.load(contentResolver, it) } }
    }

    /** The phone's camera app, writing into the app's cache through the FileProvider: no camera permission. */
    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = cameraUri
        if (saved && uri != null) choosePhoto("camera") { PhotoImport.load(contentResolver, uri) } else Log.i(TAG, "CAMERA no photo saved=$saved")
    }

    private val mode get() = if (take) "take" else if (recording) "recording" else "normal"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Creates /sdcard/Android/data/<package>/files/, where a model copy pushed with adb is picked up.
        getExternalFilesDir(null)
        cameraUri = savedInstanceState?.getString(STATE_CAMERA_URI)?.let(Uri::parse)
        models = HfModels(applicationContext)
        val descriptor = assets.open(Looker.DESCRIPTOR_ASSET).bufferedReader().use { it.readText() }
        // `--es variant <id>` overrides the choice by RAM (Variants).
        val variant = intent.getStringExtra("variant") ?: Variants.forMemory(totalMem())
        looker = Looker(models, descriptor, variant)
        recording = intent.getBooleanExtra("autostart", false)
        take = intent.getBooleanExtra("take", false)
        val delaySeconds = intent.getIntExtra("delay", 3)
        policyName = intent.getStringExtra("backend")?.trim()?.takeIf { it.isNotEmpty() } ?: "auto"
        // `--es network offline`: the load makes no request (OFFLINE_CACHE_MISS when the model is not on the phone);
        // `any`: the SDK may ask the Hub; by default the phone's store first, the network only for a missing file.
        networkName = when (intent.getStringExtra("network")) { "offline" -> "offline"; "any" -> "any"; else -> "auto" }
        looker.policy = policy()
        looker.network = when (networkName) { "offline" -> NetworkPolicy.Offline; "any" -> NetworkPolicy.Any; else -> null }
        looker.onStage = ::onStage
        looker.onEvent = ::onLoadEvent
        if (recording || take) {
            // The phone may sleep behind a secure lock screen: an activity under the keyguard is kept out of the
            // foreground (and off the big CPU cores), so these modes show over it and keep the screen on.
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        buildScreen()
        if (recording && !take) {
            window.insetsController?.let { it.hide(WindowInsets.Type.systemBars()); it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
        }
        render()
        loadThumbnails()
        val example = if (recording) Examples.photo(intent.getStringExtra("example")) ?: Examples.PHOTOS.first() else null
        val question = intent.getStringExtra("question")
        startJob {
            if (!prepareNow("Loading the model") || example == null) return@startJob
            delay(delaySeconds * 1000L)
            if (!usePhoto("example:${example.id}") { PhotoImport.loadAsset(assets, example.asset) }) return@startJob
            questionBox.setText(question ?: example.question)
            askNow()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        cameraUri?.let { outState.putString(STATE_CAMERA_URI, it.toString()) }
    }

    /** Every model job goes through here: one at a time, and the buttons follow its end. */
    private fun startJob(block: suspend () -> Unit) {
        val j = scope.launch { block() }
        job = j
        j.invokeOnCompletion {
            runOnUiThread {
                render()
                if (reloadAfterStop && !isDestroyed) {
                    reloadAfterStop = false
                    startJob { prepareNow("Stopped: loading the model again (one conversation per load)") }
                }
            }
        }
        render()
    }

    private fun busy() = job?.isActive == true

    private fun policy(): BackendPolicy = when (policyName) {
        "auto" -> BackendPolicy.Auto
        "gpu" -> BackendPolicy.Require(BackendKind.GPU)
        "cpu" -> BackendPolicy.Require(BackendKind.CPU)
        else -> BackendPolicy.RequireProfile(policyName)
    }

    private fun totalMem(): Long = ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.totalMem

    private fun mb(bytes: Long) = String.format(Locale.US, "%,d MB", bytes shr 20)

    private fun seconds(ms: Number) = String.format(Locale.US, "%.1f s", ms.toDouble() / 1000.0)

    // ---- the model ----

    /** Loads (again) and opens a conversation: READY on screen when it is open, FAILED with the reason when not. */
    private suspend fun prepareNow(reason: String): Boolean {
        why = reason
        phase = Phase.LOADING
        detail = reason
        render()
        return try {
            looker.prepare()
            true
        } catch (e: CancellationException) {
            phase = Phase.STOPPED
            detail = "Stopped before the model was ready; Ask loads it."
            why = ""
            render()
            throw e
        } catch (e: Exception) {
            why = ""
            fail(Looker.describe(e))
            false
        }
    }

    private fun onStage(stage: Looker.Stage) {
        when (stage) {
            Looker.Stage.CLOSING -> { phase = Phase.LOADING; detail = withWhy("closing the model that held the last conversation") }
            Looker.Stage.LOADING -> { phase = Phase.LOADING; detail = withWhy("resolving") }
            Looker.Stage.OPENING -> { footer.text = footerText(looker.model?.info); phase = Phase.LOADING; detail = withWhy("opening a conversation") }
            Looker.Stage.READY -> {
                why = ""
                phase = Phase.READY
                val i = looker.model?.info
                detail = if (photo == null && i != null) String.format(Locale.US, "profile %s · model loaded in %.1f s", i.profileId, looker.loadMs / 1000.0) else ""
                logReady()
            }
            Looker.Stage.ASKING -> { phase = Phase.ASKING; detail = "Waiting for the first token" }
        }
        Log.i(TAG, "stage $stage")
        render()
    }

    private fun withWhy(what: String) = if (why.isEmpty()) what.replaceFirstChar { it.uppercase() } else "$why · $what"

    private fun onLoadEvent(e: LoadEvent) {
        // Delivered on the caller's dispatcher (the SDK's promise); runOnUiThread keeps the views safe regardless.
        runOnUiThread {
            val what = when (e) {
                is LoadEvent.Resolving -> "resolving"
                is LoadEvent.DownloadStarted -> "downloading ${mb(e.totalBytes)}"
                is LoadEvent.Downloading -> "downloading ${mb(e.bytes)} / ${mb(e.totalBytes)}"
                is LoadEvent.Verifying -> "verifying sha256"
                is LoadEvent.Initializing -> "initializing profile ${e.profileId}"
                is LoadEvent.Fallback -> "fallback: ${e.reason}"
                is LoadEvent.Ready -> "loaded: profile ${e.info.profileId}"
            }
            detail = withWhy(what)
            if (e !is LoadEvent.Downloading) Log.i(TAG, "load $what")
            render()
        }
    }

    /** The READY log line, once per opened conversation, and where things are on the screen once it is laid out. */
    private fun logReady() {
        val i = looker.model?.info ?: return
        Log.i(TAG, "READY profile=${i.profileId} load_ms=${looker.loadMs} open_ms=${round1(looker.openMs)} variant=${i.variantId} " +
            "commit=${i.commit.take(8)} downloaded=${looker.downloaded} network=${looker.loadNetwork} backend=${backendLabel(i)} " +
            "components=${i.components.entries.joinToString(",") { "${it.key}=${it.value.initialized}" }} fallback=${i.fallbackHistory} " +
            "loads=${looker.loads} mode=$mode")
        scope.launch { delay(400); logBounds("ready") }
    }

    /** The model, variant, profile, runtime and SDK; the phone's name too, but not in take mode (the record has it). */
    private fun footerText(i: PreparedModelInfo?): String = listOfNotNull(
        Looker.REPO,
        i?.variantId ?: looker.variant,
        i?.profileId,
        Build.MODEL.takeUnless { take },
        i?.let { "LiteRT-LM ${it.runtimeVersion}" },
        i?.let { "hfmodels ${it.sdkVersion}" },
    ).joinToString(" · ")

    /** The backend the language model was initialized on, and the vision encoder's when it differs. */
    private fun backendLabel(i: PreparedModelInfo): String {
        val language = i.components["language"]?.initialized?.name ?: "?"
        val vision = i.components["vision"]?.initialized?.name
        return if (vision == null || vision == language) language else "$language, vision $vision"
    }

    private fun fail(reason: String) {
        Log.e(TAG, "FAILED $reason")
        phase = Phase.FAILED; detail = reason
        render()
    }

    // ---- the photo ----

    private fun onPick() {
        if (asking) return
        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun onCamera() {
        if (asking) return
        val file = File(cacheDir, CAMERA_FILE)
        file.parentFile?.mkdirs()
        file.delete()
        val uri = FileProvider.getUriForFile(this, "$packageName.photos", file)
        cameraUri = uri
        try {
            takePicture.launch(uri)
        } catch (e: ActivityNotFoundException) {
            say("This phone has no camera app to take the photo with.", REFUSED)
        }
    }

    private fun onExample(ex: Example) {
        if (asking) return
        choosePhoto("example:${ex.id}") { PhotoImport.loadAsset(assets, ex.asset) }
    }

    /**
     * A photo chosen by a tap (an example, the picker, the camera): it goes on screen, and when the loaded model has
     * already held a conversation, the model is loaded again for it right away (one conversation per load).
     */
    private fun choosePhoto(source: String, read: () -> Photo) {
        if (asking) return
        scope.launch {
            if (!usePhoto(source, read)) return@launch
            if (looker.heldConversation) {
                if (!busy()) startJob { prepareNow("A new photo: loading the model again (one conversation per load)") }
            } else if (!busy() && looker.ready) {
                phase = Phase.READY
                detail = ""
            }
            render()
        }
    }

    /** Reads a photo through PhotoImport and shows it; false, with the reason on screen, when it cannot be read. */
    private suspend fun usePhoto(source: String, read: () -> Photo): Boolean = try {
        val p = withContext(Dispatchers.IO) { read() }
        photo = p
        picture = Picture(p.bytes)
        photoSource = source
        photoView.setImageBitmap(BitmapFactory.decodeByteArray(p.bytes, 0, p.bytes.size))
        Log.i(TAG, "PHOTO source=$source w=${p.width} h=${p.height} from=${p.sourceWidth}x${p.sourceHeight} bytes=${p.bytes.size} sha256=${sha256(p.bytes)}")
        clearAnswer()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "PHOTO failed source=$source: $e")
        say(if (source.startsWith("example:") && examplesMissing) FETCH_HINT else "Could not read that photo: ${Looker.describe(e)}", REFUSED)
        false
    } finally {
        render()
    }

    private fun loadThumbnails() {
        val longSide = (px(if (take) 56f else 64f) * 2).toInt()
        scope.launch {
            var missing = 0
            for ((i, ex) in Examples.PHOTOS.withIndex()) {
                val bitmap = withContext(Dispatchers.IO) { runCatching { PhotoImport.thumbnail(assets, ex.asset, longSide) }.getOrNull() }
                if (bitmap != null) thumbs[i].setImageBitmap(bitmap) else missing++
            }
            examplesMissing = missing > 0
            if (examplesMissing) Log.w(TAG, "EXAMPLES missing=$missing of ${Examples.PHOTOS.size}: $FETCH_HINT")
        }
    }

    // ---- asking ----

    private fun onChip(q: String) {
        if (asking) return
        questionBox.setText(q)
        questionBox.setSelection(q.length)
    }

    private fun onAsk() {
        if (busy()) return
        startJob { askNow() }
    }

    private fun onStopTap() {
        val j = job?.takeIf { it.isActive } ?: return
        if (asking) reloadAfterStop = true
        j.cancel()
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
        clearAnswer()
        asking = true
        render()
        val thermalBefore = thermalStatus()
        val t0 = SystemClock.elapsedRealtime()
        var reply: Reply? = null
        var failure: String? = null
        var stopped = false
        try {
            reply = looker.ask(pic, question) { piece, ms ->
                runOnUiThread {
                    if (raw.isEmpty()) {
                        detail = "Answering"
                        numbers.text = String.format(Locale.US, "first token %.2f s", ms / 1000.0)
                        render()
                    }
                    raw.append(piece)
                    showAnswer()
                }
            }
        } catch (e: CancellationException) {
            stopped = true
            throw e
        } catch (e: Exception) {
            failure = Looker.describe(e)
        } finally {
            withContext(NonCancellable) {
                asking = false
                val info = looker.model?.info
                val offline = looker.loadNetwork == NetworkPolicy.Offline
                val r = reply
                val line = if (r != null && info != null) Stats.line(r.timing, backendLabel(info), offline) else ""
                when {
                    r != null -> {
                        phase = Phase.DONE
                        detail = when {
                            !r.firstTurn -> "A text-only turn about the same photo"
                            r.waitedForLoad -> "The photo and the question in one turn · it waited for the load (${seconds(r.loadMs)}) and the opening (${seconds(r.openMs)})"
                            else -> "The photo and the question in one turn"
                        }
                        raw.setLength(0)
                        raw.append(r.text)
                        showAnswer()
                        numbers.text = line
                        if (r.text.isEmpty()) say("The model wrote no text.", REFUSED)
                    }
                    stopped -> {
                        phase = Phase.STOPPED
                        detail = "Stopped. That conversation is closed; the model loads again for the next question."
                    }
                    else -> fail(failure ?: "unknown failure")
                }
                Log.i(TAG, "ASK first_turn=${r?.firstTurn} first_token_ms=${r?.timing?.firstTokenMs?.let { round1(it) }} chunks=${r?.timing?.chunks} " +
                    "chunks_per_s=${r?.timing?.chunksPerSecond?.let { round1(it) }} stream_ms=${r?.timing?.streamMs?.let { round1(it) }} " +
                    "load_ms=${r?.loadMs} open_ms=${r?.openMs?.let { round1(it) }} waited_for_load=${r?.waitedForLoad} stopped=$stopped " +
                    "failure=${failure?.let { JSONObject.quote(it) }} line=${JSONObject.quote(line)} text=${JSONObject.quote(r?.text ?: "")}")
                val record = JSONObject()
                    .put("mode", mode)
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
                    .put("answer_shown", r?.let { AnswerText.render(it.text).text } ?: JSONObject.NULL)
                    .put("answer_chars", r?.timing?.chars ?: JSONObject.NULL)
                    .put("line", line)
                    .put("backend", info?.let { backendLabel(it) } ?: JSONObject.NULL)
                    .put("rate_unit", Stats.RATE_UNIT)
                    .put("first_token_ms", r?.timing?.firstTokenMs?.let { round1(it) } ?: JSONObject.NULL)
                    .put("stream_ms", r?.timing?.streamMs?.let { round1(it) } ?: JSONObject.NULL)
                    .put("chunks", r?.timing?.chunks ?: JSONObject.NULL)
                    .put("all_chunks", r?.timing?.allChunks ?: JSONObject.NULL)
                    .put("chunks_per_s", r?.timing?.chunksPerSecond?.let { round2(it) } ?: JSONObject.NULL)
                    .put("chunk_ms", JSONArray(r?.timing?.chunkMs?.map { round1(it) } ?: emptyList<Double>()))
                    .put("first_turn", r?.firstTurn ?: JSONObject.NULL)
                    .put("load_ms", r?.loadMs ?: looker.loadMs.takeIf { looker.loads > 0 } ?: JSONObject.NULL)
                    .put("open_ms", r?.openMs?.let { round1(it) } ?: JSONObject.NULL)
                    .put("waited_for_load", r?.waitedForLoad ?: JSONObject.NULL)
                    .put("loads", looker.loads)
                    .put("load_downloaded", looker.downloaded)
                    .put("load_network", looker.loadNetwork?.name ?: JSONObject.NULL)
                    .put("stopped", stopped)
                    .put("failure", failure ?: JSONObject.NULL)
                    .put("model", info?.let { "${it.repoId}@${it.commit}" } ?: JSONObject.NULL)
                    .put("variant", info?.variantId ?: looker.variant)
                    .put("profile", info?.profileId ?: JSONObject.NULL)
                    .put("components", JSONObject().apply { info?.components?.forEach { (k, v) -> put(k, v.initialized?.name ?: JSONObject.NULL) } })
                    .put("fallback", JSONArray(info?.fallbackHistory ?: emptyList<String>()))
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
                if (r != null) scope.launch { delay(400); logBounds("done") }
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

    /** The answer so far, as [AnswerText] shows it. */
    private fun showAnswer() {
        val shown = AnswerText.render(raw.toString())
        val s = SpannableStringBuilder(shown.text)
        for (r in shown.bold) s.setSpan(StyleSpan(Typeface.BOLD), r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        answer.setTextColor(WHITE)
        answer.text = s
    }

    /** While the answer fits, its first line stays at the top; once it is taller than the box, its last line stays in view. */
    private fun keepAnswerInView() {
        val y = (answer.height - answerScroll.height).coerceAtLeast(0)
        if (answerScroll.scrollY != y) answerScroll.scrollTo(0, y)
    }

    private fun clearAnswer() {
        raw.setLength(0)
        answer.text = ""
        answer.setTextColor(WHITE)
        numbers.text = ""
    }

    /** A sentence in the answer area that is not the model's: a refusal, a failure to read a photo, a note. */
    private fun say(text: String, color: Int) {
        raw.setLength(0)
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

    /** A flat button: its text, a rounded fill, the touch ripple. */
    private fun button(text: String, size: Float, fill: Int, onClick: () -> Unit): TextView = label(size, WHITE, bold = true).apply {
        this.text = text
        gravity = Gravity.CENTER
        setPadding(px(8f).toInt(), 0, px(8f).toInt(), 0)
        background = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), rounded(fill, 10f), null)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun params(width: Int, height: Int, weight: Float = 0f, top: Float = 0f) =
        LinearLayout.LayoutParams(width, height, weight).apply { topMargin = px(top).toInt() }

    private fun buildScreen() {
        // Laid out in units of the screen width / 402, so a recording scaled to any width keeps its proportions.
        val dm = resources.displayMetrics
        u = dm.widthPixels / 402f
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val root = FrameLayout(this).apply { setBackgroundColor(BACKGROUND) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Nothing holds the focus at first: no cursor in the question box until it is touched.
            isFocusableInTouchMode = true
        }
        footer = label(11f, DIM).apply { maxLines = 2; text = footerText(null) }
        if (take) {
            // Everything a recording needs, from the status bar down to the numbers line, sits in the top 9:16 of the
            // screen; the model and versions line is below it, outside a 9:16 crop.
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            column.addView(content, LinearLayout.LayoutParams(match, minOf(dm.heightPixels, Math.round(dm.widthPixels * 16f / 9f))))
            column.addView(footer, LinearLayout.LayoutParams(match, wrap).apply { setMargins(px(16f).toInt(), px(4f).toInt(), px(16f).toInt(), 0) })
            root.addView(column, FrameLayout.LayoutParams(match, match))
        } else {
            root.addView(content, FrameLayout.LayoutParams(match, match))
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            // The keyboard's inset too: the question box stays above it while you type (the photo gives up the room).
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            content.setPadding(px(16f).toInt(), bars.top + px(if (take) 6f else 8f).toInt(), px(16f).toInt(), if (take) px(6f).toInt() else bars.bottom + px(8f).toInt())
            if (!take) footer.visibility = if (insets.isVisible(WindowInsets.Type.ime())) View.GONE else View.VISIBLE
            insets
        }

        // (1) state pill and what is happening
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        pill = label(15f, WHITE, bold = true).apply { setPadding(px(10f).toInt(), px(5f).toInt(), px(10f).toInt(), px(5f).toInt()) }
        status = label(14f, DIM).apply { maxLines = if (take) 1 else 2; gravity = Gravity.CENTER_VERTICAL }
        header.addView(pill)
        header.addView(status, LinearLayout.LayoutParams(0, wrap, 1f).apply { leftMargin = px(10f).toInt() })
        content.addView(header)

        // (2) the photo: the JPEG bytes the model gets
        slot = FrameLayout(this)
        photoView = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        placeholder = label(if (take) 17f else 20f, DIM, bold = true).apply {
            text = "Tap an example below, pick a photo or take one"; maxLines = 3; gravity = Gravity.CENTER; background = rounded(CARD, 14f)
            setPadding(px(16f).toInt(), 0, px(16f).toInt(), 0)
            setOnClickListener { onPick() }
        }
        slot.addView(photoView, FrameLayout.LayoutParams(match, match))
        slot.addView(placeholder, FrameLayout.LayoutParams(match, match))
        content.addView(slot, if (take) params(match, px(180f).toInt(), top = 6f) else params(match, 0, 1.1f, top = 6f))

        // (3) the examples, Pick a photo (the system picker) and Take a photo (the camera app)
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        thumbs = Examples.PHOTOS.map { ex ->
            ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(CARD, 8f)
                clipToOutline = true
                contentDescription = "Example photo: ${ex.shows}"
                setOnClickListener { onExample(ex) }
            }.also { strip.addView(it, LinearLayout.LayoutParams(0, match, 1f).apply { rightMargin = px(6f).toInt() }) }
        }
        val photoButtons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pick = button("Pick a photo", 12.5f, BUTTON) { onPick() }
        camera = button("Take a photo", 12.5f, BUTTON) { onCamera() }
        photoButtons.addView(pick, params(match, 0, 1f))
        photoButtons.addView(camera, params(match, 0, 1f, top = 4f))
        strip.addView(photoButtons, LinearLayout.LayoutParams(px(96f).toInt(), match))
        content.addView(strip, params(match, px(if (take) 56f else 64f).toInt(), top = 6f))

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
        content.addView(questionBox, params(match, px(if (take) 40f else 42f).toInt(), top = 6f))
        chips = Examples.QUESTIONS.map { q ->
            label(14f, WHITE).apply {
                text = q; gravity = Gravity.CENTER_VERTICAL
                background = rounded(CARD, 100f)
                setPadding(px(12f).toInt(), 0, px(12f).toInt(), 0)
                isClickable = true
                setOnClickListener { onChip(q) }
            }
        }
        if (take) {
            // One row that scrolls sideways: the question box and the chips take two rows.
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chips.forEachIndexed { i, chip -> row.addView(chip, LinearLayout.LayoutParams(wrap, px(30f).toInt()).apply { if (i > 0) leftMargin = px(6f).toInt() }) }
            val scroller = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
            content.addView(scroller, params(match, wrap, top = 6f))
        } else {
            for (chip in chips) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(chip, LinearLayout.LayoutParams(wrap, px(28f).toInt()))
                content.addView(row, params(match, wrap, top = 4f))
            }
        }

        // (5) backend, Ask, Stop
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val choices = if (policyName in BACKENDS) BACKENDS else BACKENDS + policyName
        backend = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, choices)
            setSelection(choices.indexOf(policyName))
        }
        backend.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val chosen = choices[position]
                if (chosen == policyName || busy()) return
                // The backend applies from the next load: the loaded model is closed and loaded again now.
                policyName = chosen
                looker.policy = policy()
                startJob {
                    looker.close()
                    prepareNow("Backend $chosen: loading the model")
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        ask = button("Ask", 17f, ACCENT) { onAsk() }
        stop = button("Stop", 17f, BUTTON) { onStopTap() }
        controls.addView(backend, LinearLayout.LayoutParams(0, match, 1f))
        controls.addView(ask, LinearLayout.LayoutParams(px(120f).toInt(), match).apply { leftMargin = px(8f).toInt() })
        controls.addView(stop, LinearLayout.LayoutParams(px(90f).toInt(), match).apply { leftMargin = px(8f).toInt() })
        content.addView(controls, params(match, px(44f).toInt(), top = 6f))

        // (6) the answer as it streams, in large type
        answerScroll = ScrollView(this).apply { background = rounded(CARD, 14f) }
        answer = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(if (take) 19f else 21f)); setTextColor(WHITE)
            setLineSpacing(0f, 1.1f)
            setPadding(px(12f).toInt(), px(10f).toInt(), px(12f).toInt(), px(10f).toInt())
        }
        answer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> keepAnswerInView() }
        answerScroll.addView(answer, FrameLayout.LayoutParams(match, wrap))
        content.addView(answerScroll, params(match, 0, 1f, top = 6f))

        // (7) the numbers: first token, speed, backend, whether the load made a request; one line, the type shrinks to fit
        numbers = label(16f, WHITE, bold = true).apply {
            setAutoSizeTextTypeUniformWithConfiguration(px(10f).toInt(), px(16f).toInt(), 1, TypedValue.COMPLEX_UNIT_PX)
            gravity = Gravity.CENTER_VERTICAL
        }
        content.addView(numbers, params(match, px(26f).toInt(), top = 6f))

        // (8) model, variant, profile, phone, runtime and SDK versions (filled in once loaded); below the 9:16 frame in take mode
        if (!take) content.addView(footer, params(match, wrap, top = 6f))

        setContentView(root)
        content.requestFocus()
    }

    /** Where the tappable things and the numbers line are on the screen, in pixels (left,top,right,bottom). */
    private fun logBounds(moment: String) {
        val at = IntArray(2)
        fun b(v: View): String { v.getLocationOnScreen(at); return "${at[0]},${at[1]},${at[0] + v.width},${at[1] + v.height}" }
        val dm = resources.displayMetrics
        val parts = Examples.PHOTOS.mapIndexed { i, ex -> "example_${ex.id}=${b(thumbs[i])}" } +
            chips.mapIndexed { i, c -> "chip_${i + 1}=${b(c)}" } +
            listOf("photo=${b(slot)}", "question=${b(questionBox)}", "ask=${b(ask)}", "answer=${b(answerScroll)}", "numbers=${b(numbers)}",
                "footer=${b(footer)}", "screen=${dm.widthPixels}x${dm.heightPixels}", "mode=$mode")
        Log.i(TAG, "BOUNDS $moment " + parts.joinToString(" "))
    }

    private fun enable(v: View, on: Boolean) {
        v.isEnabled = on
        v.alpha = if (on) 1f else 0.45f
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
            photo == null -> "Choose a photo, then ask about it"
            else -> "Type a question or tap one below, then Ask"
        }
        photoView.visibility = if (photo != null) View.VISIBLE else View.GONE
        placeholder.visibility = if (photo == null) View.VISIBLE else View.GONE
        val busy = busy()
        backend.isEnabled = !busy && !recording
        val choosing: List<View> = thumbs + listOf<View>(pick, camera) + chips
        for (v in choosing) enable(v, !asking)
        enable(ask, !busy)
        enable(stop, busy && (phase == Phase.LOADING || phase == Phase.ASKING))
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        looker.closeNow()
        models.close()
    }
}
