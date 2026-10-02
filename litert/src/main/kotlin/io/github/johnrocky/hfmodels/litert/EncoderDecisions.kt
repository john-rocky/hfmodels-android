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
import org.json.JSONObject

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
 * `litert.typed_decisions` ABI 1. Profile components use the key `inference` (`cpu` / `gpu` / `npu`).
 * `npu` is the Qualcomm HTP through LiteRT's on-device (JIT) compile; the app packages the vendor
 * libraries (docs/api.md, "NPU") and asks for it with `BackendPolicy.Require(BackendKind.NPU)`.
 */
object EncoderDecisions : Task<TypedDecisions> {
    override val id = "decide"
    override val handler: Handler<TypedDecisions> = LiteRtDecisionHandler
}

/**
 * `handler_config` keys:
 *  - `family`: `laya` (default) or `julia`; the option rendering and the decoding contract ([DecisionFamily]);
 *  - `window`: the graph's static sequence length; `head_tokens`: the head budget (laya: from the config
 *    file, up to the whole window; julia: `min(512, window - 5)`, the publisher's reference host, and
 *    never `window - 4` or more);
 *  - `hidden`: the encoder width (laya: the pooled vector, 1024 / 768; julia: the embedding row, 384);
 *  - `files`: `{main, act_head, table, tokenizer, tokenizer_config, config}` -> file ids of the variant.
 *    `table` names a `[vocab, hidden]` embedding table and switches the graph input to `inputs_embeds`
 *    (host lookup, [TokenTable]); `table_dtype`: `float16` (default) or `float32`. `act_head` and
 *    `config` (the temperature settings) are laya's; `tokenizer_config` may be replaced by
 *    `special_tokens`: `{cls, sep, mask, pad, unk}` by token text;
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
        val family = DecisionFamily.parse(hc.optString("family", "laya"))
        val files = hc.optJSONObject("files") ?: throw ModelException(ErrorCode.MANIFEST_INVALID, "handler_config.files is missing (main, tokenizer, and per family act_head / table / tokenizer_config / config)")
        fun file(key: String, required: Boolean = true): File? {
            val fid = files.optString(key, "")
            if (fid.isEmpty()) { if (required) throw ModelException(ErrorCode.MANIFEST_INVALID, "handler_config.files.$key is missing"); return null }
            return local.files[fid] ?: throw ModelException(ErrorCode.MANIFEST_INVALID, "handler_config.files.$key names file id '$fid', which profile '${plan.profile.id}' does not include")
        }
        val mainFile = file("main")!!
        val actFile = file("act_head", required = false)
        val tableFile = file("table", required = false)
        val tokenizerFile = file("tokenizer")!!
        val tokenizerConfigFile = file("tokenizer_config", required = false)
        val configFile = file("config", required = family == DecisionFamily.LAYA)
        val window = hc.optInt("window", 0).takeIf { it > 0 } ?: throw ModelException(ErrorCode.MANIFEST_INVALID, "handler_config.window is missing")
        val hidden = hc.optInt("hidden", 0).takeIf { it > 0 } ?: throw ModelException(ErrorCode.MANIFEST_INVALID, "handler_config.hidden is missing")
        val tableDtype = TokenTable.Dtype.parse(hc.optString("table_dtype", TokenTable.Dtype.FLOAT16.id))
        val gpuFp32 = hc.optString("gpu_precision", "fp32") == "fp32"
        val cpuThreads = hc.optInt("cpu_threads", 4)
        val languages = hc.optJSONArray("languages")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
        val specials = hc.optJSONObject("special_tokens")?.let { o ->
            fun tok(k: String) = o.optString(k, "").takeIf { it.isNotEmpty() } ?: throw ModelException(ErrorCode.MANIFEST_INVALID, "handler_config.special_tokens.$k is missing")
            HfTokenizer.SpecialTokens(tok("cls"), tok("sep"), tok("mask"), tok("pad"), o.optString("unk", "").takeIf { it.isNotEmpty() })
        }
        if (specials == null && tokenizerConfigFile == null) throw ModelException(ErrorCode.MANIFEST_INVALID, "handler_config needs files.tokenizer_config or special_tokens (cls, sep, mask, pad)")

        val calibration = if (family == DecisionFamily.LAYA) LayaCalibration.parse(configFile!!.readText()) else null
        val headTokens = headTokens(hc, family, window, calibration)
        val t0 = System.nanoTime()
        val tokenizer = try {
            if (specials != null) HfTokenizer.load(tokenizerFile, specials) else HfTokenizer.load(tokenizerFile, tokenizerConfigFile!!)
        } catch (e: ModelException) { throw e } catch (t: Throwable) {
            throw ModelException(ErrorCode.INITIALIZATION_FAILED, "tokenizer failed to load: ${t.javaClass.simpleName}: ${t.message}", details = mapOf("stage" to "tokenizer"), cause = t)
        }
        host.log.i("tokenizer ${tokenizerFile.name}: ${tokenizer.vocabSize} tokens, load_ms=${(System.nanoTime() - t0) / 1_000_000}")
        val builder = DecisionSequenceBuilder(tokenizer, window, headTokens, family)
        val maxOptions = if (family == DecisionFamily.JULIA) DecisionSequenceBuilder.JULIA_MAX_OPTIONS else (headTokens - 16) / 4
        val limits = DecisionLimits(windowTokens = window, headTokens = headTokens, maxOptions = maxOptions, languages = languages)
        val decoder = when (family) {
            DecisionFamily.LAYA -> LiteRtDecisionModel.Decoder { q, raw, act -> LayaDecode.answer(q, raw, act, calibration!!) }
            DecisionFamily.JULIA -> LiteRtDecisionModel.Decoder { q, raw, _ -> JuliaDecode.answer(q, raw) }
        }

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
                BackendKind.NPU -> Accelerator.NPU
            }
            val tc = System.nanoTime()
            val model = try {
                if (backend == BackendKind.NPU) LiteRtNpu.requireReady(host.appContext)
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
                    LiteRtDecisionModel.Companion.Spec(mainFile, actFile, tableFile, tableDtype, window, hidden, accelerator, gpuFp32, cpuThreads, family, tokenizer.padId),
                    info, limits, builder, decoder, calibration, host,
                )
            } catch (t: Throwable) {
                val reason = "${t.javaClass.simpleName}: ${t.message}"
                host.log.w("compile on profile '${profile.id}' failed: $reason", t)
                val next = profile.fallbackProfiles.asSequence().mapNotNull { plan.variant.profile(it) }.firstOrNull { p -> p.files.all { local.files.containsKey(it) } && fallbackHistory.none { h -> h.endsWith("-> ${p.id}") } }
                if (next == null || plan.options.backendPolicy !is BackendPolicy.Auto || attempts > 3) throw (t as? ModelException) ?: ModelException(
                    ErrorCode.INITIALIZATION_FAILED, "CompiledModel failed on profile '${profile.id}' (inference=$backend): $reason",
                    details = mapOf("stage" to "compile", "profile" to profile.id, "fallback_tried" to fallbackHistory.joinToString()), cause = t,
                )
                fallbackHistory += "${profile.id} -> ${next.id}: $reason"
                onProgress(LoadEvent.Fallback("profile '${profile.id}' failed to compile ($reason); trying '${next.id}'"))
                profile = next
                continue
            }
            notes += "family=${family.id} compile_ms=${(System.nanoTime() - tc) / 1_000_000} on ${backend.name.lowercase()}" + when (backend) {
                BackendKind.GPU -> " (precision ${if (gpuFp32) "fp32" else "default"})"
                BackendKind.NPU -> " (Qualcomm HTP ${LiteRtNpu.hexagon()}, JIT, burst; the first compile on a phone takes tens of seconds, later loads read the cache)"
                BackendKind.CPU -> " ($cpuThreads threads)"
            }
            notes += "observed backend is UNKNOWN: litert $runtimeVersion reports no per-op execution target; 'initialized' is the accelerator the CompiledModel was created with"
            if (tableFile != null) notes += "host token lookup: ${tableFile.name} (${tableDtype.id}, ${tableFile.length() / (hidden.toLong() * tableDtype.bytes)} rows x $hidden) feeds inputs_embeds"
            if (family == DecisionFamily.LAYA && actFile == null) notes += "no act head in this variant: action.act_probability is not reported"
            if (family == DecisionFamily.JULIA) notes += "no calibration in this family: probabilities are the softmax of the raw marker scores (T=1), unrounded, as the publisher's runtime reports them"
            if (fallbackHistory.isNotEmpty()) notes += "fallback applied: " + fallbackHistory.joinToString(" | ")
            return model
        }
    }

    /**
     * `head_tokens`, else the family's default, checked by the family's own rule: laya's config may give the
     * head the whole window (the multilingual 256-token graphs: `max_len` 256, `head_max_len` 256; decide()
     * refuses a question whose options do not fit), julia refuses a head that reaches into the frame ([JuliaHead]).
     */
    internal fun headTokens(hc: JSONObject, family: DecisionFamily, window: Int, calibration: LayaCalibration?): Int {
        val declared = hc.optInt("head_tokens", 0).takeIf { it > 0 }
        return when (family) {
            DecisionFamily.LAYA -> declared ?: calibration!!.headMaxLen
            DecisionFamily.JULIA -> JuliaHead.tokens(declared, window)
        }
    }
}
