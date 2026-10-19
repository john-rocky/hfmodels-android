package io.github.johnrocky.hfmodels.samples.translate

import io.github.johnrocky.hfmodels.samples.translate.TranslateLoop.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The prompt, the seconds as the screen writes them, the TURN line, the output check. */
class TranslateUiTest {
    @Test fun thePromptIsTheCardsWordForWord() {
        // litert-community/Hy-MT2-1.8B's card, "Usage" (lines 95-97 at 0d2c5681), with its Japanese example.
        val card = "Translate the following text into Japanese. Note that you should **only output the translated result without any additional explanation**:\n\n" +
            "The weather is nice today, so let's go for a walk in the park."
        assertEquals(card, translationPrompt("The weather is nice today, so let's go for a walk in the park.", "Japanese"))
        assertTrue(translationPrompt("Hola.", Direction.ES_EN.target).startsWith("Translate the following text into English. Note"))
        assertTrue(translationPrompt("Hello.", Direction.EN_ES.target).startsWith("Translate the following text into Spanish. Note"))
        assertTrue(translationPrompt("Hello.", "Spanish").endsWith(":\n\nHello."))
    }

    private fun spoken() = TurnRecord(Direction.ES_EN, "mic", hangoverMs = 800, audioMs = 5080.0).apply {
        heard = "Son 23 euros en total."; translation = "A total of 23 euros."; spoken += "A total of 23 euros,"
        transcribeMs = 700.0; translateCreateMs = 0.4; translateFirstTokenMs = 950.0; firstSentenceMs = 1700.0; translateDoneMs = 1720.0
        firstSynthMs = 1950.0; firstAudioMs = 2100.0; tokens = 9; totalMs = 4200.0; rms = 0.0812; voicedMs = 3900
    }

    @Test fun spanishToEnglishCountsToTheFirstSound() {
        val r = spoken()
        assertEquals(2900.0, r.endOfSpeechToSoundMs!!, 0.0)
        assertEquals("Spanish speech to English voice in 2.9 s", result(r))
        assertEquals("(800 ms end of speech + 700 ms hearing + 1.0 s translating + 400 ms voice)", breakdown(r))
    }

    @Test fun englishToSpanishCountsToTheWholeText() {
        val r = TurnRecord(Direction.EN_ES, "mic", hangoverMs = 800, audioMs = 6320.0).apply {
            heard = "Excuse me."; translation = "Disculpe."; transcribeMs = 600.0; translateDoneMs = 1500.0
        }
        assertEquals("English speech to Spanish text in 2.3 s", result(r))
        assertEquals("(800 ms end of speech + 600 ms hearing + 900 ms translating)", breakdown(r))
        assertNull(r.endOfSpeechToSoundMs)
    }

    @Test fun nothingHeardShowsNoSeconds() {
        val r = TurnRecord(Direction.ES_EN, "mic", hangoverMs = 800, audioMs = 1200.0).apply { nothingHeard = "too short (40 ms of voice)" }
        assertEquals("", result(r))
        assertEquals("", breakdown(r))
    }

    @Test fun theTurnLineCarriesEveryPart() {
        val line = turnLine(spoken(), "asr:cpu,mt:gpu,tts:cpu", "none", 2_345_678)
        for (key in listOf("TURN dir=es-en", "input=mic", "heard=\"Son 23 euros en total.\"", "translation=\"A total of 23 euros.\"", "spoken=\"A total of 23 euros,\"",
            "hangover_ms=800", "ms_first_audio=2100", "ms_end_of_speech_to_sound=2900", "ms_end_of_speech_to_text=2520", "ms_transcribe=700",
            "ms_translate_create=0.4", "ms_translate_first_token=950", "ms_translate_done=1720", "ms_first_sentence=1700", "ms_first_synth=1950",
            "ms_total=4200", "tokens=9", "backend=asr:cpu,mt:gpu,tts:cpu", "rms=0.0812", "voiced_ms=3900", "network=none", "vmhwm_kb=2345678")) {
            assertTrue("$key in $line", line.contains(key))
        }
        assertTrue(line, !line.contains('\n'))
    }

    @Test fun theScreenFollowsATurn() {
        var ui = TranslateUi(ready = true).startTurn(Direction.ES_EN)
        assertEquals("Hearing…", ui.status)
        ui = ui.on(Event.Heard("Son 23 euros en total.", 5080.0, 700.0))
        assertEquals("Translating…", ui.status)
        ui = ui.on(Event.Translation("A total of"))
        assertEquals("A total of", ui.translation)
        ui = ui.on(Event.Speaking("A total of 23 euros,", 250.0, 2100.0))
        assertEquals("Speaking…", ui.status)
        ui = ui.on(Event.Done(spoken()))
        assertEquals("Spanish speech to English voice in 2.9 s", ui.result)
        assertEquals("A total of 23 euros.", ui.translation)
        assertEquals(false, ui.busy)
    }

    @Test fun theOutputCheckWantsTwoDifferentRelevantTranslations() {
        assertNull(OutputCheck.verdict(listOf("Good morning, where is the train station?", "Thank you very much for your help.")))
        assertTrue(OutputCheck.verdict(listOf("Thank you.", "Thank you."))!!.contains("no \"station\""))
        assertTrue(OutputCheck.verdict(listOf("station station", "station station"))!!.contains("no \"thank\""))
        assertTrue(OutputCheck.verdict(listOf("", "Thanks."))!!.startsWith("empty output"))
        assertTrue(OutputCheck.verdict(listOf("!!!!!!!!!! station", "Thanks."))!!.startsWith("a run of one character"))
        assertTrue(OutputCheck.verdict(listOf("Good morning, where is the station?"))!!.startsWith("expected 2 outputs"))
    }

    @Test fun modelLines() {
        assertEquals("parakeet-tdt-0.6b-v3 · CPU · loaded in 2.1 s", ModelLine("parakeet-tdt-0.6b-v3", "CPU", 2140.0).text)
        assertEquals("Kitten TTS nano 0.8 · not loaded · OFFLINE", ModelLine("Kitten TTS nano 0.8", "not loaded", null, "OFFLINE").text)
        assertEquals(Direction.ES_EN, Direction.of("es-en"))
        assertNull(Direction.of("fr-en"))
    }
}
