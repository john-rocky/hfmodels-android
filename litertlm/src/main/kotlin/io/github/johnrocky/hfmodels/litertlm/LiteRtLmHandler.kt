package io.github.johnrocky.hfmodels.litertlm

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import io.github.johnrocky.hfmodels.BackendKind
import io.github.johnrocky.hfmodels.BackendPolicy
import io.github.johnrocky.hfmodels.ComponentReport
import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.Handler
import io.github.johnrocky.hfmodels.InputKind
import io.github.johnrocky.hfmodels.LoadEvent
import io.github.johnrocky.hfmodels.LocalModel
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.PrepareHost
import io.github.johnrocky.hfmodels.PreparedModelInfo
import io.github.johnrocky.hfmodels.descriptor.Profile
import java.io.File

/**
 * `litertlm.conversation` ABI 1: a `.litertlm` bundle (file role `model`) opened with LiteRT-LM's
 * `Engine`. `handler_config` keys read here:
 *  - `metadata_source`: `litertlm_manifest` | `publisher_declared` (report only on 0.16.x: the
 *    Maven runtime exposes no ModelInfo, so declared inputs are trusted and reported as such);
 *  - `context_tokens` (optional int): the bundle's max_num_tokens when the descriptor fixes it.
 */
internal object LiteRtLmHandler : Handler<ChatModel> {
    override val id = "litertlm.conversation"
    override val abi = 1
    override val runtime = "litert_lm"
    override val runtimeVersion: String = BuildConfig.LITERTLM_VERSION

    override fun prepare(local: LocalModel<ChatModel>, host: PrepareHost, onProgress: (LoadEvent) -> Unit): ChatModel {
        val plan = local.plan
        val modelFile = local.files.entries.firstOrNull { (id, _) -> plan.variant.file(id)?.role == "model" }?.value
            ?: throw ModelException(ErrorCode.MANIFEST_INVALID, "variant '${plan.variant.id}' has no file with role 'model' in profile '${plan.profile.id}'")
        val declaredContext = plan.profile.contextTokens ?: plan.variant.handlerConfig.optInt("context_tokens", 0).takeIf { it > 0 }
        val contextTokens = plan.options.contextTokens?.also { requested ->
            if (declaredContext != null && requested > declaredContext) throw ModelException(
                ErrorCode.UNSUPPORTED_CONFIGURATION, "contextTokens=$requested exceeds the declared maximum $declaredContext for variant '${plan.variant.id}'",
                details = mapOf("requested" to requested.toString(), "declared" to declaredContext.toString()),
            )
        } ?: declaredContext

        val fallbackHistory = ArrayList<String>()
        var profile: Profile = plan.profile
        var attempts = 0
        while (true) {
            attempts++
            onProgress(LoadEvent.Initializing(profile.id))
            val language = profile.components["language"] ?: throw ModelException(ErrorCode.MANIFEST_INVALID, "profile '${profile.id}' has no 'language' component")
            val vision = profile.components["vision"]
            val config = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = backendOf(language),
                visionBackend = vision?.let { backendOf(it) },
                maxNumTokens = contextTokens,
                cacheDir = File(host.cacheDir, "litertlm").apply { mkdirs() }.absolutePath,
            )
            val engine = Engine(config)
            try {
                engine.initialize()
            } catch (t: Throwable) {
                runCatching { engine.close() }
                val reason = "${t.javaClass.simpleName}: ${t.message}"
                host.log.w("initialize on profile '${profile.id}' failed: $reason", t)
                val next = nextFallback(plan, profile, local, attempts)
                if (next == null || plan.options.backendPolicy !is BackendPolicy.Auto) throw ModelException(
                    ErrorCode.INITIALIZATION_FAILED, "Engine.initialize() failed on profile '${profile.id}' (language=$language${vision?.let { ", vision=$it" } ?: ""}): $reason",
                    details = mapOf("stage" to "initialize", "profile" to profile.id, "fallback_tried" to fallbackHistory.joinToString()), cause = t,
                )
                fallbackHistory += "${profile.id} -> ${next.id}: $reason"
                onProgress(LoadEvent.Fallback("profile '${profile.id}' failed to initialize ($reason); trying '${next.id}'"))
                profile = next
                continue
            }
            val components = LinkedHashMap<String, ComponentReport>()
            components["language"] = ComponentReport(requested = plan.profile.components["language"]!!, initialized = language)
            vision?.let { components["vision"] = ComponentReport(requested = plan.profile.components["vision"] ?: it, initialized = it) }
            val notes = ArrayList<String>()
            notes += "observed backend is UNKNOWN: litertlm-android $runtimeVersion exposes no per-component execution report; 'initialized' is the backend the Engine was configured with"
            if (runtimeVersion.startsWith("0.16.")) notes += "no runtime metadata check on litertlm-android $runtimeVersion (Capabilities has no input-modality API); declared inputs come from the descriptor (${plan.variant.handlerConfig.optString("metadata_source", "publisher_declared")})"
            if (fallbackHistory.isNotEmpty()) notes += "fallback applied: " + fallbackHistory.joinToString(" | ")
            val info = PreparedModelInfo(
                repoId = plan.ref.repoId, commit = plan.modelOrigin.commit, descriptorSha256 = plan.descriptorSha256, descriptorOrigin = plan.descriptorOrigin,
                bindingSource = plan.bindingSource, variantId = plan.variant.id, profileId = profile.id, sdkVersion = host.sdkVersion,
                handlerId = id, handlerAbi = abi, runtime = runtime, runtimeVersion = runtimeVersion,
                declaredInputs = plan.variant.inputs, enabledInputs = profile.enabledInputs, requestedBackendPolicy = plan.options.backendPolicy,
                components = components, excludedProfiles = plan.excludedProfiles, fallbackHistory = fallbackHistory, verification = plan.verification,
                notes = notes, files = local.files.mapValues { it.value.absolutePath },
            )
            return LiteRtLmChatModel(engine, info, profile.enabledInputs, plan.options, host)
        }
    }

    /** The next fallback profile (spec §9.3): same variant, listed on the failed profile, eligible, files already local, at most 3 tries, image capability kept. */
    private fun nextFallback(plan: io.github.johnrocky.hfmodels.ModelPlan<ChatModel>, failed: Profile, local: LocalModel<ChatModel>, attempts: Int): Profile? {
        if (attempts >= 3) return null
        for (id in failed.fallbackProfiles) {
            val p = plan.variant.profile(id) ?: continue
            if (!p.enabledInputs.containsAll(plan.profile.enabledInputs)) continue
            if (plan.excludedProfiles.any { it.first == p.id && it.second != "not selected" }) continue
            if (p.components.values.any { it == BackendKind.NPU } && p.verification.none { it.result == "PASS" }) continue
            if (!p.files.all { local.files.containsKey(it) }) throw ModelException(ErrorCode.ADDITIONAL_DOWNLOAD_REQUIRED, "fallback profile '${p.id}' needs files that are not local: ${p.files.filter { !local.files.containsKey(it) }}", details = mapOf("profile" to p.id))
            return p
        }
        return null
    }

    private fun backendOf(kind: BackendKind): Backend = when (kind) {
        BackendKind.CPU -> Backend.CPU()
        BackendKind.GPU -> Backend.GPU()
        BackendKind.NPU -> Backend.NPU()
    }
}
