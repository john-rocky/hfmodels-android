package io.github.johnrocky.hfmodels.samples.translate

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.SystemClock
import io.github.johnrocky.hfmodels.BackendKind
import io.github.johnrocky.hfmodels.BackendPolicy
import io.github.johnrocky.hfmodels.BundledCatalog
import io.github.johnrocky.hfmodels.ErrorCode
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
import io.github.johnrocky.hfmodels.litertlm.ChatModel
import io.github.johnrocky.hfmodels.speech.Speaker
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The three models of the sample, each in its own hfmodels client (one client holds one native model; the clients share
 * the store), loaded in the order Qwen3-ASR, Hy-MT2, Kitten:
 *  - Qwen3-ASR-1.7B (the 15 s file) from the sample's descriptor (`assets/descriptors/`, [Qwen3AsrTask]), the language
 *    model and the audio encoder on the CPU;
 *  - Hy-MT2-1.8B by id from the SDK's bundled catalog, on the GPU after [OutputCheck] passes, else on the CPU. A failed
 *    load or check is kept under the phone's build, the LiteRT-LM version and the model's commit, and the next start goes
 *    to the CPU directly; so is a GPU attempt that never finished (the process ended inside it). `--es mt_backend gpu`
 *    tries the GPU again, and a pass forgets the failure;
 *  - Kitten TTS nano 0.8 (`fp32`) by id from the catalog, CPU. Without it the English is shown and not said.
 * Every load is pinned to a commit, so a phone without a network loads from the app's store.
 */
class TranslateModels(private val context: Context) {
    private val asrModels = HfModels(context)
    private val mtModels = HfModels(context)
    private val ttsModels = HfModels(context)

    @Volatile var transcriber: Qwen3Asr? = null; private set
    @Volatile var translator: Translator? = null; private set
    @Volatile var speaker: Speaker? = null; private set
    /** The English voice's load failure, when the rest loaded. */
    @Volatile var speakerFailure: String? = null; private set
    @Volatile var lines: List<ModelLine> = emptyList(); private set
    @Volatile var mtBackend: String = "none"; private set
    /** pass / fail (<why>) / skipped (<why>) / not run. */
    @Volatile var gpuCheck: String = "not run"; private set

    /** A model that did not load: [role] as the screen names it, and why. */
    class Failure(val role: String, val code: ErrorCode?, val reason: String, cause: Throwable? = null) : Exception("$role: ${code?.let { "$it: " } ?: ""}$reason", cause)

