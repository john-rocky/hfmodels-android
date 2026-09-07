package io.github.johnrocky.hfmodels.resolve

import io.github.johnrocky.hfmodels.BackendKind
import io.github.johnrocky.hfmodels.BackendPolicy
import io.github.johnrocky.hfmodels.BindingSource
import io.github.johnrocky.hfmodels.DescriptorOrigin
import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.HfLog
import io.github.johnrocky.hfmodels.InputKind
import io.github.johnrocky.hfmodels.LoadOptions
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.ModelOrigin
import io.github.johnrocky.hfmodels.ModelPlan
import io.github.johnrocky.hfmodels.ModelRef
import io.github.johnrocky.hfmodels.NetworkPolicy
import io.github.johnrocky.hfmodels.PlannedFile
import io.github.johnrocky.hfmodels.Task
import io.github.johnrocky.hfmodels.PreparedModel
import io.github.johnrocky.hfmodels.VerificationLevel
import io.github.johnrocky.hfmodels.catalog.Catalog
import io.github.johnrocky.hfmodels.descriptor.Descriptor
import io.github.johnrocky.hfmodels.descriptor.Profile
import io.github.johnrocky.hfmodels.descriptor.Variant
import io.github.johnrocky.hfmodels.hub.HfHub
import io.github.johnrocky.hfmodels.store.ArtifactRef
import io.github.johnrocky.hfmodels.store.ModelStore
import java.security.MessageDigest

/** What the resolver knows about the device it runs on (injected so the JVM tests can vary it). */
data class DeviceFacts(val androidApi: Int, val abis: List<String>)

/**
 * `inspect`: id -> immutable plan. Resolution order (spec §6.3):
 * 1. an explicit descriptor from the caller;
 * 2. `hfmodels.json` at the resolved model commit;
 * 3. an external descriptor from the catalog for that exact commit;
 * 4. [ErrorCode.MODEL_NOT_REGISTERED].
 * Exception: no revision, no saved binding, no repo-local descriptor on the branch head -> the
 * catalog's default binding for the id picks the model commit, and 2-3 run against that commit.
 */
