package io.github.johnrocky.hfmodels.samples.translate

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.johnrocky.hfmodels.BackendKind
import io.github.johnrocky.hfmodels.NetworkPolicy
import io.github.johnrocky.hfmodels.voice.Endpointer
import io.github.johnrocky.hfmodels.voice.MicSource
import io.github.johnrocky.hfmodels.voice.SpeechPlayer
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The three models ([TranslateModels]), the turn loop over them, the language choice and the two microphone buttons. A
 * press opens the microphone for one utterance: the endpointer cuts it after 800 ms of silence (or a second press ends
 * it), the turn hears, translates and, for Spanish or French heard, speaks the English. [ui] is what the screen draws;
 * every turn writes one TURN line under the log tag [TAG].
 */
class TranslateViewModel(private val app: Application) : AndroidViewModel(app) {
    private val models = TranslateModels(app)
    @Volatile private var player: SpeechPlayer? = null
    @Volatile private var loop: TranslateLoop? = null
    private var loadJob: Job? = null
    // Set on a background thread, read by the buttons on the main thread.
    @Volatile private var listenJob: Job? = null
    @Volatile private var turnJob: Job? = null
    private val afterLoad = ArrayList<() -> Unit>()
    @Volatile private var stopRequested = false
    @Volatile private var recorder: TurnRecorder? = null
    @Volatile var config = TranslateLoop.Config()
        private set
    /** null = the GPU unless the output check failed on this phone before (see [TranslateModels.load]). */
    @Volatile var mtPreference: BackendKind? = null
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Whether the screen is visible (the activity sets it): what waits for the load starts only then. */
    @Volatile var visible = true

    private val _ui = MutableStateFlow(TranslateUi())
    val ui: StateFlow<TranslateUi> = _ui

