package io.github.johnrocky.hfmodels.litert

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.PrepareHost
import io.github.johnrocky.hfmodels.PreparedModelInfo
import io.github.johnrocky.hfmodels.decide.Answer
import io.github.johnrocky.hfmodels.decide.DecisionLimits
import io.github.johnrocky.hfmodels.decide.DecisionTiming
import io.github.johnrocky.hfmodels.decide.Decisions
import io.github.johnrocky.hfmodels.decide.PreparedState
import io.github.johnrocky.hfmodels.decide.Question
import io.github.johnrocky.hfmodels.decide.TypedDecisions
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * One decision encoder on LiteRT `CompiledModel`: the main graph (tokens -> per-position scores +
 * pooled vector) and the small act head. Every native call runs on one dedicated thread; the
 * process-wide `Environment` is created once and kept. One `decide` at a time.
 */
internal class LiteRtDecisionModel private constructor(
    override val info: PreparedModelInfo,
    override val limits: DecisionLimits,
    private val builder: LayaSequenceBuilder,
    private val calibration: LayaCalibration,
    private val main: Graph,
    private val act: Graph?,
    private val hidden: Int,
    private val host: PrepareHost,
) : TypedDecisions {
    private class Graph(val model: CompiledModel, val inputs: Map<String, TensorBuffer>, val outputs: Map<String, TensorBuffer>) {
        fun close() { (inputs.values + outputs.values).forEach { runCatching { it.close() } }; runCatching { model.close() } }
    }

    private val busy = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val closed = CompletableDeferred<Unit>()
    private val window = builder.maxLen
    private val inputIds = IntArray(window)
    private val attention = FloatArray(window)
    private val qtypeOneHot = FloatArray(3)
    /** Test hook: the marker and act logits of the last forward (device parity checks read them). */
    @Volatile internal var lastRaw: Pair<FloatArray, FloatArray>? = null

    override suspend fun decide(state: Any, questions: Map<String, Question>): Decisions = withBusy {
        val t0 = System.nanoTime()
        val serialized = builder.serializeState(state)
        val stateIds = builder.stateIds(serialized)
        val stateMs = (System.nanoTime() - t0) / 1e6
        answer(stateIds, questions, stateMs, t0)
    }

    override suspend fun prefill(state: Any): PreparedState {
        checkUsable()
        val t0 = System.nanoTime()
        val serialized = builder.serializeState(state)
        val stateIds = withContext(Dispatchers.Default) { builder.stateIds(serialized) }
        val stateMs = (System.nanoTime() - t0) / 1e6
        return object : PreparedState {
            override val model: TypedDecisions get() = this@LiteRtDecisionModel
            override suspend fun decide(questions: Map<String, Question>): Decisions = withBusy { answer(stateIds, questions, stateMs, System.nanoTime()) }
            override fun close() {}
        }
    }

    private fun answer(stateIds: IntArray, questions: Map<String, Question>, stateMs: Double, t0: Long): Decisions {
        if (questions.isEmpty()) throw ModelException(ErrorCode.INVALID_INPUT, "no questions")
        val answers = LinkedHashMap<String, Answer>()
        val perQuestion = ArrayList<Double>(questions.size)
        var stateTokens = stateIds.size
        var truncated = false
        for ((id, q) in questions) {
            val tq = System.nanoTime()
            val built = builder.build(q, stateIds)
            if (built.markers.size != builder.renderOptions(q).size) throw ModelException(
                ErrorCode.CONTEXT_LIMIT_EXCEEDED, "question '$id' does not fit the ${window}-token window: ${built.markers.size} of ${builder.renderOptions(q).size} options kept",
                details = mapOf("question" to id, "window" to window.toString()),
            )
            stateTokens = built.stateTokens
            truncated = truncated || built.stateTruncated
            val (raw, actLogits) = run(built)
            lastRaw = raw to actLogits
            answers[id] = LayaDecode.answer(q, raw, actLogits, calibration)
            perQuestion += (System.nanoTime() - tq) / 1e6
        }
        return Decisions(answers, info.repoId, DecisionTiming(stateMs, perQuestion, (System.nanoTime() - t0) / 1e6), stateTokens, truncated)
    }

    /** One forward of the main graph (and the act head): marker logits and act logits. Native calls on the model thread. */
    private fun run(built: LayaSequenceBuilder.Built): Pair<FloatArray, FloatArray> = native {
        val n = built.ids.size
        java.util.Arrays.fill(inputIds, 0); java.util.Arrays.fill(attention, 0f)
        System.arraycopy(built.ids, 0, inputIds, 0, n)
        for (i in 0 until n) attention[i] = 1f
        java.util.Arrays.fill(qtypeOneHot, 0f); qtypeOneHot[built.qtype] = 1f
        try {
            main.inputs.getValue("input_ids").writeInt(inputIds)
            main.inputs.getValue("attention_mask").writeFloat(attention)
            main.inputs.getValue("qtype_onehot").writeFloat(qtypeOneHot)
            main.model.run(main.inputs, main.outputs, SIGNATURE)
            val logits = main.outputs.getValue("token_logits").readFloat()
            val raw = FloatArray(built.markers.size) { logits[built.markers[it]] }
            if (raw.any { it.isNaN() || it.isInfinite() }) throw ModelException(ErrorCode.INFERENCE_FAILED, "the main graph returned a non-finite marker score (profile ${info.profileId})", details = mapOf("profile" to info.profileId))
            val actLogits = if (act != null) {
                val pooled = main.outputs.getValue("pooled_cls").readFloat()
                act.inputs.getValue("pooled_cls").writeFloat(pooled)
                act.inputs.getValue("feats").writeFloat(LayaDecode.actFeatures(raw))
                act.model.run(act.inputs, act.outputs, SIGNATURE)
                act.outputs.getValue("act_logits").readFloat()
            } else floatArrayOf(0f, 0f)
            raw to actLogits
        } catch (e: ModelException) { throw e } catch (t: Throwable) {
            throw ModelException(ErrorCode.INFERENCE_FAILED, "CompiledModel.run failed: ${t.javaClass.simpleName}: ${t.message}", cause = t)
        }
    }

    private suspend fun <T> withBusy(block: suspend () -> T): T {
        checkUsable()
        if (!busy.compareAndSet(false, true)) throw ModelException(ErrorCode.MODEL_BUSY, "a decision is already running on ${info.repoId} (one at a time per model)")
        try { return block() } finally { busy.set(false) }
    }

    private fun checkUsable() { if (closing.get()) throw ModelException(ErrorCode.MODEL_CLOSED, "model ${info.repoId} is closing or closed") }

    override fun close() { if (closing.compareAndSet(false, true)) Runtime.executor.execute { doClose() } }

    override suspend fun closeAndJoin() {
        val first = closing.compareAndSet(false, true)
        withContext(NonCancellable) {
            if (first) native { doClose() }
            closed.await()
        }
    }

    private fun doClose() {
        if (closed.isCompleted) return
        runCatching { act?.close() }.onFailure { host.log.w("act head close: ${it.message}") }
        runCatching { main.close() }.onFailure { host.log.w("main graph close: ${it.message}") }
        host.onModelClosed(this)
        closed.complete(Unit)
    }

    private fun <T> native(block: () -> T): T = Runtime.call(block)

    /** The native thread and the process-wide Environment (LiteRT wants creation, run and close on one thread; the Environment outlives models). */
    internal object Runtime {
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "hfmodels-litert").apply { isDaemon = true } }
        val dispatcher = executor.asCoroutineDispatcher()
        @Volatile private var env: Environment? = null
        fun environment(): Environment = env ?: Environment.create().also { env = it }
        fun <T> call(block: () -> T): T {
            if (Thread.currentThread().name == "hfmodels-litert") return block()
            try { return executor.submit(Callable { block() }).get() } catch (e: ExecutionException) { throw e.cause ?: e }
        }
    }

    companion object {
        const val SIGNATURE = "serving_default"

        class Spec(val mainFile: File, val actFile: File?, val window: Int, val hidden: Int, val accelerator: Accelerator, val gpuFp32: Boolean, val cpuThreads: Int)

        /** Compiles the graphs on the model thread. Throws the runtime's exception unchanged; the handler maps it. */
        fun open(spec: Spec, info: PreparedModelInfo, limits: DecisionLimits, builder: LayaSequenceBuilder, cal: LayaCalibration, host: PrepareHost): LiteRtDecisionModel = Runtime.call {
            val options = CompiledModel.Options(spec.accelerator).apply {
                if (spec.accelerator == Accelerator.GPU) {
                    if (spec.gpuFp32) gpuOptions = CompiledModel.GpuOptions(precision = CompiledModel.GpuOptions.Precision.FP32)
                } else cpuOptions = CompiledModel.CpuOptions(numThreads = spec.cpuThreads)
            }
            val env = Runtime.environment()
            val main = graph(spec.mainFile, options, env, listOf("input_ids", "attention_mask", "qtype_onehot"), listOf("token_logits", "pooled_cls"))
            val act = try { spec.actFile?.let { graph(it, CompiledModel.Options(Accelerator.CPU), env, listOf("pooled_cls", "feats"), listOf("act_logits")) } } catch (t: Throwable) { main.close(); throw t }
            LiteRtDecisionModel(info, limits, builder, cal, main, act, spec.hidden, host)
        }

        private fun graph(file: File, options: CompiledModel.Options, env: Environment, inputs: List<String>, outputs: List<String>): Graph {
            val model = CompiledModel.create(file.absolutePath, options, env)
            val ins = LinkedHashMap<String, TensorBuffer>()
            val outs = LinkedHashMap<String, TensorBuffer>()
            try {
                for (n in inputs) ins[n] = model.createInputBuffer(n, SIGNATURE)
                for (n in outputs) outs[n] = model.createOutputBuffer(n, SIGNATURE)
            } catch (t: Throwable) {
                (ins.values + outs.values).forEach { runCatching { it.close() } }
                runCatching { model.close() }
                throw t
            }
            return Graph(model, ins, outs)
        }
    }
}
