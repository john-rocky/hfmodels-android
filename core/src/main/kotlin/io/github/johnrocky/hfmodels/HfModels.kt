package io.github.johnrocky.hfmodels

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import io.github.johnrocky.hfmodels.catalog.Catalog
import io.github.johnrocky.hfmodels.hub.HfHub
import io.github.johnrocky.hfmodels.resolve.Bindings
import io.github.johnrocky.hfmodels.resolve.DescriptorCache
import io.github.johnrocky.hfmodels.resolve.DeviceFacts
import io.github.johnrocky.hfmodels.resolve.Resolver
import io.github.johnrocky.hfmodels.store.ArtifactRef
import io.github.johnrocky.hfmodels.store.HttpSource
import io.github.johnrocky.hfmodels.store.HttpUrlConnectionSource
import io.github.johnrocky.hfmodels.store.ModelStore
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The entry point: one per application, holding only the application context.
 *
 * ```kotlin
 * val models = HfModels(applicationContext)
 * val chat = models.fromPretrained(ModelRef("litert-community/gemma-4-E2B-it-litert-lm"), Tasks.Chat)
 * ```
 *
 * `inspect` -> `download` -> `prepare` are the same three steps `fromPretrained` runs; use them
 * separately when the app wants to show sizes, ask before a download, or load offline.
 * The client holds at most ONE native model at a time (spec §11.1); a second `prepare` while one
 * is open is [ErrorCode.MODEL_BUSY].
 */
