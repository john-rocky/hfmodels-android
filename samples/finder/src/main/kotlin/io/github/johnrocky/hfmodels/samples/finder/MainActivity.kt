package io.github.johnrocky.hfmodels.samples.finder

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
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
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
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
 * One screen: a sentence goes into the input, Find asks the decision model five questions about it (Finder.QUESTIONS),
 * and the answers become the five filter chips under it; the recipe list below narrows on the spot to what the set
 * filters keep, each card's matching attributes in its filter's color. A tap on a chip opens a menu of that filter's
 * values (unset among them): the filters also work by hand, and a wrong one is fixed there.
 *
 * Recording mode, for a screen recording (README.md):
 *
 *   adb shell am start -n io.github.johnrocky.hfmodels.samples.finder/.MainActivity --ez autostart true --ei delay 2
 *
 * shows the screen over the lock screen with the display on, waits for the model, shows every recipe for `delay`
 * seconds, then types Finder.SCRIPT's two sentences into the input one character at a time (no keyboard), each
 * followed by Find and [HOLD_MS] of its result, and at DONE writes `finder-result-<epoch s>.json` to the app's external
 * files dir and the same numbers to logcat under tag `finder`. A normal start does none of that.
 *
 * `--es backend npu|gpu|cpu` on the start that creates the screen fixes the backend ([DecisionModels.choose]);
 * a running screen keeps the model it loaded.
 */
