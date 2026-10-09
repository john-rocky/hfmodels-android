package io.github.johnrocky.hfmodels.samples.eg2search

import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The model side of the sample, kept out of the Activity so the device check runs the same code.
 *
 * [open] loads the EmbeddingGemma 2 bundle into LiteRT-LM's EmbeddingEngine, or returns [Opened.Failed] with the
 * sentence the screen shows (a missing file says which file to push where). [index] embeds the album's photos and keeps
 * their vectors in a cache file, so a later call embeds only new or changed photos; it stops between two photos when its
 * cancel says so. [searchAudio] refuses a clip that is too short or too quiet ([InputGate]), otherwise embeds it and
 * ranks the album by cosine. [close] may be called more than once.
 *
 * One caller at a time: the Activity calls it from its worker thread, the device check from its test thread.
 */
class PhotoSearch private constructor(
    private val engine: EmbeddingEngine,
    val bundle: File,
    val backends: Backends,
    /** Wall time of EmbeddingEngine(config) and initialize(). */
    val initMs: Double,
    /**
     * One second of silence through the audio encoder after init, its vector dropped (null if it failed): the first
     * audio call of a process pays a one-off setup (Galaxy S26, 2026-10-08: 216 ms for the first spoken query of a
     * launch, 102 ms for the next), and the user's first query should not.
     */
    val warmupMs: Double?,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    val isClosed: Boolean get() = closed.get()

    /**
     * The album's vectors: every .jpg / .jpeg in [albumDir] ([Album.photos]). A photo whose file name and sha256 are in
     * [cacheFile] keeps its vector unless [reindex]; the others are embedded, one computeEmbedding each, and the cache is
     * written again. [cancel] is read before each photo: a cancelled run returns at once with what it has (a photo it did
     * not reach keeps its cached vector, if there is one). [progress] gets (photos done, photos to embed) after each.
     */
    fun index(
        albumDir: File,
        cacheFile: File,
        reindex: Boolean = false,
        cancel: () -> Boolean = { false },
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): Index {
        checkOpen()
        val t0 = SystemClock.elapsedRealtimeNanos()
        val jpgs = Album.photos(albumDir)
        val shas = jpgs.map { sha256(it) }
        val cached = readCache(cacheFile)
        val vectors = HashMap<String, FloatArray>()
        for ((k, f) in jpgs.withIndex()) cached[f.name]?.let { (sha, v) -> if (sha == shas[k]) vectors[f.name] = v }
        val todo = jpgs.indices.filter { k -> reindex || jpgs[k].name !in vectors }
        val perImage = ArrayList<Double>()
        val failed = ArrayList<String>()
        var embedded = 0
        var cancelled = false
        for (k in todo) {
            if (cancel()) {
                cancelled = true
                break
            }
            val f = jpgs[k]
            try {
                val bytes = f.readBytes()
                val a = SystemClock.elapsedRealtimeNanos()
                val v = engine.computeEmbedding(listOf(InputData.Image(bytes)), OPTIONS).embedding
                perImage.add((SystemClock.elapsedRealtimeNanos() - a) / 1e6)
                vectors[f.name] = v
                embedded++
            } catch (t: Throwable) {
                // A file the runtime cannot read stays out of the album; the others go on.
                vectors.remove(f.name)
                failed.add("${f.name}: ${t.message ?: t}")
            }
            progress(embedded + failed.size, todo.size)
        }
        val photos = jpgs.indices.mapNotNull { k -> vectors[jpgs[k].name]?.let { Photo(idOf(jpgs[k]), jpgs[k], shas[k], it) } }
        val cacheError = if (embedded > 0 || failed.isNotEmpty() || photos.map { it.file.name }.toSet() != cached.keys) {
            runCatching { writeCache(cacheFile, photos) }.exceptionOrNull()?.toString()
        } else {
            null
        }
        return Index(photos, jpgs.size, todo.size, embedded, photos.size - embedded, failed, cancelled,
            (SystemClock.elapsedRealtimeNanos() - t0) / 1e6, perImage, cacheError)
    }

    /**
     * One spoken query: [pcm] is 16-bit mono PCM at [sampleRate]. A clip [InputGate] refuses never reaches the model;
     * otherwise the clip is wrapped as a WAV (the form InputData.Audio takes), embedded (embed_ms, the "audio → vector"
     * number on the screen) and the album ranked by cosine (rank_ms).
     */
    fun searchAudio(pcm: ByteArray, sampleRate: Int, album: List<Photo>, minRms: Float = InputGate.MIN_RMS): Answer {
        checkOpen()
        val seconds = pcm.size / (sampleRate * 2.0)
        val rms = Wav.rms(pcm, 0, pcm.size)
        InputGate.check(seconds, rms, minRms)?.let { return Answer.Refused(it, seconds, rms) }
        val a = SystemClock.elapsedRealtimeNanos()
        val v = engine.computeEmbedding(listOf(InputData.Audio(Wav.mono16(pcm, sampleRate))), OPTIONS).embedding
        val b = SystemClock.elapsedRealtimeNanos()
        val matches = rank(v, album)
        return Answer.Found(matches, (b - a) / 1e6, (SystemClock.elapsedRealtimeNanos() - b) / 1e6, seconds, rms, v)
    }

    /** One typed query (the recording tools' run_texts and text): [text] as it is, prefix included. */
    fun searchText(text: String, album: List<Photo>): Answer.Found {
        checkOpen()
        val a = SystemClock.elapsedRealtimeNanos()
        val v = engine.computeEmbedding(listOf(InputData.Text(text)), OPTIONS).embedding
        val b = SystemClock.elapsedRealtimeNanos()
        val matches = rank(v, album)
        return Answer.Found(matches, (b - a) / 1e6, (SystemClock.elapsedRealtimeNanos() - b) / 1e6, 0.0, 0.0, v)
    }

    /**
     * Releases the engine. A second call does nothing (EmbeddingEngine.close() itself throws IllegalStateException the
     * second time); a call while [index] or [searchAudio] runs on another thread waits for that call to end.
     */
    override fun close() {
        if (closed.compareAndSet(false, true)) engine.close()
    }

    private fun checkOpen() = check(!closed.get()) { "PhotoSearch is closed" }

    /** The result of [index]: [photos] are the searchable ones, in file-name order. */
    class Index(
        val photos: List<Photo>,
        val files: Int,
        val toEmbed: Int,
        val embedded: Int,
        val reused: Int,
        val failed: List<String>,
        val cancelled: Boolean,
        val totalMs: Double,
        val perImageMs: List<Double>,
        val cacheError: String?,
    ) {
        val perImageMedian: Double? get() = perImageMs.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
    }

    sealed interface Answer {
        /** Refused before the model: too short or too quiet. */
        class Refused(val reason: InputGate.Reason, val seconds: Double, val rms: Double) : Answer

        /** The album ranked by cosine, best first. */
        class Found(
            val matches: List<Match>,
            val embedMs: Double,
            val rankMs: Double,
            val seconds: Double,
            val rms: Double,
            val vector: FloatArray,
        ) : Answer
    }

    sealed interface Opened {
        class Ready(val search: PhotoSearch) : Opened

        /** [code] is one of [BUNDLE_MISSING], [BAD_BACKEND], [INIT_FAILED]; [text] is for the screen. */
        class Failed(val code: String, val text: String, val error: Throwable?) : Opened
    }

    companion object {
        const val MODEL_ID = "litert-community/embeddinggemma-2-740m-litert-lm"
        const val DEFAULT_BUNDLE = "embeddinggemma-2-740m.litertlm"
        const val DIM = 768
        const val BUNDLE_MISSING = "BUNDLE_MISSING"
        const val BAD_BACKEND = "BAD_BACKEND"
        const val INIT_FAILED = "INIT_FAILED"

        /** normalize = true makes the dot product the cosine; outputSize = 768, the full vector (model card). */
        val OPTIONS = EmbeddingOptions(normalize = true, outputSize = DIM)

        /**
         * Loads [bundle] on [backends] ([nativeLibraryDir]: where an NPU backend finds Qualcomm's libraries), then runs
         * the warm-up. The engine's compiled kernels go to [cacheDir], so a later load is faster. Blocking.
         */
        fun open(bundle: File, cacheDir: File, backends: Backends, nativeLibraryDir: String, maxInputLength: Int? = null): Opened {
            if (!bundle.isFile) return Opened.Failed(BUNDLE_MISSING, missingText(bundle), null)
            val config = try {
                EmbeddingEngineConfig(
                    modelPath = bundle.path,
                    backend = backendOf(backends.backbone, nativeLibraryDir),
                    visionBackend = backendOf(backends.vision, nativeLibraryDir),
                    audioBackend = backendOf(backends.audio, nativeLibraryDir),
                    cacheDir = cacheDir.apply { mkdirs() }.path,
                    maxInputLength = maxInputLength,
                )
            } catch (t: IllegalArgumentException) {
                return Opened.Failed(BAD_BACKEND, "Unknown backend: ${t.message}", t)
            }
            val t0 = SystemClock.elapsedRealtimeNanos()
            val engine = EmbeddingEngine(config)
            try {
                engine.initialize()
            } catch (t: Throwable) {
                return Opened.Failed(INIT_FAILED, "The model did not load (${backends.line()}):\n${t.message ?: t}", t)
            }
            val initMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
            val warmupMs = runCatching {
                val wav = Wav.mono16(ByteArray(Mic.RATE * 2), Mic.RATE)
                val a = SystemClock.elapsedRealtimeNanos()
                engine.computeEmbedding(listOf(InputData.Audio(wav)), OPTIONS)
                (SystemClock.elapsedRealtimeNanos() - a) / 1e6
            }.getOrNull()
            return Opened.Ready(PhotoSearch(engine, bundle, backends, initMs, warmupMs))
        }

        /** The screen's text for a missing model file: which file, where from, to which directory. */
        fun missingText(bundle: File): String {
            val dir = (bundle.parentFile?.path ?: "").replace(Regex("^/storage/emulated/0/"), "/sdcard/")
            val size = if (bundle.name == DEFAULT_BUNDLE) " (485 MB)" else ""
            return "Model file missing: ${bundle.name}$size\n\n" +
                "Push it from a computer:\n" +
                "hf download $MODEL_ID ${bundle.name} --local-dir .\n" +
                "adb push ${bundle.name} $dir/\n\n" +
                "Then come back to this screen, or tap Load model."
        }

        private fun backendOf(name: String, nativeLibraryDir: String): Backend = when (name) {
            "cpu" -> Backend.CPU()
            "gpu" -> Backend.GPU()
            "npu" -> Backend.NPU(nativeLibraryDir)
            else -> throw IllegalArgumentException("$name (cpu, gpu or npu)")
        }

        /** The cache file of one bundle and one photo backend: vectors from the GPU and the CPU differ a little. */
        fun indexFile(filesDir: File, bundleName: String, visionBackend: String) = File(filesDir, "index_${bundleName}_$visionBackend.json")

        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /** File name -> (sha256, vector); empty when the file is missing or unreadable. */
        private fun readCache(f: File): Map<String, Pair<String, FloatArray>> = runCatching {
            val items = JSONObject(f.readText()).getJSONArray("items")
            (0 until items.length()).associate { k ->
                val o = items.getJSONObject(k)
                val v = o.getJSONArray("emb")
                o.getString("file") to (o.getString("sha256") to FloatArray(v.length()) { v.getDouble(it).toFloat() })
            }
        }.getOrDefault(emptyMap())

        private fun writeCache(f: File, photos: List<Photo>) {
            val items = JSONArray()
            for (p in photos) {
                items.put(JSONObject().put("file", p.file.name).put("sha256", p.sha256).put("emb", JSONArray(p.emb.map { it.toDouble() })))
            }
            val tmp = File(f.path + ".tmp")
            tmp.writeText(JSONObject().put("dim", DIM).put("items", items).toString())
            check(tmp.renameTo(f)) { "could not write ${f.path}" }
        }
    }
}