class HfModels internal constructor(
    private val appContext: Context?,
    /** `filesDir/hfmodels` in an app; a temp dir in JVM tests. */
    val root: File,
    private val cacheDir: File,
    private val hub: HfHub,
    private val store: ModelStore,
    private val catalog: Catalog,
    device: DeviceFacts,
    private val log: HfLog,
) : AutoCloseable {
    constructor(context: Context, options: HfModelsOptions = HfModelsOptions()) : this(
        appContext = context.applicationContext,
        root = File(context.applicationContext.filesDir, "hfmodels"),
        cacheDir = File(context.applicationContext.cacheDir, "hfmodels").apply { mkdirs() },
        hub = HfHub(options.http, options.hubEndpoint),
        store = ModelStore(File(context.applicationContext.filesDir, "hfmodels"), options.http),
        catalog = options.catalog ?: BundledCatalog.load(context.applicationContext),
        device = DeviceFacts(Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toList()),
        log = options.log,
    )

    private val bindings = Bindings(root)
    private val descriptors = DescriptorCache(root)
    private val resolver = Resolver(hub, store, bindings, descriptors, catalog, device, log)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloads = HashMap<String, SharedDownload>()
    private val downloadsLock = Mutex()
    private val slotLock = Mutex()
    private var active: PreparedModel? = null
    private val closed = AtomicBoolean(false)

    /** Fixes commit, variant, profile and file set. Reads metadata only; never downloads weights. */
    suspend fun <M : PreparedModel> inspect(ref: ModelRef, task: Task<M>, options: LoadOptions = LoadOptions()): ModelPlan<M> {
        checkOpen()
        return withContext(Dispatchers.IO) { runInterruptible { resolver.inspect(ref, task, options) } }
    }

    /** Fetches and verifies the plan's files (resumable, shared between concurrent callers). No native engine. */
    suspend fun <M : PreparedModel> download(plan: ModelPlan<M>, onProgress: (LoadEvent) -> Unit = {}): LocalModel<M> {
        checkOpen()
        val policy = plan.options.networkPolicy
        val toFetch = plan.files.filter { !store.isCached(it.artifactRef()) }
        val need = toFetch.sumOf { it.bytes - store.bytesPresent(it.artifactRef()) }
        if (toFetch.isNotEmpty()) {
            if (policy == NetworkPolicy.Offline) throw ModelException(ErrorCode.OFFLINE_CACHE_MISS, "${toFetch.size} file(s) of ${plan.ref.repoId} are not cached and the network policy is Offline", details = mapOf("files" to toFetch.joinToString { it.path }))
            if (policy == NetworkPolicy.Unmetered && isMetered()) throw ModelException(ErrorCode.DOWNLOAD_POLICY_BLOCKED, "the active network is metered and the policy is Unmetered; $need bytes would be fetched", details = mapOf("bytes" to need.toString()))
            plan.options.maxDownloadBytes?.let { max -> if (need > max) throw ModelException(ErrorCode.DOWNLOAD_POLICY_BLOCKED, "this load would fetch $need bytes, more than maxDownloadBytes=$max", details = mapOf("bytes" to need.toString(), "max" to max.toString())) }
            onProgress(LoadEvent.DownloadStarted(plan.totalBytes))
        }
        val token = plan.options.credentials?.tokenFor(plan.ref.repoId)
        val files = LinkedHashMap<String, File>()
        var doneBefore = plan.files.filter { store.isCached(it.artifactRef()) }.sumOf { it.bytes }
        for (f in plan.files) {
            val ref = f.artifactRef()
            if (store.isCached(ref)) { files[f.id] = store.fileFor(ref); continue }
            val base = doneBefore
            val file = shared(ref, token, plan.options.networkPolicy == NetworkPolicy.Offline) { bytes, _ ->
                onProgress(LoadEvent.Downloading(base + bytes, plan.totalBytes))
            }
            files[f.id] = file
            doneBefore += f.bytes
        }
        onProgress(LoadEvent.Verifying)
        for (f in plan.files) {
            val ref = f.artifactRef()
            if (!store.isCached(ref)) throw ModelException(ErrorCode.CHECKSUM_MISMATCH, "'${f.path}' is not verified after download", details = mapOf("path" to f.path))
        }
        return LocalModel(plan, files)
    }

    /** Initializes the native engine from local files only. Makes no network request. */
    suspend fun <M : PreparedModel> prepare(local: LocalModel<M>, onProgress: (LoadEvent) -> Unit = {}): M {
        checkOpen()
        val plan = local.plan
        slotLock.withLock {
            active?.let { throw ModelException(ErrorCode.MODEL_BUSY, "another model (${it.info.repoId}) is open; close it first (one native model per client)", details = mapOf("open" to it.info.repoId)) }
            onProgress(LoadEvent.Initializing(plan.profile.id))
            val host = object : PrepareHost {
                override val cacheDir: File get() = this@HfModels.cacheDir
                override val log: HfLog get() = this@HfModels.log
                override val sdkVersion: String get() = HfModelsVersion.SDK_VERSION
                override fun onModelClosed(model: PreparedModel) { releaseSlot(model) }
            }
            val model = withContext(Dispatchers.IO) { plan.task.handler.prepare(local, host, onProgress) }
            active = model
            if (plan.bindingSource != BindingSource.EXPLICIT_REVISION && plan.descriptorOrigin.commit != "explicit") {
                bindings.write(Bindings.Binding(plan.ref.repoId, plan.modelOrigin.commit, plan.descriptorSha256, plan.descriptorOrigin.repo, plan.descriptorOrigin.commit, plan.descriptorOrigin.path, java.time.Instant.now().toString()))
            }
            onProgress(LoadEvent.Ready(model.info))
            return model
        }
    }

    /** `inspect` + `download` + `prepare`. */
    suspend fun <M : PreparedModel> fromPretrained(ref: ModelRef, task: Task<M>, options: LoadOptions = LoadOptions(), onProgress: (LoadEvent) -> Unit = {}): M {
        onProgress(LoadEvent.Resolving)
        val plan = inspect(ref, task, options)
        val local = download(plan, onProgress)
        return prepare(local, onProgress)
    }

    /** The saved binding for an id, if any: the commit a revision-less load will use. */
    fun boundCommit(repoId: String): String? = bindings.read(repoId)?.commit

    /** Forget the saved binding so the next revision-less load resolves the branch again. Files stay cached. */
    fun unbind(repoId: String): Boolean = bindings.remove(repoId)

    /** Bytes of verified files under the cache root. */
    fun cacheBytes(): Long = File(root, "blobs").walkTopDown().filter { it.isFile && !it.name.endsWith(ModelStore.SIDECAR_SUFFIX) }.sumOf { it.length() }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel(CancellationException("HfModels closed"))
        active?.close()
    }

    suspend fun closeAndJoin() {
        close()
        withContext(NonCancellable) { active?.closeAndJoin() }
    }

    // ---- internals ------------------------------------------------------------------------

    private fun releaseSlot(model: PreparedModel) {
        if (active === model) active = null
    }

    private fun checkOpen() {
        if (closed.get()) throw ModelException(ErrorCode.MODEL_CLOSED, "this HfModels client is closed")
    }

    private fun isMetered(): Boolean {
        val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return cm.isActiveNetworkMetered
    }

    private fun PlannedFile.artifactRef() = ArtifactRef(path.substringAfterLast('/'), url, sha256, bytes)

    /**
     * One transfer per artifact, shared by concurrent callers. A caller that cancels stops waiting;
     * the transfer goes on for the others and is cancelled (partial kept) only when the last one leaves.
     */
    private suspend fun shared(ref: ArtifactRef, token: String?, offline: Boolean, onBytes: (Long, Long?) -> Unit): File {
        val job = downloadsLock.withLock {
            downloads[ref.sha256]?.also { it.waiters++ } ?: SharedDownload(ref).also { d ->
                d.waiters = 1
                d.deferred = scope.async { transferWithRetry(ref, token, offline, d) }
                downloads[ref.sha256] = d
            }
        }
        val listener: (Long, Long?) -> Unit = onBytes
        job.listeners += listener
        try {
            return job.deferred.await()
        } finally {
            job.listeners -= listener
            downloadsLock.withLock {
                job.waiters--
                if (job.waiters <= 0) {
                    downloads.remove(ref.sha256)
                    if (!job.deferred.isCompleted) { job.cancelled = true; job.deferred.cancel() }
                }
            }
        }
    }

    private class SharedDownload(val ref: ArtifactRef) {
        var waiters = 0
        @Volatile var cancelled = false
        lateinit var deferred: Deferred<File>
        val listeners = java.util.concurrent.CopyOnWriteArrayList<(Long, Long?) -> Unit>()
    }

    private suspend fun transferWithRetry(ref: ArtifactRef, token: String?, offline: Boolean, d: SharedDownload): File {
        var attempt = 0
        var lastAt = 0L
        var lastBytes = -1L
        while (true) {
            attempt++
            try {
                return runInterruptible(Dispatchers.IO) {
                    store.ensure(ref, log, offline = offline, token = token, onProgress = { bytes, total ->
                        val now = System.nanoTime()
                        if (bytes == total || bytes - lastBytes >= PROGRESS_BYTES || now - lastAt >= PROGRESS_NS) {
                            lastAt = now; lastBytes = bytes
                            d.listeners.forEach { it(bytes, total) }
                        }
                    }, cancel = { d.cancelled }).file
                }
            } catch (e: ModelStore.TransferInterrupted) {
                currentCoroutineContextEnsureActive()
                if (d.cancelled) throw CancellationException("download of ${ref.file} cancelled by the last waiter")
                if (attempt >= MAX_ATTEMPTS) throw ModelException(ErrorCode.NETWORK_ERROR, "${e.message}; gave up after $attempt attempts, partial kept for a later resume", retryable = true, details = mapOf("file" to ref.file, "bytes" to e.bytesSoFar.toString()), cause = e)
                val backoff = e.retryAfterSeconds?.let { it * 1000 } ?: (1000L shl (attempt - 1))
                log.w("download ${ref.file}: attempt $attempt failed (${e.message}); retrying in $backoff ms")
                delay(backoff)
            }
        }
    }

    private suspend fun currentCoroutineContextEnsureActive() = kotlinx.coroutines.currentCoroutineContext().ensureActive()

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val PROGRESS_BYTES = 1L shl 20
        const val PROGRESS_NS = 100_000_000L
    }
}

/** Construction-time knobs. [http] and [catalog] exist for tests and for apps that add a catalog. */
class HfModelsOptions(
    val hubEndpoint: String = "https://huggingface.co",
    val http: HttpSource = HttpUrlConnectionSource(),
    val catalog: Catalog? = null,
    val log: HfLog = AndroidHfLog,
)

/** The catalog shipped inside the AAR (`assets/hfmodels/catalog.json`). */
object BundledCatalog {
    const val ASSET = "hfmodels/catalog.json"

    fun load(context: Context): Catalog = try {
        context.assets.open(ASSET).use { Catalog.parse(String(it.readBytes(), Charsets.UTF_8)) }
    } catch (e: java.io.IOException) {
        Catalog("none", emptyList())
    }
}
