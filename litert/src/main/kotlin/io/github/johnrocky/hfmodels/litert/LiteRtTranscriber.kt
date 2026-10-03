package io.github.johnrocky.hfmodels.litert

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.PrepareHost
import io.github.johnrocky.hfmodels.PreparedModelInfo
import io.github.johnrocky.hfmodels.speech.Transcriber
import io.github.johnrocky.hfmodels.speech.TranscriberLimits
import io.github.johnrocky.hfmodels.speech.Transcript
import io.github.johnrocky.hfmodels.speech.TranscriptTiming
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * One zipformer CTC graph on LiteRT `CompiledModel`: host fbank ([ZipformerFbank]) -> the graph
 * ([ZipformerCtc] inputs) -> host greedy CTC. The fbank runs on `Dispatchers.Default`; every native
 * call runs on the shared LiteRT thread ([LiteRtDecisionModel.Runtime]) with the process-wide
 * `Environment`. One `transcribe` at a time.
 */
internal class LiteRtTranscriber private constructor(
    override val info: PreparedModelInfo,
    override val limits: TranscriberLimits,
    private val contract: ZipformerCtc,
    private val pieces: Map<Int, String>,
    private val classes: Int,
    private val model: CompiledModel,
    private val inputs: List<TensorBuffer>,
    private val outputs: List<TensorBuffer>,
    private val fbankSlot: Int,
    private val biasSlots: List<Int>,
    private val logitsSlot: Int,
    private val host: PrepareHost,
) : Transcriber {
    private val fbank = ZipformerFbank()
    private val maxSamples = (limits.sampleRate * limits.windowSeconds).toInt()
    private val features = FloatArray(contract.frames * ZipformerFbank.NMEL)
    private val busy = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val closed = CompletableDeferred<Unit>()

    override suspend fun transcribe(pcm: FloatArray): Transcript {
        checkUsable()
        if (pcm.size > maxSamples) throw ModelException(
            ErrorCode.INVALID_INPUT, "${pcm.size} samples (${"%.2f".format(pcm.size.toDouble() / limits.sampleRate)} s) is longer than the ${limits.windowSeconds} s window; split the audio (consecutive windows are not handled by this model)",
            details = mapOf("samples" to pcm.size.toString(), "max_samples" to maxSamples.toString()),
        )
        if (pcm.size < ZipformerFbank.WIN) throw ModelException(ErrorCode.INVALID_INPUT, "${pcm.size} samples is shorter than one ${ZipformerFbank.WIN}-sample analysis frame", details = mapOf("samples" to pcm.size.toString()))
        if (!busy.compareAndSet(false, true)) throw ModelException(ErrorCode.MODEL_BUSY, "a transcription is already running on ${info.repoId} (one at a time per model)")
        try {
            val t0 = System.nanoTime()
            val real = withContext(Dispatchers.Default) {
                for (x in pcm) if (!x.isFinite()) throw ModelException(ErrorCode.INVALID_INPUT, "the audio contains a non-finite sample")
                java.util.Arrays.fill(features, ZipformerFbank.LOG_PAD)
                fbank.compute(pcm, features)
                fbank.frames(pcm.size)
            }
            val t1 = System.nanoTime()
            val valid50 = contract.valid50(real)
            val logits = withContext(LiteRtDecisionModel.Runtime.dispatcher) { run(valid50) }
            val t2 = System.nanoTime()
            val text = ZipformerCtc.decode(logits, contract.validOut(valid50), classes, contract.blank, pieces)
            val t3 = System.nanoTime()
            return Transcript(text, TranscriptTiming(featureMs = (t1 - t0) / 1e6, inferenceMs = (t2 - t1) / 1e6, totalMs = (t3 - t0) / 1e6))
        } finally {
            busy.set(false)
        }
    }

    /** Writes the features and biases, runs the graph and reads the logits back (the readback waits for the GPU). On the LiteRT thread. */
    private fun run(valid50: Int): FloatArray = try {
        // close() may have run on this thread while the features were computed.
        if (closed.isCompleted) throw ModelException(ErrorCode.MODEL_CLOSED, "model ${info.repoId} was closed during the call")
        inputs[fbankSlot].writeFloat(features)
        for (r in 0 until 4) inputs[biasSlots[r]].writeFloat(contract.bias(r, valid50))
        model.run(inputs, outputs)
        outputs[logitsSlot].readFloat()
    } catch (e: ModelException) { throw e } catch (t: Throwable) {
        throw ModelException(ErrorCode.INFERENCE_FAILED, "CompiledModel.run failed: ${t.javaClass.simpleName}: ${t.message}", details = mapOf("profile" to info.profileId), cause = t)
    }

    private fun checkUsable() { if (closing.get()) throw ModelException(ErrorCode.MODEL_CLOSED, "model ${info.repoId} is closing or closed") }

    override fun close() { if (closing.compareAndSet(false, true)) LiteRtDecisionModel.Runtime.executor.execute { doClose() } }

    override suspend fun closeAndJoin() {
        val first = closing.compareAndSet(false, true)
        withContext(NonCancellable) {
            if (first) LiteRtDecisionModel.Runtime.call { doClose() }
            closed.await()
        }
    }

    private fun doClose() {
        if (closed.isCompleted) return
        (inputs + outputs).forEach { runCatching { it.close() } }
        runCatching { model.close() }.onFailure { host.log.w("transcriber graph close: ${it.message}") }
        host.onModelClosed(this)
        closed.complete(Unit)
    }

    companion object {
        /** Compiles the graph on the LiteRT thread and checks it against [contract]. Throws the runtime's exception unchanged; the handler maps it. */
        fun open(file: File, accelerator: Accelerator, gpuFp32: Boolean, cpuThreads: Int, contract: ZipformerCtc, pieces: Map<Int, String>, info: PreparedModelInfo, limits: TranscriberLimits, host: PrepareHost): LiteRtTranscriber = LiteRtDecisionModel.Runtime.call {
            // As in LiteRtDecisionModel.open: in an app that packages the NPU libraries every graph carries BURST, so a
            // transcriber compiled first does not leave a later NPU model at the slower default HTP mode.
            val burst = if (host.appContext?.let(LiteRtNpu::ready) == true) CompiledModel.QualcommOptions(htpPerformanceMode = CompiledModel.QualcommOptions.HtpPerformanceMode.BURST) else null
            val options = CompiledModel.Options(accelerator).apply {
                when (accelerator) {
                    Accelerator.GPU -> if (gpuFp32) gpuOptions = CompiledModel.GpuOptions(precision = CompiledModel.GpuOptions.Precision.FP32)
                    else -> cpuOptions = CompiledModel.CpuOptions(numThreads = cpuThreads)
                }
                qualcommOptions = burst
            }
            val model = CompiledModel.create(file.absolutePath, options, LiteRtDecisionModel.Runtime.environment(host.appContext))
            var ins: List<TensorBuffer> = emptyList()
            var outs: List<TensorBuffer> = emptyList()
            try {
                ins = model.createInputBuffers()
                outs = model.createOutputBuffers()
                val inSizes = ins.map { it.readFloat().size }
                val outSizes = outs.map { it.readFloat().size }
                val fbankSlot = inSizes.indexOf(contract.frames * ZipformerFbank.NMEL)
                val biasSlots = contract.biasLengths.map { inSizes.indexOf(it) }
                val logitsSlot = outSizes.indexOfFirst { it > 0 && it % contract.tOut == 0 }
                if (fbankSlot < 0 || biasSlots.any { it < 0 } || logitsSlot < 0) throw ModelException(
                    ErrorCode.INITIALIZATION_FAILED,
                    "the graph does not match the zipformer_ctc contract for a ${limits.windowSeconds} s window: expected inputs of ${contract.frames * ZipformerFbank.NMEL} (fbank ${contract.frames}x${ZipformerFbank.NMEL}) and ${contract.biasLengths} floats and an output of ${contract.tOut} x classes; found inputs $inSizes, outputs $outSizes",
                    details = mapOf("stage" to "contract", "inputs" to inSizes.toString(), "outputs" to outSizes.toString()),
                )
                val classes = outSizes[logitsSlot] / contract.tOut
                val missing = (0 until classes).firstOrNull { it !in pieces }
                if (missing != null) throw ModelException(ErrorCode.INITIALIZATION_FAILED, "the graph scores $classes classes but tokens has no piece for id $missing", details = mapOf("stage" to "tokens"))
                LiteRtTranscriber(info, limits, contract, pieces, classes, model, ins, outs, fbankSlot, biasSlots, logitsSlot, host)
            } catch (t: Throwable) {
                (ins + outs).forEach { runCatching { it.close() } }
                runCatching { model.close() }
                throw t
            }
        }
    }
}
