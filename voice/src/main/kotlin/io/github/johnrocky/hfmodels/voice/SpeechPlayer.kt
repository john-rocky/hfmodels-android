// The streaming writes follow google-ai-edge/litert-samples c18346a8, samples/litert/text_to_speech_streaming/.../MainViewModel.kt (Apache-2.0).
package io.github.johnrocky.hfmodels.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Speech out of the loudspeaker: mono float PCM in [-1, 1] at [sampleRate] (24000, the kitten speaker's)
 * through one [AudioTrack] in `MODE_STREAM` (`USAGE_ASSISTANT`, `CONTENT_TYPE_SPEECH`), written in blocking
 * slices of 200 ms so that a cancel takes effect within one slice. Give it to [VoiceLoopConfig.player]; the
 * loop plays each sentence while the next one is synthesized.
 *
 * [play] returns once the samples are written (about one second may still be queued); [drain] waits until
 * they have been played; [stop] drops what is queued at once. A run of audio is everything from the first
 * [play] after a [drain] or [stop] (or after creation) to the next [drain] or [stop]; [firstWriteAtNanos] is
 * when the run's first write began (`System.nanoTime()`), the moment the first sound left for the speaker.
 * One caller at a time for [play] and [drain]; [stop] may come from anywhere. [close] releases the track.
 */
class SpeechPlayer(val sampleRate: Int = 24000) : AutoCloseable {
    private val slice = sampleRate / 5
    private val track: AudioTrack

    init {
        require(slice > 0) { "sampleRate must be at least 5" }
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        check(minBytes > 0) { "this device cannot play $sampleRate Hz mono float PCM (getMinBufferSize $minBytes)" }
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            // One second of queue, so a sentence's writes rarely wait for the speaker.
            .setBufferSizeInBytes(maxOf(minBytes, sampleRate * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IllegalStateException("AudioTrack did not initialize ($sampleRate Hz mono float)")
        }
        // A stream starts by default only once its whole buffer is full, so a reply shorter than a second would
        // never sound; start as soon as 100 ms are queued.
        track.setStartThresholdInFrames(minOf(sampleRate / 10, track.bufferCapacityInFrames))
    }

    /** Frames written since the track was last flushed: the target of [AudioTrack.getPlaybackHeadPosition]. */
    private val written = AtomicLong(0)
    @Volatile private var runOpen = false
    /** Bumped by [stop]: a [play] or [drain] of an earlier generation ends. */
    @Volatile private var generation = 0
    @Volatile private var closed = false

    /** When the first write of the current (or last) run began, by `System.nanoTime()`; 0 before any. */
    @Volatile var firstWriteAtNanos: Long = 0L
        private set

    /** Writes [samples] in slices of 200 ms, blocking on a full queue (on an IO thread). A cancel stops the sound. */
    suspend fun play(samples: FloatArray) {
        check(!closed) { "SpeechPlayer is closed" }
        val gen = generation
        withContext(Dispatchers.IO) {
            try {
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                var offset = 0
                while (offset < samples.size && gen == generation) {
                    ensureActive()
                    if (!runOpen) {
                        firstWriteAtNanos = System.nanoTime()
                        runOpen = true
                    }
                    val n = track.write(samples, offset, minOf(slice, samples.size - offset), AudioTrack.WRITE_BLOCKING)
                    check(n >= 0) { "AudioTrack.write returned $n" }
                    offset += n
                    written.addAndGet(n.toLong())
                }
            } catch (e: CancellationException) {
                stop()
                throw e
            }
        }
    }

    /** Waits until everything written has been played (at most its length and 2 s more), then pauses the track. */
    suspend fun drain() {
        val gen = generation
        val target = written.get()
        val deadline = System.nanoTime() + (maxOf(0L, target - head()) * 1_000_000_000L / sampleRate) + DRAIN_SLACK_NS
        try {
            while (gen == generation && head() < target && System.nanoTime() < deadline) delay(DRAIN_POLL_MS)
        } catch (e: CancellationException) {
            stop()
            throw e
        }
        if (gen == generation) {
            runCatching { track.pause() }
            runOpen = false
        }
    }

    /** Stops the sound now and drops what is queued; a [play] in progress returns. */
    fun stop() {
        generation++
        // pause() returns a blocking write in progress; flush() then drops the queue and resets the head position.
        runCatching { track.pause() }
        runCatching { track.flush() }
        written.set(0)
        runOpen = false
    }

    override fun close() {
        if (closed) return
        closed = true
        stop()
        track.release()
    }

    /** The playback head as an unsigned 32-bit frame count (it resets on flush). */
    private fun head(): Long = track.playbackHeadPosition.toLong() and 0xffffffffL

    private companion object {
        const val DRAIN_POLL_MS = 20L
        const val DRAIN_SLACK_NS = 2_000_000_000L
    }
}
