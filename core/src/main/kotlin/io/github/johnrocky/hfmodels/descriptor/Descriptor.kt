package io.github.johnrocky.hfmodels.descriptor

import io.github.johnrocky.hfmodels.BackendKind
import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.InputKind
import io.github.johnrocky.hfmodels.ModelException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * `hfmodels.json` v1 (spec v1.0 §8 with the first-release subset). Parsed strictly: an unknown
 * major schema is [ErrorCode.UNSUPPORTED_SCHEMA], anything else malformed is
 * [ErrorCode.MANIFEST_INVALID]. Measured numbers (tok/s, verified backends per device) are not
 * here; they stay in the repo's `litertlm_manifest.json`, which `handler_config.metadata_source`
 * points at.
 */
data class Descriptor(
    val schemaVersion: Int,
    val modelId: String,
    val tasks: List<String>,
    val defaultVariant: String,
    val variants: List<Variant>,
    val license: License,
) {
    fun variant(id: String?): Variant? = variants.firstOrNull { it.id == (id ?: defaultVariant) }

    companion object {
        const val SCHEMA_VERSION = 1
        const val FILE_NAME = "hfmodels.json"
        val TASKS = setOf("chat", "object_detection")
        val RUNTIMES = setOf("litert_lm", "litert")
        val ROLES = setOf("model", "tokenizer", "labels", "processor_config")
        private val SHA256 = Regex("^[0-9a-f]{64}$")

        @Throws(ModelException::class)
        fun parse(text: String, modelIdExpected: String? = null): Descriptor {
            val o = try { JSONObject(text) } catch (e: JSONException) { invalid("not a JSON object: ${e.message}") }
            val schema = o.optInt("schema_version", -1)
            if (schema != SCHEMA_VERSION) throw ModelException(ErrorCode.UNSUPPORTED_SCHEMA, "schema_version $schema is not supported (this SDK reads $SCHEMA_VERSION)")
            val modelId = o.str("model_id")
            if (modelIdExpected != null && modelId != modelIdExpected) invalid("model_id '$modelId' does not name the target repo '$modelIdExpected'")
            val tasks = o.strList("tasks").also { if (it.isEmpty()) invalid("tasks is empty"); it.forEach { t -> if (t !in TASKS) invalid("unknown task '$t'") } }
            val defaultVariant = o.str("default_variant")
            val variants = o.arr("variants").objects().map { Variant.parse(it) }
            if (variants.isEmpty()) invalid("variants is empty")
            variants.groupBy { it.id }.filter { it.value.size > 1 }.keys.firstOrNull()?.let { invalid("duplicate variant id '$it'") }
            if (variants.none { it.id == defaultVariant }) invalid("default_variant '$defaultVariant' is not a variant id")
            val license = License.parse(o.obj("license"))
            return Descriptor(schema, modelId, tasks, defaultVariant, variants, license)
        }

        internal fun invalid(msg: String): Nothing = throw ModelException(ErrorCode.MANIFEST_INVALID, msg)

        internal fun JSONObject.str(key: String): String =
            if (has(key) && !isNull(key) && get(key) is String) getString(key) else invalid("missing string '$key'")
        internal fun JSONObject.strOrNull(key: String): String? = if (has(key) && !isNull(key)) get(key).toString() else null
        internal fun JSONObject.obj(key: String): JSONObject = optJSONObject(key) ?: invalid("missing object '$key'")
        internal fun JSONObject.arr(key: String): JSONArray = optJSONArray(key) ?: invalid("missing array '$key'")
        internal fun JSONObject.strList(key: String): List<String> = arr(key).let { a -> List(a.length()) { i -> a.get(i) as? String ?: invalid("'$key' must hold strings") } }
        internal fun JSONArray.objects(): List<JSONObject> = List(length()) { i -> optJSONObject(i) ?: invalid("array element $i is not an object") }
        internal fun JSONObject.longNonNeg(key: String): Long {
            val v = if (has(key) && !isNull(key)) get(key) else invalid("missing integer '$key'")
            val n = (v as? Number)?.toLong() ?: invalid("'$key' must be an integer")
            if (n < 0) invalid("'$key' must be >= 0")
            return n
        }
        internal fun JSONObject.sha(key: String): String = str(key).also { if (!SHA256.matches(it)) invalid("'$key' must be 64 lower-case hex chars") }
        internal fun inputKind(s: String): InputKind = when (s) {
            "text" -> InputKind.TEXT; "image" -> InputKind.IMAGE; "audio" -> InputKind.AUDIO; "video" -> InputKind.VIDEO
            else -> invalid("unknown input '$s'")
        }
        internal fun backendKind(s: String): BackendKind = when (s) {
            "cpu" -> BackendKind.CPU; "gpu" -> BackendKind.GPU; "npu" -> BackendKind.NPU
            else -> invalid("unknown backend '$s'")
        }
    }
}

