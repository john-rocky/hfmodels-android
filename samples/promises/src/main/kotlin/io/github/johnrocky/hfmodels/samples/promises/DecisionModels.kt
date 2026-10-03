package io.github.johnrocky.hfmodels.samples.promises

import android.content.Context
import io.github.johnrocky.hfmodels.BackendPolicy
import io.github.johnrocky.hfmodels.HfModels
import io.github.johnrocky.hfmodels.LoadEvent
import io.github.johnrocky.hfmodels.LoadOptions
import io.github.johnrocky.hfmodels.ModelRef
import io.github.johnrocky.hfmodels.NetworkPolicy
import io.github.johnrocky.hfmodels.decide.TypedDecisions
import io.github.johnrocky.hfmodels.litert.EncoderDecisions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The decision model behind the screen: `litert-community/GLiNER2.5-Decide-LiteRT`, variant `s128_wfp16`
 * (a 128-token window, float16 weights), loaded by id. The repo carries no hfmodels.json yet, so the
 * development descriptor (catalog/dev, an asset of this app) goes in as `LoadOptions.descriptorJson`; from
 * 0.1.3 the bundled catalog carries it. The descriptor pins the commit, so a load whose files are cached
 * makes no network request. The first load downloads 0.93 GB into the app's private storage and verifies
 * every file's sha256; a copy pushed to the app's external files dir
 * (`adb push <file> /sdcard/Android/data/<applicationId>/files/`) is imported instead.
 */
class DecisionModels(context: Context) {
    private val app = context.applicationContext
    val models = HfModels(app)
    var model: TypedDecisions? = null
        private set
    /** Milliseconds of the warm-up sentence the last load ran. */
    var warmupMs = 0.0
        private set

    val descriptor: String by lazy { app.assets.open(DESCRIPTOR_ASSET).bufferedReader().use { it.readText() } }
    /** The descriptor's commit. Passed as the revision, so resolving the id needs no request. */
    val commit: String by lazy { JSONObject(descriptor).getString("revision") }
    val ref: ModelRef get() = ModelRef(REPO, revision = commit, variant = VARIANT)

    /**
     * Loads once; later calls return the open model. `NetworkPolicy.Offline` makes no request at all: it
     * imports pushed copies and fails with OFFLINE_CACHE_MISS when a file is neither cached nor pushed.
     */
    suspend fun load(policy: BackendPolicy = BackendPolicy.Auto, network: NetworkPolicy = NetworkPolicy.Any, onEvent: (LoadEvent) -> Unit = {}): TypedDecisions {
        model?.let { return it }
        val m = models.fromPretrained(ref, EncoderDecisions, LoadOptions(backendPolicy = policy, networkPolicy = network, descriptorJson = descriptor), onEvent)
        // One sentence through the graph before the first conversation, so whatever the first forward after a
        // compile costs is not timed as the first sentence on the screen.
        val t0 = System.nanoTime()
        withContext(Dispatchers.Default) { m.decide(WARMUP, Promises.QUESTION) }
        warmupMs = (System.nanoTime() - t0) / 1e6
        model = m
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
        /** catalog/dev's file name; build.gradle.kts adds that directory to the assets. */
        const val DESCRIPTOR_ASSET = "litert-community__GLiNER2.5-Decide-LiteRT.hfmodels.json"
        private const val WARMUP = "See you tomorrow."

        @Volatile private var shared: DecisionModels? = null

        /** One per process: HfModels owns one model slot, and a share can arrive while the screen holds the model. */
        fun shared(context: Context): DecisionModels = shared ?: synchronized(this) { shared ?: DecisionModels(context).also { shared = it } }
    }
}
