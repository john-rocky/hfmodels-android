package io.github.johnrocky.hfmodels.samples.ask

import android.os.SystemClock
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import io.github.johnrocky.hfmodels.BackendPolicy
import io.github.johnrocky.hfmodels.HfModels
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

/** A picture the model is asked about: the image bytes (PNG or JPEG) and the context line of its first turn. */
class Picture(val bytes: ByteArray, val context: String = Prompt.CONTEXT)

/** One answer: the option index ([Answer.UNPARSED] when the text is not a letter of an option), the streamed text, the times. */
class Reply(
    val choice: Int,
    val text: String,
    /** From calling `stream` to the first chunk that carries text; null when no chunk did. */
    val answerMs: Double?,
    /** From calling `stream` to the end of the stream. */
    val streamMs: Double,
    /** True when this turn carried the picture (a new conversation); false for a text-only turn of the open one. */
    val firstTurn: Boolean,
    /** Opening the conversation, on a first turn. */
    val conversationMs: Double?,
    /** The load this question waited for (no model was loaded, or the loaded one had held a conversation); null when none. */
    val loadMs: Long?,
)

/**
 * The model and the one conversation it holds, for the screen and the device check alike.
 *
 * A conversation opens with a picture and its first question (turn 1: the image and the text); each
 * further question about the same picture is a text-only turn of that conversation. On this model
 * family a new conversation on the same loaded model starts from the previous conversation's state
 * (google-ai-edge/LiteRT-LM#3165), so a loaded model holds one conversation only: a new picture, or a
 * turn that was stopped or failed (its conversation is then INVALID), closes the model and loads it
 * again before the next conversation opens.
 *
 * One caller at a time: the screen runs one job, the check runs its steps in order.
 */
class Asker(
    private val models: HfModels,
    /** Passed as `LoadOptions.descriptorJson`; null lets the SDK find the descriptor (the repo's, then the bundled catalog's). */
    private val descriptorJson: String?,
    var revision: String = REVISION,
    var policy: BackendPolicy = BackendPolicy.Auto,
    var network: NetworkPolicy = NetworkPolicy.Any,
) {
    companion object {
        const val REPO = "litert-community/decider-2b-vision-LiteRT"
        /** The Hub commit the descriptor in assets was generated from; the bundled catalog pins the same one. */
        const val REVISION = "6c024e946bb8489f3faacb2514b0c61b355d1fdb"
        const val VARIANT = "int8"
        const val DESCRIPTOR_ASSET = "decider-2b-vision.hfmodels.json"

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
    /** The last load's time, and whether it fetched the file from the Hub. */
    var loadMs = 0L
        private set
    var downloaded = false
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
        val m = models.fromPretrained(
            ModelRef(REPO, revision = revision, variant = VARIANT),
            Tasks.Chat,
            LoadOptions(descriptorJson = descriptorJson, backendPolicy = policy, networkPolicy = network),
        ) { e ->
            if (e is LoadEvent.DownloadStarted) downloaded = true
            onEvent(e)
        }
        loadMs = SystemClock.elapsedRealtime() - t0
        loads++
        model = m
        used = false
        return m
    }

    /**
     * Opens a conversation for [picture] (the open one is closed; a model that has held one is closed and
     * loaded again) and returns the time it took. [ask] opens one by itself; the Demo opens it first so the
     * opening happens before the first question shows.
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
        val s = withContext(Dispatchers.Default) { m.createConversation(ConversationConfig()) }
        session = s; sessionPicture = picture; sessionTurns = 0
        sessionOpenMs = (SystemClock.elapsedRealtimeNanos() - c0) / 1e6
        return sessionOpenMs
    }

    /**
     * One question about [picture]. The first question in a conversation sends the image with the text; a
     * further question about the same picture (the same object) is a text-only turn of that conversation.
     * Cancelling the caller stops the turn: its conversation is closed, and the next question opens a new
     * one on a new load.
     */
    suspend fun ask(picture: Picture, question: String, options: List<String>): Reply {
        require(options.size in Typed.MIN_OPTIONS..Typed.MAX_OPTIONS) { "${options.size} options" }
        val loadsBefore = loads
        if (session == null || sessionPicture !== picture || session?.state != SessionState.READY) open(picture)
        val s = checkNotNull(session)
        val firstTurn = sessionTurns == 0
        val contents = if (firstTurn) {
            Contents.of(Content.ImageBytes(picture.bytes), Content.Text(Prompt.first(picture.context, question, options)))
        } else {
            Contents.of(Content.Text(Prompt.next(question, options)))
        }
        onStage(Stage.ASKING)
        try {
            val text = StringBuilder()
            var first = 0L
            val t0 = SystemClock.elapsedRealtimeNanos()
            withContext(Dispatchers.Default) {
                s.stream(contents, GenerationOptions(maxOutputTokens = 1)).collect { m ->
                    val piece = m.text
                    if (piece.isNotEmpty() && first == 0L) first = SystemClock.elapsedRealtimeNanos()
                    text.append(piece)
                }
            }
            val t1 = SystemClock.elapsedRealtimeNanos()
            sessionTurns++
            return Reply(
                choice = Answer.parse(text.toString(), options.size),
                text = text.toString(),
                answerMs = if (first == 0L) null else (first - t0) / 1e6,
                streamMs = (t1 - t0) / 1e6,
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
