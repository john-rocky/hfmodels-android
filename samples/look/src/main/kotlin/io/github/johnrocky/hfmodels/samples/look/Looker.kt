package io.github.johnrocky.hfmodels.samples.look

import android.os.SystemClock
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.SamplerConfig
import io.github.johnrocky.hfmodels.BackendPolicy
import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.HfModels
import io.github.johnrocky.hfmodels.InputKind
import io.github.johnrocky.hfmodels.LoadEvent
import io.github.johnrocky.hfmodels.LoadOptions
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.ModelRef
import io.github.johnrocky.hfmodels.NetworkPolicy
import io.github.johnrocky.hfmodels.Tasks
import io.github.johnrocky.hfmodels.litertlm.ChatModel
import io.github.johnrocky.hfmodels.litertlm.ChatSession
import io.github.johnrocky.hfmodels.litertlm.GenerationOptions
import io.github.johnrocky.hfmodels.litertlm.SessionState
import io.github.johnrocky.hfmodels.litertlm.text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** A photo the model is asked about: the JPEG bytes it gets. A new pick is a new object, and so a new conversation. */
class Picture(val bytes: ByteArray)

/** One answer: the streamed text and its times. */
class Reply(
    val text: String,
    val timing: Timing,
    /** True when this turn carried the photo (a new conversation); false for a text-only turn of the open one. */
    val firstTurn: Boolean,
    /** Opening the conversation, on a first turn. */
    val conversationMs: Double?,
    /** The load this question waited for (no model was loaded, or the loaded one had held a conversation); null when none. */
    val loadMs: Long?,
)

/**
 * The model and the one conversation it holds, for the screen and the device check alike.
 *
 * A conversation opens with a photo and its first question (turn 1: the image and the text); each further
 * question about the same photo is a text-only turn of that conversation. The decoder keeps a running state
 * (a Qwen3.5-style hybrid), and a new conversation on the same loaded model starts from the previous
 * conversation's state (google-ai-edge/LiteRT-LM#3165), so a loaded model holds one conversation only: a new
 * photo, or a turn that was stopped or failed (its conversation is then INVALID), closes the model and loads
 * it again before the next conversation opens.
 *
 * One caller at a time: the screen runs one job, the check runs its steps in order.
 */