internal class Resolver(
    private val hub: HfHub,
    private val store: ModelStore,
    private val bindings: Bindings,
    private val descriptorCache: DescriptorCache,
    private val catalog: Catalog,
    private val device: DeviceFacts,
    private val log: HfLog,
) {
    private val commitRe = Regex("^[0-9a-f]{40}$")

    fun <M : PreparedModel> inspect(ref: ModelRef, task: Task<M>, options: LoadOptions): ModelPlan<M> {
        val offline = options.networkPolicy == NetworkPolicy.Offline
        val token = options.credentials?.tokenFor(ref.repoId)

        // --- 1. which model commit ------------------------------------------------------------
        var bindingSource: BindingSource
        var commit: String
        var repoInfo: io.github.johnrocky.hfmodels.hub.RepoInfo? = null
        val explicitCommit = ref.revision?.takeIf { commitRe.matches(it) }
        val saved = if (ref.revision == null) bindings.read(ref.repoId) else null
        when {
            explicitCommit != null -> { commit = explicitCommit; bindingSource = BindingSource.EXPLICIT_REVISION }
            ref.revision != null -> {
                if (offline) throw ModelException(ErrorCode.OFFLINE_CACHE_MISS, "revision '${ref.revision}' of ${ref.repoId} is a branch or tag and cannot be resolved offline; pin a commit", details = mapOf("repo" to ref.repoId))
                repoInfo = hub.repoInfo(ref.repoId, ref.revision, token)
                commit = repoInfo.sha; bindingSource = BindingSource.EXPLICIT_REVISION
            }
            saved != null -> { commit = saved.commit; bindingSource = BindingSource.SAVED_BINDING }
            offline -> throw ModelException(ErrorCode.OFFLINE_CACHE_MISS, "${ref.repoId} has no saved binding and the network policy is Offline", details = mapOf("repo" to ref.repoId))
            else -> {
                repoInfo = hub.repoInfo(ref.repoId, null, token)
                commit = repoInfo.sha; bindingSource = BindingSource.RESOLVED_BRANCH
            }
        }

        // --- 2. which descriptor --------------------------------------------------------------
        var descriptorJson: String
        var origin: DescriptorOrigin
        val explicit = options.descriptorJson
        if (explicit != null) {
            descriptorJson = explicit
            origin = DescriptorOrigin("caller", "explicit", Descriptor.FILE_NAME)
        } else {
            var found = findDescriptor(ref.repoId, commit, token, offline)
            if (found == null && bindingSource == BindingSource.RESOLVED_BRANCH) {
                // The branch head carries no descriptor and nothing is bound yet: the catalog's default binding.
                val entry = catalog.defaultBinding(ref.repoId)
                if (entry != null) {
                    log.i("${ref.repoId}: no descriptor at branch head ${commit.take(8)}; using the catalog's default binding ${entry.modelCommit.take(8)}")
                    commit = entry.modelCommit
                    bindingSource = BindingSource.CATALOG_DEFAULT_BINDING
                    found = findDescriptor(ref.repoId, commit, token, offline)
                }
            }
            if (found == null) throw ModelException(
                ErrorCode.MODEL_NOT_REGISTERED,
                "${ref.repoId}@${commit.take(8)} has no ${Descriptor.FILE_NAME} and the catalog has no entry for that commit. Add ${Descriptor.FILE_NAME} to the repo (see the publisher guide) or pass a descriptor explicitly.",
                details = mapOf("repo" to ref.repoId, "commit" to commit),
            )
            descriptorJson = found.first; origin = found.second
        }
        val descriptor = Descriptor.parse(descriptorJson, modelIdExpected = ref.repoId)
        val descriptorSha = sha256(descriptorJson)
        // Only a descriptor that parsed is cached; a commit is immutable, so the cache never goes stale.
        if (origin.repo == ref.repoId && origin.commit == commit && descriptorCache.read(ref.repoId, commit) == null) descriptorCache.write(ref.repoId, commit, descriptorJson)
        log.i("resolve ${ref.repoId}: commit=${commit.take(8)} via $bindingSource, descriptor=${origin.repo}@${origin.commit.take(8)}/${origin.path} sha=${descriptorSha.take(8)}")
        if (task.id !in descriptor.tasks) throw ModelException(ErrorCode.UNSUPPORTED_INPUT, "${ref.repoId} declares tasks ${descriptor.tasks}, not '${task.id}'", details = mapOf("repo" to ref.repoId))

        // --- 3. variant, handler, runtime ----------------------------------------------------
        val variant = descriptor.variant(ref.variant)
            ?: throw ModelException(ErrorCode.VARIANT_NOT_FOUND, "${ref.repoId} has no variant '${ref.variant}' (have: ${descriptor.variants.map { it.id }})", details = mapOf("repo" to ref.repoId))
        val handler = task.handler
        if (variant.runtime != handler.runtime || variant.handler.id != handler.id) throw ModelException(
            ErrorCode.HANDLER_NOT_INSTALLED,
            "variant '${variant.id}' needs handler ${variant.handler.id} on ${variant.runtime}; this app has ${handler.id} on ${handler.runtime}. Add the hfmodels module for that runtime.",
            details = mapOf("handler" to variant.handler.id, "runtime" to variant.runtime),
        )
        if (variant.handler.abi != handler.abi) throw ModelException(ErrorCode.HANDLER_NOT_INSTALLED, "variant '${variant.id}' needs ${handler.id} ABI ${variant.handler.abi}; installed ABI is ${handler.abi}")
        if (!variant.runtimeRange.contains(handler.runtimeVersion)) throw ModelException(
            ErrorCode.RUNTIME_VERSION_MISMATCH,
            "variant '${variant.id}' declares ${variant.runtime} [${variant.runtimeRange.minInclusive}, ${variant.runtimeRange.maxExclusive ?: "∞"}); this app links ${handler.runtimeVersion}",
            details = mapOf("runtime" to variant.runtime, "linked" to handler.runtimeVersion, "min_inclusive" to variant.runtimeRange.minInclusive, "max_exclusive" to (variant.runtimeRange.maxExclusive ?: "")),
        )

        // --- 4. profile (spec §9.2) ------------------------------------------------------------
        val (profile, excluded) = selectProfile(variant, options)

        // --- 5. files -------------------------------------------------------------------------
        val modelOrigin = ModelOrigin(ref.repoId, commit)
        val files = profile.files.map { id ->
            val f = variant.file(id)!!
            val url = hub.fileUrl(ref.repoId, commit, f.path)
            val cached = store.isCached(ArtifactRef(f.path.substringAfterLast('/'), url, f.sha256, f.bytes))
            PlannedFile(f.id, f.role, f.path, f.bytes, f.sha256, url, cached)
        }
        // Cross-check against what the Hub lists for the commit when we have it (bytes and sha256 are
        // the descriptor's word; a disagreement is a METADATA_MISMATCH, not silently one side).
        repoInfo?.let { info ->
            if (info.sha == commit) for (f in files) {
                val s = info.siblings[f.path] ?: throw ModelException(ErrorCode.METADATA_MISMATCH, "${ref.repoId}@${commit.take(8)} does not contain '${f.path}' listed by the descriptor", details = mapOf("path" to f.path))
                if (s.sha256 != null && s.sha256 != f.sha256) throw ModelException(ErrorCode.METADATA_MISMATCH, "'${f.path}': descriptor sha256 ${f.sha256} but the Hub lists ${s.sha256}", details = mapOf("path" to f.path))
                if (s.size != null && s.size != f.bytes) throw ModelException(ErrorCode.METADATA_MISMATCH, "'${f.path}': descriptor bytes ${f.bytes} but the Hub lists ${s.size}", details = mapOf("path" to f.path))
            }
        }
        val verification = verificationLevel(profile)
        if (!options.allowUnverified && verification == VerificationLevel.UNVERIFIED) throw ModelException(
            ErrorCode.NO_COMPATIBLE_PROFILE, "profile '${profile.id}' has no verification record and allowUnverified=false", details = mapOf("profile" to profile.id),
        )
        return ModelPlan(ref, task, options, modelOrigin, origin, descriptorSha, bindingSource, descriptor, variant, profile, excluded, files, verification, descriptorJson)
    }

    /** The descriptor text and where it came from, or null when neither the repo nor the catalog has one for this commit. */
    private fun findDescriptor(repoId: String, commit: String, token: String?, offline: Boolean): Pair<String, DescriptorOrigin>? {
        descriptorCache.read(repoId, commit)?.let { return it to DescriptorOrigin(repoId, commit, Descriptor.FILE_NAME) }
        if (!offline) {
            hub.smallFile(repoId, commit, Descriptor.FILE_NAME, token)?.let { return it to DescriptorOrigin(repoId, commit, Descriptor.FILE_NAME) }
        }
        catalog.find(repoId, commit)?.let { return it.descriptorJson to it.origin }
        return null
    }

    internal fun selectProfile(variant: Variant, options: LoadOptions): Pair<Profile, List<Pair<String, String>>> {
        val excluded = ArrayList<Pair<String, String>>()
        val required = options.requiredInputs
        val candidates = variant.profiles.filter { p ->
            val reason = when {
                required != null && !p.enabledInputs.containsAll(required) -> "does not enable ${required - p.enabledInputs}"
                p.requirements.minAndroidApi > device.androidApi -> "needs Android API ${p.requirements.minAndroidApi}, device has ${device.androidApi}"
                p.requirements.abis.none { it in device.abis } -> "needs ABI ${p.requirements.abis}, device has ${device.abis}"
                p.components.values.any { it == BackendKind.NPU } && p.verification.none { it.result == "PASS" } -> "NPU profile without a verification record is never auto-selected"
                else -> null
            }
            if (reason != null) excluded += p.id to reason
            reason == null
        }
        val chosen: Profile? = when (val policy = options.backendPolicy) {
            is BackendPolicy.RequireProfile -> {
                val p = variant.profile(policy.profileId) ?: throw ModelException(ErrorCode.NO_COMPATIBLE_PROFILE, "variant '${variant.id}' has no profile '${policy.profileId}'")
                if (p !in candidates) throw ModelException(ErrorCode.NO_COMPATIBLE_PROFILE, "profile '${p.id}' is not eligible on this device: ${excluded.firstOrNull { it.first == p.id }?.second}", details = mapOf("profile" to p.id))
                p
            }
            is BackendPolicy.Require -> {
                val ok = candidates.filter { it.primary == policy.backend }
                candidates.filter { it.primary != policy.backend }.forEach { excluded += it.id to "primary backend is ${it.primary}, ${policy.backend} required" }
                ok.filter { it.defaultSelectable }.let { d -> if (d.isNotEmpty()) d else ok }.maxWithOrNull(compareBy<Profile> { it.priority }.thenByDescending { it.id })
            }
            BackendPolicy.Auto -> {
                val selectable = candidates.filter { it.defaultSelectable }
                candidates.filter { !it.defaultSelectable }.forEach { excluded += it.id to "default_selectable=false" }
                selectable.firstOrNull { it.id == variant.defaultProfile }
                    ?: selectable.maxWithOrNull(compareBy<Profile> { it.priority }.thenByDescending { it.id })
            }
        }
        if (chosen == null) throw ModelException(
            ErrorCode.NO_COMPATIBLE_PROFILE,
            "no profile of variant '${variant.id}' fits ${options.backendPolicy} on this device: " + excluded.joinToString("; ") { "${it.first}: ${it.second}" },
            details = excluded.associate { it.first to it.second },
        )
        variant.profiles.filter { it.id != chosen.id && excluded.none { e -> e.first == it.id } }.forEach { excluded += it.id to "not selected" }
        return chosen to excluded
    }

    private fun verificationLevel(p: Profile): VerificationLevel = when {
        p.verification.any { it.level == "MAINTAINER_TESTED" && it.result == "PASS" } -> VerificationLevel.MAINTAINER_TESTED
        p.verification.any { it.level == "PUBLISHER_TESTED" && it.result == "PASS" } -> VerificationLevel.PUBLISHER_TESTED
        else -> VerificationLevel.UNVERIFIED
    }

    companion object {
        fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        fun inputsOf(variant: Variant): Set<InputKind> = variant.inputs
    }
}
