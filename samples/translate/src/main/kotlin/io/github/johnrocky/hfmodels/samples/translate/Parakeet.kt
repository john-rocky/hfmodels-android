package io.github.johnrocky.hfmodels.samples.translate

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
import io.github.johnrocky.hfmodels.speech.Transcriber
import io.github.johnrocky.hfmodels.speech.TranscriberLimits
import io.github.johnrocky.hfmodels.speech.Transcript
import io.github.johnrocky.hfmodels.speech.TranscriptTiming
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter

/**
 * Parakeet-TDT-0.6B-v3 (NVIDIA, CC-BY-4.0) as the sample loads it through the SDK. Neither of its repos carries an
 * `hfmodels.json`, so the sample passes a descriptor of its own (`assets/descriptors/`, `LoadOptions.descriptorJson`) and
 * brings the handlers here; the SDK still resolves the commit, downloads with resume, checks every sha256, imports a copy
 * pushed into the app's external files dir, and loads offline from its store.
 *  - [ParakeetGraphTask]: the LiteRT graph, `mlboydaisuke/Parakeet-TDT-0.6B-v3-LiteRT` at af580dff
 *    (`parakeet_tdt_0.6b_v3_30s_i8_stateful.tflite`: one 30 s window, `encode`, `decode`, `decode_1`).
 *  - [ParakeetVocabTask]: the model's `tokenizer.json`, `nvidia/parakeet-tdt-0.6b-v3` at 541d1f99 (the graph's repo has none).
 * [ParakeetTranscriber] puts the two together as a hfmodels `Transcriber`.
 */
object ParakeetGraphTask : Task<ParakeetGraph> {
    override val id = "transcribe"
    override val handler: Handler<ParakeetGraph> = ParakeetGraphHandler
}

object ParakeetVocabTask : Task<ParakeetVocabModel> {
    override val id = "transcribe"
    override val handler: Handler<ParakeetVocabModel> = ParakeetVocabHandler
}

/** The vocabulary as a loaded "model": no native part, only the pieces. */
class ParakeetVocabModel internal constructor(override val info: PreparedModelInfo, internal val vocab: ParakeetVocab, private val host: PrepareHost) : PreparedModel {
    private val closed = AtomicBoolean(false)
    override fun close() { if (closed.compareAndSet(false, true)) host.onModelClosed(this) }
    override suspend fun closeAndJoin() = close()
}

/**
 * The graph on LiteRT's Interpreter API (CPU, XNNPACK). Not CompiledModel: the 30 s graph's decoder computes the joint for
 * all 375 encoder frames, [1, 375, 1, 8198] floats (12.3 MB) per step, and LiteRT 2.2.0's TensorBuffer reads only whole
 * buffers, where the Interpreter's output tensor is a direct view of which the loop reads its one row of 8,198. The
 * hfmodels speaker (Kitten) runs on the same Interpreter API of the same AAR in the same process. Every native call runs
 * on this graph's own thread.
 */
class ParakeetGraph internal constructor(
    override val info: PreparedModelInfo,
    internal val interpreter: Interpreter,
    internal val melFrames: Int,
    internal val encoderFrames: Int,
    private val host: PrepareHost,
) : PreparedModel {
    internal val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "parakeet").apply { isDaemon = true } }
    internal val dispatcher = executor.asCoroutineDispatcher()
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) executor.execute { release() }
    }

    override suspend fun closeAndJoin() {
        if (closed.compareAndSet(false, true)) withContext(NonCancellable + dispatcher) { release() }
    }

    internal val isClosed: Boolean get() = closed.get()

    private fun release() {
        runCatching { interpreter.close() }.onFailure { host.log.w("parakeet graph close: ${it.message}") }
        host.onModelClosed(this)
        executor.shutdown()
    }
}

/** What one call heard, with the parts the TURN line reports. */
class ParakeetResult(val text: String, val pieces: Int, val featureMs: Double, val encodeMs: Double, val decodeMs: Double, val steps: Int, val totalMs: Double)

/**
 * Speech to text with [graph] and [vocab]: NeMo's log-mel ([ParakeetFrontEnd]) over the utterance zero-padded to the
 * 30 s window, `encode`, greedy TDT ([TdtGreedy]) up to the encoder frames that carry audio plus 4, the vocabulary's
 * pieces. Audio longer than the window goes in pieces cut at pauses ([pieces]). The model finds the language itself
 * (25 European languages) and writes punctuation and capitals. One call at a time.
 */
