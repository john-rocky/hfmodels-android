package io.github.johnrocky.hfmodels

import io.github.johnrocky.hfmodels.descriptor.Descriptor
import io.github.johnrocky.hfmodels.descriptor.Profile
import io.github.johnrocky.hfmodels.descriptor.Variant
import java.io.File

/** A Hugging Face model id, optionally pinned to a revision (branch, tag or commit) and a variant. */
data class ModelRef(
    val repoId: String,
    val revision: String? = null,
    val variant: String? = null,
) {
    init {
        require(REPO.matches(repoId)) { "repoId must be 'owner/name': '$repoId'" }
    }

    private companion object {
        val REPO = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*$")
    }
}

/** What a model can take as input. The first release handles text and one still image. */
enum class InputKind { TEXT, IMAGE, AUDIO, VIDEO }

/** A component of a profile: `language` / `vision` today. */
enum class BackendKind { CPU, GPU, NPU }

sealed class BackendPolicy {
    /** Pick from the registered profiles by compatibility. Not a promise of the fastest one. */
    object Auto : BackendPolicy() { override fun toString() = "Auto" }

    /** The primary component (chat: language) must run on this backend; no fallback to another. */
    data class Require(val backend: BackendKind) : BackendPolicy()

    /** Exactly this profile id from the descriptor. */
    data class RequireProfile(val profileId: String) : BackendPolicy()
}

enum class NetworkPolicy { Any, Unmetered, Offline }

/** Returns an access token for a Hugging Face repo, or null. Never stored, logged or reported by the SDK. */
fun interface CredentialProvider {
    fun tokenFor(repoId: String): String?
}

data class LoadOptions(
    val backendPolicy: BackendPolicy = BackendPolicy.Auto,
    /** null = the descriptor's default profile decides (a VLM default enables TEXT and IMAGE). */
    val requiredInputs: Set<InputKind>? = null,
    val networkPolicy: NetworkPolicy = NetworkPolicy.Any,
    /** Ceiling on bytes newly fetched by this load, fallback files included. */
    val maxDownloadBytes: Long? = null,
    val allowAdditionalArtifacts: Boolean = false,
    /** Allow a profile that is declared compatible but has no verification record. Always reported. */
    val allowUnverified: Boolean = true,
    val contextTokens: Int? = null,
    val maxImageBytes: Long = 20L * 1024 * 1024,
    val maxImagePixels: Long = 25_000_000L,
    val credentials: CredentialProvider? = null,
    /** An explicit descriptor (the text of an hfmodels.json) that wins over the repo and the catalog. */
    val descriptorJson: String? = null,
)

/** Progress of a load. Intermediate [Downloading] updates may be coalesced; generation chunks never are. */
sealed class LoadEvent {
    object Resolving : LoadEvent() { override fun toString() = "Resolving" }
    data class DownloadStarted(val totalBytes: Long) : LoadEvent()
    data class Downloading(val bytes: Long, val totalBytes: Long) : LoadEvent()
    object Verifying : LoadEvent() { override fun toString() = "Verifying" }
    data class Initializing(val profileId: String) : LoadEvent()
    data class Fallback(val reason: String) : LoadEvent()
    data class Ready(val info: PreparedModelInfo) : LoadEvent()
}

/** Where a descriptor came from. For a repo-local hfmodels.json this equals the model origin. */
data class DescriptorOrigin(val repo: String, val commit: String, val path: String)

/** The model repo and the immutable commit its files are read from. */
data class ModelOrigin(val repo: String, val commit: String)

enum class BindingSource { EXPLICIT_REVISION, SAVED_BINDING, RESOLVED_BRANCH, CATALOG_DEFAULT_BINDING }

enum class VerificationLevel { UNVERIFIED, PUBLISHER_TESTED, MAINTAINER_TESTED }

/** One file the plan needs, with the identity the store verifies against. */
data class PlannedFile(
    val id: String,
    val role: String,
    val path: String,
    val bytes: Long,
    val sha256: String,
    /** The pinned `resolve/<commit>/<path>` URL, built from [ModelOrigin]; never from a branch name. */
    val url: String,
    val cached: Boolean,
)

