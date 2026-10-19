package io.github.johnrocky.hfmodels.samples.translate

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import io.github.johnrocky.hfmodels.BackendKind
import io.github.johnrocky.hfmodels.ComponentReport
import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.Handler
import io.github.johnrocky.hfmodels.LoadEvent
import io.github.johnrocky.hfmodels.LocalModel
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.PrepareHost
import io.github.johnrocky.hfmodels.PreparedModel
import io.github.johnrocky.hfmodels.PreparedModelInfo
import io.github.johnrocky.hfmodels.Task
import io.github.johnrocky.hfmodels.litertlm.text
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Qwen3-ASR-1.7B (Qwen, Apache-2.0) as the sample loads it through the SDK: `litert-community/Qwen3-ASR-1.7B` at
 * 184ec0f1, the file with the 15 s audio window (`Qwen3-ASR-1.7B-15s.litertlm`). The repo carries no `hfmodels.json`, so
 * the sample passes a descriptor of its own (`assets/descriptors/`, `LoadOptions.descriptorJson`) and brings the handler
 * here; the SDK still resolves the commit, downloads with resume, checks the sha256, imports a copy pushed into the app's
 * external files dir, and loads offline from its store. hfmodels' `Tasks.Chat` takes no audio in this release
 * (UNSUPPORTED_INPUT), so the handler builds the LiteRT-LM Engine itself, on the runtime the SDK links (0.16.1): the
 * language model and the audio encoder on the CPU (4 threads; the model card: the GPU delegate does not take the audio
 * encoder), the runtime's CPU weight caches (about 3 GB, written by the first load) in the SDK's LiteRT-LM cache dir.
 */
object Qwen3AsrTask : Task<Qwen3Asr> {
    override val id = "transcribe"
    override val handler: Handler<Qwen3Asr> = Qwen3AsrHandler
}

/**
 * Speech to text: one utterance (16 kHz mono, at most the 15 s window) in one new conversation, one user message = the
 * audio (a WAV in the app's cache dir) and the text `language <Name><asr_text>` that fixes the language (the model card's
 * form: the answer is then the transcript alone), greedy. One call at a time. A cancelled call stops the runtime
 * (`cancelProcess`) and returns once it has stopped, so the next call never meets a busy engine. Every call that makes,
 * sends or closes a conversation runs on the model's own thread.
 */
