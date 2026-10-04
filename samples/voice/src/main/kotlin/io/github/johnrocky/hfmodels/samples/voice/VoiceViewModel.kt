package io.github.johnrocky.hfmodels.samples.voice

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.johnrocky.hfmodels.BackendKind
import io.github.johnrocky.hfmodels.BackendPolicy
import io.github.johnrocky.hfmodels.BundledCatalog
import io.github.johnrocky.hfmodels.HfModels
import io.github.johnrocky.hfmodels.LoadEvent
import io.github.johnrocky.hfmodels.LoadOptions
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.ModelRef
import io.github.johnrocky.hfmodels.NetworkPolicy
import io.github.johnrocky.hfmodels.PreparedModel
import io.github.johnrocky.hfmodels.Task
import io.github.johnrocky.hfmodels.Tasks
import io.github.johnrocky.hfmodels.litert.Speak
import io.github.johnrocky.hfmodels.litert.Transcribe
import io.github.johnrocky.hfmodels.litertlm.ChatModel
import io.github.johnrocky.hfmodels.speech.Speaker
import io.github.johnrocky.hfmodels.speech.Transcriber
import io.github.johnrocky.hfmodels.voice.Endpointer
import io.github.johnrocky.hfmodels.voice.MicSource
import io.github.johnrocky.hfmodels.voice.PhoneTools
import io.github.johnrocky.hfmodels.voice.SpeechPlayer
import io.github.johnrocky.hfmodels.voice.VoiceLoop
import io.github.johnrocky.hfmodels.voice.VoiceLoop.Event
import io.github.johnrocky.hfmodels.voice.VoiceLoopConfig
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The three models, one [VoiceLoop] over them with the phone's real tools, and the microphone. One HfModels client per
 * model (a client holds one native model). The loop runs off the main thread; [ui] is what the screen draws.
 */
class VoiceViewModel(private val app: Application) : AndroidViewModel(app) {
    private val asrModels = HfModels(app)
    private val ttsModels = HfModels(app)
    private val llmModels = HfModels(app)
    // Written by the load on a background thread, read by the screen's calls on the main thread (and the reverse for
    // tap, recorder and config).
    @Volatile private var asr: Transcriber? = null
    @Volatile private var tts: Speaker? = null
    @Volatile private var chat: ChatModel? = null
    @Volatile private var player: SpeechPlayer? = null
    @Volatile private var loop: VoiceLoop? = null
    private var loadJob: Job? = null
    private var listenJob: Job? = null
    private var turnJob: Job? = null
    private val afterLoad = ArrayList<() -> Unit>()
    @Volatile private var tap: MicTap? = null
    @Volatile private var recorder: TurnRecorder? = null
    @Volatile private var config = VoiceLoopConfig()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Whether the screen is visible (the activity sets it in onStart / onStop): what was to start once the models are
     * loaded starts only then, so a load that ends with the app in the background stays silent.
     */
    @Volatile var visible = true

    private val _ui = MutableStateFlow(VoiceUi())
    val ui: StateFlow<VoiceUi> = _ui

