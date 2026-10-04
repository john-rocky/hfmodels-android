package io.github.johnrocky.hfmodels.litert

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
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
 *  - `family`: `laya` (default), `julia`, `gliner2_decide`, `gliclass` or `deberta_decision`; the family's
 *    [DecisionContract] (option rendering, sequence and routing, graph inputs and outputs, decoding);
 *  - `window`: the graph's static sequence length; `head_tokens`: the head budget (laya: from the config
 *    file, up to the whole window; julia: `min(512, window - 5)`, the publisher's reference host, and
 *    never `window - 4` or more; gliner2_decide, gliclass, deberta_decision: none, the window is shared
 *    and never cut, except deberta_decision's state at `state_tokens`);
 *  - `hidden`: the encoder width (laya: the pooled vector, 1024 / 768; julia: the embedding row, 384;
 *    gliner2_decide and deberta_decision: the embedding row, 1024; gliclass: the embedding row, 384);
 *  - `files`: `{main, act_head, table, tokenizer, tokenizer_config, config}` -> file ids of the variant.
 *    `table` names a `[vocab, hidden]` embedding table and switches the graph input to `inputs_embeds`
 *    (host lookup, [TokenTable]; required by gliner2_decide, gliclass and deberta_decision); `table_dtype`:
 *    `float16` (default) or `float32`. `act_head` and `config` (the temperature settings) are laya's;
 *    `tokenizer_config` may be replaced by `special_tokens`: `{cls, sep, mask, pad, unk}` by token text
 *    (laya / julia; gliclass checks the ones it declares against its tokenizer);
 *  - `label_slots`: gliner2_decide's (32) and gliclass's (25) label capacity per forward;
 *  - `pack_questions` (gliner2_decide, default false): the questions of one decide() as the tasks of one gliner2
 *    request instead of one forward each ([GlinerDecideContract.plan]);
 *  - `option_slots` (128), `state_tokens` (256), `temperature` (1.05): deberta_decision's option capacity per
 *    forward, state cut and softmax temperature;
 *  - `gpu_precision`: `fp32` (default; explicit FP32 arithmetic on the GPU), `fp16_with_fp32_accum` or `fp16`
 *    (LiteRT's `GpuOptions.Precision` of the same names), or `default` (no GPU options: LiteRT's own choice);
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
        val familyId = hc.optString("family", "laya")
        // The host-lookup encoder families: a token table, no act head, config or tokenizer_config.
        val encoder = familyId in ENCODER_FAMILIES
        val family = if (encoder) null else DecisionFamily.entries.firstOrNull { it.id == familyId } ?: throw ModelException(
            ErrorCode.UNSUPPORTED_CONFIGURATION, "handler_config.family '$familyId' is not supported by this release (${(DecisionFamily.entries.map { it.id } + ENCODER_FAMILIES).joinToString()})",
        )
        val files = hc.optJSONObject("files") ?: throw ModelException(ErrorCode.MANIFEST_INVALID, "handler_config.files is missing (main, tokenizer, and per family act_head / table / tokenizer_config / config)")
        fun file(key: String, required: Boolean = true): File? {
            val fid = files.optString(key, "")
            if (fid.isEmpty()) { if (required) throw ModelException(ErrorCode.MANIFEST_INVALID, "handler_config.files.$key is missing"); return null }
            return local.files[fid] ?: throw ModelException(ErrorCode.MANIFEST_INVALID, "handler_config.files.$key names file id '$fid', which profile '${plan.profile.id}' does not include")
        }
        val mainFile = file("main")!!
        val actFile = if (encoder) null else file("act_head", required = false)
        val tableFile = file("table", required = encoder)
        val tokenizerFile = file("tokenizer")!!
        val tokenizerConfigFile = if (encoder) null else file("tokenizer_config", required = false)
        val configFile = if (encoder) null else file("config", required = family == DecisionFamily.LAYA)
        val window = hc.optInt("window", 0).takeIf { it > 0 } ?: throw ModelException(ErrorCode.MANIFEST_INVALID, "handler_config.window is missing")
        val hidden = hc.optInt("hidden", 0).takeIf { it > 0 } ?: throw ModelException(ErrorCode.MANIFEST_INVALID, "handler_config.hidden is missing")
        val tableDtype = TokenTable.Dtype.parse(hc.optString("table_dtype", TokenTable.Dtype.FLOAT16.id))
        val gpuPrecisionId = hc.optString("gpu_precision", "fp32")
        val gpuPrecision = gpuPrecision(gpuPrecisionId)
        val cpuThreads = hc.optInt("cpu_threads", 4)
        val languages = hc.optJSONArray("languages")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
        val contract = when (familyId) {
            GlinerDecideContract.FAMILY -> GlinerDecideContract.create(hc, tokenizerFile, window, hidden, languages, host)
            GliclassContract.FAMILY -> GliclassContract.create(hc, tokenizerFile, window, hidden, languages, host)
            DebertaDecisionContract.FAMILY -> DebertaDecisionContract.create(hc, tokenizerFile, window, hidden, languages, host)
            else -> markerContract(hc, family!!, tokenizerFile, tokenizerConfigFile, configFile, actFile, window, languages, host)
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
                LiteRtDecisionModel.open(LiteRtDecisionModel.Companion.Spec(mainFile, tableFile, tableDtype, hidden, accelerator, gpuPrecision, cpuThreads), info, contract, host)
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
            notes += "family=${contract.family} compile_ms=${(System.nanoTime() - tc) / 1_000_000} on ${backend.name.lowercase()}" + when (backend) {
                BackendKind.GPU -> " (precision $gpuPrecisionId)"
                BackendKind.NPU -> " (Qualcomm HTP ${LiteRtNpu.hexagon()}, JIT, burst; the first compile on a phone takes tens of seconds, later loads read the cache)"
                BackendKind.CPU -> " ($cpuThreads threads)"
            }
            notes += "observed backend is UNKNOWN: litert $runtimeVersion reports no per-op execution target; 'initialized' is the accelerator the CompiledModel was created with"
            if (tableFile != null) notes += "host token lookup: ${tableFile.name} (${tableDtype.id}, ${tableFile.length() / (hidden.toLong() * tableDtype.bytes)} rows x $hidden) feeds inputs_embeds"
            notes += contract.notes
            if (fallbackHistory.isNotEmpty()) notes += "fallback applied: " + fallbackHistory.joinToString(" | ")
            return model
        }
    }

    private val ENCODER_FAMILIES = listOf(GlinerDecideContract.FAMILY, GliclassContract.FAMILY, DebertaDecisionContract.FAMILY)

    /** `gpu_precision` -> the precision the GPU computes in; null = `default`, no GPU options (LiteRT's own choice). */
    internal fun gpuPrecision(id: String): CompiledModel.GpuOptions.Precision? = when (id) {
        "fp32" -> CompiledModel.GpuOptions.Precision.FP32
        "fp16_with_fp32_accum" -> CompiledModel.GpuOptions.Precision.FP16_WITH_FP32_ACCUM
        "fp16" -> CompiledModel.GpuOptions.Precision.FP16
        "default" -> null
        else -> throw ModelException(ErrorCode.MANIFEST_INVALID, "handler_config.gpu_precision '$id' is not supported (fp32, fp16_with_fp32_accum, fp16, default)")
    }

    /** laya / julia: the publisher's tokenizer, the head budget and (laya) the temperature settings around [DecisionSequenceBuilder]. */
    private fun markerContract(
        hc: JSONObject, family: DecisionFamily, tokenizerFile: File, tokenizerConfigFile: File?, configFile: File?, actFile: File?,
        window: Int, languages: List<String>, host: PrepareHost,
    ): MarkerContract {
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
        return MarkerContract(builder, calibration, limits, tokenizer.padId, actFile)
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
