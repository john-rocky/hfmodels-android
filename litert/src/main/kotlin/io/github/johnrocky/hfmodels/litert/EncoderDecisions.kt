package io.github.johnrocky.hfmodels.litert

import com.google.ai.edge.litert.Accelerator
import io.github.johnrocky.hfmodels.BackendKind
import io.github.johnrocky.hfmodels.BackendPolicy
import io.github.johnrocky.hfmodels.ComponentReport
import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.Handler
import io.github.johnrocky.hfmodels.LoadEvent
import io.github.johnrocky.hfmodels.LocalModel
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.PrepareHost
import io.github.johnrocky.hfmodels.PreparedModelInfo
import io.github.johnrocky.hfmodels.Task
import io.github.johnrocky.hfmodels.decide.DecisionLimits
import io.github.johnrocky.hfmodels.decide.TypedDecisions
import io.github.johnrocky.hfmodels.descriptor.Profile
import java.io.File

/**
 * The typed-decisions task on a classic `.tflite` decision encoder run by LiteRT's `CompiledModel`
 * (`com.google.ai.edge.litert`). Pass it where `Tasks.Chat` goes:
 *
 * ```kotlin
 * val model: TypedDecisions = models.fromPretrained(ModelRef("<owner>/<name>"), EncoderDecisions)
 * val answers = model.decide(state, questions)
 * ```
 *
 * The descriptor names the task `decide`, the runtime `litert` and the handler
 * `litert.typed_decisions` ABI 1. Profile components use the key `inference` (`cpu` / `gpu`).
 */
object EncoderDecisions : Task<TypedDecisions> {
    override val id = "decide"
    override val handler: Handler<TypedDecisions> = LiteRtDecisionHandler
}

/**
 * `handler_config` keys:
 *  - `family`: `laya` (the only family this release ships; the sequence and decoding contract);
 *  - `window`: the graph's static sequence length (256 / 512); `head_tokens` (default from the config file);
 *  - `hidden`: the pooled vector width (1024 for ModernBERT-large, 768 for mmBERT-base);
 *  - `files`: `{main, act_head, tokenizer, tokenizer_config, config}` -> file ids of the variant;
 *  - `gpu_precision`: `fp32` (default; explicit FP32 arithmetic on the GPU) or `default`;
 *  - `cpu_threads` (default 4); `languages` (informational list).
 */
internal object LiteRtDecisionHandler : Handler<TypedDecisions> {
    override val id = "litert.typed_decisions"
    override val abi = 1
    override val runtime = "litert"
    override val runtimeVersion: String = BuildConfig.LITERT_VERSION

