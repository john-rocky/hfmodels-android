package io.github.johnrocky.hfmodels.samples.translate

import io.github.johnrocky.hfmodels.voice.Endpointer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * hfmodels-voice's [Endpointer] with an early cut. While an utterance is open, [earlyMs] of silence after speech gives
 * [Event.Early] with the utterance so far, so its turn can start before the endpointer's hangover (800 ms) ends the
 * utterance. Voice before the hangover ends voids that early cut ([Event.Resumed]) and the utterance goes on; the
 * endpointer's own cut ([Event.Cut]) says whether the last early cut stands (no voice after it: the early audio is the
 * start of the cut's audio, and what follows it is silence).
 *
 * The endpointer still decides every utterance. This class feeds it whole frames and watches the same frames by the same
 * rule (voiced = an RMS at or above its start level), and keeps the open utterance's audio, which the endpointer hands
 * out only at its cut: the pre-roll the endpointer keeps while idle, then every frame of the utterance.
 *
 * Not thread-safe: one stream, one caller.
 */
class EarlyEndpointer(val endpointer: Endpointer, val earlyMs: Int = EARLY_MS) {
    sealed class Event {
        object SpeechStart : Event() { override fun toString() = "SpeechStart" }

        /** [earlyMs] of silence after speech: the utterance from its pre-roll to here. */
        class Early(val pcm: FloatArray) : Event() { override fun toString() = "Early(${pcm.size} samples)" }

        /** Voice again after an [Early], before the hangover ended: that early cut is void. */
        object Resumed : Event() { override fun toString() = "Resumed" }

        /** The endpointer's utterance; [earlyStands]: an [Early] came and no voice after it. */
        class Cut(val pcm: FloatArray, val earlyStands: Boolean) : Event() { override fun toString() = "Cut(${pcm.size} samples, earlyStands=$earlyStands)" }
    }

    private val frameSamples = endpointer.sampleRate * endpointer.frameMs / 1000
    private val earlyFrames = (earlyMs + endpointer.frameMs - 1) / endpointer.frameMs
    // The endpointer's own idle ring: the pre-roll and the start run (Endpointer.kt, preRollFrames + startFrames).
    private val idleCap = (if (endpointer.preRollMs <= 0) 0 else (endpointer.preRollMs + endpointer.frameMs - 1) / endpointer.frameMs) +
        (endpointer.startMs + endpointer.frameMs - 1) / endpointer.frameMs
    private val minMeanSquare = endpointer.startRms.toDouble() * endpointer.startRms

    init {
        require(earlyMs > 0 && earlyMs < endpointer.hangoverMs) { "earlyMs must be between 0 and the endpointer's hangover (${endpointer.hangoverMs} ms)" }
    }

