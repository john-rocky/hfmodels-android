package io.github.johnrocky.hfmodels.samples.translate

import android.os.SystemClock
import io.github.johnrocky.hfmodels.samples.translate.TranslateLoop.Event
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * A turn's sound and events for a screen recording, under `<root>/<turn>/` (samples/voice's form, which the clip tools
 * read): `utterance.wav` (16 kHz, what the speech recognizer heard), `reply.wav` (24 kHz, the English chunks as played, back to back)
 * and `events.json` (every event with `System.nanoTime`, `SystemClock.elapsedRealtimeNanos` and the wall clock read
 * together; the utterance's start and end and each English chunk's write on the same clocks).
 */
class TurnRecorder(val root: File) {
    private var rows = JSONArray()
    private var next = (root.listFiles()?.mapNotNull { it.name.toIntOrNull() }?.maxOrNull() ?: 0) + 1

    /** A turn starts: the microphone opened (or the file began to play). */
    fun begin(event: String, direction: Direction) {
        rows = JSONArray()
        rows.put(stamp(JSONObject().put("event", event).put("dir", direction.tag)))
    }

    fun event(e: Event) {
        val row = stamp(JSONObject().put("event", e.javaClass.simpleName))
        when (e) {
            is Event.Heard -> row.put("text", e.text).put("audio_ms", e.audioMs).put("transcribe_ms", e.transcribeMs)
            is Event.NothingHeard -> row.put("reason", e.reason)
            is Event.Translation -> { if (rows.length() > 0 && rows.getJSONObject(rows.length() - 1).optString("event") == "Translation") return; row.put("text", e.text) }
            is Event.Speaking -> row.put("sentence", e.sentence).put("synth_ms", e.synthMs).put("first_audio_ms", e.firstAudioMs ?: JSONObject.NULL)
            is Event.Error -> row.put("stage", e.stage).put("message", e.message)
            is Event.Done -> row.put("translation", e.record.translation)
        }
        rows.put(row)
    }

    /**
     * Writes the turn: [utterance] cut at [cutElapsedNanos] (SystemClock.elapsedRealtimeNanos), [r]'s played chunks (at
     * [replyRate], the speaker's) and timing. Returns the turn's directory.
     */
    fun finish(r: TurnRecord, utterance: FloatArray?, cutElapsedNanos: Long, replyRate: Int): File {
        val dir = File(root, "%03d".format(next++)).apply { mkdirs() }
        val out = JSONObject().put("dir", r.direction.tag).put("input", r.input)
        if (utterance != null) {
            Wav.write(File(dir, "utterance.wav"), utterance, SAMPLE_RATE)
            out.put("utterance", JSONObject().put("file", "utterance.wav").put("samples", utterance.size).put("sample_rate", SAMPLE_RATE)
                .put("end_elapsed_nanos", cutElapsedNanos).put("start_elapsed_nanos", cutElapsedNanos - utterance.size * 1_000_000_000L / SAMPLE_RATE))
        }
        // System.nanoTime -> elapsedRealtimeNanos, read together now (both are monotonic; their gap is the time asleep).
        val offset = SystemClock.elapsedRealtimeNanos() - System.nanoTime()
        if (r.playedAudio.isNotEmpty()) {
            val all = FloatArray(r.playedAudio.sumOf { it.size })
            val chunks = JSONArray()
            var at = 0
            for ((i, a) in r.playedAudio.withIndex()) {
                a.copyInto(all, at)
                chunks.put(JSONObject().put("text", r.spoken.getOrNull(i) ?: "").put("offset_samples", at).put("samples", a.size)
                    .put("write_elapsed_nanos", r.playedAtNanos.getOrNull(i)?.plus(offset) ?: JSONObject.NULL))
                at += a.size
            }
            Wav.write(File(dir, "reply.wav"), all, replyRate)
            val first = r.playedAtNanos.firstOrNull()
            out.put("reply", JSONObject().put("file", "reply.wav").put("samples", all.size).put("sample_rate", replyRate)
                .put("first_write_elapsed_nanos", first?.plus(offset) ?: JSONObject.NULL).put("sentences", chunks)
                .put("how", "the English chunks as the player was given them, back to back; each chunk's write_elapsed_nanos is when its write began"))
        }
        out.put("turn_start_elapsed_nanos_est", cutElapsedNanos)
            .put("timing", JSONObject().put("hangover_ms", r.hangoverMs).put("transcribe_ms", r.transcribeMs ?: JSONObject.NULL)
                .put("translate_first_token_ms", r.translateFirstTokenMs ?: JSONObject.NULL).put("translate_done_ms", r.translateDoneMs ?: JSONObject.NULL)
                .put("first_sentence_ms", r.firstSentenceMs ?: JSONObject.NULL).put("first_audio_ms", r.firstAudioMs ?: JSONObject.NULL)
                .put("end_of_speech_to_sound_ms", r.endOfSpeechToSoundMs ?: JSONObject.NULL).put("end_of_speech_to_text_ms", r.endOfSpeechToTextMs ?: JSONObject.NULL)
                .put("heard", r.heard).put("translation", r.translation).put("screen", result(r)))
            .put("events", rows)
        File(dir, "events.json").writeText(out.toString(2))
        rows = JSONArray()
        return dir
    }

    private fun stamp(o: JSONObject): JSONObject =
        o.put("nano", System.nanoTime()).put("elapsed_nanos", SystemClock.elapsedRealtimeNanos()).put("elapsed_ms", SystemClock.elapsedRealtime()).put("wall_ms", System.currentTimeMillis())
}