data class License(val id: String, val url: String?) {
    companion object {
        fun parse(o: JSONObject): License = with(Descriptor) {
            val id = o.str("id")
            val url = o.strOrNull("url")
            if (id == "other" && url == null) invalid("license 'other' needs a url")
            License(id, url)
        }
    }
}

data class HandlerRef(val id: String, val abi: Int)

/** Exact bounds; `max_exclusive` null = open-ended (the publisher takes that risk knowingly). */
data class RuntimeRange(val minInclusive: String, val maxExclusive: String?) {
    fun contains(version: String): Boolean =
        compareVersions(version, minInclusive) >= 0 && (maxExclusive == null || compareVersions(version, maxExclusive) < 0)

    companion object {
        /** Numeric dot-compare; a suffix like `-alpha1` sorts below the bare version. */
        fun compareVersions(a: String, b: String): Int {
            fun split(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 } to v.contains('-')
            val (pa, sa) = split(a); val (pb, sb) = split(b)
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val c = (pa.getOrNull(i) ?: 0).compareTo(pb.getOrNull(i) ?: 0)
                if (c != 0) return c
            }
            return when { sa == sb -> 0; sa -> -1; else -> 1 }
        }
    }
}

data class FileEntry(val id: String, val role: String, val path: String, val bytes: Long, val sha256: String) {
    companion object {
        fun parse(o: JSONObject): FileEntry = with(Descriptor) {
            val path = o.str("path")
            if (path.startsWith("/") || path.split('/').any { it == ".." || it.isEmpty() }) invalid("file path must be relative without '..': '$path'")
            val role = o.str("role").also { if (it !in ROLES) invalid("unknown file role '$it'") }
            FileEntry(o.str("id"), role, path, o.longNonNeg("bytes"), o.sha("sha256"))
        }
    }
}

data class Requirements(val minAndroidApi: Int, val abis: List<String>) {
    companion object {
        fun parse(o: JSONObject?): Requirements = with(Descriptor) {
            if (o == null) return Requirements(31, listOf("arm64-v8a"))
            Requirements(o.optInt("min_android_api", 31), if (o.has("abis")) o.strList("abis") else listOf("arm64-v8a"))
        }
    }
}

/** A verification record attached by the publisher or the maintainer (spec §14). Exact-match only. */
data class Verification(
    val level: String,
    val device: String,
    val osBuild: String?,
    val runtime: String,
    val result: String,
    val date: String?,
) {
    companion object {
        fun parse(o: JSONObject): Verification = with(Descriptor) {
            Verification(o.str("level"), o.str("device"), o.strOrNull("os_build"), o.str("runtime"), o.str("result"), o.strOrNull("date"))
        }
    }
}

data class Profile(
    val id: String,
    val priority: Int,
    val files: List<String>,
    val enabledInputs: Set<InputKind>,
    /** component name (`language`, `vision`) -> backend */
    val components: Map<String, BackendKind>,
    val requirements: Requirements,
    val fallbackProfiles: List<String>,
    val defaultSelectable: Boolean,
    val contextTokens: Int?,
    val verification: List<Verification>,
) {
    /** The primary component: `language` for chat, `inference` for detection. */
    val primary: BackendKind get() = components["language"] ?: components["inference"] ?: components.values.first()

    companion object {
        fun parse(o: JSONObject): Profile = with(Descriptor) {
            val comps = o.obj("components")
            val components = comps.keys().asSequence().associateWith { k -> backendKind(comps.get(k) as? String ?: invalid("component '$k' must be a string")) }
            if (components.isEmpty()) invalid("profile components is empty")
            Profile(
                id = o.str("id"),
                priority = o.optInt("priority", 0),
                files = o.strList("files"),
                enabledInputs = o.strList("enabled_inputs").map { inputKind(it) }.toSet(),
                components = components,
                requirements = Requirements.parse(o.optJSONObject("requirements")),
                fallbackProfiles = if (o.has("fallback_profiles")) o.strList("fallback_profiles") else emptyList(),
                defaultSelectable = o.optBoolean("default_selectable", true),
                contextTokens = if (o.has("context_tokens") && !o.isNull("context_tokens")) o.getInt("context_tokens") else null,
                verification = o.optJSONArray("verification")?.objects()?.map { Verification.parse(it) } ?: emptyList(),
            )
        }
    }
}

