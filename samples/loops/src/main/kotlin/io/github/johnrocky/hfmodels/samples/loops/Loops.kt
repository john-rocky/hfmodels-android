package io.github.johnrocky.hfmodels.samples.loops

import android.os.SystemClock
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.HfModels
import io.github.johnrocky.hfmodels.LoadEvent
import io.github.johnrocky.hfmodels.LoadOptions
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.ModelRef
import io.github.johnrocky.hfmodels.NetworkPolicy
import io.github.johnrocky.hfmodels.Tasks
import io.github.johnrocky.hfmodels.litertlm.ChatModel
import io.github.johnrocky.hfmodels.litertlm.GenerationOptions
import io.github.johnrocky.hfmodels.litertlm.text
import java.io.File
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** The model's two files: the same int4 weights, with the shared block of layers applied once or twice per token. */
enum class Loop(val passes: Int, val variant: String, val label: String) {
    ONE(1, "int4_1loop", "1 loop"),
    TWO(2, "int4", "2 loops"),
}

/** Where a loop's file is before a run, from the SDK's plan (no request is made). */
sealed class FileState(val bytes: Long) {
    /** Verified in the app's model store. */
    class OnPhone(bytes: Long) : FileState(bytes)
    /** A copy pushed with adb into the app's external files directory: hashed and imported at its first run. */
    class Pushed(bytes: Long) : FileState(bytes)
    /** Neither: the first run downloads it, or says it is missing when downloads are off. */
    class Missing(bytes: Long) : FileState(bytes)
}

/** One answer and its times. */
class Answer(
    val loop: Loop,
    val question: String,
    val text: String,
    val thought: String,
    val timing: Timing,
    /** From calling `stream` to the first chunk with answer text; later than [Timing.firstTokenMs] when the model reasoned first. */
    val firstTextMs: Double?,
    /** The load this answer waited for; null when its file's engine was already loaded. */
    val loadMs: Long?,
    /** The backend the engine was configured with (LiteRT-LM 0.16 reports no per-op placement). */
    val backend: String,
)

/**
 * The model's two files and the one engine the app holds, for the screen and the device check alike.
 *
 * Both files carry the same int4 weights: [Loop.TWO] (variant `int4`, NVIDIA's default) applies the shared block of
 * layers twice per token, [Loop.ONE] (variant `int4_1loop`) once. One [HfModels] holds one model, so [load] closes the
 * other file's engine before it loads. Every question gets a conversation of its own and the conversation is closed
 * after the answer.
 *
 * One caller at a time: the screen runs one job, the check runs its steps in order.
 */
