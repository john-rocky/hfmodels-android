package io.github.johnrocky.hfmodels.samples.eg2search

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

/**
 * The hold-to-talk recording: 16 kHz mono PCM16 from the VOICE_RECOGNITION source, for as long as the button is held
 * (at most [MAX_S] s). Without RECORD_AUDIO it records nothing and returns [Result.Denied] with the screen's sentence.
 */
object Mic {
    const val RATE = 16000
    const val MAX_S = 10

    sealed interface Result {
        class Denied(val text: String) : Result
        class Failed(val error: Throwable) : Result

        /** The samples, 16-bit little-endian mono at [RATE]. */
        class Clip(val pcm: ByteArray) : Result
    }

    fun granted(context: Context) =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun deniedText(context: Context): String {
        val label = context.applicationInfo.loadLabel(context.packageManager)
        return "The microphone is off for this app. Allow it when Android asks, or in Settings > Apps > $label > " +
            "Permissions > Microphone (tap here)."
    }

    /**
     * Records until [keepGoing] returns false or [MAX_S] s have passed, on the calling thread (blocking). [onLevel] gets
     * the loudness of every 50 ms, 0..1 (4 x RMS, clipped).
     */
    @SuppressLint("MissingPermission") // granted() is checked first
    fun record(context: Context, keepGoing: () -> Boolean, onLevel: (Float) -> Unit = {}): Result {
        if (!granted(context)) return Result.Denied(deniedText(context))
        val pcm = ByteArrayOutputStream()
        try {
            val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE))
            try {
                check(rec.state == AudioRecord.STATE_INITIALIZED) { "the microphone did not open (another app may hold it)" }
                val buf = ByteArray(RATE / 20 * 2) // 50 ms
                val maxBytes = RATE * 2 * MAX_S
                rec.startRecording()
                try {
                    while (keepGoing() && pcm.size() < maxBytes) {
                        val n = rec.read(buf, 0, minOf(buf.size, maxBytes - pcm.size()))
                        if (n < 0) error("AudioRecord.read returned $n")
                        pcm.write(buf, 0, n)
                        onLevel((Wav.rms(buf, 0, n) * 4).toFloat().coerceIn(0f, 1f))
                    }
                } finally {
                    rec.stop()
                }
            } finally {
                rec.release()
            }
        } catch (t: Throwable) {
            return Result.Failed(t)
        }
        return Result.Clip(pcm.toByteArray())
    }
}
