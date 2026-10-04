package io.github.johnrocky.hfmodels.samples.promises

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.CalendarContract
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.LoadEvent
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.NetworkPolicy
import io.github.johnrocky.hfmodels.decide.Json
import io.github.johnrocky.hfmodels.decide.TypedDecisions
import java.io.File
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One screen: a conversation comes in (the share sheet, Paste, or Sample), every sentence is asked one question
 * by the decision model, one at a time, and lands in a bundle, You promised / They asked you / Plans, with an
 * Add that opens the system's new-event screen. The state pill and the latency line say what the model is doing
 * and how long a sentence takes; the card in the middle shows one sentence and its answer large.
 *
 * Recording mode, for a screen recording (README.md):
 *
 *   adb shell am start -n io.github.johnrocky.hfmodels.samples.promises/.MainActivity --ez autostart true --ei delay 3
 *
 * shows the screen over the lock screen with the display on, waits for the model, shows the question for `delay`
 * seconds, sorts the Sample conversation, and at DONE writes `promises-result-<epoch s>.json` to the app's
 * external files dir, the same numbers to logcat under tag `promises`, and a `TAP x= y=` line: the screen
 * position of the first Plans row's Add, for `adb shell input tap`. A normal start does none of that.
 *
 * `--es backend npu|gpu|cpu` on the start that creates the screen fixes the backend ([DecisionModels.choose]);
 * a running screen keeps the model it loaded.
 */
