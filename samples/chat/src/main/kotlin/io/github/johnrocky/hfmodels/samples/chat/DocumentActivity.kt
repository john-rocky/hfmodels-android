package io.github.johnrocky.hfmodels.samples.chat

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import com.google.ai.edge.litertlm.BenchmarkInfo
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
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
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Paste a long document, ask one question at a thinking level. Spark-X2.6-1.7B reads the whole document on the phone and
 * answers; its reasoning streams above the answer. Every question gets a conversation of its own with no system prompt.
 * The model file's runtime floor is LiteRT-LM 0.19.0, above the SDK's pin, so this screen needs the build line in README.md.
 */
class DocumentActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var models: HfModels
    private var chat: ChatModel? = null
    private var backendName = ""
    private var replyJob: Job? = null
    private var runTag: String? = null
    private var runCount = 0
    private var recording = false

    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var document: EditText
    private lateinit var documentSize: TextView
    private lateinit var question: EditText
    private lateinit var level: RadioGroup
    private lateinit var load: Button
    private lateinit var ask: Button
    private lateinit var stop: Button
    private lateinit var replyScroll: ScrollView
    private lateinit var thought: TextView
    private lateinit var answer: TextView
    private lateinit var stats: TextView

    // The status line's clock during one question.
    private var phase = ""
    private var startedNanos = 0L
    private val clock = object : Runnable {
        override fun run() {
            status.text = String.format(Locale.US, "%s · %.1f s", phase, (SystemClock.elapsedRealtimeNanos() - startedNanos) / 1e9)
            handler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT))
        setContentView(R.layout.activity_document)
        title = findViewById(R.id.title); status = findViewById(R.id.status)
        document = findViewById(R.id.document); documentSize = findViewById(R.id.document_size); question = findViewById(R.id.question)
        level = findViewById(R.id.level); load = findViewById(R.id.load); ask = findViewById(R.id.ask); stop = findViewById(R.id.stop)
        replyScroll = findViewById(R.id.reply_scroll); thought = findViewById(R.id.thought); answer = findViewById(R.id.answer); stats = findViewById(R.id.stats)
        // The bars and the keyboard go as padding on the outer frame, so the column between them resizes.
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsets.CONSUMED
        }
        // The reply follows its last line as the text grows.
        findViewById<View>(R.id.reply_column).addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> replyScroll.scrollTo(0, v.height) }
        title.text = "$MODEL_NAME · ${Build.MODEL}"
        status.text = "Press Load (1.7 GB on first use)."
        document.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { showDocumentSize() }
        })
        showDocumentSize()
        // Creates /sdcard/Android/data/<package>/files/, where a model copy pushed with adb is picked up (README.md).
        getExternalFilesDir(null)
        models = HfModels(applicationContext)

        load.setOnClickListener { loadModel() }
        ask.setOnClickListener { askQuestion() }
        stop.setOnClickListener { replyJob?.cancel() }   // cancelling the collector stops the model too
        findViewById<Button>(R.id.examples).setOnClickListener { v ->
            val names = examples()
            PopupMenu(this, v).apply {
                for (i in 0 until names.length()) menu.add(0, i, i, names.getJSONObject(i).getString("name"))
                setOnMenuItemClickListener { item -> showExample(item.itemId, 1); true }
            }.show()
        }
        findViewById<View>(R.id.column).requestFocus()
        recordingEntry(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recordingEntry(intent)
    }

    private fun showDocumentSize() {
        documentSize.text = String.format(Locale.US, "%,d characters · the model holds 4,096 tokens in all", document.text.length)
    }

    private fun examples() = JSONArray(assets.open("examples.json").bufferedReader().use { it.readText() })

    private fun showExample(index: Int, repeat: Int) {
        val e = examples().getJSONObject(index)
        document.setText(List(repeat) { e.getString("document") }.joinToString("\n\n"))
        question.setText(e.getString("question"))
    }

    private fun selectedLevel() = when (level.checkedRadioButtonId) {
        R.id.level_none -> "none"
        R.id.level_high -> "high"
        else -> "low"
    }

    private fun loadModel() {
        load.isEnabled = false
        scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            try {
                val descriptor = assets.open(DESCRIPTOR_ASSET).bufferedReader().use { it.readText() }
                val model = withBenchmarkNumbers {
                    models.fromPretrained(ModelRef(MODEL_ID, revision = MODEL_COMMIT), Tasks.Chat, LoadOptions(descriptorJson = descriptor)) { e ->
                        status.text = when (e) {
                            is LoadEvent.Resolving -> "Resolving $MODEL_ID"
                            is LoadEvent.DownloadStarted -> "Downloading ${e.totalBytes shr 20} MB"
                            is LoadEvent.Downloading -> "Downloading ${e.bytes shr 20} / ${e.totalBytes shr 20} MB"
                            is LoadEvent.Verifying -> "Verifying"
                            is LoadEvent.Initializing -> "Initializing profile ${e.profileId}"
                            is LoadEvent.Fallback -> "Fallback: ${e.reason}"
                            is LoadEvent.Ready -> "Ready"
                        }
                    }
                }
                chat = model
                val i = model.info
                backendName = i.components["language"]?.initialized?.name ?: i.profileId
                title.text = "$MODEL_NAME · ${Build.MODEL} · $backendName"
                status.text = "Ready · LiteRT-LM ${i.runtimeVersion}"
                ask.isEnabled = true
                val loadMs = SystemClock.elapsedRealtime() - t0
                Log.i(TAG, "READY ${i.repoId}@${i.commit.take(8)} ${i.variantId}/${i.profileId} backend=$backendName runtime=${i.runtimeVersion} load_ms=$loadMs fallback=${i.fallbackHistory}")
                record("load.json") {
                    put("repo", i.repoId); put("commit", i.commit); put("variant", i.variantId); put("profile", i.profileId); put("backend", backendName)
                    put("runtime", i.runtimeVersion); put("load_ms", loadMs); put("fallback", i.fallbackHistory.joinToString(" | ")); put("notes", i.notes.joinToString(" | "))
                }
            } catch (e: ModelException) {
                loadFailed("${e.code}: ${e.reason}", t0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loadFailed("Failed: $e", t0)
            }
        }
    }

    private fun loadFailed(reason: String, t0: Long) {
        status.text = reason
        load.isEnabled = true
        Log.w(TAG, "LOAD FAILED $reason")
        record("load.json") { put("error", reason); put("load_ms", SystemClock.elapsedRealtime() - t0) }
    }

    /** The engine keeps token counts and rates only when this flag is on at its creation. Read once, so it is put back at once. */
    @OptIn(ExperimentalApi::class)
    private suspend fun <T> withBenchmarkNumbers(block: suspend () -> T): T {
        val before = ExperimentalFlags.enableBenchmark
        ExperimentalFlags.enableBenchmark = true
        try {
            return block()
        } finally {
            ExperimentalFlags.enableBenchmark = before
        }
    }

    /**
     * One conversation for one question. The level reaches the template through extraContext; the thinking on / off through
     * [askQuestion]'s GenerationOptions. With the channel filter off the runtime does not read the document a second time
     * after the answer to drop the reasoning from its memory: no later turn would read that memory here.
     */
    @OptIn(ExperimentalApi::class)
    private suspend fun open(model: ChatModel, level: String): ChatSession {
        val before = ExperimentalFlags.filterChannelContentFromKvCache
        ExperimentalFlags.filterChannelContentFromKvCache = false
        try {
            return model.createConversation(ConversationConfig(extraContext = if (level == "none") emptyMap() else mapOf("reasoning_effort" to level)))
        } finally {
            ExperimentalFlags.filterChannelContentFromKvCache = before
        }
    }

    private fun askQuestion() {
        val model = chat ?: return
        val tag = runTag ?: "run_${++runCount}"
        runTag = null
        val doc = document.text.toString().trim()
        val q = question.text.toString().trim()
        if (doc.isEmpty() || q.isEmpty()) {
            status.text = if (doc.isEmpty()) "Paste a document first." else "Type a question first."
            record("$tag.json") { put("refused", status.text.toString()) }
            return
        }
        val lv = selectedLevel()
        val prompt = "Here is a document:\n\n<document>\n$doc\n</document>\n\nQuestion: $q\n\nPut the final answer on the last line as: Answer: <answer>"
        thought.text = ""; answer.text = ""; stats.text = ""
        ask.isEnabled = false; stop.isEnabled = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        phase = "Reading the document"
        startedNanos = SystemClock.elapsedRealtimeNanos()
        handler.removeCallbacks(clock)
        clock.run()
        replyJob = scope.launch {
            var session: ChatSession? = null
            var info: BenchmarkInfo? = null
            var failure: String? = null
            var stopped = false
            var chunks = 0; var firstNanos = 0L; var lastNanos = 0L
            val thoughtText = StringBuilder(); val answerText = StringBuilder()
            try {
                session = open(model, lv)
                session.stream(Contents.of(Content.Text(prompt)), GenerationOptions(enableThinking = lv != "none")).collect { m ->
                    val now = SystemClock.elapsedRealtimeNanos()
                    if (chunks++ == 0) firstNanos = now
                    lastNanos = now
                    m.channels.values.firstOrNull()?.takeIf { it.isNotEmpty() }?.let { thought.append(it); thoughtText.append(it); phase = "Thinking" }
                    m.text.takeIf { it.isNotEmpty() }?.let { answer.append(it); answerText.append(it); phase = "Answering" }
                }
                info = session.benchmarkInfo()
            } catch (e: CancellationException) {
                stopped = true
                throw e
            } catch (e: ModelException) {
                failure = failureLine(e)
            } catch (e: Exception) {
                failure = "Failed: $e"
            } finally {
                withContext(NonCancellable) {
                    runCatching { session?.closeAndJoin() }
                    handler.removeCallbacks(clock)
                    val elapsed = (SystemClock.elapsedRealtimeNanos() - startedNanos) / 1e9
                    status.text = when {
                        stopped -> String.format(Locale.US, "Stopped · %.1f s", elapsed)
                        failure != null -> failure
                        chunks == 0 -> "The model wrote nothing."
                        else -> String.format(Locale.US, "Done · %.1f s", (lastNanos - startedNanos) / 1e9)
                    }
                    stats.text = info?.let { statsLine(it) } ?: ""
                    ask.isEnabled = chat != null; stop.isEnabled = false
                    if (!recording) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    Log.i(TAG, "ASKED tag=$tag level=$lv status=${status.text} stats=${stats.text} chunks=$chunks")
                    record("$tag.json") {
                        put("device", Build.MODEL); put("level", lv); put("backend", backendName); put("prompt_sha256", sha256(prompt)); put("document_chars", doc.length); put("question", q)
                        put("status_line", status.text.toString()); put("stats_line", stats.text.toString()); put("error", failure ?: JSONObject.NULL); put("stopped", stopped)
                        put("first_chunk_ms", if (chunks > 0) (firstNanos - startedNanos) / 1e6 else -1.0); put("last_chunk_ms", if (chunks > 0) (lastNanos - startedNanos) / 1e6 else -1.0); put("chunks", chunks)
                        info?.let { i ->
                            put("prefill_tokens", i.lastPrefillTokenCount); put("prefill_tps", i.lastPrefillTokensPerSecond)
                            put("decode_tokens", i.lastDecodeTokenCount); put("decode_tps", i.lastDecodeTokensPerSecond); put("first_token_s_runtime", i.timeToFirstTokenInSecond)
                        }
                        put("thought", thoughtText.toString()); put("answer", answerText.toString())
                    }
                }
            }
        }
    }

    /** The two ways a long document ends, in the words a reader can act on; the runtime's own sentence for anything else. */
    private fun failureLine(e: ModelException) = when {
        "too long" in e.reason -> "This document is too long: the model holds 4,096 tokens, the answer included."
        "kv-cache size reached" in e.reason || "Max number of tokens reached" in e.reason ->
            "The model's 4,096 tokens ran out before the answer finished. Try another thinking level or a shorter document."
        else -> "${e.code}: ${e.reason}"
    }

    /** The runtime's own counts and rates, not the stream's: a streamed chunk can carry several tokens. */
    private fun statsLine(i: BenchmarkInfo): String {
        val read = if (i.lastPrefillTokensPerSecond > 0) i.lastPrefillTokenCount / i.lastPrefillTokensPerSecond else 0.0
        return String.format(Locale.US, "Read %,d tokens in %.1f s · wrote %,d tokens at %.1f tok/s · %s", i.lastPrefillTokenCount, read, i.lastDecodeTokenCount, i.lastDecodeTokensPerSecond, backendName)
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private val debuggable get() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Debug builds only: one JSON per load and per question under files/runs/, the recording entry's checks read them. */
    private fun record(name: String, fill: JSONObject.() -> Unit) {
        if (!debuggable) return
        val dir = File(getExternalFilesDir(null), "runs").apply { mkdirs() }
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(JSONObject().apply(fill).toString(1))
        tmp.renameTo(File(dir, name))
    }

    /**
     * Debug builds only, the recording tool: an intent drives the same fields and the same buttons a finger does.
     * am start -n <package>/.DocumentActivity --ei example 1 --es level low --ez load true --es tag t1 --ez ask true
     * Others: --ei repeat N (the example's document N times), --es question TEXT, --ez clear true (both fields empty),
     * --ez stop true, --ez keep_frames true (one moving pixel, screenrecord writes no frame for a still screen).
     */
    private fun recordingEntry(i: Intent) {
        if (!debuggable || i.extras == null) return
        recording = true
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (i.getBooleanExtra("clear", false)) { document.setText(""); question.setText("") }
        i.getIntExtra("example", -1).takeIf { it >= 0 }?.let { showExample(it, i.getIntExtra("repeat", 1)) }
        i.getStringExtra("question")?.let { question.setText(it) }
        when (i.getStringExtra("level")) {
            "none" -> level.check(R.id.level_none)
            "low" -> level.check(R.id.level_low)
            "high" -> level.check(R.id.level_high)
        }
        i.getStringExtra("tag")?.let { runTag = it }
        if (i.getBooleanExtra("keep_frames", false)) startTicking()
        if (i.getBooleanExtra("load", false) && load.isEnabled) load.performClick()
        if (i.getBooleanExtra("ask", false) && ask.isEnabled) ask.performClick()
        if (i.getBooleanExtra("stop", false) && stop.isEnabled) stop.performClick()
    }

    private fun startTicking() {
        val tick = findViewById<View>(R.id.tick)
        if (tick.visibility == View.VISIBLE) return
        tick.visibility = View.VISIBLE
        var odd = false
        handler.post(object : Runnable {
            override fun run() {
                odd = !odd
                tick.setBackgroundColor(if (odd) 0xFFFEFEFE.toInt() else Color.WHITE)
                handler.postDelayed(this, 100)
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        chat?.close()
        models.close()
    }

    private companion object {
        const val TAG = "document"
        const val MODEL_NAME = "Spark-X2.6-1.7B"
        const val MODEL_ID = "litert-community/Spark-X2.6-1.7B"
        const val MODEL_COMMIT = "127d3acbd0088c1e7fe792d5afc86c5131231fda"
        const val DESCRIPTOR_ASSET = "spark-x2.6-1.7b.hfmodels.json"
    }
}
