package io.github.johnrocky.hfmodels.samples.translate

import io.github.johnrocky.hfmodels.samples.translate.TranslateLoop.Event
import java.util.Locale

/** One model on the screen: its name, where it runs and how long its load took. */
data class ModelLine(val name: String, val backend: String, val loadMs: Double?, val note: String = "") {
    val text: String get() = "$name · $backend" + (loadMs?.let { " · loaded in ${seconds(it)}" } ?: "") + (if (note.isEmpty()) "" else " · $note")
}

/**
 * What the screen shows. [on] folds a turn's events into it: what was heard, the translation as it streams, and at the
 * end the seconds from the end of speech to the English voice (Spanish to English) or to the whole Spanish text
 * (English to Spanish), with their parts.
 */
data class TranslateUi(
    val status: String = "Not loaded",
    val loading: Boolean = false,
    val ready: Boolean = false,
    /** The button whose microphone is open, or null. */
    val listening: Direction? = null,
    /** The direction of the turn on the screen (running or last). */
    val turn: Direction? = null,
    val busy: Boolean = false,
    val heard: String = "",
    val translation: String = "",
    val result: String = "",
    val breakdown: String = "",
    val models: List<ModelLine> = emptyList(),
    val network: String = "",
    val error: String? = null,
) {
    /** While the models load, a microphone is open or a turn runs (MainActivity keeps the screen on). */
    val keepsScreenOn: Boolean get() = loading || listening != null || busy
}

fun TranslateUi.startTurn(direction: Direction): TranslateUi =
    copy(busy = true, turn = direction, heard = "", translation = "", result = "", breakdown = "", error = null, status = "Hearing…")

fun TranslateUi.on(e: Event): TranslateUi = when (e) {
    is Event.NothingHeard -> copy(status = "Nothing heard", error = null)
    is Event.Heard -> copy(heard = e.text, status = if (e.text.isBlank()) "Nothing heard" else "Translating…")
    is Event.Translation -> copy(translation = e.text)
    is Event.Speaking -> copy(status = "Speaking…")
    is Event.Error -> copy(error = "${stageName(e.stage)} failed: ${e.message}")
    is Event.Done -> copy(busy = false, status = "Ready", translation = e.record.translation.ifEmpty { translation }, result = result(e.record), breakdown = breakdown(e.record))
}

private fun stageName(stage: String) = when (stage) {
    "hearing" -> "Hearing"
    "translating" -> "Translation"
    "voice" -> "The English voice"
    else -> stage
}

/** "Spanish speech to English voice in 2.9 s" / "English speech to Spanish text in 2.1 s"; empty when it did not happen. */
fun result(r: TurnRecord): String {
    if (r.nothingHeard != null) return ""
    return if (r.direction.spoken) {
        r.endOfSpeechToSoundMs?.let { "${r.direction.heard} speech to ${r.direction.target} voice in ${seconds(it)}" } ?: if (r.translation.isEmpty()) "" else "No sound"
    } else {
        r.endOfSpeechToTextMs?.takeIf { r.translation.isNotEmpty() }?.let { "${r.direction.heard} speech to ${r.direction.target} text in ${seconds(it)}" } ?: ""
    }
}

/**
 * The parts of [result]: the end-of-speech wait (the endpointer's hangover), hearing (parakeet), translating (to the first
 * finished English sentence, or to the whole Spanish text) and the voice (that sentence synthesized, to its first sound).
 */
fun breakdown(r: TurnRecord): String {
    val heard = r.transcribeMs ?: return ""
    val parts = ArrayList<String>()
    if (r.hangoverMs > 0) parts += "${ms(r.hangoverMs.toDouble())} end of speech"
    parts += "${ms(heard)} hearing"
    if (r.direction.spoken) {
        val audio = r.firstAudioMs ?: return ""
        val sentence = r.firstSentenceMs
        if (sentence != null) {
            parts += "${ms(sentence - heard)} translating"
            parts += "${ms(audio - sentence)} voice"
        } else {
            parts += "${ms(audio - heard)} translating and voice"
        }
    } else {
        val done = r.translateDoneMs ?: return ""
        parts += "${ms(done - heard)} translating"
    }
    return parts.joinToString(" + ", "(", ")")
}

/** 640 -> "640 ms", 1234 -> "1.2 s". */
fun ms(v: Double): String = if (v < 1000) String.format(Locale.US, "%.0f ms", v) else seconds(v)

/** 2940 -> "2.9 s". */
fun seconds(v: Double): String = String.format(Locale.US, "%.1f s", v / 1000)

/**
 * The TURN line of one turn (`adb logcat -s translate`): what was heard and said, every part in milliseconds from the
 * cut of the utterance, the backends, the network and the process's peak memory.
 */
fun turnLine(r: TurnRecord, backends: String, network: String, vmHwmKb: Long?): String {
    val a = r.asr
    return "TURN dir=${r.direction.tag} input=${r.input} heard=${q(r.heard)} translation=${q(r.translation)} spoken=${q(r.spoken.joinToString(" "))} " +
        "hangover_ms=${r.hangoverMs} ms_first_audio=${f(r.firstAudioMs)} ms_end_of_speech_to_sound=${f(r.endOfSpeechToSoundMs)} " +
        "ms_end_of_speech_to_text=${f(r.endOfSpeechToTextMs)} ms_transcribe=${f(r.transcribeMs)} " +
        "asr_feature_ms=${f(a?.featureMs)} asr_encode_ms=${f(a?.encodeMs)} asr_decode_ms=${f(a?.decodeMs)} asr_steps=${a?.steps ?: "none"} asr_pieces=${a?.pieces ?: "none"} " +
        "ms_translate_create=${f1(r.translateCreateMs)} ms_translate_first_token=${f(r.translateFirstTokenMs)} ms_translate_done=${f(r.translateDoneMs)} " +
        "ms_first_sentence=${f(r.firstSentenceMs)} ms_first_synth=${f(r.firstSynthMs)} ms_total=${f(r.totalMs)} tokens=${r.tokens} " +
        "backend=$backends audio_ms=${f(r.audioMs)} rms=${String.format(Locale.US, "%.4f", r.rms)} voiced_ms=${r.voicedMs} " +
        "nothing_heard=${q(r.nothingHeard ?: "")} errors=${r.errors.joinToString(" | ", "[", "]") { it.take(160) }} network=$network vmhwm_kb=${vmHwmKb ?: "none"}"
}

private fun f(v: Double?) = v?.let { String.format(Locale.US, "%.0f", it) } ?: "none"

private fun f1(v: Double?) = v?.let { String.format(Locale.US, "%.1f", it) } ?: "none"

/** A log value in double quotes: new lines as \n, double quotes as single. */
fun q(s: String) = "\"" + s.replace("\n", "\\n").replace("\"", "'") + "\""