class ParakeetTranscriber(val graph: ParakeetGraph, private val vocab: ParakeetVocabModel) : Transcriber {
    override val info: PreparedModelInfo get() = graph.info
    override val limits = TranscriberLimits(ParakeetFrontEnd.RATE, graph.melFrames / 100.0, LANGUAGES)
    private val frontEnd = ParakeetFrontEnd(graph.melFrames)
    private val features = FloatArray(ParakeetFrontEnd.NMEL * graph.melFrames)
    private val melIn = direct(features.size * 4)
    private val encoded = direct(ENCODER_DIM * graph.encoderFrames * 4)
    private val joint = InterpreterJoint(graph.interpreter, encoded, graph.encoderFrames)
    private val busy = AtomicBoolean(false)

    override suspend fun transcribe(pcm: FloatArray): Transcript {
        val r = transcribeDetailed(pcm)
        return Transcript(r.text, TranscriptTiming(featureMs = r.featureMs, inferenceMs = r.encodeMs + r.decodeMs, totalMs = r.totalMs))
    }

    suspend fun transcribeDetailed(pcm: FloatArray): ParakeetResult {
        if (graph.isClosed) throw ModelException(ErrorCode.MODEL_CLOSED, "the parakeet graph is closing or closed")
        if (pcm.isEmpty()) throw ModelException(ErrorCode.INVALID_INPUT, "the audio is empty")
        for (x in pcm) if (!x.isFinite()) throw ModelException(ErrorCode.INVALID_INPUT, "the audio contains a non-finite sample")
        if (!busy.compareAndSet(false, true)) throw ModelException(ErrorCode.MODEL_BUSY, "a transcription is already running (one at a time)")
        try {
            val t0 = System.nanoTime()
            var featureNs = 0L; var encodeNs = 0L; var decodeNs = 0L; var steps = 0
            val texts = ArrayList<String>()
            val ranges = pieces(pcm, frontEnd.windowSamples)
            for (range in ranges) {
                val piece = pcm.copyOfRange(range.first, range.last + 1)
                val a = System.nanoTime()
                frontEnd.features(piece, features)
                val b = System.nanoTime()
                val result = withContext(graph.dispatcher) {
                    if (graph.isClosed) throw ModelException(ErrorCode.MODEL_CLOSED, "the parakeet graph was closed during the call")
                    try {
                        melIn.clear(); melIn.asFloatBuffer().put(features)
                        // The encoder output lands in this call's own buffer: every decode step passes it back as args_0.
                        encoded.clear()
                        graph.interpreter.runSignature(mapOf("args_0" to melIn), mapOf("output_0" to encoded), "encode")
                        encoded.rewind()
                        val c = System.nanoTime()
                        val limit = ParakeetFrontEnd.validEncoderFrames(piece.size, graph.melFrames, graph.encoderFrames)
                        val decoded = TdtGreedy.decode(joint, limit)
                        Triple(c, decoded, System.nanoTime())
                    } catch (e: ModelException) { throw e } catch (t: Throwable) {
                        throw ModelException(ErrorCode.INFERENCE_FAILED, "parakeet failed: ${t.javaClass.simpleName}: ${t.message}", cause = t)
                    }
                }
                val (c, decoded, d) = result
                featureNs += b - a; encodeNs += c - b; decodeNs += d - c; steps += decoded.steps
                texts += vocab.vocab.text(decoded.tokens.map { it.id }.toIntArray())
            }
            val total = System.nanoTime() - t0
            return ParakeetResult(texts.filter { it.isNotEmpty() }.joinToString(" "), ranges.size, featureNs / 1e6, encodeNs / 1e6, decodeNs / 1e6, steps, total / 1e6)
        } finally {
            busy.set(false)
        }
    }

    override fun close() { graph.close(); vocab.close() }

    override suspend fun closeAndJoin() { graph.closeAndJoin(); vocab.closeAndJoin() }

