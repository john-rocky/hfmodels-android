package io.github.johnrocky.hfmodels.samples.translate

import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.speech.Speaker
import io.github.johnrocky.hfmodels.voice.SentenceSplitter
import io.github.johnrocky.hfmodels.voice.SpeechPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** What a turn needs from the loudspeaker: [SpeechPlayer]'s calls (a fake in the JVM tests). */
interface Player {
    suspend fun play(samples: FloatArray)
    suspend fun drain()
    fun stop()
    /** `System.nanoTime()` of the current (or last) run's first write; 0 before any. */
    val firstWriteAtNanos: Long
}

fun SpeechPlayer.asPlayer(): Player = object : Player {
    override suspend fun play(samples: FloatArray) = this@asPlayer.play(samples)
    override suspend fun drain() = this@asPlayer.drain()
    override fun stop() = this@asPlayer.stop()
    override val firstWriteAtNanos: Long get() = this@asPlayer.firstWriteAtNanos
}

/**
 * One utterance through the chain, as [turn]'s events: the input gate ([InputGate]), hearing ([hear]: Qwen3-ASR, the
 * language the button gives), translating ([translate]: Hy-MT2, streamed), and for [Direction.spoken] the English said piece by piece while the
 * rest is still being translated: the first piece at the first clause mark after four words ([SpeechCutter]), then each
 * finished sentence, rewritten for the speaker ([EnglishSpeech]), cut into the speaker's chunks
 * (`SentenceSplitter.split`), synthesized on one coroutine and played on another. Nothing is played before the turn's
 * [Gate] opens: a turn that starts at an early cut hears, translates and synthesizes while the endpointer still waits,
 * and sounds only once the utterance is cut. The loop owns none of the models and not the player.
 *
 * Times are milliseconds from the cut the turn started from (`cutAtNanos`: the early cut, or the endpointer's end, which
 * includes its hangover of silence), as hfmodels-voice counts them; a listener hears the first sound
 * `hangoverMs + firstAudioMs` after they stop talking ([TurnRecord.endOfSpeechToSoundMs]).
 */