    /**
     * Loads what is not loaded yet. [mtPreference] null = the GPU unless this phone's check failed before; CPU or GPU =
     * that backend only. [onStatus]: one line per stage, for the screen. Throws [Failure] for Qwen3-ASR or Hy-MT2; a
     * Kitten failure only sets [speakerFailure].
     */
    suspend fun load(network: NetworkPolicy, mtPreference: BackendKind? = null, onStatus: (String) -> Unit = {}) {
        val catalog = BundledCatalog.load(context)
        val lines = ArrayList<ModelLine>()
        if (transcriber == null) {
            val t0 = SystemClock.elapsedRealtime()
            transcriber = load("Speech recognition", asrModels, Qwen3AsrTask, ModelRef(ASR_ID, revision = ASR_COMMIT), LoadOptions(networkPolicy = network, descriptorJson = asset(ASR_DESCRIPTOR)), onStatus)
            lines += ModelLine("Qwen3-ASR-1.7B", "CPU", (SystemClock.elapsedRealtime() - t0).toDouble())
        }
        if (translator == null) {
            val t0 = SystemClock.elapsedRealtime()
            val ref = ModelRef(MT_ID, revision = catalog.defaultBinding(MT_ID)?.modelCommit)
            val key = gpuKey(ref.revision)
            // A GPU load or check that never finished ended the process (a native crash in the GPU driver kills it before
            // anything can be noted): it counts as a failure, so the next start does not crash the same way.
            if (prefs().getString(PREF_GPU_ATTEMPT, null) == key) {
                prefs().edit().putString(PREF_GPU_FAILED, "$key\tthe app ended during the GPU load or its check").remove(PREF_GPU_ATTEMPT).commit()
            }
            val remembered = prefs().getString(PREF_GPU_FAILED, null)?.split('\t')?.takeIf { it.size == 2 && it[0] == key }?.get(1)
            val tryGpu = when (mtPreference) {
                BackendKind.CPU -> { gpuCheck = "skipped (CPU asked for)"; false }
                BackendKind.GPU -> true
                else -> if (remembered != null) { gpuCheck = "skipped (failed before on this phone: $remembered)"; false } else true
            }
            var chat: ChatModel? = null
            // Every write of the GPU marks is commit(), not apply(): the process can end right after (a stop after a failed
            // load), and the next start reads the marks.
            if (tryGpu) {
                prefs().edit().putString(PREF_GPU_ATTEMPT, key).commit()
                chat = try {
                    load("Translation", mtModels, Tasks.Chat, ref, LoadOptions(backendPolicy = BackendPolicy.Require(BackendKind.GPU), networkPolicy = network), onStatus)
                } catch (e: Failure) {
                    prefs().edit().remove(PREF_GPU_ATTEMPT).commit()
                    if (mtPreference == BackendKind.GPU) throw e
                    gpuCheck = "fail (load: ${e.reason.take(120)})"
                    // Not remembered when the file is missing: that is not the GPU's failure.
                    if (e.code == ErrorCode.INITIALIZATION_FAILED) prefs().edit().putString(PREF_GPU_FAILED, "$key\tload: ${e.reason.take(120)}").commit()
                    null
                }
                if (chat != null) {
                    onStatus("Translation: checking the GPU's output")
                    val why = try {
                        OutputCheck.verdict(OutputCheck.PROBES.map { Translator(chat!!).translateAll(it.spanish, "English") })
                    } catch (e: ModelException) { "${e.code}: ${e.reason}" }
                    prefs().edit().remove(PREF_GPU_ATTEMPT).commit()
                    if (why == null) {
                        gpuCheck = "pass"
                        prefs().edit().remove(PREF_GPU_FAILED).commit()
                    } else {
                        gpuCheck = "fail ($why)"
                        prefs().edit().putString(PREF_GPU_FAILED, "$key\t$why").commit()
                        withContext(NonCancellable) { chat!!.closeAndJoin() }
                        chat = null
                        if (mtPreference == BackendKind.GPU) throw Failure("Translation", null, "the GPU's output check failed: $why")
                    }
                }
            }
            if (chat == null) chat = load("Translation", mtModels, Tasks.Chat, ref, LoadOptions(backendPolicy = BackendPolicy.Require(BackendKind.CPU), networkPolicy = network), onStatus)
            mtBackend = chat.info.components["language"]?.initialized?.name ?: chat.info.profileId
            translator = Translator(chat)
            lines += ModelLine("Hy-MT2-1.8B", mtBackend, (SystemClock.elapsedRealtime() - t0).toDouble())
        }
        if (speaker == null) {
            val t0 = SystemClock.elapsedRealtime()
            speakerFailure = null
            try {
                speaker = load("English voice", ttsModels, Speak, ModelRef(TTS_ID, revision = catalog.defaultBinding(TTS_ID)?.modelCommit, variant = "fp32"), LoadOptions(networkPolicy = network), onStatus)
                lines += ModelLine("Kitten TTS nano 0.8", "CPU", (SystemClock.elapsedRealtime() - t0).toDouble())
            } catch (e: Failure) {
                speakerFailure = e.message
                lines += ModelLine("Kitten TTS nano 0.8", "not loaded", null, e.reason.take(80))
            }
        }
        if (lines.isNotEmpty()) this.lines = mergeLines(this.lines, lines)
    }

    private fun mergeLines(old: List<ModelLine>, new: List<ModelLine>): List<ModelLine> =
        (old.filter { o -> new.none { it.name == o.name } } + new).sortedBy { ORDER.indexOf(it.name) }