    companion object {
        const val ENCODER_DIM = 1024
        /** The card's `language:` list (litert-community/parakeet-tdt-0.6b-v3). */
        val LANGUAGES = listOf("bg", "hr", "cs", "da", "nl", "en", "et", "fi", "fr", "de", "el", "hu", "it", "lv", "lt", "mt", "pl", "pt", "ro", "sk", "sl", "es", "sv", "ru", "uk")

        internal fun direct(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
    }
}

/**
 * [TdtJoint] on the graph's `decode` and `decode_1` signatures. Every call passes the encoder output, the tokens and the
 * LSTM state, and takes the state after the call into its own pair of buffers (a few KB); the logits stay in the output
 * tensor, read in place (`asReadOnlyBuffer`, a direct view) for the one row the loop needs. Adopting a state swaps the
 * pair in.
 */
internal class InterpreterJoint(private val interpreter: Interpreter, private val encoded: ByteBuffer, override val frames: Int) : TdtJoint {
    private val tokens = ParakeetTranscriber.direct(TdtGreedy.STATELESS_SLOTS * 4)
    private val token = ParakeetTranscriber.direct(4)
    private val zeroH = ParakeetTranscriber.direct(STATE_FLOATS * 4)
    private val zeroC = ParakeetTranscriber.direct(STATE_FLOATS * 4)
    private var h = ParakeetTranscriber.direct(STATE_FLOATS * 4)
    private var c = ParakeetTranscriber.direct(STATE_FLOATS * 4)
    private var nextH = ParakeetTranscriber.direct(STATE_FLOATS * 4)
    private var nextC = ParakeetTranscriber.direct(STATE_FLOATS * 4)

    override fun stateless(tokens: IntArray, t: Int, slot: Int, row: FloatArray) {
        this.tokens.clear(); this.tokens.asIntBuffer().put(tokens)
        run(DECODE, this.tokens, zeroH, zeroC)
        readRow(DECODE, TdtGreedy.STATELESS_SLOTS, t, slot, row)
    }

    override fun stateful(token: Int, t: Int, row: FloatArray) {
        this.token.clear(); this.token.putInt(0, token)
        run(DECODE_1, this.token, h, c)
        readRow(DECODE_1, 1, t, 0, row)
    }

    override fun adoptStateless() = adopt()

    override fun adopt() {
        val ph = h; h = nextH; nextH = ph
        val pc = c; c = nextC; nextC = pc
    }

    private fun run(signature: String, tokens: ByteBuffer, stateH: ByteBuffer, stateC: ByteBuffer) {
        encoded.rewind(); tokens.rewind(); stateH.rewind(); stateC.rewind(); nextH.clear(); nextC.clear()
        interpreter.runSignature(
            mapOf("args_0" to encoded, "args_1" to tokens, "args_2" to stateH, "args_3" to stateC),
            mapOf("output_1" to nextH, "output_2" to nextC),
            signature,
        )
        nextH.rewind(); nextC.rewind()
    }

    private fun readRow(signature: String, slots: Int, t: Int, slot: Int, row: FloatArray) {
        val logits = interpreter.getOutputTensorFromSignature("output_0", signature).asReadOnlyBuffer().order(ByteOrder.nativeOrder()).asFloatBuffer()
        logits.position((t * slots + slot) * TdtGreedy.ROW)
        logits.get(row, 0, TdtGreedy.ROW)
    }

    companion object {
        const val DECODE = "decode"
        const val DECODE_1 = "decode_1"
        /** The LSTM state, [2, 1, 640], twice (hidden and cell). */
        const val STATE_FLOATS = 2 * 640
    }
}

/** Builds the graph's Interpreter and checks its signatures against what the loop needs. */
internal object ParakeetGraphHandler : Handler<ParakeetGraph> {
    override val id = "sample.parakeet_tdt"
    override val abi = 1
    override val runtime = "litert"
    override val runtimeVersion: String = BuildConfig.LITERT_VERSION