    private val pending = FloatArray(frameSamples)
    private var pendingN = 0
    private var inSpeech = false
    private val idle = ArrayDeque<FloatArray>()
    private var utterance = FloatArray(0)
    private var utteranceN = 0
    private var silentRun = 0
    private var earlyOut = false

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
                frame(pending.copyOf(), events)
                pendingN = 0
            }
        }
        return events
    }

    /** The open utterance at the stream's end (the endpointer's flush, with the samples of an unfinished frame), or null. Resets the state. */
    fun flush(): Event.Cut? {
        endpointer.feed(pending.copyOf(pendingN))
        val open = endpointer.flush()?.let { Event.Cut(it.pcm, earlyStands = earlyOut && inSpeech) }
        resetOwn()
        return open
    }

    fun reset() {
        endpointer.reset()
        resetOwn()
    }

    private fun resetOwn() {
        pendingN = 0
        inSpeech = false
        idle.clear()
        utteranceN = 0
        silentRun = 0
        earlyOut = false
    }

    private fun frame(f: FloatArray, events: MutableList<Event>) {
        var ss = 0.0
        for (x in f) ss += x.toDouble() * x
        val voiced = ss / f.size >= minMeanSquare
        val out = endpointer.feed(f)
        if (!inSpeech) {
            idle.addLast(f)
            if (idle.size > idleCap) idle.removeFirst()
            if (out.any { it is Endpointer.Event.SpeechStart }) {
                inSpeech = true
                utteranceN = 0
                for (g in idle) append(g)
                idle.clear()
                silentRun = 0
                earlyOut = false
                events += Event.SpeechStart
            }
        } else {
            append(f)
            if (voiced) {
                silentRun = 0
                if (earlyOut) { earlyOut = false; events += Event.Resumed }
            } else if (++silentRun == earlyFrames && !earlyOut) {
                earlyOut = true
                events += Event.Early(utterance.copyOf(utteranceN))
            }
        }
        val cut = out.firstOrNull { it is Endpointer.Event.Utterance } as Endpointer.Event.Utterance?
        if (cut != null) {
            events += Event.Cut(cut.pcm, earlyStands = earlyOut)
            inSpeech = false
            utteranceN = 0
            silentRun = 0
            earlyOut = false
        }
    }

    private fun append(f: FloatArray) {
        if (utterance.size < utteranceN + f.size) utterance = utterance.copyOf(maxOf(utteranceN + f.size, utterance.size * 2, endpointer.sampleRate))
        System.arraycopy(f, 0, utterance, utteranceN, f.size)
        utteranceN += f.size
    }

    companion object {
        /** The silence after speech at which a turn starts early (the lane's decision before any number: 300 ms). */
        const val EARLY_MS = 300
    }
}

/**
 * The audio a turn starts from for each utterance in [pcm] (a file, as if spoken, cut at once): the standing early cut's
 * audio and [EarlyEndpointer.earlyMs] of silence as its hangover, or the endpointer's cut and its hangover when no early
 * cut stood; [voidedEarly] counts the early cuts that voice voided before it.
 */
class EarlyTurn(val pcm: FloatArray, val early: Boolean, val hangoverMs: Int, val voidedEarly: Int)

fun earlyTurnsOf(pcm: FloatArray, ep: EarlyEndpointer, trailMs: Int = ep.endpointer.hangoverMs + 200): List<EarlyTurn> {
    ep.reset()
    val chunk = ep.endpointer.sampleRate * 20 / 1000
    val all = pcm + FloatArray(ep.endpointer.sampleRate * trailMs / 1000)
    val out = ArrayList<EarlyTurn>()
    var early: FloatArray? = null
    var voided = 0
    fun cut(c: EarlyEndpointer.Event.Cut) {
        val e = early
        out += if (c.earlyStands && e != null) EarlyTurn(e, true, ep.earlyMs, voided) else EarlyTurn(c.pcm, false, ep.endpointer.hangoverMs, voided)
        early = null
        voided = 0
    }
    var i = 0
    while (i < all.size) {
        for (e in ep.feed(all.copyOfRange(i, minOf(i + chunk, all.size)))) when (e) {
            is EarlyEndpointer.Event.Early -> early = e.pcm
            EarlyEndpointer.Event.Resumed -> { early = null; voided++ }
            is EarlyEndpointer.Event.Cut -> cut(e)
            EarlyEndpointer.Event.SpeechStart -> {}
        }
        i += chunk
    }
    ep.flush()?.let { cut(it) }
    return out
}

/**
 * [pcm] and then [trailMs] of silence as a microphone delivers them: [chunkMs] chunks, each one, when [realTime], when a
 * microphone would have it (at the end of its span).
 */
fun wavChunks(pcm: FloatArray, trailMs: Int, realTime: Boolean, rate: Int = 16000, chunkMs: Int = 20): Flow<FloatArray> = flow {
    val chunk = rate * chunkMs / 1000
    val all = pcm + FloatArray(rate * trailMs / 1000)
    val start = System.nanoTime()
    var i = 0
    var k = 1
    while (i < all.size) {
        if (realTime) {
            val wait = (start + k * chunkMs * 1_000_000L - System.nanoTime()) / 1_000_000L
            if (wait > 0) delay(wait)
        }
        emit(all.copyOfRange(i, minOf(i + chunk, all.size)))
        i += chunk
        k++
    }
}