class Qwen3Asr internal constructor(
    override val info: PreparedModelInfo,
    private val engine: Engine,
    private val wav: File,
    private val host: PrepareHost,
) : PreparedModel {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "qwen3-asr").apply { isDaemon = true } }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val busy = Mutex()
    private val closed = AtomicBoolean(false)

    /** [language] as the model card names it: "Spanish", "French", "English". */
    suspend fun transcribe(pcm: FloatArray, language: String): AsrResult {
        if (closed.get()) throw ModelException(ErrorCode.MODEL_CLOSED, "Qwen3-ASR is closing or closed")
        if (pcm.isEmpty()) throw ModelException(ErrorCode.INVALID_INPUT, "the audio is empty")
        if (pcm.size > MAX_SAMPLES) throw ModelException(ErrorCode.INVALID_INPUT, "${pcm.size / SAMPLE_RATE.toDouble()} s of audio; one message takes at most $WINDOW_SECONDS s")
        for (x in pcm) if (!x.isFinite()) throw ModelException(ErrorCode.INVALID_INPUT, "the audio contains a non-finite sample")
        // A cancelled call keeps the lock until its native call has stopped.
        return busy.withLock { run(pcm, language) }
    }

    private suspend fun run(pcm: FloatArray, language: String): AsrResult {
        val t0 = System.nanoTime()
        // The callbacks come on the runtime's thread.
        val text = StringBuilder()
        val done = CompletableDeferred<Unit>()
        val failure = AtomicReference<Throwable?>(null)
        val firstAt = AtomicLong(0L)
        val chunks = AtomicInteger(0)
        var wroteAt = 0L
        var createdAt = 0L
        var conversation: Conversation? = null
        val callback = object : MessageCallback {
            override fun onMessage(message: Message) {
                val s = message.text
                if (s.isEmpty()) return
                synchronized(text) {
                    if (chunks.getAndIncrement() == 0) firstAt.set(System.nanoTime())
                    text.append(s)
                }
            }
            override fun onDone() { done.complete(Unit) }
            override fun onError(throwable: Throwable) { failure.compareAndSet(null, throwable); done.complete(Unit) }
        }
        try {
            // Made and sent on the model's thread, not cancellable: a conversation made there is always noted (and
            // closed below), even when this call is cancelled meanwhile.
            withContext(NonCancellable) {
                withContext(dispatcher) {
                    Wav.write(wav, pcm, SAMPLE_RATE)
                    wroteAt = System.nanoTime()
                    val c = engine.createConversation(CONVERSATION)
                    conversation = c
                    createdAt = System.nanoTime()
                    try {
                        c.sendMessageAsync(Message.user(Contents.of(Content.AudioFile(wav.absolutePath), Content.Text(AsrText.forcedLanguage(language)))), callback)
                    } catch (t: Throwable) {
                        // Nothing runs: no callback will end the call.
                        failure.compareAndSet(null, t)
                        done.complete(Unit)
                    }
                }
            }
            done.await()
        } catch (e: CancellationException) {
            conversation?.let { c -> runCatching { c.cancelProcess() }.onFailure { host.log.w("qwen3-asr cancelProcess: ${it.message}") } }
            throw e
        } catch (t: Throwable) {
            failure.compareAndSet(null, t)
        } finally {
            withContext(NonCancellable) {
                val c = conversation
                if (c != null) {
                    if (withTimeoutOrNull(NATIVE_STOP_MS) { done.await() } == null) host.log.e("qwen3-asr: the call did not stop within $NATIVE_STOP_MS ms")
                    withContext(dispatcher) { runCatching { c.close() }.onFailure { host.log.w("qwen3-asr conversation close: ${it.message}") } }
                }
            }
        }
        val end = System.nanoTime()
        failure.get()?.let { f ->
            throw ModelException(ErrorCode.INFERENCE_FAILED, "Qwen3-ASR failed: ${f.javaClass.simpleName}: ${f.message}", details = mapOf("stage" to "transcribe"), cause = f)
        }
        val raw = synchronized(text) { text.toString() }
        val first = firstAt.get().takeIf { it > 0 } ?: end
        return AsrResult(
            text = AsrText.parse(raw), raw = raw, wavMs = (wroteAt - t0) / 1e6, createMs = (createdAt - wroteAt) / 1e6,
            firstChunkMs = (first - createdAt) / 1e6, decodeMs = (end - first) / 1e6, chunks = chunks.get(), totalMs = (end - t0) / 1e6,
        )
    }

    /** Does not block: the release ([closeAndJoin]'s) runs on a thread of its own. */
    override fun close() {
        if (closed.compareAndSet(false, true)) Thread({ runBlocking { shutdown() } }, "qwen3-asr-close").apply { isDaemon = true }.start()
    }

    /** Waits (at most 10 s) for a call in flight to stop, then closes the engine on its thread. Idempotent. */
    override suspend fun closeAndJoin() {
        if (closed.compareAndSet(false, true)) shutdown()
        withContext(NonCancellable) { released.await() }
    }

    private val released = CompletableDeferred<Unit>()

    private suspend fun shutdown() = withContext(NonCancellable) {
        val locked = withTimeoutOrNull(NATIVE_STOP_MS) { busy.lock(); true } ?: false
        try {
            if (locked) withContext(dispatcher) { release() } else host.log.e("qwen3-asr: a call still runs after $NATIVE_STOP_MS ms; the engine is not closed under it")
        } finally {
            if (locked) busy.unlock()
            released.complete(Unit)
        }
    }

    private fun release() {
        runCatching { engine.close() }.onFailure { host.log.w("qwen3-asr engine close: ${it.message}") }
        runCatching { wav.delete() }
        host.onModelClosed(this)
        executor.shutdown()
    }

    companion object {
        const val WINDOW_SECONDS = 15
        const val MAX_SAMPLES = SAMPLE_RATE * WINDOW_SECONDS
        const val NATIVE_STOP_MS = 10_000L
        /** Greedy, as the model card measures; no answer here comes near the cap. */
        internal val CONVERSATION = ConversationConfig(samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0), maxOutputToken = 256)
    }
}

