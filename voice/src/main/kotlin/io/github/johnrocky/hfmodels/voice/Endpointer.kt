package io.github.johnrocky.hfmodels.voice

/**
 * Cuts a stream of mono PCM into utterances by frame energy, for a transcriber that takes one
 * window per call. Feed consecutive chunks of any size; the state is kept between calls.
 *
 * A frame of [frameMs] is voiced when its RMS is at least [startRms]. Speech starts after
 * [startMs] of consecutive voiced frames ([Event.SpeechStart]); the utterance begins at the first
 * of those frames. It ends once [hangoverMs] of consecutive unvoiced frames have passed, and the
 * [Event.Utterance] carries the audio from speech start to the end of the hangover. An utterance
 * that reaches [maxUtteranceMs] is cut there and emitted (never longer); the frames after the cut
 * go through the start rule again. [flush] returns the open utterance at stream end.
 *
 * Not thread-safe: one stream, one caller.
 */
class Endpointer(
    val sampleRate: Int = 16000,
    val startRms: Float = 0.02f,
    val startMs: Int = 100,
    val hangoverMs: Int = 800,
    val maxUtteranceMs: Int = 16000,
    val frameMs: Int = 20,
) {
    sealed class Event {
        object SpeechStart : Event() { override fun toString() = "SpeechStart" }

        /** The utterance from speech start to the end of the hangover, never longer than maxUtteranceMs. */
        data class Utterance(val pcm: FloatArray) : Event() {
            override fun equals(other: Any?) = other is Utterance && pcm.contentEquals(other.pcm)
            override fun hashCode() = pcm.contentHashCode()
            override fun toString() = "Utterance(${pcm.size} samples)"
        }
    }

    private val frameSamples = sampleRate * frameMs / 1000
    private val startFrames = (startMs + frameMs - 1) / frameMs
    private val hangoverFrames = (hangoverMs + frameMs - 1) / frameMs
    private val maxSamples = (sampleRate.toLong() * maxUtteranceMs / 1000).toInt()
    private val minMeanSquare = startRms.toDouble() * startRms

    init {
        require(frameSamples > 0 && startFrames > 0 && hangoverFrames > 0 && maxSamples >= frameSamples) { "frame, start, hangover and max lengths must be positive, and max at least one frame" }
    }

    private val pending = FloatArray(frameSamples)
    private var pendingN = 0
    private var inSpeech = false
    /** While idle: the consecutive voiced frames so far, kept so the utterance starts at the first of them. */
    private val run = FloatArray(startFrames * frameSamples)
    private var runFrames = 0
    private var utterance = FloatArray(0)
    private var utteranceN = 0
    private var silentFrames = 0

    /** Call with consecutive chunks of the stream. */
    fun feed(chunk: FloatArray): List<Event> {
        val events = ArrayList<Event>(2)
        var i = 0
        while (i < chunk.size) {
            val n = minOf(frameSamples - pendingN, chunk.size - i)
            System.arraycopy(chunk, i, pending, pendingN, n)
            pendingN += n
            i += n
            if (pendingN == frameSamples) {
                frame(events)
                pendingN = 0
            }
        }
        return events
    }

    /** The open utterance at stream end (with the samples of an unfinished frame), or null when no speech is open. Resets the state. */
    fun flush(): Event.Utterance? {
        val open = if (inSpeech) {
            append(pending, pendingN)
            Event.Utterance(utterance.copyOf(utteranceN))
        } else null
        reset()
        return open
    }

    fun reset() {
        pendingN = 0
        inSpeech = false
        runFrames = 0
        utteranceN = 0
        silentFrames = 0
    }

    private fun frame(events: MutableList<Event>) {
        var ss = 0.0
        for (k in 0 until frameSamples) ss += pending[k].toDouble() * pending[k]
        val voiced = ss / frameSamples >= minMeanSquare
        if (!inSpeech) {
            if (!voiced) { runFrames = 0; return }
            System.arraycopy(pending, 0, run, runFrames * frameSamples, frameSamples)
            runFrames++
            if (runFrames < startFrames) return
            inSpeech = true
            silentFrames = 0
            utteranceN = 0
            events += Event.SpeechStart
            append(run, runFrames * frameSamples)
            runFrames = 0
        } else {
            append(pending, frameSamples)
            silentFrames = if (voiced) 0 else silentFrames + 1
        }
        if (utteranceN >= maxSamples || silentFrames >= hangoverFrames) {
            events += Event.Utterance(utterance.copyOf(utteranceN))
            inSpeech = false
            utteranceN = 0
            silentFrames = 0
        }
    }

    /** Appends up to the max length; what does not fit is dropped (the cut). */
    private fun append(src: FloatArray, n: Int) {
        val take = minOf(n, maxSamples - utteranceN)
        if (take <= 0) return
        if (utterance.size < utteranceN + take) utterance = utterance.copyOf(minOf(maxSamples, maxOf(utteranceN + take, utterance.size * 2, sampleRate)))
        System.arraycopy(src, 0, utterance, utteranceN, take)
        utteranceN += take
    }
}
