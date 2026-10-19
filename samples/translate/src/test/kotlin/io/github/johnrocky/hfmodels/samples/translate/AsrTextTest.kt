package io.github.johnrocky.hfmodels.samples.translate

import org.junit.Assert.assertEquals
import org.junit.Test

/** Qwen3-ASR's text in and out: the item that fixes each button's language, and the transcript in an answer. */
class AsrTextTest {
    @Test fun eachButtonFixesItsLanguageByTheCardsName() {
        // The model card's "Forced language": `language Chinese<asr_text>` (or another language name) in the same message;
        // the names of the original card's table (Qwen/Qwen3-ASR-1.7B, Supported Languages).
        assertEquals("language Spanish<asr_text>", AsrText.forcedLanguage(Direction.ES_EN.heard))
        assertEquals("language English<asr_text>", AsrText.forcedLanguage(Direction.EN_ES.heard))
        assertEquals("language French<asr_text>", AsrText.forcedLanguage(Direction.FR_EN.heard))
        assertEquals("language English<asr_text>", AsrText.forcedLanguage(Direction.EN_FR.heard))
    }

    @Test fun theTranscriptIsTheAnswerOrTheTextAfterTheTag() {
        // With the language fixed, LiteRT-LM 0.16.1 answered with the transcript alone (Mac, 52 clips, 2026-10-19).
        assertEquals("Le train pour Lyon part du quai 7 à 9h30.", AsrText.parse("Le train pour Lyon part du quai 7 à 9h30.\n"))
        // An answer that still carries the tag, as without a fixed language.
        assertEquals("Excuse me, what time is it?", AsrText.parse("language English<asr_text>Excuse me, what time is it?"))
        assertEquals("Hola.", AsrText.parse("language None<asr_text> Hola. "))
        assertEquals("", AsrText.parse("language Spanish<asr_text>"))
        assertEquals("", AsrText.parse("  "))
    }
}