    private suspend fun <M : PreparedModel> load(role: String, models: HfModels, task: Task<M>, ref: ModelRef, options: LoadOptions, onStatus: (String) -> Unit): M = try {
        models.fromPretrained(ref, task, options) { e -> onStatus("$role: ${describe(e)}") }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ModelException) {
        throw Failure(role, e.code, explain(e), e)
    } catch (e: Exception) {
        throw Failure(role, null, "${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** Releases the models (the last loaded first) and the clients. */
    suspend fun closeAndJoin() = withContext(NonCancellable) {
        val s = speaker; val t = translator; val a = transcriber
        speaker = null; translator = null; transcriber = null
        runCatching { s?.closeAndJoin() }
        runCatching { t?.chat?.closeAndJoin() }
        runCatching { a?.closeAndJoin() }
        for (m in listOf(ttsModels, mtModels, asrModels)) runCatching { m.closeAndJoin() }
    }

    private fun asset(name: String): String = context.assets.open("descriptors/$name").use { it.readBytes().toString(Charsets.UTF_8) }

    private fun prefs() = context.getSharedPreferences("translate", Context.MODE_PRIVATE)

    private fun gpuKey(commit: String?) = "${Build.FINGERPRINT}|${BuildConfig.LITERTLM_VERSION}|$commit"

    companion object {
        const val ASR_ID = "litert-community/Qwen3-ASR-1.7B"
        const val ASR_COMMIT = "184ec0f1ef8c8b5254100e95ec713b5557fb91de"
        const val ASR_DESCRIPTOR = "litert-community__Qwen3-ASR-1.7B.json"
        /** The descriptor's file (the device check names it to load a file the store does not have). */
        const val ASR_FILE = "Qwen3-ASR-1.7B-15s.litertlm"
        const val ASR_SHA256 = "fbec6d126627d9de449ba9a4f0dc0a541ab850d710c7aebae2dc65f62736d6bf"
        const val MT_ID = "litert-community/Hy-MT2-1.8B"
        const val TTS_ID = "litert-community/kitten-tts-nano-0.8"
        const val PREF_GPU_FAILED = "mt_gpu_failed"
        const val PREF_GPU_ATTEMPT = "mt_gpu_attempt"
        private val ORDER = listOf("Qwen3-ASR-1.7B", "Hy-MT2-1.8B", "Kitten TTS nano 0.8")

        fun describe(e: LoadEvent): String = when (e) {
            is LoadEvent.Resolving -> "resolving"
            is LoadEvent.DownloadStarted -> "downloading ${e.totalBytes shr 20} MB"
            is LoadEvent.Downloading -> "downloading ${e.bytes * 100 / maxOf(1L, e.totalBytes)}%"
            is LoadEvent.Verifying -> "verifying"
            is LoadEvent.Initializing -> "starting on ${e.profileId}"
            is LoadEvent.Fallback -> "fallback: ${e.reason}"
            is LoadEvent.Ready -> "ready on ${e.info.profileId}"
        }

        /** A load failure as a person can act on it: a missing file names the network or the push. */
        fun explain(e: ModelException): String = when (e.code) {
            ErrorCode.OFFLINE_CACHE_MISS -> "the model file is not on this phone and there is no network. Connect once, or push the files (README)"
            ErrorCode.NETWORK_ERROR -> "the download failed (${e.reason}). Check the network, or push the files (README)"
            ErrorCode.STORAGE_FULL -> "not enough free storage (${e.reason})"
            else -> e.reason
        }

        /** What the phone's network is: none in airplane mode. */
        fun network(context: Context): String {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val n = cm.activeNetwork ?: return "none"
            val caps = cm.getNetworkCapabilities(n) ?: return "unknown"
            return listOf(0 to "cellular", 1 to "wifi", 3 to "ethernet", 4 to "vpn").filter { caps.hasTransport(it.first) }.joinToString("+") { it.second }.ifEmpty { "other" }
        }

        /** This process's peak resident set (VmHWM, kB), or null. */
        fun vmHwmKb(): Long? = runCatching {
            File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmHWM:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLong()
        }.getOrNull()
    }
}
