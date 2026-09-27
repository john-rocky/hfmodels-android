package io.github.johnrocky.hfmodels.samples.decide

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
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
import io.github.johnrocky.hfmodels.decide.Answer
import io.github.johnrocky.hfmodels.decide.TypedDecisions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlin.math.ceil

/**
 * The phone's unread texts sorted on one tap. Every text is asked one question by the decision model: what
 * it needs from you (five options, "nothing" first). The model gets the text alone; the sender is only
 * shown. Reads `content://sms/inbox` where `read = 0`, newest first (READ_SMS); nothing else is read from
 * the phone and nothing leaves it.
 *
 *   adb shell am start -n io.github.johnrocky.hfmodels.samples.decide/.InboxActivity \
 *       --es variant en_s256_fp32 --es backend gpu --ez autostart true --ei delay 3
 *
 * `--ez autostart true` presses Sort `delay` seconds after READY; `--ei limit N` keeps the newest N unread
 * texts. `--ez panel true` sorts the 24 hand-labelled texts of `InboxPanel` instead of the SMS store and
 * reports the agreement per question. Either of the two (recording mode) wakes the screen, shows the
 * activity over the lock screen and writes every sorted text into the result file with its answers; a
 * normal start does none of that.
 * Every DONE writes `inbox-result-<epoch s>.json` (`panel-result-<variant>.json`) to the app's external
 * files dir and logs the same under tag `inbox`. The screen follows the Core AI inbox example
 * (InboxScreen.swift): state pill, clock, count, one latency line, the list, the bars, a small footer.
 */
class InboxActivity : ComponentActivity() {
    private enum class Phase { LOADING, READY, SORTING, DONE, FAILED }

    private class Msg(val id: Long, val sender: String, val text: String, val label: String? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // Every decide on one thread of its own, so the clock and the list keep moving while the model runs.
    private val decideThread = Executors.newSingleThreadExecutor { Thread(it, "inbox-decide") }.asCoroutineDispatcher()
    private lateinit var decisions: DecisionModels
    private var model: TypedDecisions? = null

    private var panel = false
    private var recording = false
    private var limit = Int.MAX_VALUE
    private var variant = "en_s256_fp32"
    private var phase = Phase.LOADING
    private var detail = ""
    private var messages: List<Msg>? = null
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

    private var u = 1f
    private lateinit var pill: TextView
    private lateinit var clock: TextView
    private lateinit var count: TextView
    private lateinit var latency: TextView
    private lateinit var list: InboxListView
    private lateinit var button: TextView
    private lateinit var doneLine: TextView
    private lateinit var bins: InboxBinsView
    private lateinit var needs: TextView
    private lateinit var needsDetail: TextView
    private lateinit var nothingView: TextView
    private lateinit var footer: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        decisions = DecisionModels(this)
        panel = intent.getBooleanExtra("panel", false)
        variant = intent.getStringExtra("variant") ?: "en_s256_fp32"
        val backend = when (intent.getStringExtra("backend")) { "cpu" -> BackendKind.CPU; "auto" -> null; else -> BackendKind.GPU }
        val autostart = intent.getBooleanExtra("autostart", false)
        val delaySeconds = intent.getIntExtra("delay", 3)
        recording = autostart || panel
        limit = intent.getIntExtra("limit", Int.MAX_VALUE).coerceAtLeast(1)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Recording mode (a scripted run or the panel) wakes the screen and shows over the lock screen: an app
        // behind the keyguard is kept off the network and out of the foreground. A normal start never does.
        if (recording) { setShowWhenLocked(true); setTurnScreenOn(true) }
        buildScreen()
        // After setContentView: the decor view exists only then. Hide both bars for a clean recording.
        window.insetsController?.let { it.hide(WindowInsets.Type.systemBars()); it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }

        if (panel) setMessages(InboxPanel.ROWS.mapIndexed { i, (label, text) -> Msg(i.toLong(), "#%02d · label %s".format(Locale.US, i + 1, label), text, label) })
        else if (checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) setMessages(readInbox())
        else requestPermissions(arrayOf(Manifest.permission.READ_SMS), SMS_REQUEST)

        scope.launch {
            try {
                val m = decisions.load(variant, backend) { e ->
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
                    sort()
                }
            } catch (e: ModelException) {
                fail("${e.code}: ${e.reason}")
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != SMS_REQUEST) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) setMessages(readInbox()) else fail("READ_SMS was not granted; the screen reads unread texts only")
    }