    override fun prepare(local: LocalModel<TypedDecisions>, host: PrepareHost, onProgress: (LoadEvent) -> Unit): TypedDecisions {
        val plan = local.plan
        val hc = plan.variant.handlerConfig
        val family = hc.optString("family", "laya")
        if (family != "laya") throw ModelException(ErrorCode.UNSUPPORTED_CONFIGURATION, "handler_config.family '$family' is not supported by this release (laya)")
        val files = hc.optJSONObject("files") ?: throw ModelException(ErrorCode.MANIFEST_INVALID, "handler_config.files is missing (main, act_head, tokenizer, tokenizer_config, config)")
        fun file(key: String, required: Boolean = true): File? {
            val fid = files.optString(key, "")
            if (fid.isEmpty()) { if (required) throw ModelException(ErrorCode.MANIFEST_INVALID, "handler_config.files.$key is missing"); return null }
            return local.files[fid] ?: throw ModelException(ErrorCode.MANIFEST_INVALID, "handler_config.files.$key names file id '$fid', which profile '${plan.profile.id}' does not include")
        }
        val mainFile = file("main")!!
        val actFile = file("act_head", required = false)
        val tokenizerFile = file("tokenizer")!!
        val tokenizerConfigFile = file("tokenizer_config")!!
        val configFile = file("config")!!
        val window = hc.optInt("window", 0).takeIf { it > 0 } ?: throw ModelException(ErrorCode.MANIFEST_INVALID, "handler_config.window is missing")
        val hidden = hc.optInt("hidden", 0).takeIf { it > 0 } ?: throw ModelException(ErrorCode.MANIFEST_INVALID, "handler_config.hidden is missing")
        val gpuFp32 = hc.optString("gpu_precision", "fp32") == "fp32"
        val cpuThreads = hc.optInt("cpu_threads", 4)
        val languages = hc.optJSONArray("languages")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()

        val calibration = LayaCalibration.parse(configFile.readText())
        val headTokens = hc.optInt("head_tokens", calibration.headMaxLen)
        val t0 = System.nanoTime()
        val tokenizer = try { HfTokenizer.load(tokenizerFile, tokenizerConfigFile) } catch (e: ModelException) { throw e } catch (t: Throwable) {
            throw ModelException(ErrorCode.INITIALIZATION_FAILED, "tokenizer failed to load: ${t.javaClass.simpleName}: ${t.message}", details = mapOf("stage" to "tokenizer"), cause = t)
        }
        host.log.i("tokenizer ${tokenizerFile.name}: ${tokenizer.vocabSize} tokens, load_ms=${(System.nanoTime() - t0) / 1_000_000}")
        val builder = LayaSequenceBuilder(tokenizer, window, headTokens)
        val limits = DecisionLimits(windowTokens = window, headTokens = headTokens, maxOptions = (headTokens - 16) / 4, languages = languages)

        val notes = ArrayList<String>()
        val fallbackHistory = ArrayList<String>()
        var profile: Profile = plan.profile
        var attempts = 0
        while (true) {
            attempts++
            onProgress(LoadEvent.Initializing(profile.id))
            val backend = profile.components["inference"] ?: throw ModelException(ErrorCode.MANIFEST_INVALID, "profile '${profile.id}' has no 'inference' component")
            val accelerator = when (backend) {
                BackendKind.CPU -> Accelerator.CPU
                BackendKind.GPU -> Accelerator.GPU
                BackendKind.NPU -> throw ModelException(ErrorCode.NATIVE_MODULE_MISSING, "profile '${profile.id}' asks for an NPU; no NPU module ships in this release")
            }
            val tc = System.nanoTime()
            val model = try {
                val info = PreparedModelInfo(
                    repoId = plan.ref.repoId, commit = plan.modelOrigin.commit, descriptorSha256 = plan.descriptorSha256, descriptorOrigin = plan.descriptorOrigin,
                    bindingSource = plan.bindingSource, variantId = plan.variant.id, profileId = profile.id, sdkVersion = host.sdkVersion,
                    handlerId = id, handlerAbi = abi, runtime = runtime, runtimeVersion = runtimeVersion,
                    declaredInputs = plan.variant.inputs, enabledInputs = profile.enabledInputs, requestedBackendPolicy = plan.options.backendPolicy,
                    components = mapOf("inference" to ComponentReport(requested = plan.profile.components["inference"] ?: backend, initialized = backend)),
                    excludedProfiles = plan.excludedProfiles, fallbackHistory = fallbackHistory, verification = plan.verification,
                    notes = notes, files = local.files.mapValues { it.value.absolutePath },
                )
                LiteRtDecisionModel.open(
                    LiteRtDecisionModel.Companion.Spec(mainFile, actFile, window, hidden, accelerator, gpuFp32, cpuThreads),
                    info, limits, builder, calibration, host,
                )
            } catch (t: Throwable) {
                val reason = "${t.javaClass.simpleName}: ${t.message}"
                host.log.w("compile on profile '${profile.id}' failed: $reason", t)
                val next = profile.fallbackProfiles.asSequence().mapNotNull { plan.variant.profile(it) }.firstOrNull { p -> p.files.all { local.files.containsKey(it) } && fallbackHistory.none { h -> h.endsWith("-> ${p.id}") } }
                if (next == null || plan.options.backendPolicy !is BackendPolicy.Auto || attempts > 3) throw ModelException(
                    ErrorCode.INITIALIZATION_FAILED, "CompiledModel failed on profile '${profile.id}' (inference=$backend): $reason",
                    details = mapOf("stage" to "compile", "profile" to profile.id, "fallback_tried" to fallbackHistory.joinToString()), cause = t,
                )
                fallbackHistory += "${profile.id} -> ${next.id}: $reason"
                onProgress(LoadEvent.Fallback("profile '${profile.id}' failed to compile ($reason); trying '${next.id}'"))
                profile = next
                continue
            }
            notes += "compile_ms=${(System.nanoTime() - tc) / 1_000_000} on ${backend.name.lowercase()}" + (if (backend == BackendKind.GPU) " (precision ${if (gpuFp32) "fp32" else "default"})" else " ($cpuThreads threads)")
            notes += "observed backend is UNKNOWN: litert $runtimeVersion reports no per-op execution target; 'initialized' is the accelerator the CompiledModel was created with"
            if (actFile == null) notes += "no act head in this variant: action.act_probability is not reported"
            if (fallbackHistory.isNotEmpty()) notes += "fallback applied: " + fallbackHistory.joinToString(" | ")
            return model
        }
    }
}