class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var decisions: DecisionModels
    private val loadLock = Mutex()
    private var sortJob: Job? = null
    private var sorting = false
    private var recording = false
    /** The `backend` extra of the start that created the screen; null = chosen by the runtime the app packages. */
    private var backend: String? = null
    private var u = 1f
    private var modelLine = MODEL_NAME

    private lateinit var pill: TextView
    private lateinit var latency: TextView
    private lateinit var spotlight: SpotlightView
    private lateinit var scroll: ScrollView
    private lateinit var bundles: BundlesView
    private lateinit var hint: TextView

    // The card changes at most once every SPOTLIGHT_MS; the sorting never waits for it.
    private var spotlightAt = 0L
    private var spotlightNext: Promises.Verdict? = null
    private var spotlightJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        decisions = DecisionModels.shared(this)
        backend = intent.getStringExtra(EXTRA_BACKEND)
        if (intent.getBooleanExtra(EXTRA_AUTOSTART, false)) enterRecording()
        u = resources.displayMetrics.widthPixels / 402f
        setContentView(screen())
        setPill("LOADING", PromisesStyle.PILL_IDLE)
        showQuestion()
        val first = intent
        scope.launch {
            // A load whose files are cached or pushed; the download waits for the first conversation.
            ensureModel(NetworkPolicy.Offline)
            handle(first)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onDestroy() {
        scope.cancel()
        if (isFinishing) decisions.close()
        super.onDestroy()
    }

    private fun handle(intent: Intent?) {
        if (intent == null || intent.getBooleanExtra(HANDLED, false)) return
        intent.putExtra(HANDLED, true)
        when {
            intent.action == Intent.ACTION_SEND -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                if (text.isNullOrBlank()) say("The shared item has no text.", error = true) else start(text, "share")
            }
            intent.getBooleanExtra(EXTRA_AUTOSTART, false) -> {
                enterRecording()
                start(SampleChat.TEXT, "sample", readyMs = intent.getIntExtra(EXTRA_DELAY, 3) * 1000L, scripted = true)
            }
        }
    }

    /** Recording mode: over the lock screen (a phone asleep behind a secure lock keeps a started app in the background), display on, kept on. */
    private fun enterRecording() {
        if (recording) return
        recording = true
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---- the screen ----------------------------------------------------------------------

    private fun screen(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(PromisesStyle.BACKGROUND) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14f), px(6f), px(14f), 0)
        }
        // Top: the state pill, then Paste and Sample.
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        pill = TextView(this).apply {
            setTextColor(PromisesStyle.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 12.5f * u)
            typeface = PromisesStyle.MEDIUM
            letterSpacing = 0.06f
            fontFeatureSettings = "tnum"
            setPadding(px(12f), px(5f), px(12f), px(5f))
        }
        top.addView(pill)
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(PromisesStyle.button(this, "Paste", u, PromisesStyle.AXIS).apply { setOnClickListener { paste() } })
        top.addView(PromisesStyle.button(this, "Sample", u, PromisesStyle.AXIS).apply { setOnClickListener { start(SampleChat.TEXT, "sample") } },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px(8f) })
        column.addView(top)
        latency = TextView(this).apply {
            setTextColor(PromisesStyle.LATENCY)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 12f * u)
            fontFeatureSettings = "tnum"
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, px(6f), 0, px(6f))
        }
        column.addView(latency)
        spotlight = SpotlightView(this, u)
        column.addView(spotlight)
        bundles = BundlesView(this, u) { addToCalendar(it) }
        hint = TextView(this).apply {
            text = "Share a chat to this app (Share, then hfmodels promises), or tap Paste or Sample. Every sentence is sorted on the phone."
            setTextColor(PromisesStyle.AXIS)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 12.5f * u)
            setPadding(0, px(14f), 0, 0)
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(4f), 0, px(16f))
            addView(bundles)
            addView(hint)
        }
        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) }
        column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(column)
        // Edge to edge (target SDK 36): keep the content clear of the status bar, the cutout and the navigation bar.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
        return root
    }

    private fun px(dp402: Float) = (dp402 * u).toInt()

    private fun setPill(label: String, color: Int) {
        pill.text = label
        pill.background = GradientDrawable().apply { cornerRadius = 100f; setColor(color) }
    }

    private fun say(line: String, error: Boolean = false) {
        latency.text = line
        latency.setTextColor(if (error) PromisesStyle.ERROR else PromisesStyle.LATENCY)
        if (error) Log.w(TAG, line)
    }

    private fun showQuestion() {
        spotlightJob?.cancel(); spotlightJob = null; spotlightNext = null
        spotlight.showQuestion()
        spotlightAt = SystemClock.uptimeMillis()
    }

    private fun offerSpotlight(v: Promises.Verdict) {
        val wait = spotlightAt + SPOTLIGHT_MS - SystemClock.uptimeMillis()
        if (wait <= 0 && spotlightJob == null) { spotlight(v); return }
        spotlightNext = v
        if (spotlightJob == null) spotlightJob = scope.launch {
            delay(max(0L, wait))
            spotlightJob = null
            spotlightNext?.let { spotlightNext = null; spotlight(it) }
        }
    }

    private fun spotlight(v: Promises.Verdict) {
        spotlightAt = SystemClock.uptimeMillis()
        spotlight.show(v.sentence.sender ?: "", v.sentence.text, v.key, v.probability)
    }

    // ---- the model -----------------------------------------------------------------------

    /** The open model, loading it once. With Offline a model that is not on the phone yet is not an error: the first conversation downloads it. */
    private suspend fun ensureModel(network: NetworkPolicy): TypedDecisions? = loadLock.withLock {
        decisions.model?.let { return@withLock it }
        setPill("LOADING", PromisesStyle.PILL_IDLE)
        say("$MODEL_NAME · loading")
        val t0 = SystemClock.elapsedRealtime()
        try {
            val choice = DecisionModels.choose(decisions.npuRuntime, backend)
            Log.i(TAG, "load variant=${choice.variant} policy=${choice.policy} fallback=${choice.fallback?.let { "${it.variant} on ${it.policy}" }} backend_extra=$backend npu_runtime=${decisions.npuRuntime} network=$network")
            val m = decisions.load(choice, network) { onLoadEvent(it) }
            val loadMs = SystemClock.elapsedRealtime() - t0
            modelLine = "$MODEL_NAME · ${m.info.profileId.uppercase(Locale.ROOT)}"
            say(String.format(Locale.US, "%s · loaded in %.1f s", modelLine, loadMs / 1000.0))
            Log.i(TAG, "ready model=${m.info.repoId}@${m.info.commit.take(8)} variant=${m.info.variantId} profile=${m.info.profileId} load_ms=$loadMs warmup_ms=${decisions.warmupMs} fallback=${m.info.fallbackHistory} npu_failure=${decisions.npuFailure} notes=${m.info.notes.joinToString(" | ")}")
            if (!sorting) setPill("READY", PromisesStyle.PILL_IDLE)
            m
        } catch (e: ModelException) {
            if (e.code == ErrorCode.OFFLINE_CACHE_MISS && network == NetworkPolicy.Offline) {
                setPill("SETUP", PromisesStyle.PILL_IDLE)
                say("$MODEL_NAME · not on the phone yet: the first conversation downloads 0.93 GB, once")
                Log.i(TAG, "not cached: ${e.reason}")
            } else {
                setPill("ERROR", PromisesStyle.PILL_SORTING)
                say("Load failed: ${e.code}: ${e.reason}", error = true)
                Log.e(TAG, "load failed", e)
            }
            null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            setPill("ERROR", PromisesStyle.PILL_SORTING)
            say("Load failed: ${e.javaClass.simpleName}: ${e.message}", error = true)
            Log.e(TAG, "load failed", e)
            null
        }
    }

    private var lastPercent = -1

    private fun onLoadEvent(e: LoadEvent) {
        when (e) {
            is LoadEvent.DownloadStarted -> { lastPercent = -1; setPill("DOWNLOADING 0%", PromisesStyle.PILL_IDLE); say(String.format(Locale.US, "%s · downloading %.2f GB, once", MODEL_NAME, e.totalBytes / 1e9)) }
            is LoadEvent.Downloading -> {
                val p = (e.bytes * 100 / max(1L, e.totalBytes)).toInt()
                if (p != lastPercent) { lastPercent = p; setPill("DOWNLOADING $p%", PromisesStyle.PILL_IDLE) }
            }
            is LoadEvent.Verifying -> setPill("VERIFYING", PromisesStyle.PILL_IDLE)
            is LoadEvent.Initializing -> {
                setPill("LOADING", PromisesStyle.PILL_IDLE)
                // LiteRT compiles the graph for the NPU on the phone on the first load and reads its cache on later loads (README, NPU).
                say(if (e.profileId == "npu") NPU_COMPILE_LINE else "$MODEL_NAME · compiling for the ${e.profileId.uppercase(Locale.ROOT)}")
            }
            is LoadEvent.Fallback -> say("$MODEL_NAME · ${e.reason}; trying the next profile")
            else -> Unit
        }
    }

    // ---- a conversation ------------------------------------------------------------------

    private fun paste() {
        val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this)?.toString() else null
        if (text.isNullOrBlank()) say("The clipboard holds no text.", error = true) else start(text, "clipboard")
    }

    /**
     * Sorts [text], replacing a sort that is still running (it ends after its current sentence: one decide at a time).
     * [scripted]: the recording mode's run, the only one that writes the result file and the TAP line.
     */
    private fun start(text: String, source: String, readyMs: Long = 0L, scripted: Boolean = false) {
        val previous = sortJob
        sortJob = scope.launch {
            previous?.cancelAndJoin()
            val m = ensureModel(NetworkPolicy.Any) ?: return@launch
            if (readyMs > 0) {
                bundles.clear()
                showQuestion()
                setPill("READY", PromisesStyle.PILL_IDLE)
                delay(readyMs)
            }
            sorting = true
            try {
                sort(m, text, source, scripted)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                setPill("ERROR", PromisesStyle.PILL_SORTING)
                say(if (e is ModelException) "${e.code}: ${e.reason}" else "${e.javaClass.simpleName}: ${e.message}", error = true)
                Log.e(TAG, "sort failed", e)
            } finally {
                sorting = false
            }
        }
    }

    private suspend fun sort(m: TypedDecisions, text: String, source: String, scripted: Boolean) {
        val all = Sentences.split(text)
        val sentences = all.take(Sentences.MAX)
        if (sentences.isEmpty()) { say("No sentences in that text.", error = true); return }
        hint.visibility = View.GONE
        bundles.clear()
        scroll.scrollTo(0, 0)
        val n = sentences.size
        setPill("SORTING 0/$n", PromisesStyle.PILL_SORTING)
        val thermalBefore = thermal()
        val verdicts = ArrayList<Promises.Verdict>(n)
        val t0 = SystemClock.elapsedRealtimeNanos()
        for ((i, s) in sentences.withIndex()) {
            val v = withContext(Dispatchers.Default) { Promises.judge(m, s) }
            verdicts += v
            bundles.add(v)
            offerSpotlight(v)
            setPill("SORTING ${i + 1}/$n", PromisesStyle.PILL_SORTING)
            sayLatency(verdicts, (SystemClock.elapsedRealtimeNanos() - t0) / 1e9)
            Log.i(TAG, "i=$i answer=${v.key} p=${v.probability} ms=${v.ms} question_ms=${v.questionMs} tokens=${v.tokens} expected=${SampleChat.labelOf(s.text)} sender=${s.sender} text=\"${s.text}\"")
        }
        val totalMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
        val thermalAfter = thermal()
        // More sentences than MAX: the pill says how many were sorted out of how many (the latency line has no room left).
        setPill(if (all.size > n) "DONE $n OF ${all.size}" else "DONE $n", PromisesStyle.PILL_DONE)
        sayLatency(verdicts, totalMs / 1000)
        val record = record(m, source, verdicts, all.size, totalMs, thermalBefore, thermalAfter)
        val file = if (scripted) writeRecord(record) else null
        Log.i(TAG, "DONE source=$source n=$n " + record.getValue("counts").let { c -> (c as Map<*, *>).entries.joinToString(" ") { "${it.key.toString().replace(' ', '_')}=${it.value}" } } +
            " expected_agree=${record["expected_agree"]} ms_median=${record["ms_median"]} ms_p90=${record["ms_p90"]} question_ms_median=${record["question_ms_median"]} total_ms=$totalMs sentences_per_second=${record["sentences_per_second"]}" +
            " thermal=${thermalBefore["status"]}->${thermalAfter["status"]} headroom=${thermalBefore["headroom"]}->${thermalAfter["headroom"]} profile=${m.info.profileId} file=${file?.path}")
        if (scripted) logTapTarget(verdicts)
    }

    private fun sayLatency(verdicts: List<Promises.Verdict>, seconds: Double) {
        val median = Promises.median(verdicts.filter { it.decided }.map { it.ms })
        val perSecond = if (seconds > 0) verdicts.size / seconds else 0.0
        say(String.format(Locale.US, "%.1f ms/sentence · %.1f/s · %s", median, perSecond, modelLine))
    }

    /** PowerManager's thermal status (0 = none … 6 = shutdown) and its headroom forecast (1.0 = throttling starts; null when the phone does not say). */
    private fun thermal(): Map<String, Any?> {
        val pm = getSystemService(PowerManager::class.java)
        val headroom = pm.getThermalHeadroom(0)
        return linkedMapOf("status" to pm.currentThermalStatus, "headroom" to if (headroom.isNaN()) null else headroom.toDouble())
    }

    private fun record(m: TypedDecisions, source: String, verdicts: List<Promises.Verdict>, inText: Int, totalMs: Double, before: Map<String, Any?>, after: Map<String, Any?>): Map<String, Any?> {
        val decided = verdicts.filter { it.decided }
        val keys = Promises.QUESTION.criteria.keys.toList() + Promises.TOO_LONG
        val labelled = verdicts.mapNotNull { v -> SampleChat.labelOf(v.sentence.text)?.let { v to it } }
        fun num(d: Double): Double? = if (d.isNaN()) null else d
        return linkedMapOf(
            "app" to "samples/promises",
            "source" to source,
            "epoch_s" to System.currentTimeMillis() / 1000,
            "model" to "${m.info.repoId}@${m.info.commit}",
            "variant" to m.info.variantId,
            "profile" to m.info.profileId,
            "question" to Promises.QUESTION.toMap(),
            "count" to verdicts.size,
            "sentences_in_text" to inText,
            "counts" to keys.associateWith { k -> verdicts.count { it.key == k } },
            "bundles" to keys.associateWith { k -> verdicts.filter { it.key == k }.map { it.sentence.text } },
            "expected_agree" to if (labelled.isEmpty()) null else "${labelled.count { (v, label) -> v.key == label }}/${labelled.size}",
            "ms_median" to num(Promises.median(decided.map { it.ms })),
            "ms_p90" to num(Promises.p90(decided.map { it.ms })),
            "question_ms_median" to num(Promises.median(decided.map { it.questionMs })),
            "question_ms_p90" to num(Promises.p90(decided.map { it.questionMs })),
            "total_ms" to totalMs,
            "sentences_per_second" to verdicts.size / (totalMs / 1000),
            "load_warmup_ms" to decisions.warmupMs,
            "thermal_before" to before,
            "thermal_after" to after,
            "device" to Build.MODEL,
            "soc" to "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}",
            "build" to Build.DISPLAY,
            "android" to Build.VERSION.RELEASE,
            "sdk" to m.info.sdkVersion,
            "runtime" to "${m.info.runtime} ${m.info.runtimeVersion}",
            "sentences" to verdicts.mapIndexed { i, v ->
                linkedMapOf(
                    "i" to i, "sender" to v.sentence.sender, "text" to v.sentence.text, "answer" to v.key,
                    "expected" to SampleChat.labelOf(v.sentence.text), "probabilities" to v.probabilities,
                    "ms" to v.ms, "question_ms" to v.questionMs, "tokens" to v.tokens,
                )
            },
        )
    }

    private fun writeRecord(record: Map<String, Any?>): File? = try {
        File(getExternalFilesDir(null), "promises-result-${record["epoch_s"]}.json").apply { writeText(Json.dumps(record) + "\n") }
    } catch (e: Exception) {
        Log.w(TAG, "result file not written: ${e.message}")
        null
    }

    /** The recording's last step: where the first Plans row's Add is on the screen (else the first promise's, else the first request's). */
    private fun logTapTarget(verdicts: List<Promises.Verdict>) {
        val target = listOf("plan", "promise", "request").firstNotNullOfOrNull { k -> verdicts.firstOrNull { it.key == k } } ?: return
        val button = bundles.buttonFor(target) ?: return
        button.post {
            val r = Rect()
            if (!button.getGlobalVisibleRect(r) || r.height() < button.height) button.requestRectangleOnScreen(Rect(0, 0, button.width, button.height), true)
            button.post {
                val at = IntArray(2)
                button.getLocationOnScreen(at)
                Log.i(TAG, "TAP x=${at[0] + button.width / 2} y=${at[1] + button.height / 2} visible=${button.getGlobalVisibleRect(Rect())} text=\"${target.sentence.text}\"")
            }
        }
    }

    // ---- Add -----------------------------------------------------------------------------

    /** The system's new-event screen with the sentence as the title and a start picked by fixed rules (EventTime); the person saves it. */
    private fun addToCalendar(v: Promises.Verdict) {
        val found = EventTime.find(v.sentence.text, ZonedDateTime.now())
        val begin = found.start.toInstant().toEpochMilli()
        val insert = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, v.sentence.text)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + 3_600_000L)
        Log.i(TAG, "ADD start=${found.start} words=${found.matched} text=\"${v.sentence.text}\"")
        try {
            startActivity(insert)
        } catch (e: ActivityNotFoundException) {
            say("No calendar app on this phone takes a new event.", error = true)
        }
    }

    companion object {
        const val TAG = "promises"
        const val EXTRA_AUTOSTART = "autostart"
        const val EXTRA_DELAY = "delay"
        const val EXTRA_BACKEND = "backend"
        private const val HANDLED = "io.github.johnrocky.hfmodels.samples.promises.HANDLED"
        private const val MODEL_NAME = "GLiNER2.5-Decide s128"
        private const val NPU_COMPILE_LINE = "Compiling for the NPU (first load only)"
        private const val SPOTLIGHT_MS = 1200L
    }
}