/**
 * Everything `download` and `prepare` need, fixed at `inspect` time. Immutable: the commit, variant,
 * profile and file set do not change while the plan is used.
 */
class ModelPlan<M : PreparedModel> internal constructor(
    val ref: ModelRef,
    val task: Task<M>,
    val options: LoadOptions,
    val modelOrigin: ModelOrigin,
    val descriptorOrigin: DescriptorOrigin,
    val descriptorSha256: String,
    val bindingSource: BindingSource,
    val descriptor: Descriptor,
    val variant: Variant,
    val profile: Profile,
    /** Profiles that were not chosen, with the reason each was excluded (or "not selected"). */
    val excludedProfiles: List<Pair<String, String>>,
    val files: List<PlannedFile>,
    val verification: VerificationLevel,
    internal val descriptorJson: String,
) {
    val totalBytes: Long get() = files.sumOf { it.bytes }
    val bytesToDownload: Long get() = files.filter { !it.cached }.sumOf { it.bytes }
    val enabledInputs: Set<InputKind> get() = profile.enabledInputs
    override fun toString(): String =
        "ModelPlan(${ref.repoId}@${modelOrigin.commit.take(8)} variant=${variant.id} profile=${profile.id} files=${files.size} download=${bytesToDownload}B via $bindingSource)"
}

/** The plan's files, on disk and verified. No native engine yet. */
class LocalModel<M : PreparedModel> internal constructor(
    val plan: ModelPlan<M>,
    /** file id -> verified local file */
    val files: Map<String, File>,
)

/** Report of what was requested, selected, initialized and observed (spec §9.4). */
data class ComponentReport(
    val requested: BackendKind,
    val initialized: BackendKind?,
    /** What the runtime itself reports as the execution target; UNKNOWN when it cannot be observed. */
    val observed: String = "UNKNOWN",
    val observedMethod: String = "none",
)

data class PreparedModelInfo(
    val repoId: String,
    val commit: String,
    val descriptorSha256: String,
    val descriptorOrigin: DescriptorOrigin,
    val bindingSource: BindingSource,
    val variantId: String,
    val profileId: String,
    val sdkVersion: String,
    val handlerId: String,
    val handlerAbi: Int,
    val runtime: String,
    val runtimeVersion: String,
    val declaredInputs: Set<InputKind>,
    val enabledInputs: Set<InputKind>,
    val requestedBackendPolicy: BackendPolicy,
    val components: Map<String, ComponentReport>,
    val excludedProfiles: List<Pair<String, String>>,
    val fallbackHistory: List<String>,
    val verification: VerificationLevel,
    /** Free-text caveats, e.g. "no runtime metadata check on this runtime version". */
    val notes: List<String>,
    val files: Map<String, String>,
)

interface PreparedModel {
    val info: PreparedModelInfo
    /** Request shutdown. Does not block. */
    fun close()
    /** Wait until native resources are released. Idempotent. */
    suspend fun closeAndJoin()
}

/** A task the SDK knows how to run, bound to the handler that prepares its models. */
interface Task<M : PreparedModel> {
    val id: String
    val handler: Handler<M>
}

/** What a runtime module gives core: the id the descriptor names, and how to turn files into a model. */
interface Handler<M : PreparedModel> {
    val id: String
    val abi: Int
    /** `litert_lm` / `litert`, as in `variants[].runtime`. */
    val runtime: String
    /** The exact runtime version this handler was compiled against, compared with `runtime_range`. */
    val runtimeVersion: String
    fun prepare(local: LocalModel<M>, host: PrepareHost, onProgress: (LoadEvent) -> Unit): M
}

/** Services core lends a handler during `prepare`. */
interface PrepareHost {
    val cacheDir: File
    val log: HfLog
    val sdkVersion: String
    /** The handler calls this exactly once when the model's native resources are gone. */
    fun onModelClosed(model: PreparedModel)
}

object HfModelsVersion {
    const val SDK_VERSION = "0.1.2-SNAPSHOT"
}