class TranslateLoop(
    private val hear: suspend (pcm: FloatArray, direction: Direction) -> AsrResult,
    private val translate: (text: String, target: String, onCreated: (Double) -> Unit) -> Flow<String>,
    private val speaker: Speaker?,
    private val player: Player?,
    val config: Config = Config(),
) {
    data class Config(
        val startRms: Float = io.github.johnrocky.hfmodels.voice.Endpointer.DEFAULT_START_RMS,
        val floorRms: Float = InputGate.DEFAULT_FLOOR_RMS,
        val voice: String? = null,
        val speed: Float = 1f,
        /** Keep the samples as played, for a recording's English track ([TurnRecord.playedAudio]). */
        val keepAudio: Boolean = false,
    )

    sealed class Event {
        data class Heard(val text: String, val audioMs: Double, val transcribeMs: Double) : Event()
        data class NothingHeard(val reason: String) : Event()
        data class Translation(val text: String) : Event()
        data class Speaking(val sentence: String, val synthMs: Double, val firstAudioMs: Double?) : Event()
        data class Error(val stage: String, val message: String) : Event()
        data class Done(val record: TurnRecord) : Event()
    }

    private val lock = Mutex()

    /**
     * [gate]: opened = may sound at once; closed = the turn started at an early cut and sounds when it opens.
     * [early], [discardedEarly], [specMs]: what the TURN line says of the early start (whether this turn started at an
     * early cut, the early turns cancelled before it, the early cut's silence; 0 when the listening had no early cut).
     */
    fun turn(
        direction: Direction, pcm: FloatArray, cutAtNanos: Long, hangoverMs: Int, input: String,
        gate: Gate = Gate.opened(cutAtNanos), early: Boolean = false, discardedEarly: Int = 0, specMs: Int = 0,
    ): Flow<Event> = channelFlow {
        lock.withLock {
            val r = TurnRecord(direction, input, hangoverMs, pcm.size * 1000.0 / SAMPLE_RATE, early, discardedEarly, specMs, cutAtNanos, gate)
            run(direction, pcm, cutAtNanos, r)
        }
    }

    private suspend fun ProducerScope<Event>.run(direction: Direction, pcm: FloatArray, t0: Long, r: TurnRecord) {
        try {
            steps(direction, pcm, t0, r)
        } catch (e: CancellationException) {
            player?.stop()
            throw e
        } finally {
            r.totalMs = (System.nanoTime() - t0) / 1e6
        }
        send(Event.Done(r))
    }

    /** The turn up to its end; returns early when nothing was heard or hearing failed ([run] sends Done either way). */
    private suspend fun ProducerScope<Event>.steps(direction: Direction, pcm: FloatArray, t0: Long, r: TurnRecord) {
        fun since(at: Long) = (at - t0) / 1e6
        val level = InputGate.check(pcm, SAMPLE_RATE, config.startRms, config.floorRms)
        r.rms = level.rms
        r.voicedMs = level.voicedMs
        if (!level.heard) {
            r.nothingHeard = level.reason
            send(Event.NothingHeard(level.reason))
            return
        }
        val heard = try {
            hear(pcm, direction)
        } catch (e: ModelException) {
            r.errors += "hearing: ${e.code}: ${e.reason}"
            send(Event.Error("hearing", "${e.code}: ${e.reason}"))
            return
        }
        val transcribeMs = since(System.nanoTime())
        r.transcribeMs = transcribeMs
        r.asr = heard
        r.heard = heard.text.trim()
        send(Event.Heard(r.heard, r.audioMs, transcribeMs))
        if (r.heard.isEmpty()) {
            r.nothingHeard = "no words in the audio"
            send(Event.NothingHeard("no words in the audio"))
            return
        }
        val speech = if (direction.spoken && speaker != null) Speech(this, t0, r) else null
        val cutter = SpeechCutter()
        val text = StringBuilder()
        fun sentenceEnded() { if (r.firstSentenceMs == null && cutter.sentencesEnded > 0) r.firstSentenceMs = since(System.nanoTime()) }
        try {
            translate(r.heard, direction.target) { ms -> r.translateCreateMs = ms }.collect { delta ->
                if (r.translateFirstTokenMs == null) r.translateFirstTokenMs = since(System.nanoTime())
                r.tokens++
                text.append(delta)
                send(Event.Translation(text.toString().trim()))
                val pieces = cutter.add(delta)
                sentenceEnded()
                speech?.say(pieces)
            }
            r.translateDoneMs = since(System.nanoTime())
            val rest = cutter.flush()
            sentenceEnded()
            speech?.say(rest)
        } catch (e: ModelException) {
            r.translateDoneMs = since(System.nanoTime())
            r.errors += "translating: ${e.code}: ${e.reason}"
            send(Event.Error("translating", "${e.code}: ${e.reason}"))
        }
        r.translation = text.toString().trim()
        speech?.finish()
    }

    /**
     * The English of one turn, said: pieces in, synthesized in order on one coroutine and, with a player, played on
     * another once the turn's gate is open, so the next piece is synthesized while one plays. A chunk with nothing to say
     * (INVALID_INPUT) is skipped; any other speaker or player failure ends the turn's speech (the text is still shown).
     */
    private inner class Speech(private val scope: ProducerScope<Event>, private val t0: Long, private val r: TurnRecord) {
        private val chunks = Channel<String>(Channel.UNLIMITED)
        private val audio = Channel<FloatArray>(Channel.UNLIMITED)
        @Volatile private var failed = false
        private fun since(at: Long) = (at - t0) / 1e6

        private val synth = scope.launch {
            for (c in chunks) {
                if (failed) continue
                val a = try {
                    speaker!!.synthesize(c, config.voice, config.speed)
                } catch (e: ModelException) {
                    if (e.code != ErrorCode.INVALID_INPUT) {
                        failed = true
                        r.errors += "voice: ${e.code}: ${e.reason}"
                        scope.send(Event.Error("voice", "${e.code}: ${e.reason}"))
                    }
                    continue
                }
                val done = System.nanoTime()
                val first = r.firstSynthMs == null
                if (first) { r.firstSynthMs = since(done); r.audioReadyMs = r.firstSynthMs }
                r.spoken += c
                if (player != null) audio.send(a.samples) else if (config.keepAudio) r.playedAudio += a.samples
                val firstAudioMs = when {
                    !first -> null
                    player == null -> { r.gate.await(); since(maxOf(done, System.nanoTime())) }
                    else -> { r.gate.await(); awaitFirstWrite()?.let(::since) }
                }
                scope.send(Event.Speaking(c, a.timing.totalMs, firstAudioMs))
            }
            audio.close()
        }

        private val play = player?.let { p ->
            scope.launch {
                for (pcm in audio) {
                    if (failed) continue
                    // The first sound waits for the cut of the utterance (an early turn's gate is closed until then).
                    r.gate.await()
                    try {
                        if (config.keepAudio) { r.playedAudio += pcm; r.playedAtNanos += System.nanoTime() }
                        p.play(pcm)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed = true
                        r.errors += "player: ${e.javaClass.simpleName}: ${e.message}"
                        scope.send(Event.Error("voice", "player: ${e.javaClass.simpleName}: ${e.message}"))
                    }
                }
            }
        }

        /** The player is idle when the first chunk comes, so its first write starts within milliseconds of the gate. */
        private suspend fun awaitFirstWrite(): Long? = withTimeoutOrNull(FIRST_WRITE_WAIT_MS) {
            var at = player!!.firstWriteAtNanos
            while (at < t0 && !failed) { delay(1); at = player.firstWriteAtNanos }
            at.takeIf { it >= t0 }
        }

        /** Pieces of the translation: rewritten for the speaker and cut into its chunks. */
        fun say(pieces: List<String>) {
            for (s in pieces) {
                val spoken = EnglishSpeech.rewrite(s)
                if (spoken.isBlank()) continue
                if (r.firstChunkMs == null) { r.firstChunkMs = since(System.nanoTime()); r.firstChunk = s }
                for (c in SentenceSplitter.split(spoken, speaker!!.maxChars)) chunks.trySend(c)
            }
        }

        suspend fun finish() {
            chunks.close()
            synth.join()
            play?.join()
            if (player != null && !failed) {
                player.drain()
                r.firstAudioMs = player.firstWriteAtNanos.takeIf { it >= t0 }?.let(::since)
            } else if (player == null) {
                r.firstAudioMs = r.firstSynthMs?.let { s -> r.gate.await(); maxOf(s, r.gateMs ?: 0.0) }
            }
        }
    }

    private companion object {
        const val FIRST_WRITE_WAIT_MS = 2_000L
    }
}

