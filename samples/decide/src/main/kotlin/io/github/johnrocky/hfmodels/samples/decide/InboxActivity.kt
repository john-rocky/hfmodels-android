package io.github.johnrocky.hfmodels.samples.decide

import android.Manifest
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import io.github.johnrocky.hfmodels.BackendKind
import io.github.johnrocky.hfmodels.LoadEvent
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.NetworkPolicy
import io.github.johnrocky.hfmodels.decide.TypedDecisions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Your unread texts sorted on one tap. Every text is asked one question by the decision model: what it
 * needs from you (five options, "nothing" first). The texts come from the SMS inbox (`content://sms/inbox`
 * where `read = 0`, newest first; READ_SMS, asked for on a card that first says what is read) or from the
 * clipboard (Paste: one text per line, no permission). The model gets the text alone; the sender is only
 * shown; nothing leaves the phone. The import and the sort are [Inbox]'s, which the device check runs too.
 *
 *   adb shell am start -n io.github.johnrocky.hfmodels.samples.decide/.InboxActivity \
 *       --es variant en_s256_fp32 --es backend gpu --ez autostart true --ei delay 3
 *
 * `--ez autostart true` presses Sort `delay` seconds after READY; `--ei limit N` keeps the newest N unread
 * texts; `--es network offline` loads without a single request (OFFLINE_CACHE_MISS when the files are not
 * on the phone). `--ez panel true` sorts the 24 hand-labelled texts of `InboxPanel` instead of the SMS store
 * and reports the agreement per question; `--es paste "<texts, one per line>"` takes that text as Paste takes
 * the clipboard's. Any of the three (recording mode) wakes the screen, shows the activity over the lock screen
 * and writes every sorted text into the result file with its answers; a normal start does none of that.
 * Every DONE writes `inbox-result-<epoch s>.json` (`panel-result-<variant>.json`) to the app's external
 * files dir and logs the same under tag `inbox`. The screen follows the Core AI inbox example
 * (InboxScreen.swift): state pill, clock, count, one latency line, the list, the bars, a small footer; a
 * card above the list shows one text large with the question and its answer.
 */
class InboxActivity : ComponentActivity() {
    private enum class Phase { LOADING, READY, SORTING, STOPPED, DONE, FAILED }

    /** Where the texts on the list came from. */
    private enum class Source { SMS, PASTE, PANEL }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // Every decide on one thread of its own, so the clock and the list keep moving while the model runs.
    private val decideThread = Executors.newSingleThreadExecutor { Thread(it, "inbox-decide") }.asCoroutineDispatcher()
    private lateinit var decisions: DecisionModels
    private var model: TypedDecisions? = null
    private var sortJob: Job? = null
    private var stopping = false

    private var recording = false
    private var limit = Int.MAX_VALUE
    private var variant = "en_s256_fp32"
    private var phase = Phase.LOADING
    private var detail = ""
    /** The texts on the list; null while none are chosen, when the choice card shows instead. */
    private var messages: List<Inbox.Text>? = null
    private var source = Source.SMS
    /** One sentence for the user: SMS access refused, an empty clipboard. Cleared by the next import or sort. */
    private var notice: String? = null
    private var rows: List<InboxListView.Row> = emptyList()

    // One run
    private var t0 = 0L
    private var done = 0
    private var totalS = 0.0
    private val ms = ArrayList<Double>()
    private val series = ArrayList<Pair<Double, Double>>()
    private val answers = ArrayList<JSONObject>()
    private var thermalBefore = -1
    private var thermalAfter = -1
    private var panelAgree = 0 to 0
    // The spotlight card changes at most once per SPOTLIGHT_MS (the display only; sorting never waits for it).
    private var spotlightShownAt = 0L
    private var spotlightPending = false
    private val spotlightTick = Runnable { spotlightPending = false; spotlightLatest() }

