package io.github.johnrocky.hfmodels.litert

import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.PrepareHost
import io.github.johnrocky.hfmodels.PreparedModelInfo
import io.github.johnrocky.hfmodels.speech.Speaker
import io.github.johnrocky.hfmodels.speech.SpeechAudio
import io.github.johnrocky.hfmodels.speech.SpeechTiming
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * KittenTTS on classic LiteRT: [KittenG2P] (dictionary + the out-of-dictionary graph) -> the style row
 * for the text's length -> [KittenSynthesizer], with `speed` times the voice's prior. Every native call runs on the shared LiteRT thread
 * ([LiteRtDecisionModel.Runtime]); the G2P runs there whole, so an out-of-dictionary word does not hop
 * threads. One `synthesize` at a time.
 */
internal class LiteRtSpeaker(
    override val info: PreparedModelInfo,
    override val voices: List<String>,
    override val sampleRate: Int,
    override val maxChars: Int,
    private val styles: Map<String, NpzVoices.Table>,
    /** The descriptor's `speed_priors`: the voice's factor on `speed` before the graph (none: 1). */
    private val priors: Map<String, Double>,
    private val g2p: KittenG2P,
    private val neural: KittenNeuralG2P,
    private val synth: KittenSynthesizer,
    private val host: PrepareHost,
) : Speaker {
    private val busy = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val closed = CompletableDeferred<Unit>()

    override suspend fun synthesize(text: String, voice: String?, speed: Float): SpeechAudio {
        checkUsable()
        val name = voice ?: voices[0]
        val table = styles[name] ?: throw ModelException(ErrorCode.INVALID_INPUT, "unknown voice '$name' (this model has ${voices.joinToString()})", details = mapOf("voice" to name))
        if (!speed.isFinite() || speed <= 0f) throw ModelException(ErrorCode.INVALID_INPUT, "speed $speed: expected a finite value above 0")
        val chars = checkText(text, maxChars)
        if (!busy.compareAndSet(false, true)) throw ModelException(ErrorCode.MODEL_BUSY, "a synthesis is already running on ${info.repoId} (one at a time per model)")
        try {
            val t0 = System.nanoTime()
            val ids = withContext(LiteRtDecisionModel.Runtime.dispatcher) { symbols(text) }
            val t1 = System.nanoTime()
            // The pip package's lookup: one style row per text length, the last row for anything longer.
            val style = table.row(minOf(chars, table.rows - 1))
            val out = withContext(LiteRtDecisionModel.Runtime.dispatcher) {
                checkNotClosed()
                try { synth.synthesize(ids, style, graphSpeed(speed, priors[name] ?: 1.0)) } catch (t: Throwable) {
                    throw ModelException(ErrorCode.INFERENCE_FAILED, "synthesis failed: ${t.javaClass.simpleName}: ${t.message}", details = mapOf("stage" to "synthesize", "symbols" to ids.size.toString()), cause = t)
                }
            }
            val t2 = System.nanoTime()
            return SpeechAudio(out.samples, sampleRate, SpeechTiming(g2pMs = (t1 - t0) / 1e6, synthMs = (t2 - t1) / 1e6, totalMs = (t2 - t0) / 1e6, frames = out.frames))
        } finally {
            busy.set(false)
        }
    }

    override fun phonemeIds(text: String): IntArray {
        checkUsable()
        checkText(text, maxChars)
        return LiteRtDecisionModel.Runtime.call { symbols(text) }
    }

    /** The G2P on the LiteRT thread; text with no symbol the model knows is INVALID_INPUT. */
    private fun symbols(text: String): IntArray {
        checkNotClosed()
        val ids = try { g2p.ids(text) } catch (t: Throwable) {
            throw ModelException(ErrorCode.INFERENCE_FAILED, "g2p failed: ${t.javaClass.simpleName}: ${t.message}", details = mapOf("stage" to "g2p"), cause = t)
        }
        if (ids.size <= 2) throw ModelException(ErrorCode.INVALID_INPUT, "the text has no symbol the model knows")
        return ids
    }

    private fun checkUsable() { if (closing.get()) throw ModelException(ErrorCode.MODEL_CLOSED, "model ${info.repoId} is closing or closed") }

    // close() may have run on the LiteRT thread while this call waited for it.
    private fun checkNotClosed() { if (closed.isCompleted) throw ModelException(ErrorCode.MODEL_CLOSED, "model ${info.repoId} was closed during the call") }

    override fun close() { if (closing.compareAndSet(false, true)) LiteRtDecisionModel.Runtime.executor.execute { doClose() } }

    override suspend fun closeAndJoin() {
        val first = closing.compareAndSet(false, true)
        withContext(NonCancellable) {
            if (first) LiteRtDecisionModel.Runtime.call { doClose() }
            closed.await()
        }
    }

    private fun doClose() {
        if (closed.isCompleted) return
        runCatching { synth.close() }.onFailure { host.log.w("speaker graphs close: ${it.message}") }
        runCatching { neural.close() }.onFailure { host.log.w("g2p graph close: ${it.message}") }
        host.onModelClosed(this)
        closed.complete(Unit)
    }

    companion object {
        /** say.py's `speed * SPEED_PRIORS.get(voice, 1.0)` in double, then float32 for the graph: 1.25 x 0.8 is 1.0 exactly. */
        fun graphSpeed(speed: Float, prior: Double): Float = (speed.toDouble() * prior).toFloat()

        /** Empty, or longer than [maxChars] code points: INVALID_INPUT. Returns the length in code points (Python's `len`). */
        fun checkText(text: String, maxChars: Int): Int {
            if (text.isBlank()) throw ModelException(ErrorCode.INVALID_INPUT, "the text is empty")
            val chars = text.codePointCount(0, text.length)
            if (chars > maxChars) throw ModelException(
                ErrorCode.INVALID_INPUT, "$chars characters is longer than one chunk ($maxChars); split the text (SentenceSplitter in hfmodels-voice)",
                details = mapOf("chars" to chars.toString(), "max_chars" to maxChars.toString()),
            )
            return chars
        }
    }
}