/**
 * One turn's record: the TURN log line, the screen's seconds, and a recording's English track. Milliseconds count from
 * the cut the turn started from ([cutAtNanos]); null = did not happen.
 */
class TurnRecord(
    val direction: Direction,
    val input: String,
    val hangoverMs: Int,
    val audioMs: Double,
    /** The turn started at an early cut ([EarlyEndpointer]), [hangoverMs] of silence after speech. */
    val early: Boolean = false,
    /** Early turns cancelled before this one in the same press (voice came back before the hangover ended). */
    val discardedEarly: Int = 0,
    /** The listening's early cut (300 ms), 0 when it had none. */
    val specMs: Int = 0,
    val cutAtNanos: Long = 0L,
    val gate: Gate = Gate.opened(cutAtNanos),
) {
    var heard: String = ""
    var translation: String = ""
    val spoken = ArrayList<String>()
    /** With [TranslateLoop.Config.keepAudio]: the speaker's chunks in the order played, and when each one's write began (`System.nanoTime()`). */
    val playedAudio = ArrayList<FloatArray>()
    val playedAtNanos = ArrayList<Long>()
    var nothingHeard: String? = null
    val errors = ArrayList<String>()
    var rms: Double = 0.0
    var voicedMs: Int = 0
    var asr: AsrResult? = null
    var transcribeMs: Double? = null
    var translateCreateMs: Double? = null
    var translateFirstTokenMs: Double? = null
    var translateDoneMs: Double? = null
    var tokens: Int = 0
    var firstSentenceMs: Double? = null
    /** The first piece the speaker got and when (a clause, or the first sentence when it ended first). */
    var firstChunkMs: Double? = null
    var firstChunk: String = ""
    var firstSynthMs: Double? = null
    /** The first chunk's audio ready to play, before the gate. */
    var audioReadyMs: Double? = null
    var firstAudioMs: Double? = null
    var totalMs: Double = 0.0

    /** When the gate opened (the turn may sound), or null while closed. */
    val gateMs: Double? get() = gate.openedAtNanos.takeIf { gate.isOpen }?.let { maxOf(0.0, (it - cutAtNanos) / 1e6) }

    /** From the end of speech to the first English sound: the silence at the cut plus [firstAudioMs]. */
    val endOfSpeechToSoundMs: Double? get() = firstAudioMs?.let { it + hangoverMs }

    /** From the end of speech to the whole translation on the screen (not before the gate: the screen shows the turn then). */
    val endOfSpeechToTextMs: Double? get() = translateDoneMs?.let { maxOf(it, gateMs ?: 0.0) + hangoverMs }
}
