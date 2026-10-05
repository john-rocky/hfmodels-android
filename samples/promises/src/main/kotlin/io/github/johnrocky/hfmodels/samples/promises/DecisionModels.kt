package io.github.johnrocky.hfmodels.samples.promises

import android.content.Context
import android.util.Log
import io.github.johnrocky.hfmodels.BackendKind
import io.github.johnrocky.hfmodels.BackendPolicy
import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.HfModels
import io.github.johnrocky.hfmodels.LoadEvent
import io.github.johnrocky.hfmodels.LoadOptions
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.ModelRef
import io.github.johnrocky.hfmodels.NetworkPolicy
import io.github.johnrocky.hfmodels.decide.TypedDecisions
import io.github.johnrocky.hfmodels.litert.EncoderDecisions
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The decision model behind the screen: `litert-community/GLiNER2.5-Decide-LiteRT`, loaded by id. Variant
 * `s128_wfp16` (a 128-token window, float16 weights) on the descriptor's default profile, the GPU with the CPU as
 * the fallback; in an app that packages Qualcomm's runtime, variant `s128_npu_wfp16` on the NPU first ([choose]).
 * The development descriptor (catalog/dev, an asset of this app) goes in as `LoadOptions.descriptorJson`; the
 * repo's own hfmodels.json carries `s128_npu_wfp16` too since f6f6e9c9. The descriptor pins the commit (600fe62b), so
 * a load whose files are cached makes no network request. The first load downloads 0.93 GB into the app's private
 * storage and verifies every file's sha256; a copy pushed to the app's external files dir
 * (`adb push <file> /sdcard/Android/data/<applicationId>/files/`) is imported instead. The NPU variant's graph is
 * on the Hub at the pinned commit like the other files: downloaded when the app has no copy, or pushed. The device
 * records in the README ran at db801972 and 310090c3; the files they loaded have the same bytes at 600fe62b.
 */
class DecisionModels(context: Context) {
    private val app = context.applicationContext
    val models = HfModels(app)
    var model: TypedDecisions? = null
        private set
    /** Milliseconds of the warm-up sentence the last load ran. */
    var warmupMs = 0.0
        private set
    /** When the last load fell back from the NPU: the NPU load's error code and reason. Null otherwise. */
    var npuFailure: String? = null
        private set

    val descriptor: String by lazy { app.assets.open(DESCRIPTOR_ASSET).bufferedReader().use { it.readText() } }
    /** The descriptor's commit. Passed as the revision, so resolving the id needs no request. */
    val commit: String by lazy { JSONObject(descriptor).getString("revision") }
    fun ref(variant: String): ModelRef = ModelRef(REPO, revision = commit, variant = variant)

    /** True when the app packages Qualcomm's runtime (README.md, NPU): LiteRT's Qualcomm dispatch library is in its native library dir. */
    val npuRuntime: Boolean get() = File(app.applicationInfo.nativeLibraryDir, NPU_DISPATCH_LIBRARY).isFile

    /** The variant and backend policy of a load, and what to load when that backend fails ([fallback]; null = nothing). */
    data class Choice(val variant: String, val policy: BackendPolicy, val fallback: Choice? = null)

    /**
     * Loads once; later calls return the open model. `NetworkPolicy.Offline` makes no request at all: it
     * imports pushed copies and fails with OFFLINE_CACHE_MISS when a file is neither cached nor pushed.
     * When [choice] fails with one of [BACKEND_ERRORS] and has a fallback, its error goes to [npuFailure] and the
     * log, and the fallback loads; any other error (a file that is not there, the network) goes on unchanged.
     */
    suspend fun load(choice: Choice, network: NetworkPolicy = NetworkPolicy.Any, onEvent: (LoadEvent) -> Unit = {}): TypedDecisions {
        model?.let { return it }
        npuFailure = null
        val m = try {
            open(choice, network, onEvent)
        } catch (e: ModelException) {
            val next = choice.fallback
            if (next == null || e.code !in BACKEND_ERRORS) throw e
            npuFailure = "${e.code}: ${e.reason}"
            Log.w(TAG, "${choice.variant} on ${choice.policy} failed (${e.code}: ${e.reason}); loading ${next.variant} on ${next.policy}")
            onEvent(LoadEvent.Fallback("${e.code} on the ${choice.policy.label()}"))
            open(next, network, onEvent)
        }
        model = m
        return m
    }