    /** Loads the three models (once), then runs [then] on the main thread. */
    fun load(then: (() -> Unit)? = null) {
        if (loop != null) { then?.invoke(); return }
        then?.let { afterLoad += it }
        if (loadJob?.isActive == true) return
        _ui.update { it.copy(loading = true, status = "Loading…", error = null) }
        loadJob = viewModelScope.launch(Dispatchers.Default) {
            val t0 = SystemClock.elapsedRealtime()
            val net = TranslateModels.network(app)
            try {
                models.load(if (net == "none") NetworkPolicy.Offline else NetworkPolicy.Any, mtPreference) { s -> _ui.update { it.copy(status = s) } }
                val s = models.speaker
                val p = s?.let { SpeechPlayer(it.sampleRate) }.also { player = it }
                val t = models.transcriber!!
                val tr = models.translator!!
                loop = TranslateLoop({ t.transcribeDetailed(it) }, { text, target, created -> tr.translate(text, target, created) }, s, p?.asPlayer(), config)
                val ms = SystemClock.elapsedRealtime() - t0
                Log.i(TAG, "READY asr=${t.info.repoId}@${t.info.commit.take(8)}/${t.info.variantId}/cpu mt=${tr.chat.info.repoId}@${tr.chat.info.commit.take(8)}/${tr.chat.info.profileId} " +
                    "mt_backend=${models.mtBackend} gpu_check=${q(models.gpuCheck)} tts=${s?.info?.let { "${it.repoId}@${it.commit.take(8)}/${it.variantId}/${it.profileId}" } ?: "none"} " +
                    "models=${q(models.lines.joinToString(" | ") { it.text })} load_ms=$ms litertlm=${BuildConfig.LITERTLM_VERSION} litert=${BuildConfig.LITERT_VERSION} network=$net vmhwm_kb=${TranslateModels.vmHwmKb()}")
                _ui.update {
                    it.copy(loading = false, ready = true, status = "Ready · loaded in ${seconds(ms.toDouble())}", models = models.lines, network = net,
                        error = models.speakerFailure?.let { f -> "$f. The English is shown, not said." })
                }
                withContext(Dispatchers.Main) {
                    val pending = ArrayList(afterLoad).also { afterLoad.clear() }
                    if (visible) pending.forEach { it() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TranslateModels.Failure) {
                Log.e(TAG, "LOAD FAILED model=${q(e.role)} code=${e.code} reason=${q(e.reason)} load_ms=${SystemClock.elapsedRealtime() - t0} network=$net")
                _ui.update { it.copy(loading = false, ready = false, status = "Not loaded", models = models.lines, network = net, error = "${e.role} not loaded: ${e.reason}") }
            } catch (e: Exception) {
                Log.e(TAG, "LOAD FAILED model=\"?\" code=null reason=${q("${e.javaClass.simpleName}: ${e.message}")}", e)
                _ui.update { it.copy(loading = false, ready = false, status = "Not loaded", network = net, error = "${e.javaClass.simpleName}: ${e.message}") }
            }
        }
    }

    /**
     * A button: opens the microphone for one utterance in [direction]. Pressed again while it listens, it ends the
     * utterance there (or, before any speech, stops with "Nothing heard"); pressed during a turn, it stops the turn.
     */
    fun press(direction: Direction) {
        if (listenJob?.isCompleted == false) { stopRequested = true; return }
        if (turnJob?.isCompleted == false) { turnJob?.cancel(); return }
        val l = loop ?: return load { press(direction) }
        stopRequested = false
        listenJob = viewModelScope.launch(Dispatchers.Default) {
            recorder?.begin("Listening", direction)
            _ui.update { it.copy(listening = direction, turn = direction, status = "Listening (${direction.heard})…", error = null) }
            val ep = Endpointer(startRms = config.startRms, maxUtteranceMs = WINDOW_MS)
            var started = false
            var stoppedByPress = false
            var cutNanos = 0L
            var cutElapsed = 0L
            val opened = SystemClock.elapsedRealtime()
            // The microphone's level once a second (the RMS of the second and its loudest 20 ms): the input gate's floor is
            // set above what a silent press logs on the phone.
            var levelSum = 0.0; var levelN = 0; var levelMax = 0.0; var levelAt = opened
            val utterance: FloatArray? = try {
                withTimeoutOrNull(NO_SPEECH_MS + WINDOW_MS + 2_000L) {
                    MicSource().chunks().mapNotNull { chunk ->
                        var e = 0.0
                        for (x in chunk) e += x.toDouble() * x
                        levelSum += e; levelN += chunk.size; levelMax = maxOf(levelMax, kotlin.math.sqrt(e / chunk.size))
                        val now = SystemClock.elapsedRealtime()
                        if (now - levelAt >= 1000) {
                            Log.i(TAG, "mic_level dir=${direction.tag} rms=${"%.4f".format(java.util.Locale.US, kotlin.math.sqrt(levelSum / maxOf(1, levelN)))} max_rms=${"%.4f".format(java.util.Locale.US, levelMax)} start_rms=${config.startRms} floor_rms=${config.floorRms} speech=$started")
                            levelSum = 0.0; levelN = 0; levelMax = 0.0; levelAt = now
                        }
                        val events = ep.feed(chunk)
                        if (events.any { it is Endpointer.Event.SpeechStart }) started = true
                        val cut = (events.firstOrNull { it is Endpointer.Event.Utterance } as Endpointer.Event.Utterance?)?.pcm
                            ?: when {
                                stopRequested -> { stoppedByPress = true; ep.flush()?.pcm ?: NOTHING }
                                !started && SystemClock.elapsedRealtime() - opened > NO_SPEECH_MS -> NOTHING
                                else -> null
                            }
                        if (cut != null) { cutNanos = System.nanoTime(); cutElapsed = SystemClock.elapsedRealtimeNanos() }
                        cut
                    }.first()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "microphone failed", e)
                _ui.update { it.copy(listening = null, status = "Ready", error = "Microphone: ${e.javaClass.simpleName}: ${e.message}") }
                return@launch
            }
            _ui.update { it.copy(listening = null) }
            if (utterance == null || utterance.isEmpty()) {
                nothingHeard(direction, "mic", "no speech after the button")
                return@launch
            }
            turnJob = viewModelScope.launch(Dispatchers.Default) {
                runTurn(l, direction, utterance, cutNanos, cutElapsed, hangoverMs = if (stoppedByPress) 0 else ep.hangoverMs, input = if (stoppedByPress) "mic-press" else "mic")
            }
        }
    }

    /** The other person's language (the choice at the top): its two buttons. Not while a microphone is open or a turn runs. */
    fun chooseLanguage(language: Language) {
        if (listenJob?.isCompleted == false || turnJob?.isCompleted == false) return
        _ui.update { it.withLanguage(language) }
        Log.i(TAG, "language ${_ui.value.language.tag}")
    }

    /**
     * A WAV (16 kHz mono 16-bit) as one press of [direction]'s button: its audio goes through the endpointer as the
     * microphone's would, in real time when [realTime] (the screen and the clock then look as they do for a voice), and
     * its first utterance makes the turn. For recordings and checks; the user's own path is [press]. A direction of the
     * other language switches the screen to that language first.
     */
    fun play(file: File, direction: Direction, realTime: Boolean = true) {
        val l = loop ?: return load { play(file, direction, realTime) }
        if (listenJob?.isCompleted == false || turnJob?.isCompleted == false) return
        if (direction.language != _ui.value.language) chooseLanguage(direction.language)
        listenJob = viewModelScope.launch(Dispatchers.Default) {
            val (pcm, rate) = try { Wav.read(file) } catch (e: Exception) {
                _ui.update { it.copy(error = "${file.name}: ${e.message}") }
                return@launch
            }
            if (rate != ParakeetFrontEnd.RATE) { _ui.update { it.copy(error = "${file.name}: $rate Hz, expected ${ParakeetFrontEnd.RATE}") }; return@launch }
            recorder?.begin("Listening", direction)
            _ui.update { it.copy(listening = direction, turn = direction, status = "Listening (${direction.heard})…", error = null) }
            val ep = Endpointer(startRms = config.startRms, maxUtteranceMs = WINDOW_MS)
            val start = SystemClock.elapsedRealtime()
            val all = pcm + FloatArray(ParakeetFrontEnd.RATE * (ep.hangoverMs + 200) / 1000)
            var utterance: FloatArray? = null
            var i = 0
            while (i < all.size && utterance == null) {
                val k = i / CHUNK
                if (realTime) delay(maxOf(0L, start + k * 20L - SystemClock.elapsedRealtime()))
                utterance = (ep.feed(all.copyOfRange(i, minOf(i + CHUNK, all.size))).firstOrNull { it is Endpointer.Event.Utterance } as Endpointer.Event.Utterance?)?.pcm
                i += CHUNK
            }
            if (utterance == null) utterance = ep.flush()?.pcm
            val cutNanos = System.nanoTime()
            val cutElapsed = SystemClock.elapsedRealtimeNanos()
            _ui.update { it.copy(listening = null) }
            if (utterance == null) {
                nothingHeard(direction, "file:${file.name}", "the endpointer found no speech")
                return@launch
            }
            val u: FloatArray = utterance
            turnJob = viewModelScope.launch(Dispatchers.Default) { runTurn(l, direction, u, cutNanos, cutElapsed, ep.hangoverMs, "file:${file.name}") }
        }
    }

    /** A press (or a file) with no utterance: the screen says so and the TURN line records it. */
    private fun nothingHeard(direction: Direction, input: String, why: String) {
        val r = TurnRecord(direction, input, hangoverMs = 0, audioMs = 0.0).apply { nothingHeard = why }
        Log.i(TAG, turnLine(r, "asr:cpu,mt:${models.mtBackend.lowercase()},tts:${if (models.speaker != null) "cpu" else "none"}", TranslateModels.network(app), TranslateModels.vmHwmKb()))
        _ui.update { it.startTurn(direction).copy(busy = false, status = "Nothing heard") }
    }

    private suspend fun runTurn(l: TranslateLoop, direction: Direction, pcm: FloatArray, cutNanos: Long, cutElapsed: Long, hangoverMs: Int, input: String) {
        _ui.update { it.startTurn(direction) }
        try {
            l.turn(direction, pcm, cutNanos, hangoverMs, input).collect { e ->
                _ui.update { it.on(e) }
                recorder?.event(e)
                if (e is TranslateLoop.Event.Done) withContext(NonCancellable) { afterTurn(e.record, pcm, cutElapsed) }
            }
        } catch (e: CancellationException) {
            _ui.update { it.copy(busy = false, status = "Stopped") }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "turn failed", e)
            _ui.update { it.copy(busy = false, status = "Ready", error = "${e.javaClass.simpleName}: ${e.message}") }
        }
    }

    private fun afterTurn(r: TurnRecord, pcm: FloatArray, cutElapsed: Long) {
        val net = TranslateModels.network(app)
        _ui.update { it.copy(network = net) }
        Log.i(TAG, turnLine(r, "asr:cpu,mt:${models.mtBackend.lowercase()},tts:${if (models.speaker != null) "cpu" else "none"}", net, TranslateModels.vmHwmKb()))
        val rec = recorder ?: return
        val dir = rec.finish(r, pcm, cutElapsed, models.speaker?.sampleRate ?: 24000)
        Log.i(TAG, "recorded ${dir.path}")
    }

    /** Stops the microphone and any turn in progress (the model and the sound stop with it). */
    fun stop() {
        listenJob?.cancel()
        turnJob?.cancel()
    }

    /** The endpointer's start level (0.02: a voice toward the phone; a speaker across a desk needs less) and the input gate's floor. */
    fun levels(startRms: Float?, floorRms: Float?) {
        config = config.copy(startRms = startRms ?: config.startRms, floorRms = floorRms ?: config.floorRms)
        Log.i(TAG, "levels start_rms=${config.startRms} floor_rms=${config.floorRms}")
        rebuild()
    }

    /** Each turn's sound and events go under `<external files>/record/<name>/<turn>/`; null stops recording. */
    fun record(name: String?) {
        recorder = name?.let { n -> TurnRecorder(File(app.getExternalFilesDir(null), "record/${File(n).name}").apply { mkdirs() }) }
        config = config.copy(keepAudio = recorder != null)
        Log.i(TAG, "record ${recorder?.root?.path ?: "off"}")
        rebuild()
    }

    /** A loaded loop is built again with the current [config]. */
    private fun rebuild() {
        loop ?: return
        val t = models.transcriber ?: return
        val tr = models.translator ?: return
        if (listenJob?.isCompleted == false || turnJob?.isCompleted == false) stop()
        loop = TranslateLoop({ t.transcribeDetailed(it) }, { text, target, created -> tr.translate(text, target, created) }, models.speaker, player?.asPlayer(), config)
    }

    override fun onCleared() {
        loop = null
        val p = player
        player = null
        // The models outlive viewModelScope: they close on a scope of their own, which ends with them.
        cleanupScope.launch {
            try {
                runCatching { p?.close() }
                models.closeAndJoin()
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                Log.e(TAG, "model cleanup failed", failure)
            } finally {
                cleanupScope.cancel()
            }
        }
        super.onCleared()
    }

    companion object {
        const val TAG = "translate"
        /** The parakeet graph's window: the endpointer cuts an utterance there. */
        const val WINDOW_MS = 30_000
        /** A press with no speech start in this time ends as "Nothing heard". */
        const val NO_SPEECH_MS = 8_000L
        private const val CHUNK = ParakeetFrontEnd.RATE * 20 / 1000
        private val NOTHING = FloatArray(0)
    }
}