class Looker(
    private val models: HfModels,
    /** Passed as `LoadOptions.descriptorJson`; null lets the SDK find the descriptor (the repo's, then the bundled catalog's). */
    private val descriptorJson: String?,
    /** `vl_int8` or `vl_int8vis` ([Variants]). */
    var variant: String,
    var revision: String = REVISION,
    var policy: BackendPolicy = BackendPolicy.Auto,
    /**
     * Null: from the phone's store with no request at all (NetworkPolicy.Offline), and with the network only
     * when the file is not on the phone yet. A policy here is used as it is.
     */
    var network: NetworkPolicy? = null,
) {
    companion object {
        const val REPO = "litert-community/MiniCPM-V-4.7-1B"
        /** The Hub commit the descriptor in assets was generated from. */
        const val REVISION = "8298b1bd5531737290a09da5f7a66402e49e7a7d"
        const val DESCRIPTOR_ASSET = "minicpm-v-4.7-1b.hfmodels.json"
        const val MAX_OUTPUT_TOKENS = 256

        /** An error as the screen shows it: the SDK's code first. */
        fun describe(t: Throwable): String = if (t is ModelException) "${t.code}: ${t.reason}" else "${t.javaClass.simpleName}: ${t.message}"
    }

    enum class Stage { CLOSING, LOADING, OPENING, ASKING }

    /** Called on the caller's dispatcher as [load] and [ask] move on. */
    var onStage: (Stage) -> Unit = {}
    /** The SDK's load events, on the caller's dispatcher. */
    var onEvent: (LoadEvent) -> Unit = {}

    var model: ChatModel? = null
        private set
    /** The last load's time, whether it fetched the file from the Hub, and the network policy it ran with. */
    var loadMs = 0L
        private set
    var downloaded = false
        private set
    var loadNetwork: NetworkPolicy? = null
        private set
    /** Loads that finished. */
    var loads = 0
        private set

    /** True once [model] has held a conversation: the next conversation needs a new load. */
    private var used = false
    private var session: ChatSession? = null
    private var sessionPicture: Picture? = null
    /** Turns answered on [session], and the time it took to open it. */
    private var sessionTurns = 0
    private var sessionOpenMs = 0.0

    /** The open conversation's state; null when none is open. */
    val sessionState: SessionState? get() = session?.state

    /** True when the loaded model has held a conversation: the next new conversation loads it again first. */
    val heldConversation: Boolean get() = model != null && used

    /** Loads the model unless one is loaded. Throws [ModelException] (the screen shows [describe]). */
    suspend fun load(): ChatModel {
        model?.let { return it }
        onStage(Stage.LOADING)
        downloaded = false
        val t0 = SystemClock.elapsedRealtime()
        val m = network?.let { loadWith(it) } ?: try {
            loadWith(NetworkPolicy.Offline)
        } catch (e: ModelException) {
            // Not on the phone yet (the first run): the same load with the network, which downloads the file.
            if (e.code != ErrorCode.OFFLINE_CACHE_MISS) throw e
            loadWith(NetworkPolicy.Any)
        }
        loadMs = SystemClock.elapsedRealtime() - t0
        loads++
        model = m
        used = false
        return m
    }

    private suspend fun loadWith(network: NetworkPolicy): ChatModel {
        loadNetwork = network
        return models.fromPretrained(
            ModelRef(REPO, revision = revision, variant = variant),
            Tasks.Chat,
            LoadOptions(descriptorJson = descriptorJson, backendPolicy = policy, networkPolicy = network, requiredInputs = setOf(InputKind.TEXT, InputKind.IMAGE)),
        ) { e ->
            if (e is LoadEvent.DownloadStarted) downloaded = true
            onEvent(e)
        }
    }

    /**
     * Opens a conversation for [picture] (the open one is closed; a model that has held one is closed and
     * loaded again) and returns the time it took.
     */
    suspend fun open(picture: Picture): Double {
        endConversation()
        model?.takeIf { used }?.let { held ->
            onStage(Stage.CLOSING)
            model = null
            withContext(NonCancellable) { held.closeAndJoin() }
        }
        val m = load()
        onStage(Stage.OPENING)
        // Marked before the native call: a conversation cancelled while it opens has still touched the model.
        used = true
        val c0 = SystemClock.elapsedRealtimeNanos()
        // Top-k 1: the same photo and question give the same answer. It is the engine's own default for a bundle
        // that declares no sampler (LiteRT-LM 0.16.1, CPU and GPU), written out so that it stays so.
        val config = ConversationConfig(samplerConfig = SamplerConfig(topK = 1, topP = 0.95, temperature = 1.0))
        val s = withContext(Dispatchers.Default) { m.createConversation(config) }
        session = s; sessionPicture = picture; sessionTurns = 0
        sessionOpenMs = (SystemClock.elapsedRealtimeNanos() - c0) / 1e6
        return sessionOpenMs
    }

    /**
     * One question about [picture]; [onText] gets each piece of the answer as it streams, with the ms since
     * `stream` was called (on a background thread). The first question in a conversation sends the photo with
     * the text; a further question about the same picture (the same object) is a text-only turn of that
     * conversation. Cancelling the caller stops the turn: its conversation is closed, and the next question
     * opens a new one on a new load.
     */
    suspend fun ask(picture: Picture, question: String, onText: (piece: String, ms: Double) -> Unit = { _, _ -> }): Reply {
        val loadsBefore = loads
        if (session == null || sessionPicture !== picture || session?.state != SessionState.READY) open(picture)
        val s = checkNotNull(session)
        val firstTurn = sessionTurns == 0
        val contents = if (firstTurn) {
            Contents.of(Content.ImageBytes(picture.bytes), Content.Text(question))
        } else {
            Contents.of(Content.Text(question))
        }
        onStage(Stage.ASKING)
        try {
            val text = StringBuilder()
            val chunks = ArrayList<Chunk>()
            val t0 = SystemClock.elapsedRealtimeNanos()
            withContext(Dispatchers.Default) {
                s.stream(contents, GenerationOptions(maxOutputTokens = MAX_OUTPUT_TOKENS)).collect { m ->
                    val at = SystemClock.elapsedRealtimeNanos()
                    val piece = m.text
                    chunks += Chunk(at, piece.length)
                    if (piece.isNotEmpty()) {
                        text.append(piece)
                        onText(piece, (at - t0) / 1e6)
                    }
                }
            }
            val t1 = SystemClock.elapsedRealtimeNanos()
            sessionTurns++
            return Reply(
                text = text.toString(),
                timing = Stats.of(t0, t1, chunks),
                firstTurn = firstTurn,
                conversationMs = if (firstTurn) sessionOpenMs else null,
                loadMs = if (loads != loadsBefore) loadMs else null,
            )
        } catch (t: Throwable) {
            // Stopped or failed: the session is INVALID (the runtime's rule) and the model has held it.
            endConversation()
            throw t
        }
    }

    /** Closes the open conversation, if any. The model stays loaded, but it has held a conversation. */
    suspend fun endConversation() {
        val s = session ?: return
        session = null; sessionPicture = null
        withContext(NonCancellable) { runCatching { s.closeAndJoin() } }
    }

    /** Closes the conversation and the model. A second call does nothing. */
    suspend fun close() {
        endConversation()
        val m = model ?: return
        model = null
        withContext(NonCancellable) { m.closeAndJoin() }
    }

    /** [close] for onDestroy, which cannot wait: the SDK finishes the close on its own threads. */
    fun closeNow() {
        session?.close(); session = null; sessionPicture = null
        model?.close(); model = null
    }
}