    private suspend fun open(choice: Choice, network: NetworkPolicy, onEvent: (LoadEvent) -> Unit): TypedDecisions {
        val m = models.fromPretrained(ref(choice.variant), EncoderDecisions, LoadOptions(backendPolicy = choice.policy, networkPolicy = network, descriptorJson = descriptor), onEvent)
        // One sentence through the graph before the first conversation, so whatever the first forward after a
        // compile costs is not timed as the first sentence on the screen. If it fails or is cancelled, the model
        // is closed before the error goes on: left open it would hold the client's one model slot (MODEL_BUSY).
        try {
            val t0 = System.nanoTime()
            withContext(Dispatchers.Default) { m.decide(WARMUP, Promises.QUESTION) }
            warmupMs = (System.nanoTime() - t0) / 1e6
        } catch (t: Throwable) {
            withContext(NonCancellable) { m.closeAndJoin() }
            throw t
        }
        return m
    }

    /** Asks the open model to close without waiting; the next [load] opens it again. */
    fun close() {
        model?.close()
        model = null
    }

    suspend fun release() {
        model?.closeAndJoin()
        model = null
    }

    companion object {
        const val REPO = "litert-community/GLiNER2.5-Decide-LiteRT"
        const val VARIANT = "s128_wfp16"
        /** The s128 graph rewritten for the Qualcomm HTP (catalog/dev's description); its other two files are VARIANT's. */
        const val NPU_VARIANT = "s128_npu_wfp16"
        /** catalog/dev's file name; build.gradle.kts adds that directory to the assets. */
        const val DESCRIPTOR_ASSET = "litert-community__GLiNER2.5-Decide-LiteRT.hfmodels.json"
        /** One of the files tools/fetch_npu_libs.sh puts into src/main/jniLibs; the SDK names any other that is missing (NATIVE_MODULE_MISSING). */
        const val NPU_DISPATCH_LIBRARY = "libLiteRtDispatch_Qualcomm.so"
        private const val WARMUP = "See you tomorrow."
        private const val TAG = "promises"

        /** The NPU load's errors after which the same files load on another backend (docs/errors.md). */
        val BACKEND_ERRORS = setOf(
            ErrorCode.NATIVE_MODULE_MISSING,
            ErrorCode.UNSUPPORTED_CONFIGURATION,
            ErrorCode.NO_COMPATIBLE_PROFILE,
            ErrorCode.INITIALIZATION_FAILED,
            ErrorCode.INFERENCE_FAILED,
        )

        /**
         * What a load asks for. [backend] `npu`, `gpu` or `cpu` fixes it: the NPU variant on the NPU, or the
         * published variant on the GPU or the CPU, with no fallback. Otherwise, with Qualcomm's runtime in the app
         * ([npuRuntime]), the NPU variant on the NPU and, when that backend fails, the same variant on its default
         * profile (the GPU, then the CPU: the same files, nothing more to download); without it, the published
         * variant on its default profile.
         */
        fun choose(npuRuntime: Boolean, backend: String?): Choice = when (backend) {
            "npu" -> Choice(NPU_VARIANT, BackendPolicy.Require(BackendKind.NPU))
            "gpu" -> Choice(VARIANT, BackendPolicy.Require(BackendKind.GPU))
            "cpu" -> Choice(VARIANT, BackendPolicy.Require(BackendKind.CPU))
            else -> if (npuRuntime) {
                Choice(NPU_VARIANT, BackendPolicy.Require(BackendKind.NPU), fallback = Choice(NPU_VARIANT, BackendPolicy.Auto))
            } else {
                Choice(VARIANT, BackendPolicy.Auto)
            }
        }

        private fun BackendPolicy.label(): String = when (this) {
            is BackendPolicy.Require -> backend.name
            is BackendPolicy.RequireProfile -> "profile $profileId"
            else -> "default profile"
        }

        @Volatile private var shared: DecisionModels? = null

        /** One per process: HfModels owns one model slot, and a share can arrive while the screen holds the model. */
        fun shared(context: Context): DecisionModels = shared ?: synchronized(this) { shared ?: DecisionModels(context).also { shared = it } }
    }
}