    private var u = 1f
    private lateinit var pill: TextView
    private lateinit var clock: TextView
    private lateinit var count: TextView
    private lateinit var latency: TextView
    private lateinit var spotlight: InboxSpotlightView
    private lateinit var list: InboxListView
    private lateinit var actions: LinearLayout
    private lateinit var button: TextView
    private lateinit var pasteButton: TextView
    private lateinit var choice: LinearLayout
    private lateinit var choiceTitle: TextView
    private lateinit var choiceBody: TextView
    private lateinit var allowButton: TextView
    private lateinit var choiceNote: TextView
    private lateinit var doneLine: TextView
    private lateinit var bins: InboxBinsView
    private lateinit var needs: TextView
    private lateinit var needsDetail: TextView
    private lateinit var nothingView: TextView
    private lateinit var footer: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        decisions = DecisionModels(this)
        val panel = intent.getBooleanExtra("panel", false)
        val pasted = intent.getStringExtra("paste")
        variant = intent.getStringExtra("variant") ?: "en_s256_fp32"
        val backend = when (intent.getStringExtra("backend")) { "cpu" -> BackendKind.CPU; "auto" -> null; else -> BackendKind.GPU }
        val network = if (intent.getStringExtra("network") == "offline") NetworkPolicy.Offline else NetworkPolicy.Any
        val autostart = intent.getBooleanExtra("autostart", false)
        val delaySeconds = intent.getIntExtra("delay", 3)
        recording = autostart || panel || pasted != null
        limit = intent.getIntExtra("limit", Int.MAX_VALUE).coerceAtLeast(1)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Recording mode (a scripted run, the panel or a scripted paste) wakes the screen and shows over the lock
        // screen: an app behind the keyguard is kept off the network and out of the foreground. A normal start never does.
        if (recording) { setShowWhenLocked(true); setTurnScreenOn(true) }
        buildScreen()
        // After setContentView: the decor view exists only then. Hide both bars for a clean recording.
        window.insetsController?.let { it.hide(WindowInsets.Type.systemBars()); it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }

        when {
            panel -> setMessages(Inbox.panel(), Source.PANEL)
            pasted != null -> paste(pasted)
            smsGranted() -> readInbox()
            else -> render() // the choice card: what is read, Allow, Paste
        }

