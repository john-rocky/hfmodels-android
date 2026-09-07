package io.github.johnrocky.hfmodels.samples.chat

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import io.github.johnrocky.hfmodels.BackendKind
import io.github.johnrocky.hfmodels.BackendPolicy
import io.github.johnrocky.hfmodels.HfModels
import io.github.johnrocky.hfmodels.InputKind
import io.github.johnrocky.hfmodels.LoadEvent
import io.github.johnrocky.hfmodels.LoadOptions
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.ModelRef
import io.github.johnrocky.hfmodels.Tasks
import io.github.johnrocky.hfmodels.litertlm.ChatModel
import io.github.johnrocky.hfmodels.litertlm.ChatSession
import io.github.johnrocky.hfmodels.litertlm.SessionState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The whole app: an id -> a chat. Everything model-related goes through [HfModels]; this file only
 * drives a UI. Compare with a hand-rolled version (download, hash, engine, cancel, release) in the README.
 */
class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var models: HfModels
    private var chat: ChatModel? = null
    private var session: ChatSession? = null
    private var replyJob: Job? = null
    private var pendingImage: File? = null

    private lateinit var modelId: EditText
    private lateinit var backend: RadioGroup
    private lateinit var load: Button
    private lateinit var release: Button
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var transcriptScroll: ScrollView
    private lateinit var image: Button
    private lateinit var prompt: EditText
    private lateinit var send: Button
    private lateinit var stop: Button

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        // The runtime wants a path; copy the picked image into the app's cache.
        val f = File(cacheDir, "picked_image")
        contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        pendingImage = f
        image.text = "Image ✓"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        modelId = findViewById(R.id.model_id); backend = findViewById(R.id.backend)
        load = findViewById(R.id.load); release = findViewById(R.id.release); status = findViewById(R.id.status)
        transcript = findViewById(R.id.transcript); transcriptScroll = findViewById(R.id.transcript_scroll)
        image = findViewById(R.id.image); prompt = findViewById(R.id.prompt); send = findViewById(R.id.send); stop = findViewById(R.id.stop)
        models = HfModels(applicationContext)

        load.setOnClickListener { loadModel() }
        release.setOnClickListener { releaseModel() }
        send.setOnClickListener { sendPrompt() }
        stop.setOnClickListener { replyJob?.cancel() }   // cancelling the collector stops the model too
        image.setOnClickListener { pickImage.launch("image/*") }
    }

    private fun policy(): BackendPolicy = when (backend.checkedRadioButtonId) {
        R.id.cpu -> BackendPolicy.Require(BackendKind.CPU)
        R.id.gpu -> BackendPolicy.Require(BackendKind.GPU)
        else -> BackendPolicy.Auto
    }

    private fun loadModel() {
        val id = modelId.text.toString().trim()
        load.isEnabled = false
        scope.launch {
            try {
                val model = models.fromPretrained(ModelRef(id), Tasks.Chat, LoadOptions(backendPolicy = policy())) { e ->
                    status.text = when (e) {
                        is LoadEvent.Resolving -> "Resolving $id"
                        is LoadEvent.DownloadStarted -> "Downloading ${e.totalBytes shr 20} MB"
                        is LoadEvent.Downloading -> "Downloading ${e.bytes shr 20} / ${e.totalBytes shr 20} MB"
                        is LoadEvent.Verifying -> "Verifying"
                        is LoadEvent.Initializing -> "Initializing profile ${e.profileId}"
                        is LoadEvent.Fallback -> "Fallback: ${e.reason}"
                        is LoadEvent.Ready -> "Ready"
                    }
                }
                chat = model
                val i = model.info
                status.text = "Ready: ${i.repoId}@${i.commit.take(8)} ${i.variantId}/${i.profileId} " +
                    i.components.entries.joinToString(" ") { "${it.key}=${it.value.initialized}" } + " (LiteRT-LM ${i.runtimeVersion})"
                session = model.createConversation(ConversationConfig(systemInstruction = Contents.of("You are a helpful assistant.")))
                send.isEnabled = true; release.isEnabled = true
                image.isEnabled = InputKind.IMAGE in model.enabledInputs
            } catch (e: ModelException) {
                status.text = "${e.code}: ${e.reason}"
                load.isEnabled = true
            } catch (e: Exception) {
                status.text = "Failed: $e"
                load.isEnabled = true
            }
        }
    }

    private fun releaseModel() {
        val model = chat ?: return
        replyJob?.cancel()
        send.isEnabled = false; stop.isEnabled = false; release.isEnabled = false; image.isEnabled = false
        scope.launch {
            withContext(NonCancellable) { model.closeAndJoin() }
            chat = null; session = null
            status.text = "Released"
            load.isEnabled = true
        }
    }

    private fun sendPrompt() {
        val model = chat ?: return
        val text = prompt.text.toString().trim()
        if (text.isEmpty()) return
        prompt.text.clear()
        send.isEnabled = false; stop.isEnabled = true
        val img = pendingImage; pendingImage = null; image.text = "Image"
        append("You: $text\n"); append("Model: ")
        replyJob = scope.launch {
            try {
                // A cancelled session is not reusable (the runtime's own rule): open a new conversation for the next turn.
                val s = session?.takeIf { it.state == SessionState.READY } ?: model.createConversation(
                    ConversationConfig(systemInstruction = Contents.of("You are a helpful assistant.")),
                ).also { session = it }
                val contents = if (img != null) Contents.of(Content.ImageFile(img.absolutePath), Content.Text(text)) else Contents.of(Content.Text(text))
                s.stream(contents).collect { m -> append(m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }) }
            } catch (e: ModelException) {
                append("[${e.code}: ${e.reason}]")
            } finally {
                append("\n\n")
                send.isEnabled = chat != null; stop.isEnabled = false
            }
        }
    }

    private fun append(s: String) {
        transcript.append(s)
        transcriptScroll.post { transcriptScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        chat?.close()
        models.close()
    }
}