/** One photo of the album and its vector. [id] is the part of the file name before the first "_". */
class Photo(val id: String, val file: File, val sha256: String, val emb: FloatArray)

class Match(val photo: Photo, val cos: Float)

/**
 * The backend of each stage. A clip goes through the audio encoder ([audio]) and then the backbone ([backbone]); a photo
 * through the photo encoder ([vision]) and then the same backbone. "audio → vector" on the screen is both stages.
 */
data class Backends(val backbone: String = "gpu", val vision: String = backbone, val audio: String = "cpu") {
    /** The screen's third line: stages on one backend share a phrase ("audio encoder on CPU · backbone and photos on
     *  GPU"), or "all on GPU". */
    fun line(): String {
        val parts = listOf("audio encoder" to audio, "backbone" to backbone, "photos" to vision)
        val byBackend = parts.groupBy({ it.second }, { it.first })
        if (byBackend.size == 1) return "all on ${backbone.uppercase(Locale.US)}"
        return byBackend.entries.joinToString(" · ") { (b, names) -> "${names.joinToString(" and ")} on ${b.uppercase(Locale.US)}" }
    }
}

/**
 * Refuses a clip before the model sees it. An embedding search always returns a top-1: on a Galaxy S26 a silent clip and
 * a 50 ms press came back as photos at cos 0.63 and 0.68, around the lowest correct answer of 20 spoken queries (0.64,
 * 2026-10-08), so "nothing heard" is decided here, by length and loudness, never by a cosine.
 */
