package io.github.johnrocky.hfmodels.litertlm

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Channel
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
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
import org.json.JSONObject

/**
 * `litertlm.conversation` ABI 1: a `.litertlm` bundle (file role `model`) opened with LiteRT-LM's
 * `Engine`. `handler_config` keys read here:
 *  - `metadata_source`: `litertlm_manifest` | `publisher_declared` (report only on 0.16.x: the
 *    Maven runtime exposes no ModelInfo, so declared inputs are trusted and reported as such);
 *  - `context_tokens` (optional int): the bundle's max_num_tokens when the descriptor fixes it;
 *  - `channels` (optional array of `{name, start, end}`): the reasoning channel(s) every conversation
 *    applies. When absent, the bundle's own `LlmMetadata.channels` declaration is used; the result
 *    and whether the rendered generation prompt already opens the channel are reported in
 *    [ChatModel.thinking];
 *  - `thinking_default` (optional bool): the model reasons on every turn unless asked not to.
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

        // Channels: the descriptor's word first, else the bundle's own declaration. Read before any native call.
        val notes = ArrayList<String>()
        val descriptorChannels = descriptorChannels(plan.variant.handlerConfig)
        val bundle = runCatching { LitertlmBundle.read(modelFile) }.onFailure {
            host.log.w("bundle header of ${modelFile.name} not readable: ${it.message}")
            notes += "bundle header not readable (${it.message}); channels come from the descriptor only"
        }.getOrNull()
        val bundleChannels = bundle?.channels?.map { Channel(it.name, it.start, it.end) } ?: emptyList()
        val (channels, channelSource) = when {
            descriptorChannels.isNotEmpty() -> descriptorChannels to "descriptor"
            bundleChannels.isNotEmpty() -> bundleChannels to "bundle"
            else -> emptyList<Channel>() to "none"
        }
        if (descriptorChannels.isNotEmpty() && bundleChannels.isNotEmpty() && descriptorChannels != bundleChannels) {
            notes += "descriptor channels ${channels.describe()} override the bundle's ${bundleChannels.describe()}"
        }

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
            val thinking = if (channels.isEmpty()) ThinkingInfo.NONE else probeThinking(engine, channels, channelSource, bundle, plan.variant.handlerConfig.optBoolean("thinking_default", false), host, notes)
            val components = LinkedHashMap<String, ComponentReport>()
            components["language"] = ComponentReport(requested = plan.profile.components["language"]!!, initialized = language)
            vision?.let { components["vision"] = ComponentReport(requested = plan.profile.components["vision"] ?: it, initialized = it) }
            notes += "observed backend is UNKNOWN: litertlm-android $runtimeVersion exposes no per-component execution report; 'initialized' is the backend the Engine was configured with"
            if (runtimeVersion.startsWith("0.16.")) notes += "no runtime metadata check on litertlm-android $runtimeVersion (Capabilities has no input-modality API); declared inputs come from the descriptor (${plan.variant.handlerConfig.optString("metadata_source", "publisher_declared")})"
            if (fallbackHistory.isNotEmpty()) notes += "fallback applied: " + fallbackHistory.joinToString(" | ")
            if (thinking.channels.isNotEmpty()) notes += "thinking: channels=${thinking.channels.describe()} source=${thinking.source} prefilled=${thinking.prefilled} reasons_by_default=${thinking.reasonsByDefault}"
            val info = PreparedModelInfo(
                repoId = plan.ref.repoId, commit = plan.modelOrigin.commit, descriptorSha256 = plan.descriptorSha256, descriptorOrigin = plan.descriptorOrigin,
                bindingSource = plan.bindingSource, variantId = plan.variant.id, profileId = profile.id, sdkVersion = host.sdkVersion,
                handlerId = id, handlerAbi = abi, runtime = runtime, runtimeVersion = runtimeVersion,
                declaredInputs = plan.variant.inputs, enabledInputs = profile.enabledInputs, requestedBackendPolicy = plan.options.backendPolicy,
                components = components, excludedProfiles = plan.excludedProfiles, fallbackHistory = fallbackHistory, verification = plan.verification,
                notes = notes, files = local.files.mapValues { it.value.absolutePath },
            )
            return LiteRtLmChatModel(engine, info, profile.enabledInputs, thinking, plan.options, host)
        }
    }

    /** `handler_config.channels`: `[{"name": "thought", "start": "<think>", "end": "</think>"}]`. */
    private fun descriptorChannels(hc: JSONObject): List<Channel> {
        val arr = hc.optJSONArray("channels") ?: return emptyList()
        return List(arr.length()) { i ->
            val o = arr.optJSONObject(i) ?: throw ModelException(ErrorCode.MANIFEST_INVALID, "handler_config.channels[$i] is not an object")
            val name = o.optString("name", ""); val start = o.optString("start", ""); val end = o.optString("end", "")
            if (name.isEmpty() || start.isEmpty()) throw ModelException(ErrorCode.MANIFEST_INVALID, "handler_config.channels[$i] needs a non-empty name and start")
            Channel(name, start, end)
        }
    }

    /**
     * Renders the generation prompt the runtime itself would send for a first user turn (a throwaway
     * Conversation, closed at once; no token is generated) and applies the runtime's open-channel rule
     * to it. Falls back to the bundle's structured `model.prefix` when the render is unavailable.
     */
    @OptIn(ExperimentalApi::class)
    private fun probeThinking(engine: Engine, channels: List<Channel>, source: String, bundle: LitertlmBundle?, reasonsByDefault: Boolean, host: PrepareHost, notes: MutableList<String>): ThinkingInfo {
        val rendered = runCatching {
            val conv = engine.createConversation(ConversationConfig(channels = channels))
            try { conv.renderMessageIntoString(Message.user(PROBE_TEXT)) } finally { runCatching { conv.close() } }
        }.onFailure { host.log.w("thinking probe: the runtime did not render the generation prompt: ${it.message}") }.getOrNull()
        val prompt = rendered ?: bundle?.modelPrefix?.takeIf { it.isNotEmpty() }
        if (rendered == null) notes += if (prompt != null) "thinking: generation prompt taken from the bundle's model prefix (runtime render unavailable)" else "thinking: generation prompt unknown (runtime render unavailable, no structured prefix)"
        return ThinkingInfo(
            channels = channels,
            source = source,
            prefilled = prompt?.let { openChannelName(it, channels.map { c -> ChannelMarkers(c.channelName, c.start, c.end) }) != null },
            generationPromptTail = prompt?.takeLast(TAIL_CHARS)?.escape(),
            reasonsByDefault = reasonsByDefault,
        )
    }

    private fun List<Channel>.describe() = joinToString(",", "[", "]") { "${it.channelName} ${it.start.escape()}..${it.end.escape()}" }
    private fun String.escape() = replace("\n", "\\n")

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

    private const val PROBE_TEXT = "Hello"
    private const val TAIL_CHARS = 48
}
