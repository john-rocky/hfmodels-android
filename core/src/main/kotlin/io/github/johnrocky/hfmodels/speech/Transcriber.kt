package io.github.johnrocky.hfmodels.speech

import io.github.johnrocky.hfmodels.PreparedModel

/**
 * A speech recognizer: mono PCM in, text out. One fixed window per call; audio longer than
 * [TranscriberLimits.windowSeconds] is `INVALID_INPUT` (consecutive windows are not handled here),
 * shorter audio is padded to the window.
 *
 * One call at a time per model; a second concurrent call fails with `MODEL_BUSY`.
 */
interface Transcriber : PreparedModel {
    val limits: TranscriberLimits

    /** [pcm]: mono samples at [TranscriberLimits.sampleRate], in [-1, 1]. */
    suspend fun transcribe(pcm: FloatArray): Transcript
}

/** What this load can take. */
data class TranscriberLimits(
    /** Samples per second the model expects (16000 for the zipformer family). */
    val sampleRate: Int,
    /** The longest audio one call accepts, in seconds (16 for the zipformer family). */
    val windowSeconds: Double,
    /** Languages the publisher declares for this variant (BCP-47); informational. */
    val languages: List<String>,
)

data class Transcript(val text: String, val timing: TranscriptTiming)

/** One call's wall clock on the device, not a benchmark: the host front-end, the graph run with its readback, and the whole call. */
data class TranscriptTiming(val featureMs: Double, val inferenceMs: Double, val totalMs: Double)
