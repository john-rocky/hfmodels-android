package io.github.johnrocky.hfmodels.litertlm

import android.graphics.BitmapFactory
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.InputKind
import io.github.johnrocky.hfmodels.LoadOptions
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.PrepareHost
import io.github.johnrocky.hfmodels.PreparedModelInfo
import java.io.File
import java.util.concurrent.CancellationException as JavaCancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns one LiteRT-LM Engine. Initialization, conversation creation and release run on one
 * dedicated thread; generation callbacks arrive on the runtime's own thread and are bridged into
 * a bounded channel (no chunk is dropped: an overflow cancels the native side and ends the flow
 * with SLOW_CONSUMER). One generation at a time per model.
 */
internal class LiteRtLmChatModel(
    private val engine: Engine,
    override val info: PreparedModelInfo,
    override val enabledInputs: Set<InputKind>,
    private val options: LoadOptions,
    private val host: PrepareHost,
) : ChatModel {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "hfmodels-litertlm").apply { isDaemon = true } }
    private val nativeDispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + nativeDispatcher)
    private val sessions = CopyOnWriteArrayList<Session>()
    private val generating = AtomicReference<Session?>(null)
    private val closing = AtomicBoolean(false)
    private val closed = CompletableDeferred<Unit>()
    @Volatile private var unusable: String? = null
    private val closeLock = Mutex()

    /** Test hook: chunks the bridge buffers before it declares the collector too slow. */
    internal var streamBufferChunks: Int = STREAM_BUFFER_CHUNKS
    internal var streamBufferBytes: Long = STREAM_BUFFER_BYTES

    override suspend fun createConversation(config: ConversationConfig): ChatSession {
        checkUsable()
        validate(config)
        val conv = withContext(nativeDispatcher) {
            checkUsable()
            try { engine.createConversation(config) } catch (t: Throwable) {
                throw ModelException(ErrorCode.INITIALIZATION_FAILED, "createConversation failed: ${t.javaClass.simpleName}: ${t.message}", details = mapOf("stage" to "createConversation"), cause = t)
            }
        }
        return Session(conv).also { sessions += it }
    }

    override fun close() {
        if (closing.compareAndSet(false, true)) scope.launch { doClose(); executor.shutdown() }
    }

    override suspend fun closeAndJoin() {
        closing.set(true)
        withContext(NonCancellable) {
            if (!closed.isCompleted && !executor.isShutdown) withContext(nativeDispatcher) { doClose() }
            closed.await()
            executor.shutdown()
        }
    }

    private suspend fun doClose(): Unit = closeLock.withLock {
        if (closed.isCompleted) return@withLock
        // Children first: each waits for its native work to stop (10 s cap), then the Engine.
        for (s in sessions.toList()) runCatching { s.closeAndJoin() }.onFailure { host.log.w("session close during model close: ${it.message}") }
        if (unusable == null) {
            runCatching { engine.close() }.onFailure { host.log.w("engine.close: ${it.message}", it) }
        } else {
            host.log.e("model left UNUSABLE ($unusable): the Engine is not released while a native call may still hold it")
        }
        host.onModelClosed(this)
        closed.complete(Unit)
        Unit
    }

    private fun checkUsable() {
        unusable?.let { throw ModelException(ErrorCode.NATIVE_STOP_TIMEOUT, "model is UNUSABLE: $it") }
        if (closing.get()) throw ModelException(ErrorCode.MODEL_CLOSED, "model ${info.repoId} is closing or closed")
    }

    /** First-release ConversationConfig surface (spec §11.5): system instruction, initial messages, sampling, output cap. */
    private fun validate(config: ConversationConfig) {
        fun no(what: String): Nothing = throw ModelException(ErrorCode.UNSUPPORTED_CONFIGURATION, "$what is not supported in this release; it was rejected before any native call", details = mapOf("field" to what))
        if (config.tools.isNotEmpty()) no("ConversationConfig.tools")
        if (config.enableResponseFormat) no("ConversationConfig.enableResponseFormat")
        if (config.loraConfig != null) no("ConversationConfig.loraConfig")
        if (config.channels != null) no("ConversationConfig.channels")
        if (config.thinkingConfig?.enableThinking == true) no("ConversationConfig.thinkingConfig.enableThinking")
        config.maxOutputToken?.let { if (it <= 0) throw ModelException(ErrorCode.INVALID_INPUT, "ConversationConfig.maxOutputToken must be positive") }
    }

    /** Input checks before any native call (spec §11.4): kinds, image count, bytes, pixels. Keeps the session READY on failure. */
    private fun validateInputs(contents: Contents) {
        var images = 0
        for (c in contents.contents) {
            when (c) {
                is Content.Text -> {}
                is Content.ImageFile, is Content.ImageBytes -> {
                    images++
                    if (InputKind.IMAGE !in enabledInputs) throw ModelException(ErrorCode.UNSUPPORTED_INPUT, "this load enabled ${enabledInputs}; IMAGE is not enabled (declared: ${info.declaredInputs})", details = mapOf("enabled" to enabledInputs.toString()))
                    if (images > 1) throw ModelException(ErrorCode.UNSUPPORTED_INPUT, "one image per turn in this release", details = mapOf("images" to images.toString()))
                    checkImage(c)
                }
                is Content.AudioFile, is Content.AudioBytes -> throw ModelException(ErrorCode.UNSUPPORTED_INPUT, "audio input is not supported in this release")
                else -> throw ModelException(ErrorCode.UNSUPPORTED_INPUT, "${c.javaClass.simpleName} is not supported in this release")
            }
        }
    }

    private fun checkImage(c: Content) {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val bytes: Long
        when (c) {
            is Content.ImageFile -> {
                val f = File(c.absolutePath)
                if (!f.isFile) throw ModelException(ErrorCode.INVALID_INPUT, "image file not found: ${c.absolutePath}")
                bytes = f.length()
                if (bytes > options.maxImageBytes) throw ModelException(ErrorCode.INVALID_INPUT, "image is $bytes bytes; maxImageBytes=${options.maxImageBytes}")
                BitmapFactory.decodeFile(c.absolutePath, opts)
            }
            is Content.ImageBytes -> {
                bytes = c.bytes.size.toLong()
                if (bytes > options.maxImageBytes) throw ModelException(ErrorCode.INVALID_INPUT, "image is $bytes bytes; maxImageBytes=${options.maxImageBytes}")
                BitmapFactory.decodeByteArray(c.bytes, 0, c.bytes.size, opts)
            }
            else -> return
        }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) throw ModelException(ErrorCode.INVALID_INPUT, "image could not be decoded (${opts.outMimeType ?: "unknown format"})")
        val px = opts.outWidth.toLong() * opts.outHeight
        if (px > options.maxImagePixels) throw ModelException(ErrorCode.INVALID_INPUT, "image is ${opts.outWidth}x${opts.outHeight} = $px pixels; maxImagePixels=${options.maxImagePixels}")
    }

    private inner class Session(private val conv: Conversation) : ChatSession {
        @Volatile override var state: SessionState = SessionState.READY
            private set
        private val closedDeferred = CompletableDeferred<Unit>()
        /** Completed by the runtime's onDone/onError of the generation in flight. */
        @Volatile private var nativeDone: CompletableDeferred<Unit>? = null
        @Volatile private var cancelRequested = false

        override fun stream(contents: Contents, options: GenerationOptions): Flow<Message> {
            val collected = AtomicBoolean(false)   // one collection per returned Flow; stream() again for the next turn
            return flow {
            if (!collected.compareAndSet(false, true)) throw ModelException(ErrorCode.STREAM_ALREADY_COLLECTED, "this Flow was already collected; call stream() again for another generation")
            when (state) {
                SessionState.READY -> {}
                SessionState.INVALID, SessionState.CANCELLING -> throw ModelException(ErrorCode.SESSION_INVALIDATED, "this session was cancelled or failed; create a new conversation")
                SessionState.CLOSING, SessionState.CLOSED -> throw ModelException(ErrorCode.MODEL_CLOSED, "this session is closed")
                SessionState.GENERATING -> throw ModelException(ErrorCode.MODEL_BUSY, "this session is already generating")
            }
            checkUsable()
            validateInputs(contents)   // READY is kept on failure
            if (!generating.compareAndSet(null, this@Session)) throw ModelException(ErrorCode.MODEL_BUSY, "another generation is running on ${info.repoId}; one at a time per model")
            state = SessionState.GENERATING
            cancelRequested = false
            val done = CompletableDeferred<Unit>().also { nativeDone = it }
            val channel = Channel<Message>(capacity = streamBufferChunks)
            val bytes = AtomicLong(0)
            val overflow = AtomicBoolean(false)
            var failure: Throwable? = null
            val callback = object : MessageCallback {
                override fun onMessage(message: Message) {
                    if (cancelRequested || overflow.get()) return
                    val size = message.contents.contents.sumOf { (it as? Content.Text)?.text?.length?.toLong() ?: 0L }
                    val r = channel.trySend(message)
                    if (!r.isSuccess || bytes.addAndGet(size) > streamBufferBytes) {
                        if (overflow.compareAndSet(false, true)) {
                            // The collector is too far behind. Stop the native side; the flow ends with SLOW_CONSUMER.
                            runCatching { conv.cancelProcess() }
                            channel.close(ModelException(ErrorCode.SLOW_CONSUMER, "collector fell more than $streamBufferChunks chunks or $streamBufferBytes bytes behind; generation cancelled, no chunk was silently dropped"))
                        }
                    }
                }
                override fun onDone() { done.complete(Unit); channel.close() }
                override fun onError(throwable: Throwable) {
                    done.complete(Unit)
                    if (throwable is JavaCancellationException || throwable is CancellationException) channel.close() else channel.close(throwable)
                }
            }
            var completedNormally = false
            try {
                try {
                    conv.sendMessageAsync(Message.user(contents), callback, maxOutputToken = options.maxOutputTokens)
                } catch (t: Throwable) {
                    done.complete(Unit)
                    throw ModelException(ErrorCode.INFERENCE_FAILED, "sendMessageAsync failed: ${t.javaClass.simpleName}: ${t.message}", details = mapOf("stage" to "send"), cause = t)
                }
                for (m in channel) emit(m)
                completedNormally = true
            } catch (t: Throwable) {
                failure = t
                throw when {
                    t is CancellationException -> t.also { cancelNative("collector cancelled") }
                    t is ModelException -> t
                    else -> ModelException(ErrorCode.INFERENCE_FAILED, "generation failed: ${t.javaClass.simpleName}: ${t.message}", details = mapOf("stage" to "generate"), cause = t)
                }
            } finally {
                // Never release the model's generation slot before the runtime confirmed it stopped.
                withContext(NonCancellable) { withTimeoutOrNull(NATIVE_STOP_MS) { done.await() } ?: markUnusable("generation did not stop within ${NATIVE_STOP_MS} ms") }
                generating.compareAndSet(this@Session, null)
                state = when {
                    unusable != null -> SessionState.INVALID
                    completedNormally && !cancelRequested -> SessionState.READY
                    else -> SessionState.INVALID
                }
                if (failure != null && failure !is CancellationException) host.log.w("generation ended: ${failure.message}")
            }
            }
        }

        private fun cancelNative(why: String) {
            if (cancelRequested) return
            cancelRequested = true
            if (state == SessionState.GENERATING) state = SessionState.CANCELLING
            runCatching { conv.cancelProcess() }.onFailure { host.log.w("cancelProcess ($why): ${it.message}") }
        }

        override fun cancel() {
            if (state != SessionState.GENERATING) return
            cancelNative("cancel()")
        }

        override fun close() {
            if (state == SessionState.CLOSED || state == SessionState.CLOSING) return
            scope.launch { closeAndJoin() }
        }

        override suspend fun closeAndJoin() {
            if (closedDeferred.isCompleted) return
            withContext(NonCancellable) {
                if (state == SessionState.GENERATING) cancelNative("close()")
                state = SessionState.CLOSING
                val pending = nativeDone
                if (pending != null && !pending.isCompleted) {
                    withTimeoutOrNull(NATIVE_STOP_MS) { pending.await() } ?: markUnusable("session close: generation did not stop within ${NATIVE_STOP_MS} ms")
                }
                if (unusable == null) {
                    withContext(nativeDispatcher) { runCatching { conv.close() }.onFailure { if (it !is IllegalStateException) host.log.w("conversation close: ${it.message}") } }
                }
                sessions.remove(this@Session)
                state = SessionState.CLOSED
                closedDeferred.complete(Unit)
            }
        }
    }

    private fun markUnusable(reason: String) {
        if (unusable == null) {
            unusable = reason
            host.log.e("NATIVE_STOP_TIMEOUT: $reason; model ${info.repoId} is UNUSABLE and its handle is not released")
        }
    }

    private companion object {
        const val STREAM_BUFFER_CHUNKS = 1024
        const val STREAM_BUFFER_BYTES = 8L * 1024 * 1024
        const val NATIVE_STOP_MS = 10_000L
    }
}
