package io.github.johnrocky.hfmodels.litert

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import io.github.johnrocky.hfmodels.BackendKind
import io.github.johnrocky.hfmodels.ComponentReport
import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.Handler
import io.github.johnrocky.hfmodels.LoadEvent
import io.github.johnrocky.hfmodels.LocalModel
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.PrepareHost
import io.github.johnrocky.hfmodels.PreparedModelInfo
import io.github.johnrocky.hfmodels.Task
import io.github.johnrocky.hfmodels.speech.Speaker

/**
 * Text to speech on classic `.tflite` graphs run by LiteRT's Interpreter API. Pass it where
 * `Tasks.Chat` goes:
 *
 * ```kotlin
 * val tts: Speaker = models.fromPretrained(ModelRef("litert-community/kitten-tts-nano-0.8"), Speak)
 * val pcm = tts.synthesize("Alarm set for seven thirty.").samples   // 24 kHz, [-1, 1]
 * ```
 *
 * The descriptor names the task `speak`, the runtime `litert` and the handler `litert.speak` ABI 1.
 * Profile components use the key `inference`; the kitten family runs on the CPU only.
 */
object Speak : Task<Speaker> {
    override val id = "speak"
    override val handler: Handler<Speaker> = LiteRtSpeakHandler
}

/**
 * `handler_config` keys:
 *  - `family`: `kitten` (the only one: KittenTTS nano, [KittenG2P] + [KittenSynthesizer]);
 *  - `files`: `{predictor, prosody, vocoder, voices, g2p_model, g2p_dict, g2p_meta, symbols}` -> file ids
 *    of the variant (the three graphs, `voices.npz`, the DeepPhonemizer graph, `g2p_dict.txt.gz`,
 *    `g2p_meta.json`, `symbols.json`);
 *  - `sample_rate` (24000, the vocoder's 600 samples per frame); `voices` (names in `voices.npz`, the
 *    first is the default) and `default_voice` (must be the first); `style_rows` (400) and `style_dim` (256);
 *  - `speed_priors` (voice -> factor): `speed` times the voice's factor goes to the graph, as the
 *    publisher's `say.py` does it (a voice without one: 1), so `speed = 1` is say.py's default pace;
 *  - `xnnpack` (graph -> bool): whether the predictor, prosody and vocoder graphs run with the XNNPACK
 *    delegate (false = the Interpreter's built-in kernels); default predictor false, prosody and vocoder
 *    true ([KittenSynthesizer.DEFAULT_XNNPACK]: on a Galaxy S26, 2026-10-03, fp32, the predictor without
 *    XNNPACK took the peak RSS after the load from 624 MB to 334 MB and the median synthesis from 288 ms
 *    to 301 ms, with the same frames);
 *  - `cpu_threads` (default 4); `tail_trim` (5000) and `min_samples` (1200): the pip package's trim of
 *    each chunk's end; `max_chars` (400): one chunk's limit; `languages` (informational list).
 */
internal object LiteRtSpeakHandler : Handler<Speaker> {
    override val id = "litert.speak"
    override val abi = 1
    override val runtime = "litert"
    override val runtimeVersion: String = BuildConfig.LITERT_VERSION

    private val FILE_KEYS = listOf("predictor", "prosody", "vocoder", "voices", "g2p_model", "g2p_dict", "g2p_meta", "symbols")

