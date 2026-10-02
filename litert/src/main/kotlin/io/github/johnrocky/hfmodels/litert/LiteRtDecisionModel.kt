package io.github.johnrocky.hfmodels.litert

import android.content.Context
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
import kotlinx.coroutines.withContext

/**
 * One decision encoder on LiteRT `CompiledModel`: the main graph (one question's sequence -> a
 * score per position, plus a pooled vector on laya) and, on laya, the small act head (always created
 * on the CPU). The graph takes token ids (`input_ids`) or, when the variant ships a token table,
 * embeddings the host looks up (`inputs_embeds`, [TokenTable]). Every native call runs on one
 * dedicated thread; the process-wide `Environment` is created once and kept. One `decide` at a time.
 */
internal class LiteRtDecisionModel private constructor(
    override val info: PreparedModelInfo,
    override val limits: DecisionLimits,
    /** Test hook as well: device parity checks build the publisher's rows through it. */
    internal val builder: DecisionSequenceBuilder,
    private val decoder: Decoder,
    private val main: Graph,
    private val act: Graph?,
    private val table: TokenTable?,
    private val padId: Int,
    private val host: PrepareHost,
    /** Test hook: the laya temperature settings this load decodes with (null on a family without calibration). */
    internal val calibration: LayaCalibration?,
) : TypedDecisions {
    /** Family-specific: marker scores (and act logits) -> the answer. */
    fun interface Decoder { fun answer(q: Question, rawLogits: FloatArray, actLogits: FloatArray): Answer }

    private class Graph(val model: CompiledModel, val inputs: Map<String, TensorBuffer>, val outputs: Map<String, TensorBuffer>) {
        fun close() { (inputs.values + outputs.values).forEach { runCatching { it.close() } }; runCatching { model.close() } }
    }

    private val busy = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val closed = CompletableDeferred<Unit>()
    private val window = builder.maxLen
    private val inputIds = if (table == null) IntArray(window) else null
    private val embeds = if (table != null) FloatArray(window * table.hidden) else null
    private val attention = FloatArray(window)
    private val qtypeOneHot = FloatArray(3)
    /** Test hook: the marker and act logits of the last forward (device parity checks read them). */
    @Volatile internal var lastRaw: Pair<FloatArray, FloatArray>? = null
    /** Test hook: milliseconds the host spent looking up the last forward's embeddings (0 on a token-id graph). */
    @Volatile internal var lastLookupMs: Double = 0.0

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
            builder.validate(q)
            val built = builder.build(q, stateIds)
            if (built.markers.size != builder.renderOptions(q).size) throw ModelException(
                ErrorCode.CONTEXT_LIMIT_EXCEEDED, "question '$id' does not fit the ${window}-token window: ${built.markers.size} of ${builder.renderOptions(q).size} options kept",
                details = mapOf("question" to id, "window" to window.toString()),
            )
            stateTokens = built.stateTokens
            truncated = truncated || built.stateTruncated
            val (raw, actLogits) = run(built)
            lastRaw = raw to actLogits
            answers[id] = decoder.answer(q, raw, actLogits)
            perQuestion += (System.nanoTime() - tq) / 1e6
        }
        return Decisions(answers, info.repoId, DecisionTiming(stateMs, perQuestion, (System.nanoTime() - t0) / 1e6), stateTokens, truncated)
    }

    /** One forward of the main graph (and the act head): marker logits and act logits. Native calls on the model thread. */
    private fun run(built: DecisionSequenceBuilder.Built): Pair<FloatArray, FloatArray> = native {
        val n = built.ids.size
        java.util.Arrays.fill(attention, 0f)
        for (i in 0 until n) attention[i] = 1f
        java.util.Arrays.fill(qtypeOneHot, 0f); qtypeOneHot[built.qtype] = 1f
        try {
            if (table != null) {
                val e = embeds!!
                val h = table.hidden
                val tl = System.nanoTime()
                for (p in 0 until window) table.copyRow(if (p < n) built.ids[p] else padId, e, p * h)
                lastLookupMs = (System.nanoTime() - tl) / 1e6
                main.inputs.getValue("inputs_embeds").writeFloat(e)
            } else {
                val ids = inputIds!!
                java.util.Arrays.fill(ids, 0)
                System.arraycopy(built.ids, 0, ids, 0, n)
                main.inputs.getValue("input_ids").writeInt(ids)
            }
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
        runCatching { table?.close() }.onFailure { host.log.w("token table close: ${it.message}") }
        host.onModelClosed(this)
        closed.complete(Unit)
    }

    private fun <T> native(block: () -> T): T = Runtime.call(block)

    /** The native thread and the process-wide Environment (LiteRT wants creation, run and close on one thread; the Environment outlives models). */
    internal object Runtime {
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "hfmodels-litert").apply { isDaemon = true } }
        val dispatcher = executor.asCoroutineDispatcher()
        @Volatile private var env: Environment? = null

        /**
         * Created once per process. An app that packages the NPU libraries gets both library dirs (dispatch and the JIT
         * compiler plugin: without the plugin dir a graph requested on the NPU runs on the CPU) and, through the Context
         * overload, a JIT cache in its cache dir (a cached compile takes well under a second, the first one tens of
         * seconds); every other app keeps the plain Environment, as before the NPU path existed.
         */
        fun environment(context: Context?): Environment = env ?: run {
            val npu = context != null && LiteRtNpu.ready(context)
            val created = if (npu) {
                val dir = context!!.applicationInfo.nativeLibraryDir
                Environment.create(context, mapOf(Environment.Option.DispatchLibraryDir to dir, Environment.Option.CompilerPluginLibraryDir to dir))
            } else Environment.create()
            created.also { env = it }
        }
        fun <T> call(block: () -> T): T {
            if (Thread.currentThread().name == "hfmodels-litert") return block()
            try { return executor.submit(Callable { block() }).get() } catch (e: ExecutionException) { throw e.cause ?: e }
        }
    }

    companion object {
        const val SIGNATURE = "serving_default"

        /**
         * What to compile. `tableFile` set = a host-lookup graph (`inputs_embeds` of width `hidden`,
         * the table's rows in `tableDtype`); null = a token-id graph (`input_ids`).
         */
        class Spec(
            val mainFile: File,
            val actFile: File?,
            val tableFile: File?,
            val tableDtype: TokenTable.Dtype,
            val window: Int,
            val hidden: Int,
            val accelerator: Accelerator,
            val gpuFp32: Boolean,
            val cpuThreads: Int,
            val family: DecisionFamily,
            val padId: Int,
        )

        /** Compiles the graphs on the model thread. Throws the runtime's exception unchanged; the handler maps it. */
        fun open(spec: Spec, info: PreparedModelInfo, limits: DecisionLimits, builder: DecisionSequenceBuilder, decoder: Decoder, calibration: LayaCalibration?, host: PrepareHost): LiteRtDecisionModel = Runtime.call {
            // LiteRT 2.2.0 starts the NPU runtime once per process, from the options of the first graph it compiles on any
            // accelerator, and the HTP performance mode is fixed then. So every graph of an app that packages the NPU
            // libraries carries BURST (the clocks the S26 numbers were measured at; the HTP computes in fp16, and the
            // published NPU profiles are the graphs rewritten to stay finite there). Without it, a GPU or CPU model
            // opened first leaves a later NPU model about 5x slower.
            val burst = if (host.appContext?.let(LiteRtNpu::ready) == true) CompiledModel.QualcommOptions(htpPerformanceMode = CompiledModel.QualcommOptions.HtpPerformanceMode.BURST) else null
            val options = CompiledModel.Options(spec.accelerator).apply {
                when (spec.accelerator) {
                    Accelerator.GPU -> if (spec.gpuFp32) gpuOptions = CompiledModel.GpuOptions(precision = CompiledModel.GpuOptions.Precision.FP32)
                    Accelerator.NPU -> Unit
                    else -> cpuOptions = CompiledModel.CpuOptions(numThreads = spec.cpuThreads)
                }
                qualcommOptions = burst
            }
            val env = Runtime.environment(host.appContext)
            // LiteRT 2.2.0 falls back to the CPU when the NPU cannot be used; refuse instead of reporting an NPU load.
            if (spec.accelerator == Accelerator.NPU && Accelerator.NPU !in env.getAvailableAccelerators()) {
                throw IllegalStateException("LiteRT has no NPU accelerator in this process (the Qualcomm runtime did not load)")
            }
            val table = spec.tableFile?.let { TokenTable.open(it, spec.hidden, spec.tableDtype) }
            try {
                val inputs = listOf(if (table != null) "inputs_embeds" else "input_ids", "attention_mask", "qtype_onehot")
                val main = graph(spec.mainFile, options, env, inputs, spec.family.graphOutputs)
                val act = try { spec.actFile?.let { graph(it, CompiledModel.Options(Accelerator.CPU).apply { qualcommOptions = burst }, env, listOf("pooled_cls", "feats"), listOf("act_logits")) } } catch (t: Throwable) { main.close(); throw t }
                LiteRtDecisionModel(info, limits, builder, decoder, main, act, table, spec.padId, host, calibration)
            } catch (t: Throwable) { table?.close(); throw t }
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
