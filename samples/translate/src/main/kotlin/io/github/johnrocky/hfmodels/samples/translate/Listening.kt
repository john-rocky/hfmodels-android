package io.github.johnrocky.hfmodels.samples.translate

import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * When a turn may make its first sound: open at once for a turn that starts at the endpointer's cut; for a turn that
 * starts at an early cut, opened at the endpointer's cut (or a press), when the early cut stood.
 */
class Gate {
    private val opened = CompletableDeferred<Unit>()

    /** `System.nanoTime()` when it opened; 0 while closed. */
    @Volatile var openedAtNanos: Long = 0L
        private set

    val isOpen: Boolean get() = opened.isCompleted

    @Synchronized fun open(atNanos: Long = System.nanoTime()) {
        if (opened.isCompleted) return
        openedAtNanos = atNanos
        opened.complete(Unit)
    }

    suspend fun await() = opened.await()

    companion object {
        fun opened(atNanos: Long = System.nanoTime()) = Gate().apply { open(atNanos) }
    }
}

/**
 * A turn the listening starts: the audio it hears, the cut its clock starts from ([cutNanos] `System.nanoTime()`,
 * [cutElapsedNanos] `SystemClock.elapsedRealtimeNanos()`), the silence at that cut ([hangoverMs]: the early cut's 300 ms,
 * the endpointer's 800 ms, or 0 when a press ended it), whether it started early, the early turns cancelled before it,
 * the listening's early cut ([specMs], 0 without one) and the gate its first sound waits for.
 */
class TurnStart(
    val pcm: FloatArray,
    val cutNanos: Long,
    val cutElapsedNanos: Long,
    val hangoverMs: Int,
    val early: Boolean,
    val discardedEarly: Int,
    val specMs: Int,
    val gate: Gate,
    val input: String,
)

/** How one press ended: the turn that stood (null when nothing was heard) and the early turns cancelled on the way. */
class Heard(val turn: Job?, val start: TurnStart?, val discardedEarly: Int, val nothingHeard: String?, val stoppedByPress: Boolean)

/**
 * One press of a button: [chunks] (the microphone's, or a WAV's at the microphone's pace) through [ep], and the turn of
 * the first utterance it cuts. With [early], an early cut starts a turn at once ([startTurn], its gate closed: the turn
 * hears, translates and synthesizes, and makes no sound before the gate opens); voice before the hangover ends cancels
 * that turn and the utterance goes on; the endpointer's cut opens the gate when the early cut stood, or starts the turn
 * with the whole utterance when it did not. [stopRequested] (a second press) ends the utterance where it is. With no
 * speech start within [noSpeechMs] (null: no limit, a file) it is "Nothing heard"; so is a stream that ends with no
 * speech. The listening ends at the first utterance; the turn goes on in [startTurn]'s job.
 */
suspend fun listen(
    chunks: Flow<FloatArray>,
    ep: EarlyEndpointer,
    early: Boolean,
    input: String,
    stopRequested: () -> Boolean,
    noSpeechMs: Long?,
    startTurn: (TurnStart) -> Job,
    onChunk: (chunk: FloatArray, speech: Boolean) -> Unit = { _, _ -> },
): Heard {
    ep.reset()
    val opened = System.nanoTime()
    var started = false
    var pressed = false
    var discarded = 0
    var spec: Pair<TurnStart, Job>? = null
    var result: Heard? = null

    fun cancelSpec() {
        spec?.second?.cancel()
        spec = null
        discarded++
    }
    val specMs = if (early) ep.earlyMs else 0
    fun startNow(pcm: FloatArray, hangoverMs: Int, inputName: String): Heard {
        val now = System.nanoTime()
        val s = TurnStart(pcm, now, SystemClock.elapsedRealtimeNanos(), hangoverMs, early = false, discardedEarly = discarded, specMs = specMs, gate = Gate.opened(now), input = inputName)
        return Heard(startTurn(s), s, discarded, null, pressed)
    }
    fun cut(c: EarlyEndpointer.Event.Cut, hangoverMs: Int, inputName: String): Heard {
        val s = spec
        if (c.earlyStands && s != null) {
            s.first.gate.open()
            spec = null
            return Heard(s.second, s.first, discarded, null, pressed)
        }
        if (s != null) cancelSpec()
        return startNow(c.pcm, hangoverMs, inputName)
    }
    fun step(chunk: FloatArray): Boolean {
        val events = ep.feed(chunk)
        if (events.any { it is EarlyEndpointer.Event.SpeechStart }) started = true
        onChunk(chunk, started)
        for (e in events) {
            when (e) {
                EarlyEndpointer.Event.SpeechStart -> {}
                is EarlyEndpointer.Event.Early -> if (early) {
                    if (spec != null) cancelSpec()
                    val s = TurnStart(e.pcm, System.nanoTime(), SystemClock.elapsedRealtimeNanos(), ep.earlyMs, early = true, discardedEarly = discarded, specMs = specMs, gate = Gate(), input = input)
                    spec = s to startTurn(s)
                }
                EarlyEndpointer.Event.Resumed -> if (spec != null) cancelSpec()
                is EarlyEndpointer.Event.Cut -> { result = cut(e, ep.endpointer.hangoverMs, input); return true }
            }
        }
        if (stopRequested()) {
            pressed = true
            val c = ep.flush()
            result = if (c == null) Heard(null, null, discarded, "no speech after the button", true) else cut(c, 0, "$input-press")
            return true
        }
        if (!started && noSpeechMs != null && (System.nanoTime() - opened) / 1_000_000L > noSpeechMs) {
            result = Heard(null, null, discarded, "no speech after the button", false)
            return true
        }
        return false
    }

    try {
        try {
            chunks.first { step(it) }
        } catch (e: NoSuchElementException) {
            // The stream ended (a file): the open utterance, if any, is the one.
            val c = ep.flush()
            result = if (c == null) Heard(null, null, discarded, "the endpointer found no speech", false) else cut(c, ep.endpointer.hangoverMs, input)
        }
        return result!!
    } finally {
        // Cancelled, failed or timed out with an early turn still waiting for its cut: that turn goes too.
        if (result == null) spec?.second?.cancel()
    }
}
