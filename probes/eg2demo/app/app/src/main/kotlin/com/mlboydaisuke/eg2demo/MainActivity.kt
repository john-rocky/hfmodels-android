package com.mlboydaisuke.eg2demo

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.Modality
import com.google.ai.edge.litertlm.ModelInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * EmbeddingGemma 2 740M on the phone: hold the button, say one sentence, and the photo of the album that matches comes
 * up first. The spoken clip (16 kHz mono PCM16 wrapped as WAV) and every photo are embedded by the same model into one
 * 768-d space (normalize = true, outputSize = 768; no prefix on audio or images, model card) and ranked by cosine.
 * Nothing is transcribed.
 *
 * Files (the external files dir, `files/` below, pushed with adb; the app creates the three dirs at start):
 *   files/<bundle>                   default embeddinggemma-2-740m.litertlm (--es bundle <name>)
 *   files/album/a<NN>_<slug>.jpg     the album; the id is the part before the first "_"
 *   files/queries/<id>.wav, queries.json  the scored clips and their gold photos (K/fixtures/queries.json)
 *   files/mic/<epoch>.wav            every mic recording, so a take's voice can be scored again on the Mac
 *   files/index_<bundle>_<vision>.json  the album embeddings, reused while the file list and sha256 are the same
 *   files/Documents/eg2-demo-<epoch>.json  the run record
 * Launch extras: --es backend gpu|cpu|npu (gpu), --es vision_backend (= backend), --es audio_backend (cpu),
 *   --ei max_input_length, --es bundle, --ez reindex true (embed the album again instead of reading the cache: the
 *   footer then shows this launch's index time), --ef min_rms (the mic's loudness floor, MIC_MIN_RMS);
 *   after READY: --es run_queries all|<comma list of wav names> [--ei gap_ms 2500] (embedded, not played),
 *   --es run_texts all [--es text_prefix card|devsite|none|all|<literal>], --es text "<sentence>",
 *   --ez autoplay true --es query <wav name>[,<wav name>...] [--ei delay_ms 1500] [--ei gap_ms 2500] (each clip played
 *   through the speaker first, the pill says "audio file": the fallback take when nobody speaks to the phone).
 * Log tag Eg2Demo: RUN_JSON, BUNDLE, MODEL_INFO, ENGINE_READY, INDEX_DONE, WARMUP, READY, LAYOUT, PILL, QUERY_START,
 * PLAY_START, PLAY_HEAD, MIC_RECORDED, MIC_REJECTED, RESULT, RESULT_LAYOUT, DONE, ERROR (gate.sh and take.sh wait on
 * them; make_media.sh maps the phone clock onto a take's frames with the PILL lines).
 * Every number on screen is measured by this app: "audio → vector" is the wall time of computeEmbedding for the clip,
 * "indexed in" the wall time of this launch's album index.
 */
class MainActivity : Activity() {
    private class Photo(val id: String, val file: File, val sha256: String, val emb: FloatArray, val thumb: Bitmap?)
    private class Ranked(val photo: Photo, val cos: Float)

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    @Volatile private var engine: EmbeddingEngine? = null
    @Volatile private var recording = false
    private var track: AudioTrack? = null
    private var busy = true
    private var ready = false
    private var album: List<Photo> = emptyList()
    private var golds: Map<String, String?> = emptyMap()      // wav name -> gold album id (null = decoy)
    private var sentences: List<Pair<String, String>> = emptyList()   // query id -> sentence (wording 1)
    private var backendName = "gpu"
    private var visionName = "gpu"
    private var audioName = "cpu"
    private var bundleName = DEFAULT_BUNDLE
    private var reindex = false
    private var minRms = MIC_MIN_RMS
    private var pillColor = 0
    private var resultLayoutLogged = false

    // Run record: touched on the worker thread only.
    private val startEpochMs = System.currentTimeMillis()
    private val report = JSONObject()
    private val rows = JSONArray()
    private val micRejected = JSONArray()
    private lateinit var reportFile: File

    private lateinit var files: File
    private lateinit var frame: LinearLayout
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var backendLine: TextView
    private lateinit var pill: TextView
    private lateinit var gridScroll: ScrollView
    private lateinit var grid: LinearLayout
    private lateinit var result: LinearLayout
    private lateinit var bigPhoto: ImageView
    private lateinit var queryLine: TextView
    private lateinit var msChip: TextView
    private lateinit var cosChip: TextView
    private lateinit var smallRow: LinearLayout
    private lateinit var level: LevelBar
    private lateinit var status: TextView
    private lateinit var micButton: TextView
    private lateinit var footer: TextView

    private val deviceName: String
        get() = if (Build.MODEL.startsWith("SM-S94")) "Galaxy S26" else Build.MODEL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Show over the lock screen and keep the display on: a locked phone parks a hidden activity's process in the
        // background cpuset (little cores), which runs the same model about 10x slower.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        window.insetsController?.let {
            it.hide(WindowInsets.Type.navigationBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        backendName = (intent.getStringExtra("backend") ?: "gpu").lowercase(Locale.US)
        visionName = (intent.getStringExtra("vision_backend") ?: backendName).lowercase(Locale.US)
        audioName = (intent.getStringExtra("audio_backend") ?: "cpu").lowercase(Locale.US)
        bundleName = intent.getStringExtra("bundle") ?: DEFAULT_BUNDLE
        reindex = intent.getBooleanExtra("reindex", false)
        minRms = intent.getFloatExtra("min_rms", MIC_MIN_RMS)
        files = requireNotNull(getExternalFilesDir(null)) { "no external files dir" }
        for (d in listOf("album", "queries", "mic")) File(files, d).mkdirs()
        subtitle.text = subtitleText()
        setPill("LOADING", C_IDLE)
        setMicEnabled(false)
        worker.execute { prepare() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!ready) return
        if (busy) {
            Log.i(TAG, "IGNORED busy source=intent")
            return
        }
        runIntent(intent)
    }

    override fun onDestroy() {
        recording = false
        track?.release()
        track = null
        // One close: a second EmbeddingEngine.close() throws IllegalStateException, and a failed init leaves no engine.
        worker.execute {
            if (closed.compareAndSet(false, true)) {
                engine?.let { e -> runCatching { e.close() }.onFailure { Log.w(TAG, "close failed", it) } }
                engine = null
            }
        }
        worker.shutdown()
        super.onDestroy()
    }

    // ---- engine and index (worker thread) ---------------------------------------------------------------------

    private fun backendOf(name: String): Backend = when (name) {
        "cpu" -> Backend.CPU()
        "gpu" -> Backend.GPU()
        "npu" -> Backend.NPU(applicationInfo.nativeLibraryDir)
        else -> throw IllegalArgumentException("unknown backend $name (cpu, gpu or npu)")
    }

    private fun prepare() {
        val docs = File(files, "Documents").apply { mkdirs() }
        reportFile = File(docs, "eg2-demo-$startEpochMs.json")
        Log.i(TAG, "RUN_JSON path=${reportFile.path}")
        val bundle = File(files, bundleName)
        val maxInputLength = intent.getIntExtra("max_input_length", -1).takeIf { it > 0 }
        report.put("app", packageName).put("started_epoch_ms", startEpochMs)
            .put("device", JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                .put("device", Build.DEVICE).put("soc_manufacturer", Build.SOC_MANUFACTURER).put("soc_model", Build.SOC_MODEL)
                .put("android", Build.VERSION.RELEASE).put("sdk_int", Build.VERSION.SDK_INT)
                .put("build", Build.DISPLAY).put("shown_as", deviceName))
            .put("runtime", JSONObject().put("litertlm_android", BuildConfig.LITERTLM_VERSION).put("backend", backendName)
                .put("vision_backend", visionName).put("audio_backend", audioName)
                .put("max_input_length", maxInputLength ?: JSONObject.NULL)
                .put("native_library_dir", applicationInfo.nativeLibraryDir)
                .put("options", "EmbeddingOptions(normalize = true, outputSize = 768)")
                .put("reindex", reindex).put("mic_min_rms", minRms.toDouble()).put("mic_min_s", MIC_MIN_S))
            .put("airplane_mode_at_start", airplaneMode())
            .put("thermal_at_start", thermal())
            .put("rows", rows)
            .put("mic_rejected", micRejected)
        if (!bundle.isFile) {
            Log.e(TAG, "ERROR bundle missing ${bundle.path}")
            report.put("error", "bundle missing ${bundle.path}")
            writeReport()
            ui { setPill("bundle missing", C_LIVE); status.text = bundle.path }
            return
        }
        val bundleBytes = bundle.length()
        val s0 = SystemClock.elapsedRealtimeNanos()
        val bundleSha = sha256(bundle)
        val shaMs = (SystemClock.elapsedRealtimeNanos() - s0) / 1e6
        report.put("bundle", JSONObject().put("name", bundleName).put("bytes", bundleBytes)
            .put("sha256_12", bundleSha.take(12)).put("sha256_ms", shaMs))
        Log.i(TAG, "BUNDLE name=$bundleName bytes=$bundleBytes sha256_12=${bundleSha.take(12)} sha_ms=${fmt1(shaMs)}")
        report.put("model_info", modelInfo(bundle))
        ui { setPill("LOADING MODEL", C_IDLE) }
        val e: EmbeddingEngine
        val initMs: Double
        try {
            val cache = File(cacheDir, "eg2").apply { mkdirs() }
            val config = EmbeddingEngineConfig(
                modelPath = bundle.path,
                backend = backendOf(backendName),
                visionBackend = backendOf(visionName),
                audioBackend = backendOf(audioName),
                cacheDir = cache.path,
                maxInputLength = maxInputLength,
            )
            val t0 = SystemClock.elapsedRealtimeNanos()
            e = EmbeddingEngine(config)
            e.initialize()
            initMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
        } catch (t: Throwable) {
            Log.e(TAG, "ERROR init ${t.message ?: t}", t)
            report.put("error", "init: $t").put("thermal_at_error", thermal())
            writeReport()
            ui { setPill("ERROR", C_LIVE); status.text = "init failed: ${t.message ?: t}" }
            return
        }
        if (closed.get()) { e.close(); return }
        engine = e
        report.put("engine", JSONObject().put("init_ms", initMs))
        Log.i(TAG, "ENGINE_READY init_ms=${fmt1(initMs)} backend=$backendName vision_backend=$visionName " +
            "audio_backend=$audioName bundle=$bundleName")
        ui { setBackendLine(backendLineText()) }
        buildIndex(e)
        loadQueries()
        warmUp(e)
        writeReport()
        ui {
            ready = true
            showGrid()
            setPill("READY", C_IDLE)
            setIdle()
            status.text = "Hold the button and say what is in the photo"
            Log.i(TAG, "READY album=${album.size} queries=${golds.size}")
            // After a layout pass with the READY screen (grid shown, footer text set).
            main.postDelayed({ if (!isDestroyed) recordLayout() }, 300)
            runIntent(intent)
        }
    }

    private fun modelInfo(bundle: File): JSONObject = runCatching {
        ModelInfo.from(bundle.path).use { info ->
            val o = JSONObject().put("type", info.modelType.name).put("max_context_tokens", info.maxContextTokens())
                .put("min_runtime_version", info.minRuntimeVersion() ?: JSONObject.NULL)
            val m = info.inputModalities()
            o.put("modalities", JSONObject().put("text", m.text).put("vision", m.vision).put("audio", m.audio).put("video", m.video))
            for (mod in listOf(Modality.TEXT, Modality.VISION, Modality.AUDIO)) {
                o.put("backends_${mod.name.lowercase(Locale.US)}", JSONArray(info.supportedBackends(mod).map { it.name }))
                info.socName(mod)?.let { o.put("soc_${mod.name.lowercase(Locale.US)}", it) }
            }
            if (info is ModelInfo.Embedding) {
                o.put("dimension", info.dimension() ?: JSONObject.NULL)
                    .put("signatures", JSONArray(info.signatureSelection()?.toList() ?: emptyList<Int>()))
                    .put("vision_signatures", JSONArray(info.visionSignatureSelection()?.toList() ?: emptyList<Int>()))
            }
            Log.i(TAG, "MODEL_INFO $o")
            o
        }
    }.getOrElse { t ->
        Log.w(TAG, "MODEL_INFO failed: $t")
        JSONObject().put("error", t.toString())
    }

    /** Embeds the .jpg files in files/album once per bundle and vision backend; reuses the index_<bundle>_<vision>.json
     *  cache while the file names and sha256 are the same, unless the launch said --ez reindex true. */
    private fun buildIndex(e: EmbeddingEngine) {
        val jpgs = File(files, "album").listFiles { f -> f.isFile && f.name.lowercase(Locale.US).endsWith(".jpg") }
            ?.sortedBy { it.name }.orEmpty()
        val shas = jpgs.map { sha256(it) }
        val indexFile = File(files, "index_${bundleName}_$visionName.json")
        val cached = if (reindex) null else runCatching { JSONObject(indexFile.readText()) }.getOrNull()?.takeIf { c ->
            val items = c.getJSONArray("items")
            items.length() == jpgs.size && (0 until items.length()).all { k ->
                items.getJSONObject(k).getString("file") == jpgs[k].name && items.getJSONObject(k).getString("sha256") == shas[k]
            }
        }
        val photos = ArrayList<Photo>()
        val perImage = ArrayList<Double>()
        val t0 = SystemClock.elapsedRealtimeNanos()
        if (cached != null) {
            val items = cached.getJSONArray("items")
            for (k in jpgs.indices) {
                val v = items.getJSONObject(k).getJSONArray("emb")
                photos.add(Photo(idOf(jpgs[k]), jpgs[k], shas[k], FloatArray(v.length()) { v.getDouble(it).toFloat() }, thumb(jpgs[k])))
            }
        } else {
            val items = JSONArray()
            for ((k, f) in jpgs.withIndex()) {
                ui { setPill("INDEXING ${k + 1}/${jpgs.size}", C_WORK) }
                try {
                    val bytes = f.readBytes()
                    val a = SystemClock.elapsedRealtimeNanos()
                    val v = e.computeEmbedding(listOf(InputData.Image(bytes)), OPTIONS).embedding
                    val ms = (SystemClock.elapsedRealtimeNanos() - a) / 1e6
                    perImage.add(ms)
                    photos.add(Photo(idOf(f), f, shas[k], v, thumb(f)))
                    items.put(JSONObject().put("file", f.name).put("sha256", shas[k]).put("ms", ms)
                        .put("emb", JSONArray(v.map { it.toDouble() })))
                } catch (t: Throwable) {
                    Log.e(TAG, "ERROR index ${f.name} ${t.message ?: t}", t)
                }
            }
            runCatching {
                indexFile.writeText(JSONObject().put("bundle", bundleName).put("vision_backend", visionName)
                    .put("dim", DIM).put("items", items).toString())
            }.onFailure { Log.w(TAG, "index cache not written: $it") }
        }
        val totalMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
        album = photos
        val median = if (perImage.isEmpty()) -1.0 else perImage.sorted()[perImage.size / 2]
        report.put("index", JSONObject().put("n", photos.size).put("files", jpgs.size).put("total_ms", totalMs)
            .put("per_image_ms_median", if (median < 0) JSONObject.NULL else median).put("cached", cached != null)
            .put("reindex", reindex).put("per_image_ms", JSONArray(perImage)).put("embed_ms_sum", perImage.sum())
            .put("file", indexFile.name))
        Log.i(TAG, "INDEX_DONE n=${photos.size} total_ms=${fmt1(totalMs)} " +
            "per_image_ms_median=${if (median < 0) "-" else fmt1(median)} cached=${cached != null} reindex=$reindex")
        // The footer shows an index time only when this launch embedded the whole album.
        val shownMs = if (cached == null && photos.isNotEmpty() && photos.size == jpgs.size) totalMs else null
        ui { buildGrid(shownMs) }
    }

    /** One second of silence through the audio encoder before READY, its embedding dropped: the first audio call pays a
     *  one-off setup (round 2: 216 ms for the first spoken query of a launch, 102 ms for the next), so the first query
     *  the user makes does not. */
    private fun warmUp(e: EmbeddingEngine) {
        val o = JSONObject().put("seconds", 1.0).put("backend", audioName)
        try {
            val wav = Wav.mono16(ByteArray(MIC_RATE * 2), MIC_RATE)
            val a = SystemClock.elapsedRealtimeNanos()
            e.computeEmbedding(listOf(InputData.Audio(wav)), OPTIONS)
            val ms = (SystemClock.elapsedRealtimeNanos() - a) / 1e6
            o.put("ms", ms)
            Log.i(TAG, "WARMUP ms=${fmt1(ms)} seconds=1.00 audio_backend=$audioName")
        } catch (t: Throwable) {
            o.put("error", t.toString())
            Log.w(TAG, "WARMUP failed ${t.message ?: t}")
        }
        report.put("warmup", o)
    }

    private fun loadQueries() {
        val f = File(files, "queries/queries.json")
        if (!f.isFile) return
        runCatching {
            val doc = JSONObject(f.readText())
            val g = HashMap<String, String?>()
            val s = LinkedHashMap<String, String>()
            for (key in listOf("queries", "decoys")) {
                val arr = doc.optJSONArray(key) ?: continue
                for (k in 0 until arr.length()) {
                    val q = arr.getJSONObject(k)
                    g[q.getString("file")] = if (q.isNull("gold")) null else q.getString("gold")
                    if (key == "queries" && q.optInt("wording", 1) == 1) s.putIfAbsent(q.getString("query"), q.getString("text"))
                }
            }
            golds = g
            sentences = s.entries.map { it.key to it.value }
        }.onFailure { Log.w(TAG, "queries.json not read: $it") }
    }

    // ---- queries ----------------------------------------------------------------------------------------------

    private sealed class Job {
        class AudioFile(val name: String, val play: Boolean) : Job()
        class Text(val id: String, val text: String, val prefixName: String, val prefix: String) : Job()
    }

    /** The intent's work after READY: a batch (run_queries / run_texts / text) or one played clip (autoplay). */
    private fun runIntent(i: Intent) {
        val jobs = ArrayList<Job>()
        i.getStringExtra("run_queries")?.let { spec ->
            val all = File(files, "queries").listFiles { f -> f.name.endsWith(".wav") }?.map { it.name }?.sorted().orEmpty()
            val names = if (spec == "all") all else spec.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            names.forEach { jobs.add(Job.AudioFile(it, play = false)) }
        }
        val prefixSpec = i.getStringExtra("text_prefix") ?: "none"
        val prefixes: List<Pair<String, String>> = when (prefixSpec) {
            "all" -> PREFIXES.toList()
            in PREFIXES -> listOf(prefixSpec to PREFIXES.getValue(prefixSpec))
            else -> listOf("literal" to prefixSpec)
        }
        if (i.getStringExtra("run_texts") == "all") {
            for ((qid, text) in sentences) for ((pn, p) in prefixes) jobs.add(Job.Text("${qid}_t_$pn", text, pn, p))
        }
        i.getStringExtra("text")?.let { t -> for ((pn, p) in prefixes) jobs.add(Job.Text("text_$pn", t, pn, p)) }
        val auto = i.getBooleanExtra("autoplay", false)
        if (auto) i.getStringExtra("query")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.forEach { jobs.add(Job.AudioFile(it, play = true)) }
        if (jobs.isEmpty()) return
        // Each intent is handled once: a rotation or a resume does not run it again.
        setIntent(Intent(i).apply { removeExtra("run_queries"); removeExtra("run_texts"); removeExtra("text"); removeExtra("autoplay") })
        val delay = if (auto) i.getIntExtra("delay_ms", 1500).toLong() else 0L
        val gap = i.getIntExtra("gap_ms", 2500).toLong()
        busy = true
        setMicEnabled(false)
        Log.i(TAG, "PLAN jobs=${jobs.size} delay_ms=$delay gap_ms=$gap")
        worker.execute { report.put("plan", JSONObject().put("jobs", jobs.size).put("delay_ms", delay).put("gap_ms", gap)) }
        main.postDelayed({ runJobs(jobs, 0, gap) }, delay)
    }

    private fun runJobs(jobs: List<Job>, k: Int, gap: Long) {
        if (isDestroyed) return
        if (k >= jobs.size) {
            worker.execute {
                report.put("done_epoch_ms", System.currentTimeMillis()).put("thermal_at_done", thermal())
                writeReport()
                Log.i(TAG, "DONE json=${reportFile.path}")
            }
            setIdle()
            return
        }
        val next: () -> Unit = { main.postDelayed({ runJobs(jobs, k + 1, gap) }, gap) }
        when (val j = jobs[k]) {
            is Job.AudioFile -> {
                val f = File(files, "queries/${j.name}")
                if (!f.isFile) {
                    Log.e(TAG, "ERROR query ${j.name} missing ${f.path}")
                    next()
                    return
                }
                val id = j.name.removeSuffix(".wav")
                val gold = golds[j.name]
                if (j.play) playThenEmbed(f, id, gold) { next() } else audioQuery(f.readBytes(), id, "file", gold, f.path, next)
            }
            is Job.Text -> textQuery(j, next)
        }
    }

    /** One clip from a file, played through the speaker (pill "audio file") and then embedded: the fallback take. */
    private fun playThenEmbed(f: File, id: String, gold: String?, onDone: () -> Unit) {
        val pcm = runCatching { Wav.read(f) }.getOrElse {
            Log.e(TAG, "ERROR query $id unreadable ${it.message}")
            onDone()
            return
        }
        try {
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(pcm.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.data.size)
                .build()
            check(t.write(pcm.data, 0, pcm.data.size) == pcm.data.size) { "AudioTrack took a partial buffer" }
            track = t
            val frames = pcm.data.size / 2
            val startEpoch = System.currentTimeMillis()
            Log.i(TAG, "QUERY_START epoch_ms=$startEpoch id=$id source=file play=1 seconds=${fmt2(pcm.seconds)}")
            setPill("● audio file", C_LIVE)
            status.text = "Listening… (audio file ${f.name})"
            // The pill turned red in this UI pass; make_media.sh lays the clip into the take from this time.
            val playEpoch = System.currentTimeMillis()
            Log.i(TAG, "PLAY_START epoch_ms=$playEpoch id=$id")
            t.play()
            val t0 = SystemClock.elapsedRealtime()
            var headEpoch = 0L
            val poll = object : Runnable {
                override fun run() {
                    if (track !== t) return
                    val head = t.playbackHeadPosition
                    if (headEpoch == 0L && head > 0) {
                        headEpoch = System.currentTimeMillis()
                        Log.i(TAG, "PLAY_HEAD epoch_ms=$headEpoch id=$id frames=$head")
                    }
                    val at = head * 2
                    level.level = (Wav.rms(pcm.data, at - 1600, at + 1600) * 4).toFloat().coerceIn(0f, 1f)
                    val elapsed = SystemClock.elapsedRealtime() - t0
                    if (head >= frames - pcm.sampleRate / 50 || elapsed > pcm.seconds * 1000 + 3000) {
                        t.release()
                        track = null
                        level.level = 0f
                        val extra = mapOf<String, Any>("play_start_epoch_ms" to playEpoch,
                            "play_head_epoch_ms" to (if (headEpoch > 0) headEpoch else JSONObject.NULL))
                        audioQuery(Wav.mono16(pcm.data, pcm.sampleRate), id, "file", gold, f.path, onDone, startEpoch,
                            played = true, extra = extra)
                    } else {
                        main.postDelayed(this, 33)
                    }
                }
            }
            main.post(poll)
        } catch (t: Throwable) {
            Log.e(TAG, "ERROR query $id playback ${t.message ?: t}", t)
            track?.release()
            track = null
            onDone()
        }
    }

    /**
     * One spoken query: [wav] is a whole WAV file in memory. Embeds it (the wall time is embed_ms), ranks the album by
     * cosine (rank_ms), shows the top photo and logs the RESULT line.
     */
    private fun audioQuery(wav: ByteArray, id: String, source: String, gold: String?, path: String, onDone: () -> Unit,
                           startEpoch: Long = System.currentTimeMillis(), played: Boolean = false,
                           extra: Map<String, Any> = emptyMap()) {
        val e = engine ?: return onDone()
        if (!played) Log.i(TAG, "QUERY_START epoch_ms=$startEpoch id=$id source=$source play=0")
        setPill("EMBEDDING", C_WORK)
        status.text = "Embedding audio…"
        val seconds = runCatching { (wav.size - 44).toDouble() / (MIC_RATE * 2) }.getOrDefault(0.0)
        worker.execute {
            val row = JSONObject().put("kind", "audio").put("id", id).put("source", source).put("path", path)
                .put("gold", gold ?: JSONObject.NULL).put("query_start_epoch_ms", startEpoch).put("seconds", seconds)
                .put("thermal_before", thermal())
            for ((k, v) in extra) row.put(k, v)
            try {
                val a = SystemClock.elapsedRealtimeNanos()
                val v = e.computeEmbedding(listOf(InputData.Audio(wav)), OPTIONS).embedding
                val b = SystemClock.elapsedRealtimeNanos()
                val ranked = rank(v)
                val c = SystemClock.elapsedRealtimeNanos()
                finish(row, ranked, (b - a) / 1e6, (c - b) / 1e6, gold, v)
                ui { showResult(ranked, "audio", (b - a) / 1e6, if (source == "mic") "mic · ${fmt1(seconds)} s" else "audio file · $id · ${fmt1(seconds)} s"); onDone() }
            } catch (t: Throwable) {
                Log.e(TAG, "ERROR query $id ${t.message ?: t}", t)
                rows.put(row.put("error", t.toString()))
                writeReport()
                ui { setPill("ERROR", C_LIVE); status.text = t.message ?: t.toString(); onDone() }
            }
        }
    }

    private fun textQuery(j: Job.Text, onDone: () -> Unit) {
        val e = engine ?: return onDone()
        val startEpoch = System.currentTimeMillis()
        Log.i(TAG, "QUERY_START epoch_ms=$startEpoch id=${j.id} source=text prefix=${j.prefixName}")
        setPill("EMBEDDING", C_WORK)
        status.text = "Embedding text…"
        val gold = golds["${j.id.substringBefore("_t_")}_a.wav"]
        worker.execute {
            val row = JSONObject().put("kind", "text").put("id", j.id).put("text", j.text).put("prefix", j.prefixName)
                .put("gold", gold ?: JSONObject.NULL).put("query_start_epoch_ms", startEpoch).put("thermal_before", thermal())
            try {
                val a = SystemClock.elapsedRealtimeNanos()
                val v = e.computeEmbedding(listOf(InputData.Text(j.prefix + j.text.trim())), OPTIONS).embedding
                val b = SystemClock.elapsedRealtimeNanos()
                val ranked = rank(v)
                val c = SystemClock.elapsedRealtimeNanos()
                finish(row, ranked, (b - a) / 1e6, (c - b) / 1e6, gold, v)
                ui { showResult(ranked, "text", (b - a) / 1e6, "“${j.text}”"); onDone() }
            } catch (t: Throwable) {
                Log.e(TAG, "ERROR query ${j.id} ${t.message ?: t}", t)
                rows.put(row.put("error", t.toString()))
                writeReport()
                ui { setPill("ERROR", C_LIVE); status.text = t.message ?: t.toString(); onDone() }
            }
        }
    }

    private fun rank(v: FloatArray): List<Ranked> {
        // Both sides are unit vectors (normalize = true): the dot product is the cosine.
        return album.map { p ->
            var s = 0f
            for (k in 0 until minOf(v.size, p.emb.size)) s += v[k] * p.emb[k]
            Ranked(p, s)
        }.sortedByDescending { it.cos }
    }

    /** The RESULT line and the run JSON row (worker thread). */
    private fun finish(row: JSONObject, ranked: List<Ranked>, embedMs: Double, rankMs: Double, gold: String?, v: FloatArray) {
        val top3 = ranked.take(3)
        val goldRank = gold?.let { g -> ranked.indexOfFirst { it.photo.id == g }.takeIf { it >= 0 }?.plus(1) }
        var norm = 0.0
        for (x in v) norm += x.toDouble() * x
        row.put("embed_ms", embedMs).put("rank_ms", rankMs).put("dim", v.size).put("norm", Math.sqrt(norm))
            .put("top1", top3.firstOrNull()?.photo?.id ?: JSONObject.NULL)
            .put("top1_cos", top3.firstOrNull()?.cos?.toDouble() ?: JSONObject.NULL)
            .put("top3", JSONArray(top3.map { JSONObject().put("id", it.photo.id).put("cos", it.cos.toDouble()) }))
            .put("gold_rank", goldRank ?: JSONObject.NULL)
            .put("hit1", if (gold == null) JSONObject.NULL else if (goldRank == 1) 1 else 0)
            .put("hit3", if (gold == null) JSONObject.NULL else if (goldRank != null && goldRank <= 3) 1 else 0)
            .put("thermal_after", thermal())
        rows.put(row)
        writeReport()
        val h1 = if (gold == null) "-" else if (goldRank == 1) "1" else "0"
        val h3 = if (gold == null) "-" else if (goldRank != null && goldRank <= 3) "1" else "0"
        Log.i(TAG, "RESULT kind=${row.getString("kind")} id=${row.getString("id")} " +
            "seconds=${if (row.has("seconds")) fmt2(row.getDouble("seconds")) else "-"} embed_ms=${fmt1(embedMs)} " +
            "rank_ms=${fmt2(rankMs)} top1=${top3.firstOrNull()?.photo?.id ?: "-"} " +
            "top1_cos=${top3.firstOrNull()?.let { fmt4(it.cos.toDouble()) } ?: "-"} " +
            "top3=${top3.joinToString(",") { "${it.photo.id}:${fmt4(it.cos.toDouble())}" }} gold=${gold ?: "-"} hit1=$h1 hit3=$h3")
    }

    // ---- mic --------------------------------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun onMicTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!ready || busy) {
                    Log.i(TAG, "IGNORED busy source=mic ready=$ready")
                    return true
                }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "MIC_PERMISSION missing")
                    setPill("mic permission", C_LIVE)
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
                    return true
                }
                startRecording()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> recording = false
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        val ok = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "MIC_PERMISSION granted=$ok")
        if (ready && !busy) setPill(if (ok) "READY" else "mic permission", if (ok) C_IDLE else C_LIVE)
    }

    private fun startRecording() {
        busy = true
        recording = true
        val startEpoch = System.currentTimeMillis()
        Log.i(TAG, "QUERY_START epoch_ms=$startEpoch id=mic-$startEpoch source=mic play=0")
        setPill("● LISTENING", C_LIVE)
        status.text = "Listening…"
        micButton.text = "Release to search"
        Thread {
            val pcm = ByteArrayOutputStream()
            var error: Throwable? = null
            try {
                val minBuf = AudioRecord.getMinBufferSize(MIC_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, MIC_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, MIC_RATE))
                val buf = ByteArray(MIC_RATE / 20 * 2)   // 50 ms
                val maxBytes = MIC_RATE * 2 * MIC_MAX_S
                rec.startRecording()
                try {
                    while (recording && pcm.size() < maxBytes) {
                        val n = rec.read(buf, 0, minOf(buf.size, maxBytes - pcm.size()))
                        if (n < 0) break
                        pcm.write(buf, 0, n)
                        val lv = (Wav.rms(buf, 0, n) * 4).toFloat().coerceIn(0f, 1f)
                        ui { level.level = lv }
                    }
                } finally {
                    rec.stop()
                    rec.release()
                }
            } catch (t: Throwable) {
                error = t
                Log.e(TAG, "ERROR mic ${t.message ?: t}", t)
            }
            recording = false
            val bytes = pcm.toByteArray()
            val err = error
            val seconds = bytes.size / (MIC_RATE * 2.0)
            val rms = Wav.rms(bytes, 0, bytes.size)
            ui {
                micButton.text = "Hold to talk"
                level.level = 0f
                // Every recording with samples is kept, a rejected one too, so its loudness can be measured on the Mac.
                val out = File(files, "mic/$startEpoch.wav")
                if (bytes.isNotEmpty()) {
                    runCatching { out.writeBytes(Wav.mono16(bytes, MIC_RATE)) }.onFailure { Log.w(TAG, "mic wav not saved: $it") }
                }
                // A clip that is too short or carries no voice is never handed to the model: either one comes back as a
                // confident-looking wrong photo (round 2: a 0.05 s press and 1.35 s of silence gave cos 0.68 and 0.63).
                val reason = when {
                    err != null -> "error"
                    seconds < MIC_MIN_S -> "short"
                    rms < minRms -> "quiet"
                    else -> null
                }
                if (reason != null) {
                    Log.w(TAG, "MIC_REJECTED reason=$reason bytes=${bytes.size} seconds=${fmt2(seconds)} rms=${fmt5(rms)} " +
                        "min_rms=${fmt5(minRms.toDouble())} min_s=$MIC_MIN_S error=${err ?: "-"} wav=${if (bytes.isNotEmpty()) out.path else "-"}")
                    worker.execute {
                        micRejected.put(JSONObject().put("id", "mic-$startEpoch").put("reason", reason).put("bytes", bytes.size)
                            .put("seconds", seconds).put("rms", rms).put("min_rms", minRms.toDouble()).put("min_s", MIC_MIN_S)
                            .put("error", err?.toString() ?: JSONObject.NULL).put("wav", if (bytes.isNotEmpty()) out.path else JSONObject.NULL))
                        writeReport()
                    }
                    when (reason) {
                        "error" -> { setPill("ERROR", C_LIVE); status.text = "mic failed: ${err?.message}" }
                        "short" -> { setPill("too short", C_LIVE); status.text = "Hold the button while you speak" }
                        else -> { setPill("too quiet", C_LIVE); status.text = "Nothing heard: speak closer to the phone" }
                    }
                    setIdle()
                    return@ui
                }
                Log.i(TAG, "MIC_RECORDED bytes=${bytes.size} seconds=${fmt2(seconds)} rms=${fmt5(rms)} wav=${out.path}")
                audioQuery(Wav.mono16(bytes, MIC_RATE), "mic-$startEpoch", "mic", null, out.path, { setIdle() }, startEpoch,
                    played = true, extra = mapOf("rms" to rms, "min_rms" to minRms.toDouble()))
            }
        }.start()
    }

    // ---- screen -----------------------------------------------------------------------------------------------

    private fun subtitleText() = "$deviceName · LiteRT-LM ${BuildConfig.LITERTLM_VERSION}"

    /**
     * The backends the engine was initialised with (ENGINE_READY): the audio encoder = audio, the backbone = backend
     * (it turns the audio encoder's output, and every photo's, into the vector: "audio → vector" is both), photos =
     * vision. Parts on one backend share a phrase: "audio encoder on CPU · backbone and photos on GPU", "all on GPU".
     */
    private fun backendLineText(): String {
        val parts = listOf("audio encoder" to audioName, "backbone" to backendName, "photos" to visionName)
        val byBackend = parts.groupBy({ it.second }, { it.first })
        if (byBackend.size == 1) return "all on ${backendName.uppercase(Locale.US)}"
        return byBackend.entries.joinToString(" · ") { (b, names) ->
            "${names.joinToString(" and ")} on ${b.uppercase(Locale.US)}"
        }
    }

    /** One line (maxLines = 1 hides a second one): 13 sp when it fits, else 0.5 sp smaller until it does. */
    private fun setBackendLine(text: String) {
        val avail = backendLine.width - backendLine.paddingLeft - backendLine.paddingRight
        var sp = 13f
        backendLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        while (avail > 0 && sp > 10f && backendLine.paint.measureText(text) > avail) {
            sp -= 0.5f
            backendLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        }
        backendLine.text = text
    }

    private fun showGrid() {
        result.visibility = View.GONE
        gridScroll.visibility = View.VISIBLE
    }

    private fun showResult(ranked: List<Ranked>, kind: String, embedMs: Double, what: String) {
        if (ranked.isEmpty()) {
            setPill("no photos", C_LIVE)
            return
        }
        val top = ranked[0]
        bigPhoto.setImageBitmap(BitmapFactory.decodeFile(top.photo.file.path, BitmapFactory.Options().apply { inSampleSize = 1 }))
        queryLine.text = what
        msChip.text = String.format(Locale.US, "%s → vector %.0f ms", kind, embedMs)
        cosChip.text = String.format(Locale.US, "cos %.2f", top.cos)
        smallRow.removeAllViews()
        for ((k, r) in ranked.drop(1).take(2).withIndex()) {
            val cell = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            cell.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(r.photo.thumb)
                clipToOutline = true
                background = GradientDrawable().apply { cornerRadius = dp(8f); setColor(C_BUTTON) }
            }, LinearLayout.LayoutParams(dp(SMALL_DP).toInt(), dp(SMALL_DP).toInt()))
            cell.addView(label(13f, C_SUB).apply { text = String.format(Locale.US, "cos %.2f", r.cos) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(8f).toInt()
                })
            smallRow.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (k == 0) marginEnd = dp(10f).toInt()
            })
        }
        gridScroll.visibility = View.GONE
        result.visibility = View.VISIBLE
        setPill("DONE", C_DONE)
        status.text = "Hold the button and say what is in the photo"
        if (!resultLayoutLogged) {
            resultLayoutLogged = true
            result.post { recordResultLayout() }
        }
    }

    /** The whole album in one screen: 6 columns of square thumbnails (36 photos = 6 rows, nothing cut off). */
    private fun buildGrid(indexMs: Double?) {
        grid.removeAllViews()
        val cols = GRID_COLS
        val gap = dp(3f).toInt()
        val width = gridScroll.width.takeIf { it > 0 } ?: (frame.width - frame.paddingLeft - frame.paddingRight)
        val cell = (width - (cols - 1) * gap) / cols
        for (r in 0 until (album.size + cols - 1) / cols) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until cols) {
                val k = r * cols + c
                val iv = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    album.getOrNull(k)?.thumb?.let { setImageBitmap(it) }
                }
                row.addView(iv, LinearLayout.LayoutParams(cell, cell).apply { if (c > 0) marginStart = gap })
            }
            grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cell).apply {
                if (r > 0) topMargin = gap
            })
        }
        val text = if (indexMs != null) {
            String.format(Locale.US, "album %d photos · indexed in %.1f s on %s · no network needed", album.size,
                indexMs / 1000.0, visionName.uppercase(Locale.US))
        } else {
            String.format(Locale.US, "album %d photos · no network needed", album.size)
        }
        footer.post { setFooter(text) }
    }

    /** One line when it fits; otherwise the line breaks at the last " · ", never inside a phrase. */
    private fun setFooter(text: String) {
        val avail = footer.width - footer.paddingLeft - footer.paddingRight
        footer.text = if (avail <= 0 || footer.paint.measureText(text) <= avail || !text.contains(" · ")) text
            else text.substringBeforeLast(" · ") + "\n" + text.substringAfterLast(" · ")
    }

    private fun rectOf(v: View): JSONObject {
        val a = IntArray(2).also { v.getLocationOnScreen(it) }
        return JSONObject().put("left", a[0]).put("top", a[1]).put("width", v.width).put("height", v.height)
    }

    /**
     * Screen pixels at READY: the pill (make_media.sh reads its colour in every frame of a take), the level bar, the mic
     * button (gate.sh presses it), the demo frame (the 9:16 band under the status bar that the X cut crops to) and the
     * top of the title and the bottom of the footer, which must lie inside that band.
     */
    private fun recordLayout() {
        val screen = windowManager.currentWindowMetrics.bounds
        val t = rectOf(title)
        val f = rectOf(footer)
        val fr = rectOf(frame)
        val layout = JSONObject().put("screen_px", JSONArray().put(screen.width()).put(screen.height()))
            .put("density", resources.displayMetrics.density.toDouble())
            .put("font_scale", resources.configuration.fontScale.toDouble())
            .put("pill_px", rectOf(pill).put("pad_left", pill.paddingLeft))
            .put("level_px", rectOf(level))
            .put("mic_px", rectOf(micButton))
            .put("frame_px", fr)
            .put("status_bar_px", fr.getInt("top"))
            .put("title_top_px", t.getInt("top"))
            .put("footer_bottom_px", f.getInt("top") + f.getInt("height"))
            .put("title_to_footer_px", f.getInt("top") + f.getInt("height") - t.getInt("top"))
            .put("title_lines", title.lineCount).put("footer_lines", footer.lineCount)
            .put("pill_live_rgb", String.format(Locale.US, "#%06X", C_LIVE and 0xFFFFFF))
        Log.i(TAG, "LAYOUT $layout")
        worker.execute { report.put("layout", layout); writeReport() }
    }

    /** Screen pixels of the first result screen: the two chips must not touch, the next-match row must end inside the
     *  middle area (round 2: "216 mscos 0.72" ran together, the last grid row was cut). */
    private fun recordResultLayout() {
        val ms = rectOf(msChip)
        val cos = rectOf(cosChip)
        val row = rectOf(smallRow)
        val mid = rectOf(result)
        val o = JSONObject().put("photo_px", rectOf(bigPhoto)).put("ms_chip_px", ms).put("cos_chip_px", cos)
            .put("chip_gap_px", cos.getInt("left") - (ms.getInt("left") + ms.getInt("width")))
            .put("small_row_bottom_px", row.getInt("top") + row.getInt("height"))
            .put("middle_bottom_px", mid.getInt("top") + mid.getInt("height"))
            .put("ms_chip_lines", msChip.lineCount).put("cos_chip_lines", cosChip.lineCount)
        Log.i(TAG, "RESULT_LAYOUT $o")
        worker.execute { report.put("result_layout", o); writeReport() }
    }

    private fun setMicEnabled(on: Boolean) {
        micButton.alpha = if (on) 1f else 0.4f
    }

    private fun setIdle() {
        busy = false
        setMicEnabled(ready)
    }

    private fun setPill(text: String, color: Int) {
        pill.text = text
        pill.background = GradientDrawable().apply { cornerRadius = dp(999f); setColor(color) }
        if (color != pillColor) {
            pillColor = color
            // One line per colour change: make_media.sh matches these to the frames where the pill changes colour and so
            // maps the phone clock onto a take's timeline.
            Log.i(TAG, "PILL rgb=${String.format(Locale.US, "#%06X", color and 0xFFFFFF)} epoch_ms=${System.currentTimeMillis()} text=$text")
        }
    }

    // 18 sp: both chips with their gap take about 890 of the S26's 960 px (measured from round 2's 22 sp text).
    private fun chip(color: Int) = label(18f, color, bold = true).apply {
        maxLines = 1
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8f).toInt(), dp(6f).toInt(), dp(8f).toInt(), dp(6f).toInt())
        background = GradientDrawable().apply { cornerRadius = dp(10f); setColor(C_BUTTON) }
    }

    /**
     * The screen is a 9:16 frame (at most width x 16/9 tall) right under the status bar, so the X cut
     * (crop=1080:1920:0:<status bar>) holds the title, the photo, the numbers, the button and the footer. On the S26
     * (1080 x 2340, density 3) the frame is 1920 px; the band under it stays empty.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi(): View {
        val side = dp(20f).toInt()
        val pad = dp(8f).toInt()
        frame = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val root = FrameLayout(this).apply {
            setBackgroundColor(C_BG)
            addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setOnApplyWindowInsetsListener { _, insets ->
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val b = windowManager.currentWindowMetrics.bounds
                val h = minOf(b.height() - i.top - i.bottom, b.width() * 16 / 9)
                frame.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h).apply { topMargin = i.top }
                frame.setPadding(side + i.left, pad, side + i.right, pad)
                insets
            }
        }
        title = label(24f, C_TEXT, bold = true).apply { text = "EmbeddingGemma 2 740M"; maxLines = 1 }
        subtitle = label(15f, C_SUB).apply { maxLines = 1 }
        backendLine = label(13f, C_SUB).apply { maxLines = 1 }
        pill = label(13f, C_TEXT, bold = true).apply {
            letterSpacing = 0.08f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f).toInt(), 0, dp(12f).toInt(), 0)
        }
        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        gridScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(grid) }
        bigPhoto = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = dp(16f); setColor(C_BUTTON) }
            setOnClickListener { if (!busy) showGrid() }
        }
        queryLine = label(14f, C_SUB).apply { maxLines = 1 }
        msChip = chip(C_TEXT)
        cosChip = chip(C_ACCENT)
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(msChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(cosChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(10f).toInt()
            })
        }
        smallRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(queryLine, lp(top = 8f))
            addView(chips, lp(top = 6f))
            addView(label(12f, C_SUB).apply { text = "next matches" }, lp(top = 8f))
            addView(smallRow, lp(top = 4f))
        }
        result = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(bigPhoto, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(info, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val middle = FrameLayout(this).apply {
            addView(gridScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(result, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        level = LevelBar(this)
        status = label(14f, C_SUB).apply { gravity = Gravity.CENTER_HORIZONTAL; maxLines = 1 }
        micButton = label(20f, C_TEXT, bold = true).apply {
            text = "Hold to talk"
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(999f)
                setColor(C_BUTTON)
                setStroke(dp(2f).toInt(), C_LIVE)
            }
            setOnTouchListener { _, ev -> onMicTouch(ev) }
        }
        footer = label(12f, C_SUB).apply { gravity = Gravity.CENTER_HORIZONTAL; maxLines = 2 }

        frame.addView(title, lp(top = 0f))
        frame.addView(subtitle, lp(top = 2f))
        frame.addView(backendLine, lp(top = 0f))
        frame.addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28f).toInt()).apply {
            topMargin = dp(10f).toInt()
        })
        frame.addView(middle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(10f).toInt()
        })
        frame.addView(level, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6f).toInt()).apply {
            topMargin = dp(10f).toInt()
        })
        frame.addView(status, lp(top = 6f))
        frame.addView(micButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64f).toInt()).apply {
            topMargin = dp(8f).toInt()
        })
        frame.addView(footer, lp(top = 6f))
        return root
    }

    /** A thin rounded bar: the loudness of the last 50 ms (mic) or of the clip at the playback head. */
    private class LevelBar(context: Context) : View(context) {
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_BUTTON }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_LIVE }
        private val r = RectF()
        var level = 0f
            set(v) { field = v; invalidate() }

        override fun onDraw(canvas: Canvas) {
            val h = height.toFloat()
            r.set(0f, 0f, width.toFloat(), h)
            canvas.drawRoundRect(r, h / 2, h / 2, track)
            if (level > 0f) {
                r.set(0f, 0f, width * level, h)
                canvas.drawRoundRect(r, h / 2, h / 2, fill)
            }
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private fun idOf(f: File) = f.name.substringBefore("_").removeSuffix(".jpg")

    private fun thumb(f: File): Bitmap? = runCatching {
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 4 })
    }.getOrNull()

    private fun sha256(f: File): String {
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

    private fun airplaneMode(): Boolean = Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

    private fun thermal(): JSONObject {
        val pm = getSystemService(PowerManager::class.java)
        val headroom = pm.getThermalHeadroom(10)
        return JSONObject().put("status", pm.currentThermalStatus)
            .put("headroom_10s", if (headroom.isNaN()) JSONObject.NULL else headroom.toDouble())
    }

    private fun writeReport() {
        if (!::reportFile.isInitialized) return
        runCatching {
            val tmp = File(reportFile.path + ".tmp")
            tmp.writeText(report.toString(1))
            check(tmp.renameTo(reportFile)) { "could not write ${reportFile.path}" }
        }.onFailure { Log.w(TAG, "run JSON not written: $it") }
    }

    private fun ui(block: () -> Unit) {
        main.post(block)
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun label(sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun lp(top: Float) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top).toInt() }

    companion object {
        private const val TAG = "Eg2Demo"
        private const val DEFAULT_BUNDLE = "embeddinggemma-2-740m.litertlm"
        private const val DIM = 768
        private val OPTIONS = EmbeddingOptions(normalize = true, outputSize = DIM)
        // Text only (model card: "Prefixes apply to text only"); the card's query prefix and the DevSite one differ.
        private val PREFIXES = linkedMapOf(
            "card" to "task: search result | query: ",
            "devsite" to "task: search query | text: ",
            "none" to "",
        )
        private const val REQ_MIC = 1
        private const val MIC_RATE = 16000
        private const val MIC_MAX_S = 10
        private const val MIC_MIN_S = 0.5
        // The loudness floor of a mic clip (RMS of the whole clip, 1.0 = full scale): -60 dBFS, the middle in dB between
        // a quiet room (about -77 dBFS) and speech at arm's length (about -45 dBFS) on a VOICE_RECOGNITION input
        // calibrated as the CDD asks (90 dB SPL at 1 kHz = RMS 2500 of 32768). --ef min_rms overrides it.
        private const val MIC_MIN_RMS = 0.001f
        private const val GRID_COLS = 6
        private const val SMALL_DP = 56f
        private const val C_BG = 0xFF0E1116.toInt()
        private const val C_IDLE = 0xFF5F6368.toInt()
        private const val C_LIVE = 0xFFE53935.toInt()      // sound: LISTENING, audio file playing
        private const val C_WORK = 0xFF1565C0.toInt()
        private const val C_DONE = 0xFF2E7D32.toInt()
        private const val C_TEXT = 0xFFE6E8EB.toInt()
        private const val C_SUB = 0xFF8A919C.toInt()
        private const val C_ACCENT = 0xFF8AB4F8.toInt()
        private const val C_BUTTON = 0xFF1A1F27.toInt()

        private fun fmt1(v: Double) = String.format(Locale.US, "%.1f", v)
        private fun fmt2(v: Double) = String.format(Locale.US, "%.2f", v)
        private fun fmt4(v: Double) = String.format(Locale.US, "%.4f", v)
        private fun fmt5(v: Double) = String.format(Locale.US, "%.5f", v)
    }
}
