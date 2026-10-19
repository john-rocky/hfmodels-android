package io.github.johnrocky.hfmodels.samples.translate

/**
 * What one Qwen3-ASR call heard, with the parts the TURN line reports, in milliseconds from the call's start: the WAV
 * written ([wavMs]), the conversation made ([createMs]), the first piece of the answer ([firstChunkMs]: the audio encoder,
 * the prefill and the first decode step), the rest of the decoding ([decodeMs], [chunks] pieces in all).
 */
class AsrResult(val text: String, val raw: String, val wavMs: Double, val createMs: Double, val firstChunkMs: Double, val decodeMs: Double, val chunks: Int, val totalMs: Double)

/** Qwen3-ASR's text in and out (no runtime here: the JVM tests run it). */
object AsrText {
    private const val ASR_TEXT = "<asr_text>"

    /**
     * The text item that fixes the language, sent in the same message as the audio: `language Spanish<asr_text>` (the
     * model card, "Forced language"; the language names of the original card's table: Spanish, French, English).
     */
    fun forcedLanguage(name: String): String = "language $name$ASR_TEXT"

    /**
     * The transcript in an answer: with the language fixed the answer is the transcript alone (LiteRT-LM 0.16.1 puts the
     * prefix at the head of the model's turn and does not return it); an answer that still carries the tag
     * (`language <name><asr_text><transcript>`) gives the text after it.
     */
    fun parse(raw: String): String {
        val t = raw.trim()
        val i = t.lastIndexOf(ASR_TEXT)
        return if (i < 0) t else t.substring(i + ASR_TEXT.length).trim()
    }
}