data class Variant(
    val id: String,
    val runtime: String,
    val handler: HandlerRef,
    val runtimeRange: RuntimeRange,
    val inputs: Set<InputKind>,
    val files: List<FileEntry>,
    val defaultProfile: String,
    val profiles: List<Profile>,
    /** Handler-defined declarations, kept as JSON for the handler to interpret. */
    val handlerConfig: JSONObject,
) {
    fun profile(id: String): Profile? = profiles.firstOrNull { it.id == id }
    fun file(id: String): FileEntry? = files.firstOrNull { it.id == id }

    companion object {
        fun parse(o: JSONObject): Variant = with(Descriptor) {
            val id = o.str("id")
            val runtime = o.str("runtime").also { if (it !in RUNTIMES) invalid("variant '$id': unknown runtime '$it'") }
            val h = o.obj("handler")
            val handler = HandlerRef(h.str("id"), h.optInt("abi", -1).also { if (it < 1) invalid("variant '$id': handler.abi must be a positive integer") })
            val rr = o.obj("runtime_range")
            val range = RuntimeRange(rr.str("min_inclusive"), rr.strOrNull("max_exclusive"))
            val inputs = o.strList("inputs").map { inputKind(it) }.toSet().also { if (it.isEmpty()) invalid("variant '$id': inputs is empty") }
            val files = o.arr("files").objects().map { FileEntry.parse(it) }
            if (files.isEmpty()) invalid("variant '$id': files is empty")
            files.groupBy { it.id }.filter { it.value.size > 1 }.keys.firstOrNull()?.let { invalid("variant '$id': duplicate file id '$it'") }
            val defaultProfile = o.str("default_profile")
            val profiles = o.arr("profiles").objects().map { Profile.parse(it) }
            if (profiles.isEmpty()) invalid("variant '$id': profiles is empty")
            profiles.groupBy { it.id }.filter { it.value.size > 1 }.keys.firstOrNull()?.let { invalid("variant '$id': duplicate profile id '$it'") }
            if (profiles.none { it.id == defaultProfile }) invalid("variant '$id': default_profile '$defaultProfile' is not a profile id")
            for (p in profiles) {
                p.files.forEach { f -> if (files.none { it.id == f }) invalid("variant '$id' profile '${p.id}': unknown file id '$f'") }
                p.fallbackProfiles.forEach { f -> if (profiles.none { it.id == f }) invalid("variant '$id' profile '${p.id}': unknown fallback profile '$f'") }
                if (!inputs.containsAll(p.enabledInputs)) invalid("variant '$id' profile '${p.id}': enabled_inputs exceed the variant's inputs")
            }
            checkNoFallbackCycle(id, profiles)
            val handlerConfig = o.optJSONObject("handler_config") ?: JSONObject()
            Variant(id, runtime, handler, range, inputs, files, defaultProfile, profiles, handlerConfig)
        }

        private fun checkNoFallbackCycle(variantId: String, profiles: List<Profile>) {
            val byId = profiles.associateBy { it.id }
            for (start in profiles) {
                val seen = mutableSetOf(start.id)
                val stack = ArrayDeque(start.fallbackProfiles)
                while (stack.isNotEmpty()) {
                    val n = stack.removeFirst()
                    if (n == start.id) Descriptor.invalid("variant '$variantId': fallback cycle through profile '${start.id}'")
                    if (seen.add(n)) byId[n]?.fallbackProfiles?.let { stack.addAll(it) }
                }
            }
        }
    }
}