object InputGate {
    const val MIN_SECONDS = 0.5

    /**
     * The loudness floor: RMS of the whole clip, 1.0 = full scale. 0.005 (-46 dBFS) is above the louder of two silent
     * recordings made through this app's mic path on a Galaxy S26 (RMS 0.0034 and 0.0009, 2026-10-08). A soft voice at
     * arm's length can fall under it; the screen then asks to speak closer. --ef min_rms changes it.
     */
    const val MIN_RMS = 0.005f

    enum class Reason(val word: String) { SHORT("short"), QUIET("quiet") }

    fun check(seconds: Double, rms: Double, minRms: Float = MIN_RMS, minSeconds: Double = MIN_SECONDS): Reason? = when {
        seconds < minSeconds -> Reason.SHORT
        rms < minRms -> Reason.QUIET
        else -> null
    }
}

/** The album by cosine, best first. Both sides are unit vectors (normalize = true): the dot product is the cosine. */
fun rank(v: FloatArray, album: List<Photo>): List<Match> = album.map { p ->
    var s = 0f
    for (k in 0 until minOf(v.size, p.emb.size)) s += v[k] * p.emb[k]
    Match(p, s)
}.sortedByDescending { it.cos }

fun idOf(f: File) = f.name.substringBefore("_").substringBeforeLast(".")
