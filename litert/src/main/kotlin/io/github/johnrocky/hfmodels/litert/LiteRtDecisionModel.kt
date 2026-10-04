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
import io.github.johnrocky.hfmodels.decide.DecisionLimits
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
 * One decision encoder on LiteRT `CompiledModel`: the main graph (one question's sequence -> option
 * scores) and, when the family has one, a small head graph after it (laya's act head, always created on
 * the CPU). The family's [DecisionContract] plans and builds the forwards, names the graph inputs and outputs
 * and decodes the scores; this class runs them. The graph takes token ids or, when the variant ships a token
 * table, embeddings the host looks up ([TokenTable]). Every native call runs on one dedicated thread; the
 * process-wide `Environment` is created once and kept. One `decide` at a time.
 */
internal class LiteRtDecisionModel private constructor(
    override val info: PreparedModelInfo,
    /** Test hook as well: device parity checks build the publisher's rows through it. */
    internal val contract: DecisionContract,
    private val signature: Signature,
    private val main: Graph,
    private val head: Graph?,
    private val table: TokenTable?,
    private val host: PrepareHost,
) : TypedDecisions {
    override val limits: DecisionLimits get() = contract.limits

    private class Graph(val model: CompiledModel, val inputs: Map<String, TensorBuffer>, val outputs: Map<String, TensorBuffer>) {
        fun close() { (inputs.values + outputs.values).forEach { runCatching { it.close() } }; runCatching { model.close() } }
    }

    private val busy = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val closed = CompletableDeferred<Unit>()
    private val window = contract.limits.windowTokens
    private val inputIds = if (table == null) IntArray(window) else null
    private val embeds = if (table != null) FloatArray(window * table.hidden) else null
    private val attention = FloatArray(window)
    /** Test hook: the option scores and head logits of the last forward (device parity checks read them). */
    @Volatile internal var lastRaw: Pair<FloatArray, FloatArray>? = null
    /** Test hook: milliseconds the host spent looking up the last forward's embeddings (0 on a token-id graph). */
    @Volatile internal var lastLookupMs: Double = 0.0

    override suspend fun decide(state: Any, questions: Map<String, Question>): Decisions = withBusy {
        val t0 = System.nanoTime()
        val stateIds = contract.stateIds(state)
        val stateMs = (System.nanoTime() - t0) / 1e6
        answer(stateIds, questions, stateMs, t0)
    }

    override suspend fun prefill(state: Any): PreparedState {
        checkUsable()
        val t0 = System.nanoTime()
        val stateIds = withContext(Dispatchers.Default) { contract.stateIds(state) }
        val stateMs = (System.nanoTime() - t0) / 1e6
        return object : PreparedState {
            override val model: TypedDecisions get() = this@LiteRtDecisionModel
            override suspend fun decide(questions: Map<String, Question>): Decisions = withBusy { answer(stateIds, questions, stateMs, System.nanoTime()) }
            override fun close() {}
        }
    }

    /** The contract's plan, one forward per batch ([DecisionRun]). */
    private fun answer(stateIds: IntArray, questions: Map<String, Question>, stateMs: Double, t0: Long): Decisions =
        DecisionRun.decide(contract, stateIds, questions, info.repoId, stateMs, t0) { f -> run(f).also { (raw, head) -> lastRaw = raw to (head ?: floatArrayOf(0f, 0f)) } }

    /** Test hook: the option scores of one forward the caller built (device checks replay the publisher's own requests through it). */
    internal fun scores(f: Forward): FloatArray { checkUsable(); return run(f).first }

    /** One forward of the main graph (and the head): option scores and head logits. Native calls on the model thread. */
    private fun run(f: Forward): Pair<FloatArray, FloatArray?> = native {
        val n = f.ids.size
        java.util.Arrays.fill(attention, 0f)
        for (i in 0 until n) attention[i] = 1f
        try {
            if (table != null) {
                val e = embeds!!
                val h = table.hidden
                val padId = contract.padId
                val tl = System.nanoTime()
                for (p in 0 until window) table.copyRow(if (p < n) f.ids[p] else padId, e, p * h)
                lastLookupMs = (System.nanoTime() - tl) / 1e6
                main.inputs.getValue(signature.tokens).writeFloat(e)
            } else {
                val ids = inputIds!!
                java.util.Arrays.fill(ids, 0)
                System.arraycopy(f.ids, 0, ids, 0, n)
                main.inputs.getValue(signature.tokens).writeInt(ids)
            }
            main.inputs.getValue(signature.attention).writeFloat(attention)
            main.inputs.getValue(signature.routing).writeFloat(f.routing)
            if (f.extraInputs.size != signature.extraInputs.size) throw IllegalStateException("the forward carries ${f.extraInputs.size} extra inputs; the graph takes ${signature.extraInputs}")
            signature.extraInputs.forEachIndexed { i, name -> main.inputs.getValue(name).writeFloat(f.extraInputs[i]) }
            main.model.run(main.inputs, main.outputs, SIGNATURE)
            val read = HashMap<String, FloatArray>()
            val output: (String) -> FloatArray = { name -> read.getOrPut(name) { main.outputs.getValue(name).readFloat() } }
            val raw = contract.scores(f, output)
            if (raw.any { it.isNaN() || it.isInfinite() }) throw ModelException(ErrorCode.INFERENCE_FAILED, "the main graph returned a non-finite option score (profile ${info.profileId})", details = mapOf("profile" to info.profileId))
            val headLogits = if (head != null) {
                val h = contract.head!!
                for ((name, v) in h.feed(output, raw)) head.inputs.getValue(name).writeFloat(v)
                head.model.run(head.inputs, head.outputs, SIGNATURE)
                head.outputs.getValue(h.output).readFloat()
            } else null
            raw to headLogits
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
        runCatching { head?.close() }.onFailure { host.log.w("head graph close: ${it.message}") }
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
         * What to compile. `tableFile` set = a host-lookup graph (the token input takes embeddings of width
         * `hidden`, the table's rows in `tableDtype`); null = a token-id graph. `gpuPrecision` null = no GPU options.
         */
        class Spec(
            val mainFile: File,
            val tableFile: File?,
            val tableDtype: TokenTable.Dtype,
            val hidden: Int,
            val accelerator: Accelerator,
            val gpuPrecision: CompiledModel.GpuOptions.Precision?,
            val cpuThreads: Int,
        )

        /** Compiles the graphs on the model thread. Throws the runtime's exception unchanged; the handler maps it. */
        fun open(spec: Spec, info: PreparedModelInfo, contract: DecisionContract, host: PrepareHost): LiteRtDecisionModel = Runtime.call {
            // LiteRT 2.2.0 starts the NPU runtime once per process, from the options of the first graph it compiles on any
            // accelerator, and the HTP performance mode is fixed then. So every graph of an app that packages the NPU
            // libraries carries BURST (the clocks the S26 numbers were measured at; the HTP computes in fp16, and the
            // published NPU profiles are the graphs rewritten to stay finite there). Without it, a GPU or CPU model
            // opened first leaves a later NPU model about 5x slower.
            val burst = if (host.appContext?.let(LiteRtNpu::ready) == true) CompiledModel.QualcommOptions(htpPerformanceMode = CompiledModel.QualcommOptions.HtpPerformanceMode.BURST) else null
            val options = CompiledModel.Options(spec.accelerator).apply {
                when (spec.accelerator) {
                    Accelerator.GPU -> spec.gpuPrecision?.let { gpuOptions = CompiledModel.GpuOptions(precision = it) }
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
                val model = CompiledModel.create(spec.mainFile.absolutePath, options, env)
                val signature = try {
                    contract.signature(table != null, { n -> model.getInputTensorType(n, SIGNATURE).layout?.dimensions }, { n -> model.getOutputTensorType(n, SIGNATURE).layout?.dimensions })
                } catch (t: Throwable) { runCatching { model.close() }; throw t }
                val main = graph(model, signature.inputs, signature.outputs)
                val head = try {
                    contract.head?.let { h -> graph(CompiledModel.create(h.file.absolutePath, CompiledModel.Options(Accelerator.CPU).apply { qualcommOptions = burst }, env), h.inputs, listOf(h.output)) }
                } catch (t: Throwable) { main.close(); throw t }
                LiteRtDecisionModel(info, contract, signature, main, head, table, host)
            } catch (t: Throwable) { table?.close(); throw t }
        }

        /** The buffers of a compiled graph; closes the graph when one cannot be created. */
        private fun graph(model: CompiledModel, inputs: List<String>, outputs: List<String>): Graph {
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
