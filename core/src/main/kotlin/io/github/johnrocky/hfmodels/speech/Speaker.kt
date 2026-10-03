package io.github.johnrocky.hfmodels.speech

import io.github.johnrocky.hfmodels.PreparedModel

/**
 * A speech synthesizer: text in, mono PCM out. One sentence or short chunk per call; splitting a
 * longer text is the caller's (`SentenceSplitter` in hfmodels-voice).
 *
 * One `synthesize` at a time per model; a second concurrent call fails with `MODEL_BUSY`.
 */
interface Speaker : PreparedModel {
    /** The voices this load offers, in the descriptor's order; `voices[0]` is the default. */
    val voices: List<String>

    /** Samples per second of [SpeechAudio.samples] (24000 for the kitten family). */
    val sampleRate: Int

    /**
     * One chunk's limit in characters (Unicode code points; 400 for the kitten family). Longer text is
     * `INVALID_INPUT`, and so is text that is empty or has no symbol the model knows.
     */
    val maxChars: Int

    /**
     * [voice]: one of [voices], null = `voices[0]`. [speed] goes to the model unchanged (1 = the
     * model's own pace; the publisher's `say.py` multiplies it by a per-voice prior, this call does not).
     */
    suspend fun synthesize(text: String, voice: String? = null, speed: Float = 1f): SpeechAudio

    /**
     * The symbol ids the synthesizer receives for this text, including the 0 at each end; for tests
     * and for showing what was said. Blocks while the out-of-dictionary graph runs.
     */
    fun phonemeIds(text: String): IntArray
}

/** [samples]: mono, in [-1, 1], at [sampleRate]. */
data class SpeechAudio(val samples: FloatArray, val sampleRate: Int, val timing: SpeechTiming)

/**
 * One call's wall clock on the device, not a benchmark: text to symbols, the graphs with the host
 * glue, and the whole call. [frames]: the number of 40 Hz acoustic frames the model gave the text
 * (600 samples each at 24 kHz, before the end of the audio is trimmed).
 */
data class SpeechTiming(val g2pMs: Double, val synthMs: Double, val totalMs: Double, val frames: Int)