/** Builds the Engine (the language model and the audio encoder on the CPU) and makes its first conversation. */
internal object Qwen3AsrHandler : Handler<Qwen3Asr> {
    override val id = "sample.qwen3_asr"
    override val abi = 1
    override val runtime = "litert_lm"
    override val runtimeVersion: String = BuildConfig.LITERTLM_VERSION

    override fun prepare(local: LocalModel<Qwen3Asr>, host: PrepareHost, onProgress: (LoadEvent) -> Unit): Qwen3Asr {
        val file = local.files["model"] ?: throw ModelException(ErrorCode.MANIFEST_INVALID, "the descriptor has no file id 'model'")
        val threads = local.plan.variant.handlerConfig.optInt("cpu_threads", 4)
        onProgress(LoadEvent.Initializing(local.plan.profile.id))
        val cache = File(host.cacheDir, "litertlm").apply { mkdirs() }
        val engine = Engine(EngineConfig(modelPath = file.absolutePath, backend = Backend.CPU(threadCount = threads), audioBackend = Backend.CPU(threadCount = threads), cacheDir = cache.absolutePath))
        val t0 = System.nanoTime()
        try {
            engine.initialize()
            val t1 = System.nanoTime()
            // The runtime creates the audio encoder with the first conversation: here, so that it counts as loading.
            engine.createConversation(Qwen3Asr.CONVERSATION).close()
            val t2 = System.nanoTime()
            val msInit = (t1 - t0) / 1_000_000
            val msFirst = (t2 - t1) / 1_000_000
            host.log.i("qwen3-asr ready: ${file.name}, language model CPU $threads threads, audio encoder CPU $threads threads, initialize $msInit ms, first conversation $msFirst ms, cache ${cache.path}")
            return Qwen3Asr(info(local, host, threads, listOf("engine CPU + audio CPU ($threads threads), window ${Qwen3Asr.WINDOW_SECONDS} s, initialize_ms=$msInit, first_conversation_ms=$msFirst")), engine, File(host.cacheDir, "qwen3asr_utterance.wav"), host)
        } catch (t: Throwable) {
            runCatching { engine.close() }
            throw ModelException(ErrorCode.INITIALIZATION_FAILED, "Qwen3-ASR did not load: ${t.javaClass.simpleName}: ${t.message}", details = mapOf("stage" to "initialize"), cause = t)
        }
    }

    private fun info(local: LocalModel<Qwen3Asr>, host: PrepareHost, threads: Int, notes: List<String>): PreparedModelInfo {
        val plan = local.plan
        return PreparedModelInfo(
            repoId = plan.ref.repoId, commit = plan.modelOrigin.commit, descriptorSha256 = plan.descriptorSha256, descriptorOrigin = plan.descriptorOrigin,
            bindingSource = plan.bindingSource, variantId = plan.variant.id, profileId = plan.profile.id, sdkVersion = host.sdkVersion,
            handlerId = id, handlerAbi = abi, runtime = runtime, runtimeVersion = runtimeVersion,
            declaredInputs = plan.variant.inputs, enabledInputs = plan.profile.enabledInputs, requestedBackendPolicy = plan.options.backendPolicy,
            components = mapOf("language" to ComponentReport(requested = BackendKind.CPU, initialized = BackendKind.CPU), "audio" to ComponentReport(requested = BackendKind.CPU, initialized = BackendKind.CPU)),
            excludedProfiles = plan.excludedProfiles, fallbackHistory = emptyList(), verification = plan.verification,
            notes = notes + "cpu_threads=$threads", files = local.files.mapValues { it.value.absolutePath },
        )
    }
}