    /** Loads the transcriber, the speaker and the chat model in turn (once); then runs [then] (on the main thread, as the caller). */
    fun load(then: (() -> Unit)? = null) {
        if (loop != null) { then?.invoke(); return }
        then?.let { afterLoad += it }
        if (loadJob?.isActive == true) return
        _ui.update { it.copy(loading = true, status = "Loading…") }
        loadJob = viewModelScope.launch(Dispatchers.Default) {
            val t0 = SystemClock.elapsedRealtime()
            try {
                val a = load(asrModels, Transcribe, ASR, "Transcriber").also { asr = it }
                val s = load(ttsModels, Speak, TTS, "Speaker").also { tts = it }
                val c = load(llmModels, Tasks.Chat, LLM, "Chat model").also { chat = it }
                val p = SpeechPlayer(s.sampleRate).also { player = it }
                loop = VoiceLoop(a, c, s, PhoneTools.all(app), config.copy(player = p))
                val ms = SystemClock.elapsedRealtime() - t0
                Log.i(TAG, "ready asr=${a.info.repoId}/${a.info.variantId}/${a.info.profileId} tts=${s.info.repoId}/${s.info.variantId}/${s.info.profileId} " +
                    "llm=${c.info.repoId}/${c.info.variantId}/${c.info.profileId} load_ms=$ms network=${network()}")
                _ui.update { it.copy(loading = false, ready = true, status = "Ready · loaded in ${ms(ms.toDouble())}") }
                refreshPhoneState()
                // afterLoad is touched on the main thread only.
                withContext(Dispatchers.Main) {
                    val pending = ArrayList(afterLoad).also { afterLoad.clear() }
                    if (visible) pending.forEach { it() }
                }
            } catch (e: CancellationException) {
                releaseLoaded()
                throw e
            } catch (e: ModelException) {
                Log.e(TAG, "load failed ${e.code}: ${e.reason}")
                releaseLoaded()
                _ui.update { it.copy(loading = false, ready = false, status = "${e.code}: ${e.reason}") }
            } catch (e: Exception) {
                Log.e(TAG, "load failed", e)
                releaseLoaded()
                _ui.update { it.copy(loading = false, ready = false, status = "${e.javaClass.simpleName}: ${e.message}") }
            }
        }
    }

    /**
     * Closes what a failed or cancelled load opened, the last first, and forgets it, so the next load starts clean (each
     * client holds one model: one left open would refuse the next load with MODEL_BUSY).
     */
    private suspend fun releaseLoaded() {
        val p = player; val c = chat; val s = tts; val a = asr
        loop = null; player = null; chat = null; tts = null; asr = null
        withContext(NonCancellable) {
            runCatching { p?.close() }.onFailure { Log.w(TAG, "player close: ${it.message}") }
            for ((name, m) in listOf("chat model" to c, "speaker" to s, "transcriber" to a)) {
                runCatching { m?.closeAndJoin() }.onFailure { Log.w(TAG, "$name close: ${it.message}") }
            }
        }
    }

    /** Pinned to a commit (the descriptor's, or the bundled catalog's) so a phone without a network loads from its store. */
    private suspend fun <M : PreparedModel> load(models: HfModels, task: Task<M>, spec: ModelSpec, label: String): M {
        val descriptor = spec.descriptorAsset?.let { a -> app.assets.open(a).bufferedReader().use { it.readText() } }
        val commit = descriptor?.let { JSONObject(it).getString("revision") } ?: BundledCatalog.load(app).defaultBinding(spec.id)?.modelCommit
        val online = network() != "none"
        val options = LoadOptions(backendPolicy = spec.policy, networkPolicy = if (online) NetworkPolicy.Any else NetworkPolicy.Offline, descriptorJson = descriptor)
        return models.fromPretrained(ModelRef(spec.id, revision = commit, variant = spec.variant), task, options) { e ->
            _ui.update { it.copy(status = "$label: ${describe(e)}") }
        }
    }

    /** Opens the microphone and takes a turn per utterance until [stop]; the button's second press stops it. */
    fun toggleListen() = if (stillRunning(listenJob)) stop() else listen()

