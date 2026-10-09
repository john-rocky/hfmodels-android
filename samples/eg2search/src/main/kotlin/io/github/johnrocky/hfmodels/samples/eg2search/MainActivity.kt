package io.github.johnrocky.hfmodels.samples.eg2search

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.ai.edge.litertlm.Modality
import com.google.ai.edge.litertlm.ModelInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hold the button, say what is in a photo, and the matching photo of the album comes up first. EmbeddingGemma 2 740M
 * (LiteRT-LM's EmbeddingEngine) embeds the spoken clip (16 kHz mono PCM16 wrapped as WAV) and every photo into one 768-d
 * space (normalize = true, outputSize = 768; no prefix on audio or images, model card) and the album is ranked by
 * cosine. Nothing is transcribed and nothing leaves the phone (the app has no INTERNET permission).
 *
 * The album is files/album/: Add photos copies the photos picked in the system photo picker there (Album.importUris),
 * Remove my photos deletes those copies, and adb can push .jpg files there. PhotoSearch holds the model and the index;
 * the device check (src/androidTest) runs the same code.
 *
 * Files (the external files dir, `files/` below; the app creates the three dirs at start):
 *   files/<bundle>                      embeddinggemma-2-740m.litertlm, pushed with adb (README.md); --es bundle <name>
 *   files/album/u<NN>_<name>.jpg        the photos added in the app
 *   files/album/<id>_<slug>.jpg         pushed photos (the fixture album: a01_red_bicycle.jpg ...); id = the part before "_"
 *   files/queries/<id>.wav, queries.json  clips for the recording tools and the device check, with their gold photos
 *   files/index_<bundle>_<vision>.json  the album's vectors; a photo is embedded again only when it is new or changed
 *   files/mic/<epoch>.wav               with --ez keep_mic true only: every mic recording, refused ones too
 *   files/Documents/eg2search-<epoch>.json  in record mode only: the run record
 * Launch extras: --es backend gpu|cpu|npu (gpu), --es vision_backend (= backend), --es audio_backend (cpu),
 *   --ei max_input_length, --es bundle, --ez reindex true (embed every photo again; the footer then shows this launch's
 *   index time), --ef min_rms (InputGate.MIN_RMS), --ez keep_mic true. Recording tools, after READY:
 *   --es run_queries all|<wav names> [--ei gap_ms 2500] (embedded, not played), --es run_texts all
 *   [--es text_prefix card|devsite|none|all|<literal>], --es text "<sentence>",
 *   --ez autoplay true --es query <wav name>[,...] [--ei delay_ms 1500] [--ei gap_ms 2500] (each clip played through the
 *   speaker, then embedded from the file), --ei mic_test_ms <ms> [--ez mic_test_play true --es query <wav name>]
 *   (a press without a finger: the mic records for <ms> while the speaker plays the clip if asked, then the recording
 *   goes through the same gate and search as a press). Any of them, or --ez record true, turns on record mode: the screen
 *   shows over the lock screen, lights the display, stays on, hides the navigation bar and keeps a 9:16 frame for the
 *   cut; a normal start does none of that.
 * Log tag eg2search: RUN_JSON, BUNDLE, MODEL_INFO, ENGINE_READY, WARMUP, INDEX_DONE, READY, LAYOUT, PILL, QUERY_START,
 *   PLAY_START, PLAY_HEAD, MIC_TEST, MIC_RECORDED, MIC_REJECTED, MIC_PERMISSION, PICKER, IMPORT, REMOVE, RESULT,
 *   RESULT_LAYOUT, DONE, ERROR.
 * Every number on screen is measured by this app: "audio → vector" is the wall time of computeEmbedding for the clip,
 * "indexed in" the wall time of this launch's album index.
 */
class MainActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val destroyed = AtomicBoolean(false)
    private val stopIndex = AtomicBoolean(false)
    @Volatile private var search: PhotoSearch? = null
    @Volatile private var album: List<Photo> = emptyList()
    @Volatile private var recording = false
    @Volatile private var recordMode = false
    @Volatile private var loading = false
    private var track: AudioTrack? = null

    // Screen state, main thread only.
    private var busy = true
    private var ready = false
    private var indexing = false
    private var problemCode: String? = null
    private var micDenied = false
    private var userCount = 0
    private var gridThumbs: Map<String, Bitmap?> = emptyMap()
    private var pillColor = 0
    private var resultLayoutLogged = false

    // Launch configuration.
    private lateinit var files: File
    private lateinit var albumDir: File
    private var backends = Backends()
    private var bundleName = PhotoSearch.DEFAULT_BUNDLE
    private var reindexFirst = false
    private var minRms = InputGate.MIN_RMS
    private var keepMic = false
    private var maxInputLength: Int? = null
    private var golds: Map<String, String?> = emptyMap()                // wav name -> gold album id (null = decoy)
    private var sentences: List<Pair<String, String>> = emptyList()     // query id -> sentence (wording 1)

    // Worker thread only.
    private val thumbCache = HashMap<String, Bitmap?>()
    private val startEpochMs = System.currentTimeMillis()
    private val report = JSONObject()
    private val rows = JSONArray()
    private val refused = JSONArray()
    private var reportFile: File? = null

    private lateinit var root: FrameLayout
    private lateinit var frame: LinearLayout
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var backendLine: TextView
    private lateinit var pill: TextView
    private lateinit var gridScroll: ScrollView
    private lateinit var grid: LinearLayout
    private lateinit var note: TextView
    private lateinit var noteScroll: ScrollView
    private lateinit var result: LinearLayout
    private lateinit var bigPhoto: ImageView
    private lateinit var queryLine: TextView
    private lateinit var msChip: TextView
    private lateinit var cosChip: TextView
    private lateinit var smallRow: LinearLayout
    private lateinit var level: LevelBar
    private lateinit var status: TextView
    private lateinit var micButton: TextView
    private lateinit var addButton: TextView
    private lateinit var removeButton: TextView
    private lateinit var footer: TextView

    private val pickPhotos = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { onPicked(it) }
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { onMicPermission(it) }

    private val deviceName: String
        get() = if (Build.MODEL.startsWith("SM-S94")) "Galaxy S26" else Build.MODEL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        files = requireNotNull(getExternalFilesDir(null)) { "no external files dir" }
        for (d in listOf(Album.DIR, "queries", "mic")) File(files, d).mkdirs()
        albumDir = File(files, Album.DIR)
        val backbone = (intent.getStringExtra("backend") ?: "gpu").lowercase(Locale.US)
        backends = Backends(backbone, (intent.getStringExtra("vision_backend") ?: backbone).lowercase(Locale.US),
            (intent.getStringExtra("audio_backend") ?: "cpu").lowercase(Locale.US))
        bundleName = intent.getStringExtra("bundle") ?: PhotoSearch.DEFAULT_BUNDLE
        reindexFirst = intent.getBooleanExtra("reindex", false)
        minRms = intent.getFloatExtra("min_rms", InputGate.MIN_RMS)
        keepMic = intent.getBooleanExtra("keep_mic", false)
        maxInputLength = intent.getIntExtra("max_input_length", -1).takeIf { it > 0 }
        setContentView(buildUi())
        applyRecordMode(intent)
        subtitle.text = "$deviceName · LiteRT-LM ${BuildConfig.LITERTLM_VERSION}"
        setPill("LOADING", C_IDLE)
        refreshControls()
        loading = true
        work {
            startReport()
            load()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyRecordMode(intent)
        if (!ready) return
        if (busy || indexing) {
            Log.i(TAG, "IGNORED busy source=intent")
            return
        }
        runIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Back from pushing the model with adb (or from any other app): load it if it is there now.
        if (problemCode == PhotoSearch.BUNDLE_MISSING && !loading && File(files, bundleName).isFile) retryLoad()
    }

    override fun onDestroy() {
        destroyed.set(true)
        recording = false
        track?.release()
        track = null
        // A running index stops before its next photo; then the engine closes (PhotoSearch.close may run twice).
        work {
            search?.close()
            search = null
        }
        worker.shutdown()
        super.onDestroy()
    }

    // ---- model and album (worker thread) ----------------------------------------------------------------------

    private fun load() {
        val bundle = File(files, bundleName)
        if (bundle.isFile) {
            Log.i(TAG, "BUNDLE name=$bundleName bytes=${bundle.length()} path=${bundle.path}")
            report.put("bundle", JSONObject().put("name", bundleName).put("bytes", bundle.length()))
            report.put("model_info", modelInfo(bundle))
        }
        ui {
            setPill("LOADING MODEL", C_IDLE)
            status.text = "Loading the model…"
        }
        when (val o = PhotoSearch.open(bundle, File(cacheDir, "eg2"), backends, applicationInfo.nativeLibraryDir, maxInputLength)) {
            is PhotoSearch.Opened.Failed -> {
                Log.e(TAG, "ERROR ${o.code} ${o.error?.message ?: bundle.path}", o.error)
                report.put("error", JSONObject().put("code", o.code).put("text", o.text).put("thermal", thermal()))
                writeReport()
                ui {
                    loading = false
                    showProblem(if (o.code == PhotoSearch.BUNDLE_MISSING) "MODEL MISSING" else "LOAD FAILED", o.text, o.code)
                }
            }
            is PhotoSearch.Opened.Ready -> {
                val s = o.search
                if (destroyed.get()) {
                    s.close()
                    return
                }
                search = s
                report.put("engine", JSONObject().put("init_ms", s.initMs).put("warmup_ms", s.warmupMs ?: JSONObject.NULL))
                Log.i(TAG, "ENGINE_READY init_ms=${fmt1(s.initMs)} backend=${backends.backbone} vision_backend=${backends.vision} " +
                    "audio_backend=${backends.audio} bundle=$bundleName")
                Log.i(TAG, "WARMUP ms=${s.warmupMs?.let { fmt1(it) } ?: "failed"} seconds=1.00 audio_backend=${backends.audio}")
                ui { setBackendLine(backends.line()) }
                loadQueries()
                val idx = indexAlbum(reindexFirst)
                writeReport()
                ui {
                    loading = false
                    ready = true
                    problemCode = null
                    showAlbum()
                    setPill("READY", C_IDLE)
                    status.text = when {
                        idx?.cancelled == true -> stoppedText(idx)
                        album.isEmpty() -> "Add photos to start"
                        else -> HINT
                    }
                    setIdle()
                    Log.i(TAG, "READY album=${album.size} user_photos=$userCount queries=${golds.size}")
                    // After a layout pass with the READY screen (grid or note shown, footer text set).
                    main.postDelayed({ if (!isDestroyed) recordLayout() }, 300)
                    runIntent(intent)
                }
            }
        }
    }

    /**
     * Brings the album's vectors up to date (only new or changed photos are embedded, all of them with [reindex]) and
     * the grid with them. Null when there is no model. The caller sets the status line.
     */
    private fun indexAlbum(reindex: Boolean = false): PhotoSearch.Index? {
        val s = search ?: return null
        stopIndex.set(false)
        ui {
            indexing = true
            keepScreenOn(true)
            refreshControls()
        }
        val idx = try {
            s.index(albumDir, PhotoSearch.indexFile(files, bundleName, backends.vision), reindex,
                cancel = { stopIndex.get() || destroyed.get() }) { done, todo -> ui { setPill("INDEXING $done/$todo", C_WORK) } }
        } catch (t: Throwable) {
            Log.e(TAG, "ERROR index ${t.message ?: t}", t)
            ui {
                indexing = false
                keepScreenOn(false)
                setPill("ERROR", C_LIVE)
                status.text = "Indexing failed: ${t.message ?: t}"
                refreshControls()
            }
            return null
        }
        album = idx.photos
        val users = Album.userPhotos(albumDir).size
        val keep = idx.photos.map { "${it.file.name}:${it.sha256}" }.toSet()
        thumbCache.keys.retainAll(keep)
        val thumbs = idx.photos.associate { p ->
            p.file.name to thumbCache.getOrPut("${p.file.name}:${p.sha256}") {
                runCatching { BitmapFactory.decodeFile(p.file.path, BitmapFactory.Options().apply { inSampleSize = 4 }) }.getOrNull()
            }
        }
        for (f in idx.failed) Log.w(TAG, "INDEX_FAILED $f")
        idx.cacheError?.let { Log.w(TAG, "index cache not written: $it") }
        val median = idx.perImageMedian
        report.put("index", JSONObject().put("n", idx.photos.size).put("files", idx.files).put("embedded", idx.embedded)
            .put("reused", idx.reused).put("failed", idx.failed.size).put("cancelled", idx.cancelled).put("total_ms", idx.totalMs)
            .put("per_image_ms_median", median ?: JSONObject.NULL).put("per_image_ms", JSONArray(idx.perImageMs))
            .put("reindex", reindex).put("user_photos", users))
        Log.i(TAG, "INDEX_DONE n=${idx.photos.size} files=${idx.files} embedded=${idx.embedded} reused=${idx.reused} " +
            "failed=${idx.failed.size} cancelled=${idx.cancelled} total_ms=${fmt1(idx.totalMs)} " +
            "per_image_ms_median=${median?.let { fmt1(it) } ?: "-"} reindex=$reindex user_photos=$users")
        // The footer shows an index time only when this call embedded the whole album.
        val shownMs = if (!idx.cancelled && idx.files > 0 && idx.embedded == idx.files) idx.totalMs else null
        ui {
            indexing = false
            keepScreenOn(false)
            userCount = users
            gridThumbs = thumbs
            buildGrid(shownMs)
            if (problemCode == null) showAlbum()
            refreshControls()
        }
        return idx
    }

    private fun stoppedText(idx: PhotoSearch.Index) =
        "Stopped: ${idx.embedded} of ${idx.toEmbed} new photos indexed. The rest are indexed on the next start."

    private fun modelInfo(bundle: File): JSONObject = runCatching {
        ModelInfo.from(bundle.path).use { info ->
            val o = JSONObject().put("type", info.modelType.name).put("max_context_tokens", info.maxContextTokens())
                .put("min_runtime_version", info.minRuntimeVersion() ?: JSONObject.NULL)
            val m = info.inputModalities()
            o.put("modalities", JSONObject().put("text", m.text).put("vision", m.vision).put("audio", m.audio).put("video", m.video))
            for (mod in listOf(Modality.TEXT, Modality.VISION, Modality.AUDIO)) {
                o.put("backends_${mod.name.lowercase(Locale.US)}", JSONArray(info.supportedBackends(mod).map { it.name }))
            }
            Log.i(TAG, "MODEL_INFO $o")
            o
        }
    }.getOrElse { t ->
        Log.w(TAG, "MODEL_INFO failed: $t")
        JSONObject().put("error", t.toString())
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

    // ---- loading again, adding and removing photos ------------------------------------------------------------

    private fun retryLoad() {
        if (loading) return
        loading = true
        setPill("LOADING", C_IDLE)
        status.text = "Looking for the model file…"
        work {
            val b = File(files, bundleName)
            // A file still arriving over adb grows: load it only once its size holds for a second.
            val before = if (b.isFile) b.length() else -1L
            if (before >= 0) Thread.sleep(1000)
            if (before >= 0 && b.length() != before) {
                ui {
                    loading = false
                    setPill("MODEL MISSING", C_LIVE)
                    status.text = "The model file is still arriving: tap Load model when the push has finished"
                }
                return@work
            }
            ui {
                problemCode = null
                noteScroll.visibility = View.GONE
            }
            load()
        }
    }

    private fun onAddClick() {
        if (indexing) {
            stopIndex.set(true)
            Log.i(TAG, "INDEX_STOP requested")
            status.text = "Stopping after this photo…"
            return
        }
        if (!ready || busy || problemCode != null) return
        Log.i(TAG, "PICKER open")
        pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    /** The photo picker's answer. After a process death the new screen gets it, and the copy waits for the load. */
    private fun onPicked(uris: List<Uri>) {
        Log.i(TAG, "PICKER picked=${uris.size}")
        if (uris.isEmpty()) {
            status.text = "No photos picked: the album is as it was"
            return
        }
        busy = true
        refreshControls()
        setPill("ADDING ${uris.size}", C_WORK)
        status.text = "Copying ${uris.size} photo${plural(uris.size)}…"
        work {
            val r = Album.importUris(this, uris)
            Log.i(TAG, "IMPORT picked=${uris.size} imported=${r.files.size} failed=${r.failed.size} ms=${fmt1(r.ms)} " +
                "files=${r.files.joinToString(",") { it.name }}")
            for (f in r.failed) Log.w(TAG, "IMPORT_FAILED $f")
            val idx = indexAlbum()
            ui {
                val added = "Added ${r.files.size} photo${plural(r.files.size)}" +
                    (if (r.failed.isNotEmpty()) " (${r.failed.size} could not be read)" else "")
                status.text = when {
                    idx == null -> "$added. They are searched once the model loads."
                    idx.cancelled -> "$added. ${stoppedText(idx)}"
                    else -> "$added. $HINT"
                }
                if (problemCode == null) setPill("READY", C_IDLE)
                setIdle()
            }
        }
    }

    private fun onRemoveClick() {
        if (!ready || busy || indexing || problemCode != null) return
        if (userCount == 0) {
            status.text = "No photo was added here: nothing to remove"
            return
        }
        busy = true
        refreshControls()
        setPill("REMOVING", C_WORK)
        work {
            val n = Album.removeUserPhotos(albumDir)
            Log.i(TAG, "REMOVE removed=$n")
            indexAlbum()
            ui {
                status.text = "Removed $n photo${plural(n)} added here. The originals stay in your photo library."
                setPill("READY", C_IDLE)
                setIdle()
            }
        }
    }

    // ---- queries ----------------------------------------------------------------------------------------------

    private sealed class Job {
        class AudioFile(val name: String, val play: Boolean) : Job()
        class Text(val id: String, val text: String, val prefixName: String, val prefix: String) : Job()
        class MicTest(val ms: Int, val clip: String?) : Job()
    }

    /** The intent's work after READY (the recording tools): a batch, played clips, or a mic test. */
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
        val clips = i.getStringExtra("query")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val auto = i.getBooleanExtra("autoplay", false)
        if (auto) clips.forEach { jobs.add(Job.AudioFile(it, play = true)) }
        val micMs = i.getIntExtra("mic_test_ms", 0)
        if (micMs > 0) jobs.add(Job.MicTest(micMs, if (i.getBooleanExtra("mic_test_play", false)) clips.firstOrNull() else null))
        if (jobs.isEmpty()) return
        // Each intent is handled once: a resume does not run it again.
        setIntent(Intent(i).apply {
            for (k in listOf("run_queries", "run_texts", "text", "autoplay", "mic_test_ms")) removeExtra(k)
        })
        val delay = if (auto || micMs > 0) i.getIntExtra("delay_ms", 1500).toLong() else 0L
        val gap = i.getIntExtra("gap_ms", 2500).toLong()
        busy = true
        refreshControls()
        Log.i(TAG, "PLAN jobs=${jobs.size} delay_ms=$delay gap_ms=$gap")
        work { report.put("plan", JSONObject().put("jobs", jobs.size).put("delay_ms", delay).put("gap_ms", gap)) }
        main.postDelayed({ runJobs(jobs, 0, gap) }, delay)
    }

    private fun runJobs(jobs: List<Job>, k: Int, gap: Long) {
        if (isDestroyed) return
        if (k >= jobs.size) {
            work {
                report.put("done_epoch_ms", System.currentTimeMillis()).put("thermal_at_done", thermal())
                writeReport()
                Log.i(TAG, "DONE json=${reportFile?.path ?: "-"}")
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
                if (j.play) {
                    playThenEmbed(f, id, gold, next)
                } else {
                    val pcm = runCatching { Wav.read(f) }.getOrElse {
                        Log.e(TAG, "ERROR query $id unreadable ${it.message}")
                        next()
                        return
                    }
                    audioQuery(pcm.data, pcm.sampleRate, id, "file", gold, f.path, next)
                }
            }
            is Job.Text -> textQuery(j, next)
            is Job.MicTest -> micTest(j, next)
        }
    }

    /** One clip from a file, played through the speaker (pill "audio file") and then embedded from the file. */
    private fun playThenEmbed(f: File, id: String, gold: String?, onDone: () -> Unit) {
        val startEpoch = System.currentTimeMillis()
        Log.i(TAG, "QUERY_START epoch_ms=$startEpoch id=$id source=file play=1")
        setPill("● audio file", C_LIVE)
        status.text = "Listening… (audio file ${f.name})"
        val started = startPlayback(f, id) { pcm, playEpoch, headEpoch ->
            val extra = mapOf<String, Any>("play_start_epoch_ms" to playEpoch,
                "play_head_epoch_ms" to (if (headEpoch > 0) headEpoch else JSONObject.NULL))
            audioQuery(pcm.data, pcm.sampleRate, id, "file", gold, f.path, onDone, startEpoch, logged = true, extra = extra)
        }
        if (!started) onDone()
    }

    /**
     * A press without a finger (--ei mic_test_ms): the mic records for [Job.MicTest.ms] like a held button while, if
     * asked, the phone's speaker plays a clip from files/queries/; then the recording goes through the gate and search
     * of a press. The media volume is logged, not changed.
     */
    private fun micTest(j: Job.MicTest, onDone: () -> Unit) {
        val am = getSystemService(AudioManager::class.java)
        Log.i(TAG, "MIC_TEST ms=${j.ms} clip=${j.clip ?: "-"} music_volume=${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/" +
            "${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}")
        if (album.isEmpty()) {
            status.text = "Add photos first: there is nothing to search yet"
            onDone()
            return
        }
        if (!Mic.granted(this)) {
            Log.w(TAG, "MIC_PERMISSION missing source=mic_test")
            showMicDenied()
            onDone()
            return
        }
        startRecording("mic_test", onDone)
        j.clip?.let { name ->
            val f = File(files, "queries/$name")
            main.postDelayed({ if (recording && !startPlayback(f, "mic_test_$name") { _, _, _ -> }) Log.w(TAG, "MIC_TEST clip not played") }, 300)
        }
        main.postDelayed({ recording = false }, j.ms.toLong())
    }

    /** Plays a 16-bit mono WAV through the media stream; [onEnd] gets the samples and the PLAY_START / PLAY_HEAD times. */
    private fun startPlayback(f: File, id: String, onEnd: (Wav.Pcm, Long, Long) -> Unit): Boolean {
        val pcm = runCatching { Wav.read(f) }.getOrElse {
            Log.e(TAG, "ERROR query $id unreadable ${it.message}")
            return false
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
            track?.release()
            track = t
            val frames = pcm.data.size / 2
            // The pill turned red in this UI pass; a take's audio is laid in from this time.
            val playEpoch = System.currentTimeMillis()
            Log.i(TAG, "PLAY_START epoch_ms=$playEpoch id=$id seconds=${fmt2(pcm.seconds)}")
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
                    // While the mic records, its own levels drive the bar.
                    val at = head * 2
                    if (!recording) level.level = (Wav.rms(pcm.data, at - 1600, at + 1600) * 4).toFloat().coerceIn(0f, 1f)
                    if (head >= frames - pcm.sampleRate / 50 || SystemClock.elapsedRealtime() - t0 > pcm.seconds * 1000 + 3000) {
                        t.release()
                        track = null
                        if (!recording) level.level = 0f
                        onEnd(pcm, playEpoch, headEpoch)
                    } else {
                        main.postDelayed(this, 33)
                    }
                }
            }
            main.post(poll)
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "ERROR query $id playback ${t.message ?: t}", t)
            track?.release()
            track = null
            return false
        }
    }

    /**
     * One spoken query: [pcm] is 16-bit mono at [rate]. PhotoSearch refuses a clip that is too short or too quiet, or
     * embeds it and ranks the album; the RESULT line and the screen follow.
     */
    private fun audioQuery(pcm: ByteArray, rate: Int, id: String, source: String, gold: String?, path: String, onDone: () -> Unit,
                           startEpoch: Long = System.currentTimeMillis(), logged: Boolean = false,
                           extra: Map<String, Any> = emptyMap()) {
        val s = search ?: return onDone()
        if (!logged) Log.i(TAG, "QUERY_START epoch_ms=$startEpoch id=$id source=$source play=0")
        setPill("EMBEDDING", C_WORK)
        status.text = "Embedding audio…"
        val photos = album
        work {
            val row = JSONObject().put("kind", "audio").put("id", id).put("source", source).put("path", path)
                .put("gold", gold ?: JSONObject.NULL).put("query_start_epoch_ms", startEpoch).put("thermal_before", thermal())
            for ((k, v) in extra) row.put(k, v)
            try {
                when (val a = s.searchAudio(pcm, rate, photos, minRms)) {
                    is PhotoSearch.Answer.Refused -> {
                        Log.w(TAG, "${if (source == "file") "QUERY_REFUSED" else "MIC_REJECTED"} reason=${a.reason.word} id=$id " +
                            "source=$source bytes=${pcm.size} seconds=${fmt2(a.seconds)} rms=${fmt5(a.rms)} " +
                            "min_rms=${fmt5(minRms.toDouble())} min_s=${InputGate.MIN_SECONDS} wav=$path")
                        refused.put(row.put("reason", a.reason.word).put("seconds", a.seconds).put("rms", a.rms)
                            .put("min_rms", minRms.toDouble()))
                        writeReport()
                        ui {
                            showRefused(a.reason)
                            onDone()
                        }
                    }
                    is PhotoSearch.Answer.Found -> {
                        if (source != "file") {
                            Log.i(TAG, "MIC_RECORDED id=$id bytes=${pcm.size} seconds=${fmt2(a.seconds)} rms=${fmt5(a.rms)} wav=$path")
                        }
                        row.put("seconds", a.seconds).put("rms", a.rms)
                        finish(row, a, gold)
                        val what = if (source == "file") "audio file · $id · ${fmt1(a.seconds)} s" else "mic · ${fmt1(a.seconds)} s"
                        ui {
                            showResult(a.matches, "audio", a.embedMs, what)
                            onDone()
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "ERROR query $id ${t.message ?: t}", t)
                rows.put(row.put("error", t.toString()))
                writeReport()
                ui {
                    setPill("ERROR", C_LIVE)
                    status.text = t.message ?: t.toString()
                    onDone()
                }
            }
        }
    }

    private fun textQuery(j: Job.Text, onDone: () -> Unit) {
        val s = search ?: return onDone()
        val startEpoch = System.currentTimeMillis()
        Log.i(TAG, "QUERY_START epoch_ms=$startEpoch id=${j.id} source=text prefix=${j.prefixName}")
        setPill("EMBEDDING", C_WORK)
        status.text = "Embedding text…"
        val gold = golds["${j.id.substringBefore("_t_")}_a.wav"]
        val photos = album
        work {
            val row = JSONObject().put("kind", "text").put("id", j.id).put("text", j.text).put("prefix", j.prefixName)
                .put("gold", gold ?: JSONObject.NULL).put("query_start_epoch_ms", startEpoch).put("thermal_before", thermal())
            try {
                val a = s.searchText(j.prefix + j.text.trim(), photos)
                finish(row, a, gold)
                ui {
                    showResult(a.matches, "text", a.embedMs, "“${j.text}”")
                    onDone()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "ERROR query ${j.id} ${t.message ?: t}", t)
                rows.put(row.put("error", t.toString()))
                writeReport()
                ui {
                    setPill("ERROR", C_LIVE)
                    status.text = t.message ?: t.toString()
                    onDone()
                }
            }
        }
    }

    /** The RESULT line and the run record's row (worker thread). */
    private fun finish(row: JSONObject, a: PhotoSearch.Answer.Found, gold: String?) {
        val top3 = a.matches.take(3)
        val goldRank = gold?.let { g -> a.matches.indexOfFirst { it.photo.id == g }.takeIf { it >= 0 }?.plus(1) }
        var norm = 0.0
        for (x in a.vector) norm += x.toDouble() * x
        row.put("embed_ms", a.embedMs).put("rank_ms", a.rankMs).put("dim", a.vector.size).put("norm", Math.sqrt(norm))
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
            "seconds=${if (row.has("seconds")) fmt2(row.getDouble("seconds")) else "-"} embed_ms=${fmt1(a.embedMs)} " +
            "rank_ms=${fmt2(a.rankMs)} top1=${top3.firstOrNull()?.photo?.id ?: "-"} " +
            "top1_cos=${top3.firstOrNull()?.let { fmt4(it.cos.toDouble()) } ?: "-"} " +
            "top3=${top3.joinToString(",") { "${it.photo.id}:${fmt4(it.cos.toDouble())}" }} gold=${gold ?: "-"} hit1=$h1 hit3=$h3")
    }

    // ---- mic --------------------------------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun onMicTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (problemCode != null) {
                    retryLoad()
                    return true
                }
                if (!ready || busy || indexing) {
                    Log.i(TAG, "IGNORED busy source=mic ready=$ready indexing=$indexing")
                    return true
                }
                if (album.isEmpty()) {
                    status.text = "Add photos first: there is nothing to search yet"
                    return true
                }
                if (!Mic.granted(this)) {
                    Log.w(TAG, "MIC_PERMISSION missing")
                    showMicDenied()
                    askMic.launch(Manifest.permission.RECORD_AUDIO)
                    return true
                }
                startRecording("mic") { setIdle() }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> recording = false
        }
        return true
    }

    private fun onMicPermission(granted: Boolean) {
        Log.i(TAG, "MIC_PERMISSION granted=$granted")
        if (!granted) {
            showMicDenied()
            return
        }
        micDenied = false
        if (ready && !busy && problemCode == null) {
            setPill("READY", C_IDLE)
            status.text = HINT
        }
    }

    private fun showMicDenied() {
        micDenied = true
        setPill("MIC OFF", C_LIVE)
        status.text = Mic.deniedText(this)
    }

    private fun startRecording(source: String, onDone: () -> Unit) {
        busy = true
        recording = true
        refreshControls()
        val startEpoch = System.currentTimeMillis()
        val id = "mic-$startEpoch"
        Log.i(TAG, "QUERY_START epoch_ms=$startEpoch id=$id source=$source play=0")
        setPill("● LISTENING", C_LIVE)
        status.text = "Listening…"
        Thread {
            val r = Mic.record(this, { recording && !destroyed.get() }) { lv -> ui { if (recording) level.level = lv } }
            recording = false
            ui { onRecorded(r, id, source, startEpoch, onDone) }
        }.start()
    }

    private fun onRecorded(r: Mic.Result, id: String, source: String, startEpoch: Long, onDone: () -> Unit) {
        level.level = 0f
        refreshControls()
        when (r) {
            is Mic.Result.Denied -> {
                Log.w(TAG, "MIC_PERMISSION missing source=$source")
                showMicDenied()
                onDone()
            }
            is Mic.Result.Failed -> {
                Log.e(TAG, "ERROR mic ${r.error.message ?: r.error}", r.error)
                setPill("MIC ERROR", C_LIVE)
                status.text = "The microphone did not record: ${r.error.message ?: r.error}"
                onDone()
            }
            is Mic.Result.Clip -> {
                val path = if (keepMic && r.pcm.isNotEmpty()) {
                    val out = File(files, "mic/$startEpoch.wav")
                    runCatching { out.writeBytes(Wav.mono16(r.pcm, Mic.RATE)); out.path }
                        .getOrElse { Log.w(TAG, "mic wav not saved: $it"); "-" }
                } else {
                    "-"
                }
                audioQuery(r.pcm, Mic.RATE, id, source, null, path, onDone, startEpoch, logged = true)
            }
        }
    }

    // ---- screen -----------------------------------------------------------------------------------------------

    private fun isRecordingStart(i: Intent) = i.getBooleanExtra("record", false) || RECORD_EXTRAS.any { i.hasExtra(it) }

    /**
     * Record mode, for the recording tools only: a phone asleep behind a secure lock keeps a started app in the
     * background cpuset (little cores), where the same model runs about 10x slower, so the screen shows over the lock
     * screen, lights the display and stays on; the navigation bar is hidden and the screen keeps a 9:16 frame for the cut.
     */
    private fun applyRecordMode(i: Intent) {
        if (recordMode || !isRecordingStart(i)) return
        recordMode = true
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.navigationBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        root.requestApplyInsets()
        Log.i(TAG, "RECORD_MODE on")
        writeReport()
    }

    /** The display stays on while the album is indexed (a dark screen would park the work on the little cores). */
    private fun keepScreenOn(on: Boolean) {
        if (on || recordMode) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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

    /** The middle shows the grid, or a note when the album is empty. */
    private fun showAlbum() {
        result.visibility = View.GONE
        if (album.isEmpty()) {
            gridScroll.visibility = View.GONE
            note.text = "No photos yet.\n\nTap Add photos and pick some from this phone. The app keeps a smaller copy of " +
                "each (long side ${Album.MAX_SIDE} px, no location data) and finds them by voice.\n\nOr push .jpg files " +
                "with adb to\n${sdcard(albumDir)}/\nand start the app again."
            noteScroll.visibility = View.VISIBLE
        } else {
            noteScroll.visibility = View.GONE
            gridScroll.visibility = View.VISIBLE
        }
    }

    private fun showProblem(pillText: String, text: String, code: String) {
        problemCode = code
        setPill(pillText, C_LIVE)
        gridScroll.visibility = View.GONE
        result.visibility = View.GONE
        note.text = text
        noteScroll.visibility = View.VISIBLE
        status.text = if (code == PhotoSearch.BUNDLE_MISSING) "The model is a separate file: see the steps above" else "Tap Load model to try again"
        busy = false
        refreshControls()
    }

    private fun showRefused(reason: InputGate.Reason) {
        when (reason) {
            InputGate.Reason.SHORT -> {
                setPill("too short", C_LIVE)
                status.text = "Hold the button while you speak"
            }
            InputGate.Reason.QUIET -> {
                setPill("too quiet", C_LIVE)
                status.text = "Nothing heard: speak closer to the phone"
            }
        }
    }

    private fun showResult(ranked: List<Match>, kind: String, embedMs: Double, what: String) {
        if (ranked.isEmpty()) {
            setPill("no photos", C_LIVE)
            return
        }
        val top = ranked[0]
        bigPhoto.setImageBitmap(BitmapFactory.decodeFile(top.photo.file.path))
        queryLine.text = what
        msChip.text = String.format(Locale.US, "%s → vector %.0f ms", kind, embedMs)
        cosChip.text = String.format(Locale.US, "cos %.2f", top.cos)
        smallRow.removeAllViews()
        for ((k, r) in ranked.drop(1).take(2).withIndex()) {
            val cell = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            cell.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(gridThumbs[r.photo.file.name])
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
        noteScroll.visibility = View.GONE
        gridScroll.visibility = View.GONE
        result.visibility = View.VISIBLE
        setPill("DONE", C_DONE)
        status.text = HINT
        if (!resultLayoutLogged) {
            resultLayoutLogged = true
            result.post { recordResultLayout() }
        }
    }

    /** The whole album, 6 columns of square thumbnails (36 photos = 6 rows); more photos scroll. */
    private fun buildGrid(indexMs: Double?) {
        grid.removeAllViews()
        val photos = album
        val cols = GRID_COLS
        val gap = dp(3f).toInt()
        val width = gridScroll.width.takeIf { it > 0 } ?: (frame.width - frame.paddingLeft - frame.paddingRight)
        val cell = (width - (cols - 1) * gap) / cols
        for (r in 0 until (photos.size + cols - 1) / cols) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until cols) {
                val k = r * cols + c
                val iv = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    photos.getOrNull(k)?.let { setImageBitmap(gridThumbs[it.file.name]) }
                }
                row.addView(iv, LinearLayout.LayoutParams(cell, cell).apply { if (c > 0) marginStart = gap })
            }
            grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cell).apply {
                if (r > 0) topMargin = gap
            })
        }
        val head = if (userCount > 0) "album ${photos.size} photos ($userCount added here)" else "album ${photos.size} photos"
        val text = if (indexMs != null) {
            String.format(Locale.US, "%s · indexed in %.1f s on %s · no network needed", head, indexMs / 1000.0,
                backends.vision.uppercase(Locale.US))
        } else {
            "$head · no network needed"
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
     * Screen pixels at READY: the pill (its colour marks a take's frames), the level bar, the three buttons (where adb
     * taps), the frame (in record mode the 9:16 band under the status bar that the cut crops to), the title and the footer.
     */
    private fun recordLayout() {
        val screen = windowManager.currentWindowMetrics.bounds
        val t = rectOf(title)
        val f = rectOf(footer)
        val fr = rectOf(frame)
        val layout = JSONObject().put("screen_px", JSONArray().put(screen.width()).put(screen.height()))
            .put("density", resources.displayMetrics.density.toDouble())
            .put("font_scale", resources.configuration.fontScale.toDouble())
            .put("record_mode", recordMode)
            .put("pill_px", rectOf(pill).put("pad_left", pill.paddingLeft))
            .put("level_px", rectOf(level))
            .put("mic_px", rectOf(micButton))
            .put("add_px", rectOf(addButton))
            .put("remove_px", rectOf(removeButton))
            .put("frame_px", fr)
            .put("status_bar_px", fr.getInt("top"))
            .put("title_top_px", t.getInt("top"))
            .put("footer_bottom_px", f.getInt("top") + f.getInt("height"))
            .put("title_to_footer_px", f.getInt("top") + f.getInt("height") - t.getInt("top"))
            .put("title_lines", title.lineCount).put("footer_lines", footer.lineCount)
            .put("pill_live_rgb", String.format(Locale.US, "#%06X", C_LIVE and 0xFFFFFF))
        Log.i(TAG, "LAYOUT $layout")
        work {
            report.put("layout", layout)
            writeReport()
        }
    }

    /** Screen pixels of the first result screen: the two chips must not touch, the next-match row must end inside the
     *  middle area. */
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
        work {
            report.put("result_layout", o)
            writeReport()
        }
    }

    /** The buttons' look follows what a tap would do now. */
    private fun refreshControls() {
        val canTalk = ready && !busy && !indexing && album.isNotEmpty() && problemCode == null
        micButton.text = when {
            problemCode != null -> "Load model"
            recording -> "Release to search"
            else -> "Hold to talk"
        }
        micButton.alpha = if (canTalk || problemCode != null || recording) 1f else 0.4f
        addButton.text = if (indexing) "Stop" else "Add photos"
        addButton.alpha = if (indexing || (ready && !busy && problemCode == null)) 1f else 0.4f
        removeButton.alpha = if (ready && !busy && !indexing && userCount > 0 && problemCode == null) 1f else 0.4f
    }

    private fun setIdle() {
        busy = false
        refreshControls()
    }

    private fun setPill(text: String, color: Int) {
        pill.text = text
        pill.background = GradientDrawable().apply { cornerRadius = dp(999f); setColor(color) }
        if (color != pillColor) {
            pillColor = color
            // One line per colour change: a take's timeline is matched to the phone clock with these.
            Log.i(TAG, "PILL rgb=${String.format(Locale.US, "#%06X", color and 0xFFFFFF)} epoch_ms=${System.currentTimeMillis()} text=$text")
        }
    }

    // 18 sp: both chips with their gap take about 890 of the Galaxy S26's 960 px.
    private fun chip(color: Int) = label(18f, color, bold = true).apply {
        maxLines = 1
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8f).toInt(), dp(6f).toInt(), dp(8f).toInt(), dp(6f).toInt())
        background = GradientDrawable().apply { cornerRadius = dp(10f); setColor(C_BUTTON) }
    }

    private fun button(text: String, sizeSp: Float) = label(sizeSp, C_TEXT, bold = true).apply {
        this.text = text
        gravity = Gravity.CENTER
        maxLines = 1
        background = GradientDrawable().apply {
            cornerRadius = dp(999f)
            setColor(C_BUTTON)
            setStroke(dp(1f).toInt(), C_SUB)
        }
    }

    /**
     * Title, the device and runtime, the backend of each stage, the pill, the middle (the grid, the result or a note),
     * the level bar, the status line, the mic button, Add photos and Remove my photos, the footer. In record mode the
     * column is a 9:16 frame (at most width x 16/9 tall) under the status bar; otherwise it fills the window.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi(): View {
        val side = dp(20f).toInt()
        val pad = dp(8f).toInt()
        frame = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root = FrameLayout(this).apply {
            setBackgroundColor(C_BG)
            addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setOnApplyWindowInsetsListener { _, insets ->
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val b = windowManager.currentWindowMetrics.bounds
                val avail = b.height() - i.top - i.bottom
                val h = if (recordMode) minOf(avail, b.width() * 16 / 9) else avail
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
        note = label(15f, C_TEXT).apply {
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.15f)
        }
        // The scroll view itself is hidden with the note: an empty one over the grid would take the grid's scrolling.
        noteScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            visibility = View.GONE
            addView(note)
        }
        bigPhoto = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = dp(16f); setColor(C_BUTTON) }
            setOnClickListener { if (!busy) showAlbum() }
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
            addView(noteScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(result, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        level = LevelBar(this)
        status = label(14f, C_SUB).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            maxLines = 3
            setOnClickListener {
                if (micDenied) {
                    runCatching {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                    }
                }
            }
        }
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
        addButton = button("Add photos", 15f).apply { setOnClickListener { onAddClick() } }
        removeButton = button("Remove my photos", 15f).apply { setOnClickListener { onRemoveClick() } }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(addButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(removeButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dp(10f).toInt()
            })
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
        frame.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44f).toInt()).apply {
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

    private fun startReport() {
        if (recordMode) openReportFile()
        report.put("app", packageName).put("started_epoch_ms", startEpochMs)
            .put("device", JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                .put("device", Build.DEVICE).put("soc_manufacturer", Build.SOC_MANUFACTURER).put("soc_model", Build.SOC_MODEL)
                .put("android", Build.VERSION.RELEASE).put("sdk_int", Build.VERSION.SDK_INT)
                .put("build", Build.DISPLAY).put("shown_as", deviceName))
            .put("runtime", JSONObject().put("litertlm_android", BuildConfig.LITERTLM_VERSION).put("backend", backends.backbone)
                .put("vision_backend", backends.vision).put("audio_backend", backends.audio)
                .put("max_input_length", maxInputLength ?: JSONObject.NULL)
                .put("options", "EmbeddingOptions(normalize = true, outputSize = 768)")
                .put("reindex", reindexFirst).put("mic_min_rms", minRms.toDouble()).put("mic_min_s", InputGate.MIN_SECONDS))
            .put("airplane_mode_at_start", Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1)
            .put("thermal_at_start", thermal())
            .put("rows", rows)
            .put("mic_rejected", refused)
    }

    private fun openReportFile() {
        if (reportFile != null) return
        val docs = File(files, "Documents").apply { mkdirs() }
        reportFile = File(docs, "eg2search-$startEpochMs.json").also { Log.i(TAG, "RUN_JSON path=${it.path}") }
    }

    private fun thermal(): JSONObject {
        val pm = getSystemService(PowerManager::class.java)
        val headroom = pm.getThermalHeadroom(10)
        return JSONObject().put("status", pm.currentThermalStatus)
            .put("headroom_10s", if (headroom.isNaN()) JSONObject.NULL else headroom.toDouble())
    }

    /** The run record, in record mode only (worker thread; a call from the main thread is passed to the worker). */
    private fun writeReport() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            work { writeReport() }
            return
        }
        if (!recordMode) return
        openReportFile()
        val f = reportFile ?: return
        runCatching {
            val tmp = File(f.path + ".tmp")
            tmp.writeText(report.toString(1))
            check(tmp.renameTo(f)) { "could not write ${f.path}" }
        }.onFailure { Log.w(TAG, "run JSON not written: $it") }
    }

    private fun work(block: () -> Unit) {
        try {
            worker.execute(block)
        } catch (_: RejectedExecutionException) {
            // The screen is gone; so is its work.
        }
    }

    private fun ui(block: () -> Unit) {
        main.post { if (!isDestroyed) block() }
    }

    private fun sdcard(f: File) = f.path.replace(Regex("^/storage/emulated/0/"), "/sdcard/")

    private fun plural(n: Int) = if (n == 1) "" else "s"

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
        private const val TAG = "eg2search"
        private const val HINT = "Hold the button and say what is in the photo"
        private val RECORD_EXTRAS = listOf("autoplay", "run_queries", "run_texts", "text", "mic_test_ms")
        // Text only (model card: "Prefixes apply to text only"); the card's query prefix and the DevSite one differ.
        private val PREFIXES = linkedMapOf(
            "card" to "task: search result | query: ",
            "devsite" to "task: search query | text: ",
            "none" to "",
        )
        private const val GRID_COLS = 6
        private const val SMALL_DP = 56f
        private const val C_BG = 0xFF0E1116.toInt()
        private const val C_IDLE = 0xFF5F6368.toInt()
        private const val C_LIVE = 0xFFE53935.toInt()      // sound: LISTENING, audio file playing; problems
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