class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var decisions: DecisionModels
    private val loadLock = Mutex()
    private var findJob: Job? = null
    private var recording = false
    /** The `backend` extra of the start that created the screen; null = chosen by the runtime the app packages. */
    private var backend: String? = null
    private var u = 1f
    private var modelLine = MODEL_NAME

    private lateinit var recipes: List<Recipe>
    private var filters = Filters()
    private lateinit var root: FrameLayout
    private lateinit var input: EditText
    private lateinit var find: TextView
    private lateinit var chips: Map<Facet, FilterChip>
    private lateinit var count: TextView
    private lateinit var latency: TextView
    private lateinit var scroll: ScrollView
    private lateinit var cards: List<RecipeCard>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        decisions = DecisionModels.shared(this)
        backend = intent.getStringExtra(EXTRA_BACKEND)
        u = resources.displayMetrics.widthPixels / 402f
        recipes = assets.open(Recipes.ASSET).bufferedReader().use { Recipes.parse(it.readText()) }
        setContentView(screen())
        if (intent.getBooleanExtra(EXTRA_AUTOSTART, false)) enterRecording()
        show(Filters(), animate = false)
        root.requestFocus()
        // The external files dir, made by the app itself on its first start: a copy pushed there with adb (README.md)
        // then lands in a directory the app owns, and the recording mode writes its result file there.
        getExternalFilesDir(null)
        val first = intent
        scope.launch {
            // A load whose id is bound and whose files are cached or pushed; the download waits for the first search.
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
        if (!intent.getBooleanExtra(EXTRA_AUTOSTART, false)) return
        enterRecording()
        val readyMs = intent.getIntExtra(EXTRA_DELAY, 2) * 1000L
        val previous = findJob
        findJob = scope.launch {
            previous?.cancelAndJoin()
            script(readyMs)
        }
    }

    /** Recording mode: over the lock screen (a phone asleep behind a secure lock keeps a started app in the background), display on, kept on, no keyboard. */
    private fun enterRecording() {
        if (recording) return
        recording = true
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        input.showSoftInputOnFocus = false
    }

    // ---- the screen ----------------------------------------------------------------------

    private fun screen(): View {
        root = FrameLayout(this).apply {
            setBackgroundColor(FinderStyle.BACKGROUND)
            // Holds the focus at the start, so the input shows no cursor until it is tapped.
            isFocusableInTouchMode = true
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14f), px(10f), px(14f), 0)
        }
        // The input and Find.
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setHorizontallyScrolling(false)
            // Three lines tall from the start, so the recording's longest sentence never moves what is below it.
            minLines = 3
            maxLines = 4
            hint = "What do you feel like eating?"
            setTextColor(FinderStyle.WHITE)
            setHintTextColor(FinderStyle.AXIS)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f * u)
            background = FinderStyle.rounded(FinderStyle.LANE, 12f * u)
            setPadding(px(12f), px(9f), px(12f), px(9f))
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEARCH) { submit(text.toString(), "typed"); true } else false }
        }
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        find = TextView(this).apply {
            text = "Find"
            setTextColor(FinderStyle.INK)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 16f * u)
            typeface = FinderStyle.MEDIUM
            gravity = Gravity.CENTER
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x55FFFFFF), FinderStyle.rounded(FinderStyle.FIND, 12f * u), null)
            setPadding(px(16f), px(11f), px(16f), px(11f))
            isClickable = true
            setOnClickListener { submit(input.text.toString(), "typed") }
        }
        row.addView(find, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px(8f) })
        column.addView(row)
        // Try: three sentences.
        column.addView(tryRow(this, u, Finder.EXAMPLES.map { it.text }) { s -> input.setText(s); submit(s, "try") },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(6f) })
        // The five filters.
        val flow = FlowLayout(this, px(7f), px(7f))
        chips = Facet.values().associateWith { f -> FilterChip(this, f, u) { menu(it) }.also { flow.addView(it) } }
        column.addView(flow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(12f) })
        // How many recipes are left, and what the model did.
        count = TextView(this).apply {
            setTextColor(FinderStyle.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f * u)
            typeface = FinderStyle.MEDIUM
            fontFeatureSettings = "tnum"
        }
        column.addView(count, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(12f) })
        latency = TextView(this).apply {
            setTextColor(FinderStyle.LATENCY)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 12f * u)
            fontFeatureSettings = "tnum"
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, px(2f), 0, px(8f))
        }
        column.addView(latency)
        // The recipes.
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, px(16f))
        }
        cards = recipes.map { r ->
            RecipeCard(this, r, u).also {
                list.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(6f) })
            }
        }
        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) }
        column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(column)
        // Edge to edge (target SDK 36): keep the content clear of the status bar, the cutout, the navigation bar and the keyboard.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
            WindowInsets.CONSUMED
        }
        return root
    }

    private fun px(dp402: Float) = (dp402 * u).toInt()

    private fun say(line: String, error: Boolean = false) {
        latency.text = line
        latency.setTextColor(if (error) FinderStyle.ERROR else FinderStyle.LATENCY)
        if (error) Log.w(TAG, line)
    }

    /** Sets the five chips and the list to [f]: the cards the filters keep, their matching attributes colored. */
    private fun show(f: Filters, animate: Boolean): List<Recipe> {
        filters = f
        for ((facet, chip) in chips) chip.show(f[facet], animate)
        val kept = ArrayList<Recipe>()
        for (c in cards) {
            val keep = f.keeps(c.recipe)
            c.visibility = if (keep) View.VISIBLE else View.GONE
            if (keep) { kept += c.recipe; c.show(f) }
        }
        count.text = "${kept.size} of ${recipes.size} recipes"
        scroll.scrollTo(0, 0)
        return kept
    }

    /** A tap on a chip: a menu of that filter's values, the current one checked; a pick sets the filter, the list follows. */
    private fun menu(chip: FilterChip) {
        val f = chip.facet
        val keys = Finder.keys(f)
        val popup = PopupMenu(this, chip)
        keys.forEachIndexed { i, k -> popup.menu.add(0, i, i, Finder.chipText(f, k)) }
        popup.menu.setGroupCheckable(0, true, true)
        popup.menu.getItem(keys.indexOf(filters[f]).coerceAtLeast(0)).isChecked = true
        popup.setOnMenuItemClickListener { item ->
            val kept = show(filters.with(f, keys[item.itemId]), animate = true)
            Log.i(TAG, "PICK facet=${f.id} key=${keys[item.itemId]} kept=${kept.size} filters=${filters.keys}")
            true
        }
        popup.show()
    }

    // ---- the model -----------------------------------------------------------------------

    /** The open model, loading it once. With Offline a model that is not on the phone yet is not an error: the first search downloads it. */
    private suspend fun ensureModel(network: NetworkPolicy): TypedDecisions? = loadLock.withLock {
        // A model this process loaded for an earlier screen: the line still names its backend.
        decisions.model?.let { modelLine = "$MODEL_NAME · ${it.info.profileId.uppercase(Locale.ROOT)}"; return@withLock it }
        say("$MODEL_NAME · loading")
        val t0 = SystemClock.elapsedRealtime()
        try {
            val choice = DecisionModels.choose(decisions.npuRuntime, backend)
            Log.i(TAG, "load variant=${choice.variant} policy=${choice.policy} fallback=${choice.fallback?.let { "${it.variant} on ${it.policy}" }} backend_extra=$backend npu_runtime=${decisions.npuRuntime} network=$network")
            val m = decisions.load(choice, network) { onLoadEvent(it) }
            val loadMs = SystemClock.elapsedRealtime() - t0
            modelLine = "$MODEL_NAME · ${m.info.profileId.uppercase(Locale.ROOT)}"
            say("Ready · $modelLine")
            Log.i(TAG, "ready model=${m.info.repoId}@${m.info.commit.take(8)} variant=${m.info.variantId} profile=${m.info.profileId} load_ms=$loadMs warmup_ms=${decisions.warmupMs} " +
                "bound=${decisions.models.boundCommit(DecisionModels.REPO)?.take(8)} fallback=${m.info.fallbackHistory} npu_failure=${decisions.npuFailure} notes=${m.info.notes.joinToString(" | ")}")
            m
        } catch (e: ModelException) {
            if (e.code == ErrorCode.OFFLINE_CACHE_MISS && network == NetworkPolicy.Offline) {
                say("$MODEL_NAME · the first search downloads 0.93 GB, once")
                Log.i(TAG, "not cached: ${e.reason}")
            } else {
                say("Load failed: ${e.code}: ${e.reason}", error = true)
                Log.e(TAG, "load failed", e)
            }
            null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            say("Load failed: ${e.javaClass.simpleName}: ${e.message}", error = true)
            Log.e(TAG, "load failed", e)
            null
        }
    }

    private var lastPercent = -1

    private fun onLoadEvent(e: LoadEvent) {
        when (e) {
            is LoadEvent.DownloadStarted -> { lastPercent = -1; say(String.format(Locale.US, "%s · downloading %.2f GB, once", MODEL_NAME, e.totalBytes / 1e9)) }
            is LoadEvent.Downloading -> {
                val p = (e.bytes * 100 / max(1L, e.totalBytes)).toInt()
                if (p != lastPercent) { lastPercent = p; say(String.format(Locale.US, "%s · downloading %.2f GB, once: %d%%", MODEL_NAME, e.totalBytes / 1e9, p)) }
            }
            is LoadEvent.Verifying -> say("$MODEL_NAME · verifying the files")
            // LiteRT compiles the graph for the NPU on the phone on the first load and reads its cache on later loads (README, NPU).
            is LoadEvent.Initializing -> say(if (e.profileId == "npu") NPU_COMPILE_LINE else "$MODEL_NAME · compiling for the ${e.profileId.uppercase(Locale.ROOT)}")
            is LoadEvent.Fallback -> say("$MODEL_NAME · ${e.reason}; trying the next profile")
            else -> Unit
        }
    }

    // ---- a search ------------------------------------------------------------------------

    /** Find, Try, or the keyboard's search key: replaces a search that is still running (one decide at a time). */
    private fun submit(text: String, source: String) {
        val t = text.trim()
        if (t.isEmpty()) { say("Type what you feel like eating, then Find.", error = true); return }
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
        root.requestFocus()
        val previous = findJob
        findJob = scope.launch {
            previous?.cancelAndJoin()
            val m = ensureModel(NetworkPolicy.Any) ?: return@launch
            search(m, t, source, Finder.EXAMPLES.firstOrNull { it.text == t })
        }
    }

    /** One sentence: the five decisions, then the chips and the list. Null when the model refused it (the line says why). */
    private suspend fun search(m: TypedDecisions, text: String, source: String, known: Finder.Sentence?): Searched? {
        val r = try {
            withContext(Dispatchers.Default) { Finder.ask(m, text) }
        } catch (e: ModelException) {
            say(if (e.code == ErrorCode.CONTEXT_LIMIT_EXCEEDED) "Too long for the model's 128-token window: try a shorter sentence." else "${e.code}: ${e.reason}", error = true)
            Log.w(TAG, "search failed source=$source text=${q(text)}: ${e.code}: ${e.reason}")
            return null
        }
        val kept = show(r.filters, animate = true)
        val line = String.format(Locale.US, "%d decisions · %d ms · %s", Finder.QUESTIONS.size, r.totalMs.roundToInt(), modelLine)
        say(line)
        val agree = known?.let { s -> Facet.values().count { r.filters[it] == s.expected[it] } }
        Log.i(TAG, "QUERY source=$source id=${known?.id} text=${q(text)} " + Facet.values().joinToString(" ") { "${it.id}=${r.filters[it]}" } +
            " expected_agree=${agree?.let { "$it/${Facet.values().size}" }} kept=${kept.size} ids=${kept.joinToString(",") { it.id }} total_ms=${r.totalMs} " +
            "question_ms=" + r.questionMs.entries.joinToString(",") { "${it.key}:${it.value}" } + " tokens=${r.stateTokens} profile=${m.info.profileId} line=${q(line)}")
        Log.i(TAG, "PROBS id=${known?.id} " + r.probabilities.entries.joinToString(" ") { (id, p) -> "$id=" + p.entries.joinToString(",", "{", "}") { "${it.key}:${it.value}" } })
        return Searched(known, r, kept, line)
    }

    private class Searched(val sentence: Finder.Sentence?, val result: Finder.Result, val kept: List<Recipe>, val line: String)

    // ---- recording mode ------------------------------------------------------------------

    /** The scripted run: every recipe for [readyMs], then each sentence of Finder.SCRIPT typed, found and held; the record at DONE. */
    private suspend fun script(readyMs: Long) {
        val m = ensureModel(NetworkPolicy.Any) ?: return
        input.setText("")
        show(Filters(), animate = false)
        say("Ready · $modelLine")
        Log.i(TAG, "SCRIPT ready delay_ms=$readyMs")
        delay(readyMs)
        val thermalBefore = thermal()
        val runs = ArrayList<Searched>()
        for ((i, s) in Finder.SCRIPT.withIndex()) {
            if (i > 0) delay(HOLD_MS)
            type(s.text)
            delay(PAUSE_MS)
            press(find)
            runs += search(m, s.text, "script", s) ?: return
        }
        delay(HOLD_MS)
        val thermalAfter = thermal()
        val record = record(m, runs, thermalBefore, thermalAfter)
        val file = writeRecord(record)
        Log.i(TAG, "DONE n=${runs.size} " + runs.joinToString(" ") { "${it.sentence?.id}_kept=${it.kept.size} ${it.sentence?.id}_agree=${Facet.values().count { f -> it.result.filters[f] == it.sentence?.expected?.get(f) }}/${Facet.values().size} ${it.sentence?.id}_ms=${it.result.totalMs}" } +
            " thermal=${thermalBefore["status"]}->${thermalAfter["status"]} headroom=${thermalBefore["headroom"]}->${thermalAfter["headroom"]} variant=${m.info.variantId} profile=${m.info.profileId} file=${file?.path}")
    }

    /** The sentence into the input one character at a time, [TYPE_MS] apart, as a person would type it; no keyboard on screen. */
    private suspend fun type(text: String) {
        input.setText("")
        for (i in 1..text.length) {
            input.setText(text.substring(0, i))
            delay(TYPE_MS)
        }
    }

    /** Find shown pressed for a moment; the search starts at once, it does not wait for the press. */
    private fun press(v: View) {
        v.drawableHotspotChanged(v.width / 2f, v.height / 2f)
        v.isPressed = true
        scope.launch { delay(PRESSED_MS); v.isPressed = false }
    }

    /** PowerManager's thermal status (0 = none … 6 = shutdown) and its headroom forecast (1.0 = throttling starts; null when the phone does not say). */
    private fun thermal(): Map<String, Any?> {
        val pm = getSystemService(PowerManager::class.java)
        val headroom = pm.getThermalHeadroom(0)
        return linkedMapOf("status" to pm.currentThermalStatus, "headroom" to if (headroom.isNaN()) null else headroom.toDouble())
    }

    private fun record(m: TypedDecisions, runs: List<Searched>, before: Map<String, Any?>, after: Map<String, Any?>): Map<String, Any?> = linkedMapOf(
        "app" to "samples/finder",
        "epoch_s" to System.currentTimeMillis() / 1000,
        "model" to "${m.info.repoId}@${m.info.commit}",
        "variant" to m.info.variantId,
        "profile" to m.info.profileId,
        "questions" to Finder.QUESTIONS.mapValues { it.value.toMap() },
        "queries" to runs.mapIndexed { i, s ->
            linkedMapOf(
                "i" to i,
                "id" to s.sentence?.id,
                "text" to s.result.text,
                "answers" to s.result.filters.keys,
                "expected" to s.sentence?.expected?.keys,
                "agree" to s.sentence?.let { e -> "${Facet.values().count { s.result.filters[it] == e.expected[it] }}/${Facet.values().size}" },
                "probabilities" to s.result.probabilities,
                "question_ms" to s.result.questionMs,
                "total_ms" to s.result.totalMs,
                "state_tokens" to s.result.stateTokens,
                "kept" to s.kept.map { it.id },
                "kept_count" to s.kept.size,
                "screen_line" to s.line,
            )
        },
        "load_warmup_ms" to decisions.warmupMs,
        "thermal_before" to before,
        "thermal_after" to after,
        "device" to Build.MODEL,
        "soc" to "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}",
        "build" to Build.DISPLAY,
        "android" to Build.VERSION.RELEASE,
        "sdk" to m.info.sdkVersion,
        "runtime" to "${m.info.runtime} ${m.info.runtimeVersion}",
    )

    private fun writeRecord(record: Map<String, Any?>): File? = try {
        File(getExternalFilesDir(null), "finder-result-${record["epoch_s"]}.json").apply { writeText(Json.dumps(record) + "\n") }
    } catch (e: Exception) {
        Log.w(TAG, "result file not written: ${e.message}")
        null
    }

    private fun q(s: String) = "\"" + s.replace("\n", " ").replace("\"", "'") + "\""

    companion object {
        const val TAG = "finder"
        const val EXTRA_AUTOSTART = "autostart"
        const val EXTRA_DELAY = "delay"
        const val EXTRA_BACKEND = "backend"
        private const val HANDLED = "io.github.johnrocky.hfmodels.samples.finder.HANDLED"
        private const val MODEL_NAME = "GLiNER2.5-Decide s128"
        private const val NPU_COMPILE_LINE = "Compiling for the NPU (first load only)"
        /** Recording mode: per typed character, before Find, Find shown pressed, and each result on screen. */
        const val TYPE_MS = 35L
        const val PAUSE_MS = 300L
        const val PRESSED_MS = 150L
        const val HOLD_MS = 2500L
    }
}