    fun listen() {
        val l = loop ?: return load { listen() }
        if (stillRunning(listenJob)) return
        val mic = MicSource()
        // The record mode writes each turn's utterance: only then is the microphone's last audio kept.
        val t = if (recorder != null) MicTap(mic.sampleRate) else null
        tap = t
        _ui.update { it.copy(listening = true, status = "Listening…", error = null) }
        // While recording: the loudest 20 ms of each second against the endpointer's start level, to tell a quiet
        // room from a microphone that hears nothing.
        var peak = 0.0
        var since = SystemClock.elapsedRealtime()
        listenJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                l.listen(mic.chunks().onEach { c ->
                    if (t != null) {
                        t.add(c)
                        peak = maxOf(peak, kotlin.math.sqrt(c.sumOf { (it * it).toDouble() } / c.size))
                        val now = SystemClock.elapsedRealtime()
                        if (now - since >= 1000) {
                            Log.i(TAG, "mic_level max_rms=${String.format(java.util.Locale.US, "%.4f", peak)} start_rms=${config.endpointer.startRms}")
                            peak = 0.0; since = now
                        }
                    }
                }).collect { e -> onEvent(e, fromMic = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "listen failed", e)
                _ui.update { it.copy(error = "${e.javaClass.simpleName}: ${e.message}") }
            } finally {
                _ui.update { it.copy(listening = false, busy = false, status = if (it.ready) "Ready" else it.status) }
            }
        }
    }

    /** One turn from typed text (no microphone). */
    fun say(text: String) {
        val l = loop ?: return load { say(text) }
        turnJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                l.turn(text).collect { e -> onEvent(e, fromMic = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "turn failed", e)
                _ui.update { it.copy(busy = false, error = "${e.javaClass.simpleName}: ${e.message}") }
            }
        }
    }

    /** Stops the microphone and any turn in progress (the model and the sound stop with it). */
    fun stop() {
        listenJob?.cancel()
        turnJob?.cancel()
    }

    /**
     * The endpointer's start level ([Endpointer.startRms]; 0.02 is a voice toward the phone, sound through a speaker
     * needs less). A loaded loop is built again with it; a listen in progress stops.
     */
    fun startRms(v: Float) {
        val e = config.endpointer
        config = config.copy(endpointer = Endpointer(e.sampleRate, v, e.startMs, e.hangoverMs, e.maxUtteranceMs, e.frameMs, e.preRollMs))
        Log.i(TAG, "start_rms=$v")
        val old = loop ?: return
        stop()
        old.close()
        loop = VoiceLoop(asr ?: return, chat ?: return, tts ?: return, PhoneTools.all(app), config.copy(player = player))
    }

    /** Each turn's sound and events go under `<external files>/record/<name>/<turn>/`; null stops recording. */
    fun record(name: String?) {
        recorder = name?.let { n ->
            val root = File(app.getExternalFilesDir(null), "record/${File(n).name}").apply { mkdirs() }
            Log.i(TAG, "record to ${root.path}")
            TurnRecorder(root)
        }
    }

    private suspend fun onEvent(e: Event, fromMic: Boolean) {
        val hangover = if (fromMic) config.endpointer.hangoverMs else 0
        _ui.update { it.on(e, hangover) }
        recorder?.event(e, fromMic)
        Log.i(TAG, describe(e))
        // The turn is over: its TURN line and record are written whole even if the screen goes away now (stop() cancels
        // the job this runs in, and a scripted take may bring the Clock app to the front right after Done).
        if (e is Event.Done) withContext(NonCancellable) { afterTurn(e.timing, fromMic, hangover) }
    }

    private suspend fun afterTurn(t: VoiceLoop.TurnTiming, fromMic: Boolean, hangover: Int) {
        val state = refreshPhoneState()
        val calls = _ui.value.tools.joinToString(",", "[", "]") { "${it.call}->\"${it.result}\"" }
        Log.i(TAG, "TURN input=${if (fromMic) "mic" else "text"} heard=${q(t.heard)} tools=$calls ms_first_audio=${f(t.firstAudioMs)} " +
            "hangover_ms=$hangover ms_end_of_speech_to_sound=${f(t.firstAudioMs?.let { it + hangover })} ms_transcribe=${f(t.transcribeMs)} " +
            "ms_first_token=${f(t.firstTokenMs)} ms_first_sentence=${f(t.firstSentenceMs)} ms_reply=${f(t.replyMs)} ms_total=${f(t.totalMs)} " +
            "spoken=${q(t.spoken)} reply=${q(t.reply)} phone=${q(state.lineSequence().firstOrNull() ?: "")} network=${network()}")
        val r = recorder ?: return
        val s = tts ?: return
        // reply.wav: the sentences said, synthesized again (the loop plays them and keeps no copy).
        val audio = r.sentences().map { s.synthesize(it, config.voice, config.speed).samples }
        val dir = r.finish(tap.takeIf { fromMic }, audio, s.sampleRate, player?.firstWriteAtNanos ?: 0L, state)
        Log.i(TAG, "recorded ${dir?.path}")
    }

    private suspend fun refreshPhoneState(): String {
        val s = try { PhoneTools.phoneState(app) } catch (e: Exception) { "?" }
        val net = network()
        _ui.update { it.copy(phoneState = s, network = net) }
        return s
    }

    /** What the phone's network is: none in airplane mode. */
    fun network(): String {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return "none"
        val caps = cm.getNetworkCapabilities(n) ?: return "unknown"
        return listOf(0 to "cellular", 1 to "wifi", 3 to "ethernet", 4 to "vpn").filter { caps.hasTransport(it.first) }.joinToString("+") { it.second }.ifEmpty { "other" }
    }

    override fun onCleared() {
        val l = loop
        loop = null
        // The models outlive viewModelScope: they close on a scope of their own, which ends with them.
        cleanupScope.launch {
            try {
                l?.closeAndJoin()
                releaseLoaded()
                asrModels.closeAndJoin(); ttsModels.closeAndJoin(); llmModels.closeAndJoin()
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                Log.e(TAG, "model cleanup failed", failure)
            } finally {
                cleanupScope.cancel()
            }
        }
        super.onCleared()
    }

    /** A model of the loop: its id, variant, backend and (until its repo carries hfmodels.json) its descriptor asset. */
    class ModelSpec(val id: String, val variant: String?, val policy: BackendPolicy, val descriptorAsset: String?)

    companion object {
        const val TAG = "hfmodels-voice-sample"
        val ASR = ModelSpec("litert-community/Zipformer-medium-CR-CTC-LiteRT", "medium_fp16", BackendPolicy.Require(BackendKind.GPU), "litert-community__Zipformer-medium-CR-CTC-LiteRT.hfmodels.json")
        val TTS = ModelSpec("litert-community/kitten-tts-nano-0.8", "fp32", BackendPolicy.Auto, "litert-community__kitten-tts-nano-0.8.hfmodels.json")
        val LLM = ModelSpec("litert-community/gemma-4-E2B-it-litert-lm", null, BackendPolicy.Require(BackendKind.GPU), null)

        private fun describe(e: LoadEvent): String = when (e) {
            is LoadEvent.Downloading -> "downloading ${e.bytes * 100 / maxOf(1L, e.totalBytes)}%"
            is LoadEvent.Initializing -> "starting on ${e.profileId}"
            is LoadEvent.Ready -> "ready on ${e.info.profileId}"
            else -> e.toString()
        }

        private fun describe(e: Event): String = when (e) {
            is Event.Heard -> "event=Heard text=${q(e.text)} audio_ms=${f(e.audioMs)} transcribe_ms=${f(e.transcribeMs)}"
            is Event.ToolCalled -> "event=ToolCalled name=${e.name} args=${e.args} result=${q(e.result)} ms=${f(e.ms)}"
            is Event.Speaking -> "event=Speaking sentence=${q(e.sentence)} synth_ms=${f(e.synthMs)} first_audio_ms=${f(e.firstAudioMs)}"
            is Event.Error -> "event=Error code=${e.code} message=${q(e.message)}"
            is Event.Done -> "event=Done total_ms=${f(e.timing.totalMs)}"
            else -> "event=$e"
        }

        private fun f(v: Double?) = v?.let { String.format(java.util.Locale.US, "%.0f", it) } ?: "none"
        private fun q(s: String) = "\"" + s.replace("\n", "\\n").replace("\"", "'") + "\""
    }
}

/**
 * Whether a listen still holds the microphone and the loop: until its job has completed, not only while it is active. A
 * stopped listen is no longer active at once but unwinds for a while (the runtime confirms the model's stop, the
 * conversation closes, the sentence in synthesis finishes); a listen started meanwhile opens a second microphone, and
 * the first one's end then shows the screen as idle while the second one listens.
 */
internal fun stillRunning(job: Job?): Boolean = job?.isCompleted == false
