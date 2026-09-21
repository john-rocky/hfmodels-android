package io.github.johnrocky.hfmodels.samples.decide

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.activity.ComponentActivity
import io.github.johnrocky.hfmodels.BackendKind
import io.github.johnrocky.hfmodels.LoadEvent
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.decide.Answer
import io.github.johnrocky.hfmodels.decide.Decisions
import io.github.johnrocky.hfmodels.decide.Question
import io.github.johnrocky.hfmodels.decide.TypedDecisions
import io.github.johnrocky.hfmodels.samples.decide.gliner.GlinerAssets
import io.github.johnrocky.hfmodels.samples.decide.gliner.GlinerExtractor
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Three uses of one decision model, each with the measured milliseconds on screen:
 *  (a) voice gate: every utterance -> "does it ask the assistant for something?" (noul) -> only then a language model would run;
 *  (b) clipboard: before pasting, what the text holds and which pieces the chosen purpose needs (choice + noul), then the spans (GLiNER2.5-Small-LiteRT);
 *  (c) query x passages: does each passage answer the query (noul) and how well (score), ranked.
 * Everything model-related goes through the SDK (`DecisionModels`); this file drives the UI.
 */
class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var decisions: DecisionModels
    private var voice: VoiceGate? = null
    private var extractor: GlinerExtractor? = null

    private lateinit var status: TextView
    private lateinit var voiceLog: TextView
    private lateinit var clipLog: TextView
    private lateinit var rankLog: TextView
    private lateinit var partial: TextView
    private lateinit var language: Spinner
    private lateinit var purpose: Spinner

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MIC_REQUEST) { if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startListening() else status.text = "microphone permission denied" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        decisions = DecisionModels(this)
        status = findViewById(R.id.status)
        voiceLog = findViewById(R.id.voice_log); clipLog = findViewById(R.id.clip_log); rankLog = findViewById(R.id.rank_log)
        partial = findViewById(R.id.partial)
        val flipper: ViewFlipper = findViewById(R.id.flipper)
        findViewById<RadioGroup>(R.id.tabs).setOnCheckedChangeListener { _, id ->
            flipper.displayedChild = when (id) { R.id.tab_clip -> 1; R.id.tab_rank -> 2; else -> 0 }
        }

        val variant: Spinner = findViewById(R.id.variant)
        variant.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, decisions.variants)
        val backend: Spinner = findViewById(R.id.backend)
        backend.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("auto", "gpu", "cpu"))
        findViewById<Button>(R.id.load).setOnClickListener {
            val kind = when (backend.selectedItem as String) { "gpu" -> BackendKind.GPU; "cpu" -> BackendKind.CPU; else -> null }
            scope.launch {
                try {
                    val t0 = System.nanoTime()
                    val m = decisions.load(variant.selectedItem as String, kind) { e -> status.text = e.describe() }
                    status.text = "Ready: ${m.info.variantId} on ${m.info.profileId}, window ${m.limits.windowTokens}, ${(System.nanoTime() - t0) / 1_000_000} ms total; ${m.info.notes.firstOrNull() ?: ""}"
                } catch (e: ModelException) {
                    status.text = "${e.code}: ${e.reason}" + if (e.code.name == "MODEL_NOT_FOUND_OR_INACCESSIBLE" || e.code.name == "NETWORK_ERROR") "\nThe graphs are not published yet: push them to ${getExternalFilesDir(null)?.path}/ (see README.md)" else ""
                }
            }
        }
        findViewById<Button>(R.id.release).setOnClickListener { scope.launch { decisions.release(); status.text = "Released" } }
        // For scripted runs: `adb shell am start -n <pkg>/.MainActivity --es variant en_s256_fp32 --es backend gpu` preselects and loads.
        intent.getStringExtra("variant")?.let { v -> decisions.variants.indexOf(v).takeIf { it >= 0 }?.let { variant.setSelection(it) } }
        intent.getStringExtra("backend")?.let { b -> listOf("auto", "gpu", "cpu").indexOf(b).takeIf { it >= 0 }?.let { backend.setSelection(it) } }
        if (intent.hasExtra("variant")) findViewById<Button>(R.id.load).post { findViewById<Button>(R.id.load).performClick() }

        // (a) voice gate
        language = findViewById(R.id.language)
        language.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("en-US", "ja-JP"))
        findViewById<Button>(R.id.listen).setOnClickListener {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startListening() else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_REQUEST)
        }
        findViewById<Button>(R.id.stop_listen).setOnClickListener { voice?.stop() }
        val typed: EditText = findViewById(R.id.typed_utterance)
        findViewById<Button>(R.id.decide_typed).setOnClickListener { typed.text.toString().trim().takeIf { it.isNotEmpty() }?.let { gate(it) } }

        // (b) clipboard
        purpose = findViewById(R.id.purpose)
        purpose.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, PURPOSES.keys.toList())
        val clipText: EditText = findViewById(R.id.clip_text)
        findViewById<Button>(R.id.read_clip).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            if (text.isNullOrBlank()) clipLog.text = "clipboard is empty" else clipText.setText(text)
        }
        findViewById<Button>(R.id.gliner_setup).setOnClickListener { setupExtractor() }
        findViewById<Button>(R.id.decide_clip).setOnClickListener { clipText.text.toString().trim().takeIf { it.isNotEmpty() }?.let { clipboard(it) } }

        // (c) query x passages
        val query: EditText = findViewById(R.id.query)
        val passages: EditText = findViewById(R.id.passages)
        query.setText("When does the store close on Sundays?")
        passages.setText("Our store hours are 9 to 6 on weekdays and 10 to 5 on Sundays.\nThe parking lot is free for the first two hours.\nOn Sundays we close at 5 pm; the pharmacy counter closes at 4.\n日曜日は17時に閉店します。\nWe were founded in 1998 in a small garage.")
        findViewById<Button>(R.id.rank).setOnClickListener {
            val q = query.text.toString().trim()
            val ps = passages.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (q.isNotEmpty() && ps.isNotEmpty()) rank(q, ps)
        }
    }

    private fun model(): TypedDecisions? = decisions.model ?: run { status.text = "Load the model first"; null }

    private fun startListening() {
        if (voice == null) voice = VoiceGate(this, onPartial = { partial.text = it }, onUtterance = { gate(it) }, onStatus = { partial.text = it })
        voice!!.start(language.selectedItem as String)
    }

    /** (a): one utterance -> noul gate + a kind. */
    private fun gate(utterance: String) {
        val m = model() ?: return
        scope.launch {
            try {
                val d = m.decide(mapOf("utterance" to utterance), GATE_QUESTIONS)
                val need = d.answers.getValue("needs_response") as Answer.Noul
                val kind = d.answers.getValue("kind") as Answer.Choice
                // The gate: the utterance is a question or a request (choice), or the noul says the assistant is addressed.
                val open = kind.choice == "question" || kind.choice == "request_or_command" || need.noul >= 0.5
                voiceLog.text = "[${if (open) "OPEN " else "closed"}] \"$utterance\"\n   kind=${kind.choice} (${"%.2f".format(kind.probabilities.getValue(kind.choice))}), addressed p=${"%.2f".format(need.noul)}  ${d.timing.questionMs.joinToString("+") { "%.0f".format(it) }} ms = ${"%.0f".format(d.timing.totalMs)} ms\n" + voiceLog.text
            } catch (e: ModelException) { voiceLog.text = "${e.code}: ${e.reason}\n" + voiceLog.text }
        }
    }

    /** (b): what the text holds, which pieces the purpose needs, then the spans. */
    private fun clipboard(text: String) {
        val m = model() ?: return
        val purposeName = purpose.selectedItem as String
        val fields = PURPOSES.getValue(purposeName)
        scope.launch {
            try {
                val questions = LinkedHashMap<String, Question>()
                questions["kind"] = KIND_QUESTION
                questions["personal_data"] = Question.Noul("Does the text contain personal data (a name, an address, a phone number, an email address or an account number)?")
                for ((id, phrase) in fields) questions[id] = Question.Noul("The clipboard text contains $phrase, and the purpose needs it.")
                val d: Decisions = m.decide(linkedMapOf("purpose" to purposeName, "clipboard" to text), questions)
                val kind = d.answers.getValue("kind") as Answer.Choice
                val sb = StringBuilder()
                sb.append("kind=${kind.choice} (${"%.2f".format(kind.probabilities.getValue(kind.choice))}), personal data p=${"%.2f".format((d.answers.getValue("personal_data") as Answer.Noul).noul)}\n")
                sb.append("needed for \"$purposeName\":\n")
                for ((id, _) in fields) { val a = d.answers.getValue(id) as Answer.Noul; sb.append("   ${if (a.noul >= 0.5) "yes" else "no "} p=${"%.2f".format(a.noul)}  $id\n") }
                sb.append("decision: ${d.timing.questionMs.size} questions, ${d.timing.questionMs.joinToString("+") { "%.0f".format(it) }} ms = ${"%.0f".format(d.timing.totalMs)} ms (state ${d.stateTokens} tokens${if (d.truncated) ", truncated" else ""})\n")
                val ex = extractor
                if (ex == null) sb.append("extraction: press 'Get extractor' for the spans\n")
                else {
                    val r = withContext(Dispatchers.IO) { ex.extract(text) }
                    sb.append("spans (GLiNER2.5-Small, ${r.window} window, ${"%.0f".format(r.timing.tokenizeEmbedMs + r.timing.graphMs + r.timing.decodeMs)} ms):\n")
                    for (s in r.spans) sb.append("   ${s.label}: \"${s.text}\" (${"%.2f".format(s.confidence)})\n")
                    if (r.spans.isEmpty()) sb.append("   none\n")
                }
                clipLog.text = sb.toString() + "\n" + clipLog.text
            } catch (e: ModelException) { clipLog.text = "${e.code}: ${e.reason}\n" + clipLog.text }
            catch (e: Exception) { clipLog.text = "${e.javaClass.simpleName}: ${e.message}\n" + clipLog.text }
        }
    }

    private fun setupExtractor() {
        scope.launch {
            try {
                clipLog.text = "extraction model: checking files\n" + clipLog.text
                val ex = withContext(Dispatchers.IO) {
                    GlinerAssets.ensure(filesDir, getExternalFilesDir(null)?.let { File(it, "gliner") }) { msg -> scope.launch { clipLog.text = "extraction model: $msg\n" + clipLog.text } }
                    GlinerExtractor(GlinerAssets.dir(filesDir)).also { it.initialize(GlinerExtractor.Backend.GPU); it.warmUp("The store in Osaka opens on Monday.", GlinerExtractor.Backend.GPU) }
                }
                extractor = ex
                clipLog.text = "extraction model ready (GPU FP32)\n" + clipLog.text
            } catch (e: Exception) { clipLog.text = "extraction model: ${e.javaClass.simpleName}: ${e.message}\n" + clipLog.text }
        }
    }

    /** (c): each passage against the query, ranked by the noul. */
    private fun rank(query: String, passages: List<String>) {
        val m = model() ?: return
        scope.launch {
            try {
                val rows = ArrayList<Triple<String, Decisions, Int>>()
                for ((i, p) in passages.withIndex()) rows += Triple(p, m.decide(linkedMapOf("query" to query, "passage" to p), RANK_QUESTIONS), i)
                val sorted = rows.sortedByDescending { (it.second.answers.getValue("answers") as Answer.Noul).noul }
                val sb = StringBuilder("query: $query\n")
                for ((p, d, i) in sorted) {
                    val a = d.answers.getValue("answers") as Answer.Noul
                    val s = d.answers.getValue("relevance") as Answer.Score
                    sb.append("${"%.2f".format(a.noul)}  score ${"%.2f".format(s.score)}/3  ${"%.0f".format(d.timing.totalMs)} ms  #${i + 1} ${p.take(70)}\n")
                }
                sb.append("total ${"%.0f".format(rows.sumOf { it.second.timing.totalMs })} ms for ${rows.size} passages x 2 questions\n")
                rankLog.text = sb.toString() + "\n" + rankLog.text
            } catch (e: ModelException) { rankLog.text = "${e.code}: ${e.reason}\n" + rankLog.text }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voice?.destroy()
        val ex = extractor; extractor = null
        val d = decisions
        scope.cancel()
        CoroutineScope(Dispatchers.IO).launch { withContext(NonCancellable) { runCatching { ex?.close() }; d.release(); d.models.closeAndJoin() } }
    }

    private fun LoadEvent.describe(): String = when (this) {
        is LoadEvent.Downloading -> "Downloading ${bytes / 1_000_000} / ${totalBytes / 1_000_000} MB"
        is LoadEvent.Ready -> "Ready"
        else -> toString()
    }

    companion object {
        private const val MIC_REQUEST = 1
        val GATE_QUESTIONS: Map<String, Question> = linkedMapOf(
            "needs_response" to Question.Noul("The speaker is asking the assistant a question or giving it an instruction."),
            "kind" to Question.Choice("What is this utterance?", linkedMapOf("question" to "asks for information", "request_or_command" to "asks for an action", "statement_or_remark" to "says something without asking", "filler_or_noise" to "fragments, hesitations or nothing meaningful")),
        )
        val KIND_QUESTION = Question.Choice("What does the clipboard text hold?", linkedMapOf(
            "postal_address" to null, "phone_number" to null, "email_address" to null, "url" to null, "order_or_tracking_number" to null,
            "date_or_time" to null, "code_or_password" to "a one-time code, password or key", "message_or_note" to "prose: a message, note or article", "other" to null,
        ))
        /** purpose -> (question id -> the phrase the noul asks about) */
        val PURPOSES: Map<String, Map<String, String>> = linkedMapOf(
            "fill a shipping form" to linkedMapOf("recipient_name" to "the recipient's name", "postal_address" to "a postal address", "phone_number" to "a phone number", "order_number" to "an order or tracking number"),
            "save a contact" to linkedMapOf("person_name" to "a person's name", "phone_number" to "a phone number", "email_address" to "an email address", "organization" to "a company or organization name"),
            "add a calendar event" to linkedMapOf("date_or_time" to "a date or a time", "place" to "a place or venue", "event_title" to "what the event is"),
            "reply to a message" to linkedMapOf("sender_name" to "who wrote it", "question_asked" to "a question the sender asks", "deadline" to "a deadline or due date"),
        )
        val RANK_QUESTIONS: Map<String, Question> = linkedMapOf(
            "answers" to Question.Noul("Does the passage answer the query?"),
            "relevance" to Question.Score("How relevant is the passage to the query?", listOf("unrelated", "same topic, no answer", "partly answers it", "answers it directly")),
        )
    }
}