        scope.launch {
            try {
                val m = decisions.load(variant, backend, network) { e ->
                    if (e is LoadEvent.Downloading && e.totalBytes > 0) runOnUiThread { if (phase == Phase.LOADING) { detail = "${e.bytes * 100 / e.totalBytes}%"; render() } }
                }
                model = m
                footer.text = listOf("laya ${m.info.variantId}", m.info.profileId.uppercase(Locale.US), Build.MODEL, "Android ${Build.VERSION.RELEASE}", LocalDate.now().toString()).joinToString(" · ")
                Log.i(TAG, "ready ${m.info.repoId}@${m.info.commit.take(8)} variant=${m.info.variantId} profile=${m.info.profileId} notes=${m.info.notes}")
                if (phase == Phase.LOADING) { phase = Phase.READY; detail = "" }
                render()
                if (autostart) {
                    while (messages == null && phase != Phase.FAILED) delay(100)
                    delay(delaySeconds * 1000L)
                    startSort()
                }
            } catch (e: ModelException) {
                fail(Inbox.failure(e))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from the system settings with SMS allowed, or texts arrived since the inbox was found empty.
        if (messages == null && !recording && smsGranted()) readInbox()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != SMS_REQUEST || grantResults.isEmpty()) return
        if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            notice = null
            readInbox()
        } else {
            // Refused: the same card again, Paste still works.
            Log.i(TAG, "READ_SMS not granted")
            notice = SMS_REFUSED
            render()
        }
    }

    private fun smsGranted() = checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /** The unread texts of the inbox onto the list, or the card's sentence when there are none. */
    private fun readInbox() {
        val r = try {
            Inbox.unread(contentResolver, limit)
        } catch (e: SecurityException) {
            notice = SMS_REFUSED; render(); return
        }
        when (r) {
            is Inbox.Import.Texts -> setMessages(r.texts, Source.SMS)
            is Inbox.Import.Empty -> { Log.i(TAG, "no unread texts"); render() }
        }
    }

    /** The clipboard's text (or `--es paste`'s) onto the list, one text per line; a sentence when it holds none. */
    private fun paste(text: CharSequence?) {
        when (val r = Inbox.paste(text)) {
            is Inbox.Import.Texts -> { notice = null; setMessages(r.texts, Source.PASTE) }
            is Inbox.Import.Empty -> { Log.i(TAG, "paste: no text"); notice = r.message; render() }
        }
    }

    /** The clipboard's first item as text; Android hands it over while this screen has the focus. */
    private fun clipboardText(): CharSequence? {
        val clip = getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
        return if (clip.itemCount > 0) clip.getItemAt(0).coerceToText(this) else null
    }

    private fun setMessages(texts: List<Inbox.Text>, from: Source) {
        messages = texts
        source = from
        rows = texts.map { InboxListView.Row(it.sender, it.text) }
        list.rows = rows
        bins.reset(); bins.total = texts.size
        ms.clear(); series.clear(); answers.clear()
        done = 0; totalS = 0.0
        if (phase == Phase.DONE || phase == Phase.STOPPED) phase = Phase.READY
        spotlightFirst()
        Log.i(TAG, "texts ${texts.size} from ${from.name.lowercase(Locale.US)}")
        render()
    }

    /** Before sorting: the first text with the question's options. */
    private fun spotlightFirst() {
        spotlight.removeCallbacks(spotlightTick); spotlightPending = false; spotlightShownAt = 0L
        val first = rows.firstOrNull()
        spotlight.show(first?.sender ?: "", first?.text ?: "", -1)
    }

    /** The text answered last, now or when the card may change next. */
    private fun spotlightAnswered() {
        val wait = spotlightShownAt + SPOTLIGHT_MS - SystemClock.uptimeMillis()
        if (wait <= 0) spotlightLatest()
        else if (!spotlightPending) { spotlightPending = true; spotlight.postDelayed(spotlightTick, wait) }
    }

    private fun spotlightLatest() {
        val r = rows.getOrNull(done - 1) ?: return
        spotlight.show(r.sender, r.text, r.bin)
        spotlightShownAt = SystemClock.uptimeMillis()
    }

    private fun fail(reason: String) {
        phase = Phase.FAILED; detail = reason
        list.current = -1
        Log.e(TAG, "FAILED $reason")
        render()
    }

    private fun startSort() {
        if (sortJob?.isActive == true) return
        sortJob = scope.launch { sort() }
    }

    /** Stop: the text in progress finishes (a forward is not cut half-way), then the sort ends; Sort starts it over. */
    private fun stopSort() {
        val job = sortJob ?: return
        if (!job.isActive) return
        stopping = true
        job.cancel()
        render()
    }

    private suspend fun sort() {
        val m = model ?: return
        val texts = messages ?: return
        if (texts.isEmpty() || (phase != Phase.READY && phase != Phase.DONE && phase != Phase.STOPPED)) return
        notice = null
        rows.forEach { it.bin = -1 }
        bins.reset(); ms.clear(); series.clear(); answers.clear()
        done = 0; totalS = 0.0
        spotlightFirst()
        thermalBefore = thermalStatus()
        phase = Phase.SORTING
        stopping = false
        t0 = SystemClock.elapsedRealtimeNanos()
        list.current = 0
        render()
        clock.post(clockTick)
        Log.i(TAG, "SORT ${texts.size} ${what()} variant=${m.info.variantId} profile=${m.info.profileId} thermal=$thermalBefore")
        val sorted = try {
            Inbox.sort(m, texts, decideThread) { i, s -> answered(i, s, texts.size) }
        } catch (e: ModelException) {
            fail(Inbox.failure(e)); return
        } catch (e: CancellationException) {
            stopped(); throw e
        }
        list.current = -1
        totalS = series.lastOrNull()?.first ?: 0.0
        thermalAfter = thermalStatus()
        if (source == Source.PANEL) panelAgree = Inbox.agreement(sorted)
        phase = Phase.DONE
        render()
        writeResult(m)
    }

    private fun answered(i: Int, s: Inbox.Sorted, n: Int) {
        val t = (SystemClock.elapsedRealtimeNanos() - t0) / 1e9
        val msg = s.text
        rows[i].bin = s.bin
        bins.counts[s.bin]++
        bins.update()
        ms += s.ms
        series += t to s.ms
        // The texts go into the result file only in recording mode (seeded, panel or scripted texts), never on a normal run.
        answers += JSONObject().put("id", msg.id)
            .apply { msg.label?.let { put("label", it) }; if (recording) { put("from", msg.sender); put("text", msg.text) } }
            .put("choice", s.choice).put("p_choice", s.probabilities[s.choice]).put("probabilities", JSONObject(s.probabilities))
            .put("ms", s.ms).put("t_s", t)
        Log.i(TAG, "msg ${i + 1}/$n id=${msg.id}${msg.label?.let { " label=$it" } ?: ""} choice=\"${s.choice}\" p=${s.probabilities[s.choice]} ms=${s.ms} t=$t")
        done = i + 1
        list.current = if (done < n) done else -1
        spotlightAnswered()
        render()
    }

    /** The sort ended early (Stop, or the screen closing): the answers so far stay on screen, nothing is written. */
    private fun stopped() {
        list.current = -1
        totalS = (SystemClock.elapsedRealtimeNanos() - t0) / 1e9
        phase = Phase.STOPPED
        stopping = false
        Log.i(TAG, "STOPPED after $done of ${messages?.size ?: 0}")
        render()
    }

    private fun thermalStatus(): Int = getSystemService(PowerManager::class.java).currentThermalStatus

    private fun what() = when (source) { Source.PANEL -> "labelled texts"; Source.PASTE -> "pasted texts"; Source.SMS -> "unread texts" }

    private fun writeResult(m: TypedDecisions) {
        if (ms.isEmpty()) return
        val o = JSONObject()
            .put("source", when (source) { Source.PANEL -> "panel"; Source.PASTE -> "pasted"; Source.SMS -> "sms inbox, read = 0" })
            .put("count", ms.size)
            .put("total_s", totalS)
            .put("median_ms", Inbox.median(ms))
            .put("p90_ms", Inbox.p90(ms))
            .put("msg_per_s", ms.size / totalS)
            .put("bins", JSONObject().apply { InboxPanel.OPTIONS.forEachIndexed { i, o -> put(o, bins.counts[i]) } })
            .put("variant", m.info.variantId)
            .put("backend", m.info.profileId)
            .put("device", Build.MODEL)
            .put("android_build", Build.DISPLAY)
            .put("android_release", Build.VERSION.RELEASE)
            .put("date", Instant.now().toString())
            .put("thermal_before", thermalBefore)
            .put("thermal_after", thermalAfter)
            .put("model", "${m.info.repoId}@${m.info.commit}")
            .put("sdk_notes", JSONArray(m.info.notes))
            .put("questions", JSONObject(InboxPanel.QUESTIONS.mapValues { it.value.toMap() }))
            .put("series", JSONArray(series.map { JSONArray().put(it.first).put(it.second) }))
            .put("answers", JSONArray(answers))
        if (source == Source.PANEL) o.put("agree", panelAgree.first).put("of", panelAgree.second).put("gate_80", panelAgree.first >= 0.8 * panelAgree.second)
        val name = if (source == Source.PANEL) "panel-result-${m.info.variantId}.json" else "inbox-result-${System.currentTimeMillis() / 1000}.json"
        val file = File(getExternalFilesDir(null), name)
        file.writeText(o.toString(2))
        val summary = JSONObject(o.toString()).apply { remove("series"); remove("answers"); remove("questions") }
        Log.i(TAG, "RESULT $summary")
        Log.i(TAG, "wrote ${file.absolutePath}")
    }

    private val clockTick = object : Runnable {
        override fun run() {
            renderClock()
            if (phase == Phase.SORTING) clock.postDelayed(this, 50)
        }
    }

    // ---- the screen ----

    private fun px(v: Float) = v * u

    private fun label(size: Float, color: Int, bold: Boolean = false, digits: Boolean = false): TextView = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size)); setTextColor(color); includeFontPadding = false
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
        if (digits) fontFeatureSettings = "tnum"
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }

    /** A paragraph: the label's font, wrapped over as many lines as it needs. */
    private fun paragraph(size: Float, color: Int): TextView = label(size, color).apply { maxLines = Int.MAX_VALUE; ellipsize = null; setLineSpacing(0f, 1.2f) }

    /** A capsule button; [primary] is filled with the action color, otherwise with [fill] and grey text. */
    private fun capsuleButton(text: String, primary: Boolean, fill: Int = InboxStyle.LANE, onClick: () -> Unit): TextView = label(15f, if (primary) InboxStyle.WHITE else InboxStyle.LATENCY, bold = true).apply {
        this.text = text
        gravity = Gravity.CENTER
        background = capsule(if (primary) InboxStyle.ACTION else fill)
        setPadding(px(20f).toInt(), px(11f).toInt(), px(20f).toInt(), px(11f).toInt())
        setOnClickListener { onClick() }
    }

    private fun capsule(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = px(100f) }

    private fun buildScreen() {
        u = resources.displayMetrics.widthPixels / 402f
        val frame = FrameLayout(this).apply { setBackgroundColor(InboxStyle.BACKGROUND) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        frame.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.setOnApplyWindowInsetsListener { _, insets ->
            val cut = insets.getInsets(WindowInsets.Type.displayCutout())
            content.setPadding(px(16f).toInt(), cut.top + px(8f).toInt(), px(16f).toInt(), cut.bottom + px(6f).toInt())
            insets
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        pill = label(13.4f, InboxStyle.WHITE, bold = true).apply { setPadding(px(8f).toInt(), px(5f).toInt(), px(8f).toInt(), px(5f).toInt()) }
        clock = label(26.8f, InboxStyle.WHITE, digits = true)
        count = label(16.4f, InboxStyle.WHITE, bold = true, digits = true)
        header.addView(pill)
        header.addView(clock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(12f).toInt() })
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(count)
        content.addView(header)

        latency = label(13.4f, InboxStyle.LATENCY, digits = true)
        content.addView(latency, margins(top = 10f))
        spotlight = InboxSpotlightView(this, u)
        content.addView(spotlight, margins(top = 10f))

        val listFrame = FrameLayout(this)
        list = InboxListView(this, u)
        listFrame.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Sort (Stop while sorting) and Paste, over the bottom of the list.
        actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        button = label(18f, InboxStyle.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { if (phase == Phase.SORTING) stopSort() else startSort() }
        }
        pasteButton = capsuleButton("Paste", primary = false) { paste(clipboardText()) }
        actions.addView(button)
        actions.addView(pasteButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(10f).toInt() })
        listFrame.addView(actions, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = px(12f).toInt() })
        // While no texts are chosen: what is read and why (READ_SMS), Allow, and Paste, which needs no permission.
        choice = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(InboxStyle.LANE); cornerRadius = px(12f) }
            setPadding(px(18f).toInt(), px(16f).toInt(), px(18f).toInt(), px(16f).toInt())
        }
        choiceTitle = label(18f, InboxStyle.WHITE, bold = true)
        choiceBody = paragraph(14f, InboxStyle.LATENCY)
        allowButton = capsuleButton("Allow", primary = true) { requestPermissions(arrayOf(Manifest.permission.READ_SMS), SMS_REQUEST) }
        // On the card (LANE) the second button takes the page color, so it stands out from the card.
        val pasteInstead = capsuleButton("Paste texts instead", primary = false, fill = InboxStyle.BACKGROUND) { paste(clipboardText()) }
        choiceNote = paragraph(12.5f, InboxStyle.AXIS)
        val choiceButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        choiceButtons.addView(allowButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = px(10f).toInt() })
        choiceButtons.addView(pasteInstead)
        choice.addView(choiceTitle)
        choice.addView(choiceBody, margins(top = 8f))
        choice.addView(choiceButtons, margins(top = 14f))
        choice.addView(choiceNote, margins(top = 12f))
        listFrame.addView(choice, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        content.addView(listFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = px(12f).toInt() })

        doneLine = label(12.5f, InboxStyle.WHITE, bold = true, digits = true).apply { text = " " }
        content.addView(doneLine, margins(top = 12f))
        bins = InboxBinsView(this, u)
        content.addView(bins, margins(top = 6f))

        val needsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        needs = label(12.5f, InboxStyle.WHITE, bold = true, digits = true)
        nothingView = label(12.5f, InboxStyle.AXIS, bold = true, digits = true)
        needsRow.addView(needs, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        needsRow.addView(nothingView)
        content.addView(needsRow, margins(top = 8f))
        needsDetail = label(11.5f, InboxStyle.AXIS, bold = true, digits = true)
        content.addView(needsDetail, margins(top = 4f))

        footer = label(10.4f, InboxStyle.AXIS, digits = true).apply { text = "laya $variant · ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · ${LocalDate.now()}" }
        content.addView(footer, margins(top = 10f))
        setContentView(frame)
        render()
    }

    private fun margins(top: Float) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(top).toInt() }

    private fun render() {
        val n = messages?.size ?: 0
        val (badge, color) = when (phase) {
            Phase.LOADING -> (if (detail.isEmpty()) "LOADING" else "LOADING $detail") to InboxStyle.PILL_IDLE
            Phase.READY -> "READY" to InboxStyle.PILL_IDLE
            Phase.SORTING -> "● SORTING" to InboxStyle.PILL_SORTING
            Phase.STOPPED -> "STOPPED" to InboxStyle.PILL_IDLE
            Phase.DONE -> "DONE" to InboxStyle.PILL_DONE
            Phase.FAILED -> "FAILED" to InboxStyle.PILL_SORTING
        }
        pill.text = badge; pill.background = capsule(color)
        count.text = "${grouped(done)} / ${grouped(n)}"
        // With texts on the list a notice takes the latency line; without, the choice card carries it.
        val shown = notice?.takeIf { messages != null }
        latency.maxLines = if (phase == Phase.FAILED || shown != null) 4 else 1
        latency.text = when (phase) {
            Phase.LOADING -> if (messages == null) "loading the model" else "${grouped(n)} ${what()} · loading the model"
            Phase.READY -> shown ?: if (messages == null) "the model is ready" else "${grouped(n)} ${what()} · tap Sort"
            Phase.SORTING, Phase.STOPPED, Phase.DONE -> shown ?: if (ms.isEmpty()) "${grouped(n)} ${what()}" else
                "${msText(Inbox.median(ms))} per message · ${"%.1f".format(Locale.US, done / elapsedSeconds())} msg/s · ${model?.info?.variantId} · ${model?.info?.profileId?.uppercase(Locale.US)}"
            Phase.FAILED -> detail
        }
        renderActions(n)
        renderChoice()
        doneLine.text = when {
            phase == Phase.STOPPED -> "stopped after ${grouped(done)} of ${grouped(n)}" + (if (ms.isEmpty()) "" else " · median ${msText(Inbox.median(ms))}") + " · tap Sort to start over"
            phase != Phase.DONE -> " "
            source == Source.PANEL -> "${grouped(n)} texts · ${panelAgree.first}/${panelAgree.second} as labelled · median ${msText(Inbox.median(ms))}"
            else -> "${grouped(n)} messages · one decision each · ${tenths(totalS)} · median ${msText(Inbox.median(ms))}"
        }
        renderNeeds()
        renderClock()
    }

    /** Sort (Stop while sorting) and Paste over the list; hidden while there is nothing to sort. */
    private fun renderActions(n: Int) {
        val sorting = phase == Phase.SORTING
        val canSort = (phase == Phase.READY || phase == Phase.DONE || phase == Phase.STOPPED) && n > 0
        actions.visibility = if (sorting || canSort) View.VISIBLE else View.GONE
        pasteButton.visibility = if (canSort && source != Source.PANEL) View.VISIBLE else View.GONE
        when {
            sorting -> {
                button.text = if (stopping) "Stopping…" else "Stop"; button.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(15f)); button.setTextColor(InboxStyle.WHITE)
                button.background = capsule(InboxStyle.PILL_SORTING); button.setPadding(px(24f).toInt(), px(10f).toInt(), px(24f).toInt(), px(10f).toInt())
            }
            phase == Phase.READY -> {
                button.text = if (source == Source.SMS) "Sort inbox" else "Sort"; button.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(18f)); button.setTextColor(InboxStyle.WHITE)
                button.background = capsule(InboxStyle.ACTION); button.setPadding(px(34f).toInt(), px(13f).toInt(), px(34f).toInt(), px(13f).toInt())
            }
            else -> {
                button.text = "Sort again"; button.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(15f)); button.setTextColor(InboxStyle.LATENCY)
                button.background = capsule(InboxStyle.LANE); button.setPadding(px(24f).toInt(), px(10f).toInt(), px(24f).toInt(), px(10f).toInt())
            }
        }
    }

    /** The card shown while no texts are chosen: without READ_SMS what would be read and Allow; with it, the empty inbox. Paste either way. */
    private fun renderChoice() {
        if (messages != null) { choice.visibility = View.GONE; return }
        choice.visibility = View.VISIBLE
        val granted = smsGranted()
        choiceTitle.text = if (granted) "Nothing to sort" else "Sort your unread texts"
        choiceBody.text = if (granted) Inbox.NO_UNREAD else RATIONALE
        allowButton.visibility = if (granted) View.GONE else View.VISIBLE
        choiceNote.text = notice ?: PASTE_HINT
        choiceNote.setTextColor(if (notice != null) InboxStyle.OPTION_COLORS[2] else InboxStyle.AXIS)
    }

    /** The four bins that ask something of you, and how many ask nothing. */
    private fun renderNeeds() {
        val c = bins.counts
        val need = (1 until c.size).sumOf { c[it] }
        needs.text = "Needs you today  ${grouped(need)}"
        nothingView.text = "Nothing to do: ${grouped(c[0])}"
        val parts = listOf(
            plural(c[1], "code", "codes"), plural(c[2], "delivery", "deliveries"),
            plural(c[3], "payment", "payments"), plural(c[4], "appointment", "appointments"),
        )
        val s = SpannableStringBuilder()
        parts.forEachIndexed { i, p ->
            if (i > 0) s.append("  ·  ", ForegroundColorSpan(InboxStyle.AXIS), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.append(p, ForegroundColorSpan(InboxStyle.OPTION_COLORS[i + 1]), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        needsDetail.text = s
    }

    private fun renderClock() {
        clock.text = when (phase) {
            Phase.SORTING -> tenths(elapsedSeconds())
            Phase.DONE, Phase.STOPPED -> tenths(totalS)
            else -> "–.– s"
        }
    }

    private fun elapsedSeconds() = if (phase == Phase.DONE || phase == Phase.STOPPED) totalS else (SystemClock.elapsedRealtimeNanos() - t0) / 1e9

    private fun tenths(s: Double): String { val t = (s * 10).toLong(); return "${t / 10}.${t % 10} s" }
    private fun msText(v: Double) = if (v >= 100) "%.0f ms".format(Locale.US, v) else "%.1f ms".format(Locale.US, v)
    private fun grouped(n: Int) = String.format(Locale.US, "%,d", n)
    private fun plural(n: Int, one: String, many: String) = "${grouped(n)} ${if (n == 1) one else many}"

    override fun onDestroy() {
        super.onDestroy()
        val d = decisions
        scope.cancel()
        CoroutineScope(Dispatchers.IO).launch {
            withContext(NonCancellable) { d.release(); d.models.closeAndJoin() }
            decideThread.close()
        }
    }

    private companion object {
        const val TAG = "inbox"
        const val SMS_REQUEST = 7
        const val SPOTLIGHT_MS = 1200L
        /** The card's two sentences before READ_SMS is asked for: what is read, and that nothing is sent. */
        const val RATIONALE = "To sort your texts, this screen reads only the unread messages of your SMS inbox (content://sms/inbox where read = 0). " +
            "Nothing is sent: the model runs on this phone, and no text leaves it."
        const val PASTE_HINT = "Paste needs no permission: copy your texts first, one per line."
        const val SMS_REFUSED = "SMS access was not allowed. Paste texts instead, or allow SMS for this app in Settings (Android stops asking after two refusals)."
    }
}