    override fun prepare(local: LocalModel<Speaker>, host: PrepareHost, onProgress: (LoadEvent) -> Unit): Speaker {
        val plan = local.plan
        val profile = plan.profile
        val hc = plan.variant.handlerConfig
        fun invalid(msg: String): Nothing = throw ModelException(ErrorCode.MANIFEST_INVALID, msg)
        val family = hc.optString("family", "kitten")
        if (family != "kitten") invalid("handler_config.family '$family' is not known to $id (known: kitten)")
        val files = hc.optJSONObject("files") ?: invalid("handler_config.files is missing (${FILE_KEYS.joinToString()})")
        val file = FILE_KEYS.associateWith { key ->
            val fid = files.optString(key, "")
            if (fid.isEmpty()) invalid("handler_config.files.$key is missing")
            local.files[fid] ?: invalid("handler_config.files.$key names file id '$fid', which profile '${profile.id}' does not include")
        }
        val sampleRate = hc.optInt("sample_rate", SAMPLE_RATE)
        if (sampleRate != SAMPLE_RATE) invalid("handler_config.sample_rate $sampleRate: the kitten vocoder writes $SAMPLE_RATE Hz")
        val voices = hc.optJSONArray("voices")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
        if (voices.isEmpty()) invalid("handler_config.voices is missing or empty")
        val defaultVoice = hc.optString("default_voice", voices[0])
        if (defaultVoice != voices[0]) invalid("handler_config.default_voice '$defaultVoice' must be the first of handler_config.voices (${voices[0]})")
        val styleRows = hc.optInt("style_rows", 400)
        val styleDim = hc.optInt("style_dim", KittenSynthesizer.STYLE_DIM)
        if (styleDim != KittenSynthesizer.STYLE_DIM) invalid("handler_config.style_dim $styleDim: the kitten graphs take ${KittenSynthesizer.STYLE_DIM}")
        val cpuThreads = hc.optInt("cpu_threads", 4)
        val tailTrim = hc.optInt("tail_trim", 5000)
        val minSamples = hc.optInt("min_samples", 1200)
        val maxChars = hc.optInt("max_chars", 400)
        if (cpuThreads < 1 || tailTrim < 0 || minSamples < 0 || maxChars < 1 || styleRows < 1) invalid("handler_config: cpu_threads, max_chars and style_rows must be >= 1, tail_trim and min_samples >= 0")
        val priors = hc.optJSONObject("speed_priors")?.let { o ->
            o.keys().asSequence().associateWith { k -> o.optDouble(k, Double.NaN).also { if (!it.isFinite() || it <= 0.0) invalid("handler_config.speed_priors.$k must be a number above 0") } }
        }.orEmpty()
        val xnnpackOn = LinkedHashMap(KittenSynthesizer.DEFAULT_XNNPACK)
        hc.optJSONObject("xnnpack")?.let { o ->
            for (k in o.keys()) {
                if (k !in KittenSynthesizer.GRAPHS) invalid("handler_config.xnnpack.$k: not one of the kitten graphs (${KittenSynthesizer.GRAPHS.joinToString()})")
                xnnpackOn[k] = (o.get(k) as? Boolean) ?: invalid("handler_config.xnnpack.$k must be true or false")
            }
        }
        val xnnpackOff = xnnpackOn.filterValues { !it }.keys

        val backend = profile.components["inference"] ?: invalid("profile '${profile.id}' has no 'inference' component")
        if (backend != BackendKind.CPU) throw ModelException(
            ErrorCode.UNSUPPORTED_CONFIGURATION, "profile '${profile.id}': the kitten family runs on the CPU only (the predictor and prosody graphs need the Interpreter API)",
            details = mapOf("profile" to profile.id),
        )
        onProgress(LoadEvent.Initializing(profile.id))

        fun <T> stage(stage: String, block: () -> T): T = try { block() } catch (e: ModelException) { throw e } catch (t: Throwable) {
            throw ModelException(ErrorCode.INITIALIZATION_FAILED, "$stage failed to load: ${t.javaClass.simpleName}: ${t.message}", details = mapOf("stage" to stage, "profile" to profile.id), cause = t)
        }
        val ms = LinkedHashMap<String, Long>()
        fun <T> timed(key: String, block: () -> T): T { val t0 = System.nanoTime(); return block().also { ms[key] = (System.nanoTime() - t0) / 1_000_000 } }

        val npz = timed("voices") { stage("voices") { NpzVoices.read(file.getValue("voices")) } }
        val styles = voices.associateWith { v ->
            val t = npz[v] ?: throw ModelException(ErrorCode.INITIALIZATION_FAILED, "voices.npz has no voice '$v' (it has ${npz.names.joinToString()})", details = mapOf("stage" to "voices"))
            if (t.rows != styleRows || t.dim != styleDim) throw ModelException(ErrorCode.INITIALIZATION_FAILED, "voices.npz '$v' is ${t.rows} x ${t.dim}, handler_config says $styleRows x $styleDim", details = mapOf("stage" to "voices"))
            t
        }
        val symbolToId = stage("lexicon") { KittenG2P.readSymbols(file.getValue("symbols").readText()) }
        val meta = stage("lexicon") { KittenNeuralG2P.Meta.parse(file.getValue("g2p_meta").readText()) }
        val dictionary = timed("g2p_dict") { stage("lexicon") { KittenG2P.readDictionary(file.getValue("g2p_dict")) } }
        host.log.i("kitten lexicon: ${symbolToId.size} symbols, ${dictionary.size} dictionary words, ${npz.names.size} voices")

        val (neural, synth) = LiteRtDecisionModel.Runtime.call {
            // As in LiteRtTranscriber.open: in an app that packages the NPU libraries every graph carries BURST.
            val burst = if (host.appContext?.let(LiteRtNpu::ready) == true) CompiledModel.QualcommOptions(htpPerformanceMode = CompiledModel.QualcommOptions.HtpPerformanceMode.BURST) else null
            val options = CompiledModel.Options(Accelerator.CPU).apply { cpuOptions = CompiledModel.CpuOptions(numThreads = cpuThreads); qualcommOptions = burst }
            val neural = timed("g2p_graph") { stage("g2p_graph") { KittenNeuralG2P.open(file.getValue("g2p_model"), meta, options, LiteRtDecisionModel.Runtime.environment(host.appContext)) } }
            val synth = try {
                stage("interpreter") { KittenSynthesizer.open(file.getValue("predictor"), file.getValue("prosody"), file.getValue("vocoder"), cpuThreads, tailTrim, minSamples, ms, xnnpackOff = xnnpackOff) }
            } catch (t: Throwable) { runCatching { neural.close() }; throw t }
            neural to synth
        }
        val g2p = KittenG2P(dictionary, symbolToId, neural::word)

        val notes = ArrayList<String>()
        notes += "family=kitten load_ms " + ms.entries.joinToString(" ") { "${it.key}=${it.value}" } + " (dictionary ${dictionary.size} words)"
        val xnnpack = if (xnnpackOff.isEmpty()) "xnnpack" else "xnnpack off on ${KittenSynthesizer.GRAPHS.filter { it in xnnpackOff }.joinToString("+")}"
        notes += "interpreter api ($xnnpack, $cpuThreads threads): predictor ${file.getValue("predictor").name}, prosody ${file.getValue("prosody").name}, vocoder ${file.getValue("vocoder").name}; g2p graph on CompiledModel cpu ($cpuThreads threads)"
        if (xnnpackOn == KittenSynthesizer.DEFAULT_XNNPACK) notes += "xnnpack default (predictor off): Galaxy S26, 2026-10-03, fp32: peak RSS after load 334 MB instead of 624 MB, median synthesis 301 ms instead of 288 ms, same frames"
        notes += "speed x speed_priors[voice] goes to the graph (say.py's pace at speed 1)" + if (priors.isEmpty()) "; no priors, so speed goes unchanged" else ": " + priors.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value}" }
        notes += "style row = min(chars, ${styleRows - 1}); tail_trim $tailTrim samples, min_samples $minSamples; max_chars $maxChars per call"
        notes += "observed backend is UNKNOWN: litert $runtimeVersion reports no per-op execution target; 'initialized' is the CPU the graphs were created for"
        val info = PreparedModelInfo(
            repoId = plan.ref.repoId, commit = plan.modelOrigin.commit, descriptorSha256 = plan.descriptorSha256, descriptorOrigin = plan.descriptorOrigin,
            bindingSource = plan.bindingSource, variantId = plan.variant.id, profileId = profile.id, sdkVersion = host.sdkVersion,
            handlerId = id, handlerAbi = abi, runtime = runtime, runtimeVersion = runtimeVersion,
            declaredInputs = plan.variant.inputs, enabledInputs = profile.enabledInputs, requestedBackendPolicy = plan.options.backendPolicy,
            components = mapOf("inference" to ComponentReport(requested = backend, initialized = BackendKind.CPU)),
            excludedProfiles = plan.excludedProfiles, fallbackHistory = emptyList(), verification = plan.verification,
            notes = notes, files = local.files.mapValues { it.value.absolutePath },
        )
        return LiteRtSpeaker(info, voices, sampleRate, maxChars, styles, priors, g2p, neural, synth, host)
    }

    private const val SAMPLE_RATE = 24000
}