    override fun prepare(local: LocalModel<ParakeetGraph>, host: PrepareHost, onProgress: (LoadEvent) -> Unit): ParakeetGraph {
        val plan = local.plan
        val file = local.files["graph"] ?: throw ModelException(ErrorCode.MANIFEST_INVALID, "the descriptor has no file id 'graph'")
        val threads = plan.variant.handlerConfig.optInt("cpu_threads", 4)
        onProgress(LoadEvent.Initializing(plan.profile.id))
        val t0 = System.nanoTime()
        val interpreter = try {
            Interpreter(file, Interpreter.Options().setNumThreads(threads).setUseXNNPACK(true))
        } catch (t: Throwable) {
            throw ModelException(ErrorCode.INITIALIZATION_FAILED, "the parakeet graph did not load: ${t.javaClass.simpleName}: ${t.message}", details = mapOf("stage" to "interpreter"), cause = t)
        }
        try {
            val keys = interpreter.signatureKeys.toSet()
            val missing = listOf("encode", "decode", "decode_1").filter { it !in keys }
            if (missing.isNotEmpty()) throw contract("the graph has no signature ${missing.joinToString()} (has ${keys.joinToString()})")
            val mel = interpreter.getInputTensorFromSignature("args_0", "encode").shape()
            val enc = interpreter.getOutputTensorFromSignature("output_0", "encode").shape()
            val logits = interpreter.getOutputTensorFromSignature("output_0", "decode_1").shape()
            if (mel.size != 3 || mel[1] != ParakeetFrontEnd.NMEL || enc.size != 3 || enc[1] != ParakeetTranscriber.ENCODER_DIM || logits.toList() != listOf(1, enc[2], 1, TdtGreedy.ROW)) {
                throw contract("unexpected shapes: encode ${mel.toList()} -> ${enc.toList()}, decode_1 logits ${logits.toList()}")
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            host.log.i("parakeet graph ${file.name}: mel ${mel[2]} frames -> ${enc[2]} encoder frames, $threads threads, interpreter ${ms} ms")
            val info = info(local, host, id, abi, runtime, runtimeVersion, listOf("interpreter api (xnnpack, $threads threads), window ${mel[2] / 100.0} s, load_ms=$ms"))
            return ParakeetGraph(info, interpreter, mel[2], enc[2], host)
        } catch (t: Throwable) {
            runCatching { interpreter.close() }
            throw t
        }
    }

    private fun contract(msg: String) = ModelException(ErrorCode.INITIALIZATION_FAILED, msg, details = mapOf("stage" to "contract"))
}

/** Reads the vocabulary from tokenizer.json. */
internal object ParakeetVocabHandler : Handler<ParakeetVocabModel> {
    override val id = "sample.parakeet_vocab"
    override val abi = 1
    override val runtime = "litert"
    override val runtimeVersion: String = BuildConfig.LITERT_VERSION

    override fun prepare(local: LocalModel<ParakeetVocabModel>, host: PrepareHost, onProgress: (LoadEvent) -> Unit): ParakeetVocabModel {
        val file: File = local.files["tokenizer"] ?: throw ModelException(ErrorCode.MANIFEST_INVALID, "the descriptor has no file id 'tokenizer'")
        onProgress(LoadEvent.Initializing(local.plan.profile.id))
        val vocab = try { ParakeetVocab.parse(file.readText()) } catch (t: Throwable) {
            throw ModelException(ErrorCode.INITIALIZATION_FAILED, "tokenizer.json did not parse: ${t.javaClass.simpleName}: ${t.message}", details = mapOf("stage" to "tokens"), cause = t)
        }
        if (vocab.size < TdtGreedy.BLANK) throw ModelException(ErrorCode.INITIALIZATION_FAILED, "tokenizer.json has ${vocab.size} pieces; the graph scores ${TdtGreedy.BLANK}", details = mapOf("stage" to "tokens"))
        host.log.i("parakeet vocab ${file.name}: ${vocab.size} pieces")
        return ParakeetVocabModel(info(local, host, id, abi, runtime, runtimeVersion, listOf("${vocab.size} pieces")), vocab, host)
    }
}

private fun info(local: LocalModel<*>, host: PrepareHost, id: String, abi: Int, runtime: String, runtimeVersion: String, notes: List<String>): PreparedModelInfo {
    val plan = local.plan
    return PreparedModelInfo(
        repoId = plan.ref.repoId, commit = plan.modelOrigin.commit, descriptorSha256 = plan.descriptorSha256, descriptorOrigin = plan.descriptorOrigin,
        bindingSource = plan.bindingSource, variantId = plan.variant.id, profileId = plan.profile.id, sdkVersion = host.sdkVersion,
        handlerId = id, handlerAbi = abi, runtime = runtime, runtimeVersion = runtimeVersion,
        declaredInputs = plan.variant.inputs, enabledInputs = plan.profile.enabledInputs, requestedBackendPolicy = plan.options.backendPolicy,
        components = mapOf("inference" to ComponentReport(requested = BackendKind.CPU, initialized = BackendKind.CPU)),
        excludedProfiles = plan.excludedProfiles, fallbackHistory = emptyList(), verification = plan.verification,
        notes = notes, files = local.files.mapValues { it.value.absolutePath },
    )
}