class Loops(
    private val models: HfModels,
    /** The app's external files directory, where a copy pushed with adb is picked up; null: not looked for. */
    private val pushDir: File?,
    var network: NetworkPolicy = NetworkPolicy.Any,
    /** The variant each loop loads. The device check points one at a file it never pushes, to walk the failure paths. */
    private val variantOf: (Loop) -> String = { it.variant },
) {
    companion object {
        const val REPO = "litert-community/NVIDIA-Nemotron-Labs-Dory1.0-4B"
        /** The Hub commit the SDK's bundled catalog pins for this id: the int4, int4_1loop and int8 files. */
        const val REVISION = "ff0f6e323fe38ad2bc988ac3f0c7820877937a3f"
        /** Prompt and answer together, from the files' manifest. */
        const val CONTEXT_TOKENS = 2048

        /** What the screen says instead of running an empty question; null when the question can be asked. */
        fun refusal(question: String): String? = if (question.isBlank()) "Type a question first, or pick an example." else null

        fun mb(bytes: Long) = String.format(Locale.US, "%,d", bytes shr 20)
        fun gb(bytes: Long) = String.format(Locale.US, "%.2f GB", bytes / 1e9)
    }

    enum class Stage { CLOSING, LOADING, ANSWERING }

    /** Called on the caller's dispatcher as [load] and [answer] move on. */
    var onStage: (Loop, Stage) -> Unit = { _, _ -> }
    /** The SDK's load events, on the caller's dispatcher. */
    var onEvent: (Loop, LoadEvent) -> Unit = { _, _ -> }

    var model: ChatModel? = null
        private set
    var loaded: Loop? = null
        private set
    /** Loads that finished, and the last one's time. */
    var loads = 0
        private set
    var loadMs = 0L
        private set

    /** The last load's download: its progress (bytes of the file on the phone, the file's size) and where it started. */
    var downloadBytes: Long? = null
        private set
    var downloadTotal: Long? = null
        private set
    /** The first progress of the last download: above 0 when it continued a part a stopped download kept. */
    var resumedFrom: Long? = null
        private set

    /** The load the turn that runs or ran last waited for; null when its file's engine was already loaded. */
    var answerLoadMs: Long? = null
        private set

    /** The answer so far of the turn that runs or ran last: a stopped turn leaves its text and chunk count here. */
    var partialText = ""
        private set
    var partialChunks = 0
        private set

    private var stage: Stage? = null
    private val sizes = HashMap<Loop, Long>()

    fun ref(loop: Loop) = ModelRef(REPO, revision = REVISION, variant = variantOf(loop))

    /** Where [loop]'s file is now. Reads the SDK's plan with the network off, so it never makes a request. */
    suspend fun fileState(loop: Loop): FileState {
        val plan = models.inspect(ref(loop), Tasks.Chat, LoadOptions(networkPolicy = NetworkPolicy.Offline))
        val file = plan.files.first { it.role == "model" }
        sizes[loop] = file.bytes
        if (plan.bytesToDownload == 0L) return FileState.OnPhone(file.bytes)
        val pushed = pushDir?.let { File(it, file.path.substringAfterLast('/')) }
        return if (pushed != null && pushed.isFile && pushed.length() == file.bytes) FileState.Pushed(file.bytes) else FileState.Missing(file.bytes)
    }

    /** Loads [loop]'s file unless its engine is the one loaded; the other file's engine is closed first. */
    suspend fun load(loop: Loop): ChatModel {
        if (loaded == loop) model?.let { return it }
        release()
        stage = Stage.LOADING
        onStage(loop, Stage.LOADING)
        downloadBytes = null; downloadTotal = null; resumedFrom = null
        val t0 = SystemClock.elapsedRealtime()
        val m = models.fromPretrained(ref(loop), Tasks.Chat, LoadOptions(networkPolicy = network)) { e ->
            when (e) {
                is LoadEvent.DownloadStarted -> downloadTotal = e.totalBytes
                is LoadEvent.Downloading -> {
                    if (resumedFrom == null) resumedFrom = e.bytes
                    downloadBytes = e.bytes; downloadTotal = e.totalBytes
                }
                else -> Unit
            }
            onEvent(loop, e)
        }
        loadMs = SystemClock.elapsedRealtime() - t0
        loads++
        model = m; loaded = loop
        return m
    }

    /**
     * One question on [loop]'s file, in a new conversation. [think] false asks the template for its no-think form, the
     * form the model card's GSM8K numbers were measured in. [onChunk] gets the answer so far and the reasoning's length
     * after each chunk, on a background thread. Cancelling the caller stops the model ([partialText] keeps what came).
     */
    suspend fun answer(loop: Loop, question: String, think: Boolean, onChunk: (text: String, thoughtChars: Int) -> Unit = { _, _ -> }): Answer {
        val loadsBefore = loads
        val m = load(loop)
        answerLoadMs = if (loads != loadsBefore) loadMs else null
        stage = Stage.ANSWERING
        onStage(loop, Stage.ANSWERING)
        partialText = ""; partialChunks = 0
        val session = withContext(Dispatchers.Default) { m.createConversation(ConversationConfig()) }
        try {
            val text = StringBuilder()
            val thought = StringBuilder()
            val chunkNs = ArrayList<Long>(512)
            var firstText = 0L
            val t0 = SystemClock.elapsedRealtimeNanos()
            withContext(Dispatchers.Default) {
                session.stream(Contents.of(question), GenerationOptions(enableThinking = think)).collect { msg ->
                    val piece = msg.text
                    val reasoning = msg.channels.values.firstOrNull().orEmpty()
                    if (piece.isEmpty() && reasoning.isEmpty()) return@collect
                    val now = SystemClock.elapsedRealtimeNanos()
                    chunkNs += now
                    if (piece.isNotEmpty() && firstText == 0L) firstText = now
                    text.append(piece)
                    thought.append(reasoning)
                    partialText = text.toString(); partialChunks = chunkNs.size
                    onChunk(partialText, thought.length)
                }
            }
            val t1 = SystemClock.elapsedRealtimeNanos()
            return Answer(
                loop = loop,
                question = question,
                text = text.toString(),
                thought = thought.toString(),
                timing = Timing.of(t0, chunkNs, t1),
                firstTextMs = if (firstText == 0L) null else (firstText - t0) / 1e6,
                loadMs = answerLoadMs,
                backend = m.info.components["language"]?.initialized?.name ?: m.info.profileId,
            )
        } finally {
            withContext(NonCancellable) { runCatching { session.closeAndJoin() } }
        }
    }

    /** The sentence the screen shows for a run of [loop] that [t] ended before its answer was complete. */
    fun sentence(loop: Loop, t: Throwable): String {
        val size = sizes[loop]?.let { " (${gb(it)} from Hugging Face)" } ?: ""
        return when {
            t is CancellationException && stage == Stage.LOADING && downloadBytes != null ->
                "Download stopped at ${mb(downloadBytes!!)} of ${mb(downloadTotal ?: 0)} MB. The part stays on the phone; the next run continues from it."
            t is CancellationException && stage == Stage.ANSWERING -> "Stopped after $partialChunks tokens."
            t is CancellationException -> "Stopped before the answer."
            t is ModelException -> when (t.code) {
                ErrorCode.OFFLINE_CACHE_MISS ->
                    "The ${loop.label} file is not on this phone and downloads are off. Tick Download missing files$size, or push the file with adb (README)."
                ErrorCode.NETWORK_ERROR -> "The download stopped: ${t.reason}. The part stays on the phone; the next run continues from it."
                ErrorCode.STORAGE_FULL -> "Not enough free space for the ${loop.label} file: ${t.reason}"
                ErrorCode.CONTEXT_LIMIT_EXCEEDED -> "The question is too long: the model reads at most ${String.format(Locale.US, "%,d", CONTEXT_TOKENS)} tokens, question and answer together."
                else -> "${t.code}: ${t.reason}"
            }
            else -> "${t.javaClass.simpleName}: ${t.message}"
        }
    }

    /** Closes the loaded engine, if any. A second call does nothing. */
    suspend fun release() {
        val m = model ?: return
        loaded?.let { stage = Stage.CLOSING; onStage(it, Stage.CLOSING) }
        model = null; loaded = null
        withContext(NonCancellable) { m.closeAndJoin() }
    }

    /** [release] for onDestroy, which cannot wait: the SDK finishes the close on its own threads. */
    fun closeNow() {
        model?.close(); model = null; loaded = null
    }
}
