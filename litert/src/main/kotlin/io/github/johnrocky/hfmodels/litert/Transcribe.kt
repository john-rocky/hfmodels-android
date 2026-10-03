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
import io.github.johnrocky.hfmodels.descriptor.Profile
import io.github.johnrocky.hfmodels.speech.Transcriber
import io.github.johnrocky.hfmodels.speech.TranscriberLimits
import java.io.File

/**
 * Speech to text on a classic `.tflite` CTC encoder run by LiteRT's `CompiledModel`. Pass it where
 * `Tasks.Chat` goes:
 *
 * ```kotlin
 * val asr: Transcriber = models.fromPretrained(ModelRef("litert-community/Zipformer-medium-CR-CTC-LiteRT", variant = "small_fp16"), Transcribe)
 * val text = asr.transcribe(pcm16k).text
 * ```
 *
 * The descriptor names the task `transcribe`, the runtime `litert` and the handler
 * `litert.transcribe` ABI 1. Profile components use the key `inference` (`gpu` / `cpu`).
 */
object Transcribe : Task<Transcriber> {
    override val id = "transcribe"
    override val handler: Handler<Transcriber> = LiteRtTranscribeHandler
}

/**
 * `handler_config` keys:
 *  - `family`: `zipformer_ctc` (the only one: kaldi fbank front-end, greedy CTC, [ZipformerCtc]);
 *  - `files`: `{main, tokens}` -> file ids of the variant (the graph and `tokens.txt`);
 *  - `sample_rate` (16000, the only rate the fbank is defined for) and `window_seconds` (16): the
 *    longest audio one call takes; the graph's fbank input must be `window_seconds * 100` frames;
 *  - `blank_id` (default 0); `cpu_threads` (default 4);
 *  - `gpu_precision`: `default` (default; what the LiteRT model zoo app runs) or `fp32`;
 *  - `languages` (informational list).
 */
internal object LiteRtTranscribeHandler : Handler<Transcriber> {
    override val id = "litert.transcribe"
    override val abi = 1
    override val runtime = "litert"
    override val runtimeVersion: String = BuildConfig.LITERT_VERSION

    override fun prepare(local: LocalModel<Transcriber>, host: PrepareHost, onProgress: (LoadEvent) -> Unit): Transcriber {
        val plan = local.plan
        val hc = plan.variant.handlerConfig
        val family = hc.optString("family", "zipformer_ctc")
        if (family != "zipformer_ctc") throw ModelException(ErrorCode.MANIFEST_INVALID, "handler_config.family '$family' is not known to ${id} (known: zipformer_ctc)")
        val files = hc.optJSONObject("files") ?: throw ModelException(ErrorCode.MANIFEST_INVALID, "handler_config.files is missing (main, tokens)")
        fun file(key: String): File {
            val fid = files.optString(key, "")
            if (fid.isEmpty()) throw ModelException(ErrorCode.MANIFEST_INVALID, "handler_config.files.$key is missing")
            return local.files[fid] ?: throw ModelException(ErrorCode.MANIFEST_INVALID, "handler_config.files.$key names file id '$fid', which profile '${plan.profile.id}' does not include")
        }
        val mainFile = file("main")
        val tokensFile = file("tokens")
        val sampleRate = hc.optInt("sample_rate", ZipformerFbank.SR)
        if (sampleRate != ZipformerFbank.SR) throw ModelException(ErrorCode.MANIFEST_INVALID, "handler_config.sample_rate $sampleRate: the zipformer_ctc front-end is defined at ${ZipformerFbank.SR} Hz")
        val windowSeconds = hc.optDouble("window_seconds", 0.0).takeIf { it > 0.0 } ?: throw ModelException(ErrorCode.MANIFEST_INVALID, "handler_config.window_seconds is missing")
        val frames = Math.round(windowSeconds * sampleRate / ZipformerFbank.HOP).toInt()
        val blank = hc.optInt("blank_id", 0)
        val cpuThreads = hc.optInt("cpu_threads", 4)
        val gpuFp32 = when (val p = hc.optString("gpu_precision", "default")) {
            "default" -> false
            "fp32" -> true
            else -> throw ModelException(ErrorCode.MANIFEST_INVALID, "handler_config.gpu_precision '$p' (expected default or fp32)")
        }
        val languages = hc.optJSONArray("languages")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
        val limits = TranscriberLimits(sampleRate = sampleRate, windowSeconds = windowSeconds, languages = languages)
        val contract = ZipformerCtc(frames, blank)

        val pieces = try { ZipformerCtc.readTokens(tokensFile) } catch (t: Throwable) {
            throw ModelException(ErrorCode.INITIALIZATION_FAILED, "tokens failed to load: ${t.javaClass.simpleName}: ${t.message}", details = mapOf("stage" to "tokens"), cause = t)
        }
        host.log.i("tokens ${tokensFile.name}: ${pieces.size} pieces")

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
                BackendKind.NPU -> throw ModelException(ErrorCode.UNSUPPORTED_CONFIGURATION, "profile '${profile.id}': ${id} has no NPU path in this release (gpu or cpu)", details = mapOf("profile" to profile.id))
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
                LiteRtTranscriber.open(mainFile, accelerator, gpuFp32, cpuThreads, contract, pieces, info, limits, host)
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
            notes += "family=$family compile_ms=${(System.nanoTime() - tc) / 1_000_000} on ${backend.name.lowercase()}" + when (backend) {
                BackendKind.GPU -> " (precision ${if (gpuFp32) "fp32" else "default"})"
                else -> " ($cpuThreads threads)"
            }
            notes += "observed backend is UNKNOWN: litert $runtimeVersion reports no per-op execution target; 'initialized' is the accelerator the CompiledModel was created with"
            notes += "window ${windowSeconds} s = ${frames} fbank frames at $sampleRate Hz; longer audio is INVALID_INPUT, shorter is padded"
            if (fallbackHistory.isNotEmpty()) notes += "fallback applied: " + fallbackHistory.joinToString(" | ")
            return model
        }
    }
}