    /** Unread texts in the inbox, newest first (at most `--ei limit`): the id, the sender as a phone shows it, the text. */
    private fun readInbox(): List<Msg> {
        val out = ArrayList<Msg>()
        contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY),
            "${Telephony.Sms.READ} = 0", null, "${Telephony.Sms.DATE} DESC",
        )?.use { c -> while (c.moveToNext() && out.size < limit) out += Msg(c.getLong(0), sender(c.getString(1) ?: ""), c.getString(2) ?: "") }
        return out
    }

    private fun sender(address: String): String =
        if (Regex("""\+?\d{7,15}""").matches(address)) PhoneNumberUtils.formatNumber(address, "US") ?: address else address

    private fun setMessages(ms: List<Msg>) {
        messages = ms
        rows = ms.map { InboxListView.Row(it.sender, it.text) }
        list.rows = rows
        bins.total = ms.size
        render()
    }

    private fun fail(reason: String) {
        phase = Phase.FAILED; detail = reason
        Log.e(TAG, "FAILED $reason")
        render()
    }

    private suspend fun sort() {
        val m = model ?: return
        val msgs = messages ?: return
        if (phase != Phase.READY && phase != Phase.DONE) return
        rows.forEach { it.bin = -1 }
        bins.reset(); ms.clear(); series.clear(); answers.clear()
        done = 0; totalS = 0.0
        thermalBefore = thermalStatus()
        phase = Phase.SORTING
        t0 = SystemClock.elapsedRealtimeNanos()
        render()
        clock.post(clockTick)
        Log.i(TAG, "SORT ${msgs.size} ${if (panel) "panel texts" else "unread texts"} variant=${m.info.variantId} profile=${m.info.profileId} thermal=$thermalBefore")
        for ((i, msg) in msgs.withIndex()) {
            list.current = i
            val d = try {
                withContext(decideThread) { m.decide(msg.text, InboxPanel.QUESTIONS) }
            } catch (e: ModelException) {
                fail("${e.code}: ${e.reason}"); return
            }
            val t = (SystemClock.elapsedRealtimeNanos() - t0) / 1e9
            val a = d.answers.getValue("need") as Answer.Choice
            val k = InboxPanel.OPTIONS.indexOf(a.choice)
            rows[i].bin = k
            bins.counts[k]++
            bins.update()
            ms += d.timing.totalMs
            series += t to d.timing.totalMs
            // The texts go into the result file only in recording mode (seeded or panel texts), never on a normal run.
            answers += JSONObject().put("id", msg.id)
                .apply { msg.label?.let { put("label", it) }; if (recording) { put("from", msg.sender); put("text", msg.text) } }
                .put("choice", a.choice).put("p_choice", a.probabilities[a.choice]).put("probabilities", JSONObject(a.probabilities))
                .put("ms", d.timing.totalMs).put("t_s", t)
            Log.i(TAG, "msg ${i + 1}/${msgs.size} id=${msg.id}${msg.label?.let { " label=$it" } ?: ""} choice=\"${a.choice}\" p=${a.probabilities[a.choice]} ms=${d.timing.totalMs} t=$t")
            done = i + 1
            render()
        }
        list.current = -1
        totalS = series.lastOrNull()?.first ?: 0.0
        thermalAfter = thermalStatus()
        if (panel) panelAgreement()
        phase = Phase.DONE
        render()
        writeResult(m)
    }

    /** The choice against the label on the panel, scam rows left out. */
    private fun panelAgreement() {
        var agree = 0; var of = 0
        for ((i, msg) in messages!!.withIndex()) {
            val label = msg.label ?: continue
            if (label == "scam") continue
            of++
            if (answers[i].getString("choice") == InboxPanel.LABEL_OPTION[label]) agree++
        }
        panelAgree = agree to of
    }

    private fun thermalStatus(): Int = getSystemService(PowerManager::class.java).currentThermalStatus

    private fun median(xs: List<Double>): Double = xs.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }
    private fun p90(xs: List<Double>): Double = xs.sorted().let { s -> s[(ceil(0.9 * s.size).toInt() - 1).coerceIn(0, s.size - 1)] }

    private fun writeResult(m: TypedDecisions) {
        if (ms.isEmpty()) return
        val o = JSONObject()
            .put("source", if (panel) "panel" else "sms inbox, read = 0")
            .put("count", ms.size)
            .put("total_s", totalS)
            .put("median_ms", median(ms))
            .put("p90_ms", p90(ms))
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
        if (panel) o.put("agree", panelAgree.first).put("of", panelAgree.second).put("gate_80", panelAgree.first >= 0.8 * panelAgree.second)
        val name = if (panel) "panel-result-${m.info.variantId}.json" else "inbox-result-${System.currentTimeMillis() / 1000}.json"
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

        val listFrame = FrameLayout(this)
        list = InboxListView(this, u)
        listFrame.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        button = label(18f, InboxStyle.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { scope.launch { sort() } }
        }
        listFrame.addView(button, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = px(12f).toInt() })
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
            Phase.DONE -> "DONE" to InboxStyle.PILL_DONE
            Phase.FAILED -> "FAILED" to InboxStyle.PILL_SORTING
        }
        pill.text = badge; pill.background = capsule(color)
        count.text = "${grouped(done)} / ${grouped(n)}"
        val what = if (panel) "labelled texts" else "unread texts"
        latency.text = when (phase) {
            Phase.LOADING -> if (messages == null) "loading the model" else "${grouped(n)} $what · loading the model"
            Phase.READY -> "${grouped(n)} $what · tap Sort"
            Phase.SORTING, Phase.DONE -> if (ms.isEmpty()) "${grouped(n)} $what" else
                "${msText(median(ms))} per message · ${"%.1f".format(Locale.US, done / elapsedSeconds())} msg/s · ${model?.info?.variantId} · ${model?.info?.profileId?.uppercase(Locale.US)}"
            Phase.FAILED -> detail
        }
        val showButton = (phase == Phase.READY || phase == Phase.DONE) && n > 0
        button.visibility = if (showButton) View.VISIBLE else View.GONE
        if (phase == Phase.READY) {
            button.text = "Sort inbox"; button.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(18f)); button.setTextColor(InboxStyle.WHITE)
            button.background = capsule(InboxStyle.ACTION); button.setPadding(px(34f).toInt(), px(13f).toInt(), px(34f).toInt(), px(13f).toInt())
        } else {
            button.text = "Sort again"; button.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(15f)); button.setTextColor(InboxStyle.LATENCY)
            button.background = capsule(InboxStyle.LANE); button.setPadding(px(24f).toInt(), px(10f).toInt(), px(24f).toInt(), px(10f).toInt())
        }
        doneLine.text = when {
            phase != Phase.DONE -> " "
            panel -> "${grouped(n)} texts · ${panelAgree.first}/${panelAgree.second} as labelled · median ${msText(median(ms))}"
            else -> "${grouped(n)} messages · one decision each · ${tenths(totalS)} · median ${msText(median(ms))}"
        }
        renderNeeds()
        renderClock()
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
            Phase.DONE -> tenths(totalS)
            else -> "–.– s"
        }
    }

    private fun elapsedSeconds() = if (phase == Phase.DONE) totalS else (SystemClock.elapsedRealtimeNanos() - t0) / 1e9

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
    }
}
