package io.github.johnrocky.hfmodels.samples.translate

import io.github.johnrocky.hfmodels.samples.translate.TranslateLoop.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prompt, the seconds as the screen writes them, the TURN line, the output check, the two pairs, the language choice
 * and the order of a turn's cards.
 */
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
        firstChunkMs = 1700.0; firstChunk = "A total of 23 euros."
        firstSynthMs = 1950.0; firstAudioMs = 2100.0; tokens = 9; totalMs = 4200.0; rms = 0.0812; voicedMs = 3900
    }

    @Test fun anEarlyTurnCountsFromTheEarlyCutAndItsFirstPiece() {
        // Started at the early cut (300 ms of silence), its first piece a clause, its gate opened 510 ms after the cut.
        val r = TurnRecord(Direction.FR_EN, "mic", hangoverMs = 300, audioMs = 4600.0, early = true, discardedEarly = 0, specMs = 300, cutAtNanos = 1_000_000_000L,
            gate = Gate.opened(1_510_000_000L)).apply {
            heard = "Pour aller au musée du Louvre, continuez tout droit."; translation = "To get to the Louvre Museum, continue straight."
            transcribeMs = 1900.0; firstChunkMs = 2400.0; firstChunk = "To get to the Louvre Museum,"; firstSentenceMs = 2700.0; translateDoneMs = 2750.0
            firstSynthMs = 2750.0; audioReadyMs = 2750.0; firstAudioMs = 2760.0
        }
        assertEquals(510.0, r.gateMs!!, 1e-9)
        assertEquals(3060.0, r.endOfSpeechToSoundMs!!, 0.0)
        assertEquals("French speech to English voice in 3.1 s", result(r))
        assertEquals("(300 ms end of speech + 1.9 s hearing + 500 ms translating + 360 ms voice)", breakdown(r))
        val line = turnLine(r, "asr:cpu,mt:gpu,tts:cpu", "none", null)
        for (key in listOf("spec_ms=300", "early=true", "discarded_early=0", "hangover_ms=300", "ms_gate=510", "l1=\"300+2760\"", "ms_end_of_speech_to_sound=3060",
            "ms_first_chunk=2400", "first_chunk=\"To get to the Louvre Museum,\"", "ms_first_sentence=2700", "ms_audio_ready=2750")) {
            assertTrue("$key in $line", line.contains(key))
        }
    }

    @Test fun theWholeTextIsNotShownBeforeTheGate() {
        // An early English turn whose translation was done before the utterance was cut: its seconds count to the cut.
        val r = TurnRecord(Direction.EN_FR, "mic", hangoverMs = 300, audioMs = 3000.0, early = true, specMs = 300, cutAtNanos = 0L, gate = Gate.opened(500_000_000L)).apply {
            heard = "Thanks."; translation = "Merci."; transcribeMs = 300.0; translateDoneMs = 420.0
        }
        assertEquals(800.0, r.endOfSpeechToTextMs!!, 0.0)
        assertEquals("(300 ms end of speech + 300 ms hearing + 200 ms translating)", breakdown(r))
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

    @Test fun theTurnLineCarriesTheRecognizersParts() {
        val r = spoken().apply { asr = AsrResult("Son 23 euros en total.", "Son 23 euros en total.", 1.2, 0.6, 712.4, 905.3, 21, 1619.5) }
        val line = turnLine(r, "asr:cpu,mt:gpu,tts:cpu", "none", null)
        for (key in listOf("asr_wav_ms=1", "asr_create_ms=1", "asr_first_chunk_ms=712", "asr_decode_ms=905", "asr_chunks=21")) assertTrue("$key in $line", line.contains(key))
        val none = turnLine(TurnRecord(Direction.ES_EN, "mic", hangoverMs = 800, audioMs = 1200.0), "asr:cpu,mt:gpu,tts:cpu", "none", null)
        assertTrue(none, none.contains("asr_first_chunk_ms=none") && none.contains("asr_chunks=none"))
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
        assertEquals("Qwen3-ASR-1.7B · CPU · loaded in 2.1 s", ModelLine("Qwen3-ASR-1.7B", "CPU", 2140.0).text)
        assertEquals("Kitten TTS nano 0.8 · not loaded · OFFLINE", ModelLine("Kitten TTS nano 0.8", "not loaded", null, "OFFLINE").text)
        assertEquals(Direction.ES_EN, Direction.of("es-en"))
        assertNull(Direction.of("de-en"))
        assertEquals(Direction.FR_EN, Direction.of("fr-en"))
    }

    @Test fun theFrenchPromptIsTheCardsFormWithFrench() {
        // The card's prompt with the source card's English name of the language (tencent/Hy-MT2-1.8B, "Supported Languages").
        assertEquals("Translate the following text into French. Note that you should **only output the translated result without any additional explanation**:\n\nHello.",
            translationPrompt("Hello.", Direction.EN_FR.target))
        assertEquals("Translate the following text into English. Note that you should **only output the translated result without any additional explanation**:\n\nBonjour.",
            translationPrompt("Bonjour.", Direction.FR_EN.target))
        // The Spanish pair's two prompts, byte for byte.
        assertEquals("Translate the following text into Spanish. Note that you should **only output the translated result without any additional explanation**:\n\nHello.",
            translationPrompt("Hello.", Direction.EN_ES.target))
        assertEquals("Translate the following text into English. Note that you should **only output the translated result without any additional explanation**:\n\nHola.",
            translationPrompt("Hola.", Direction.ES_EN.target))
    }

    @Test fun theFourDirections() {
        assertEquals(listOf("es-en", "en-es", "fr-en", "en-fr"), Direction.entries.map { it.tag })
        for (d in Direction.entries) assertEquals(d, Direction.of(d.tag))
        assertEquals(listOf("Habla español", "Speak English", "Parlez français", "Speak English"), Direction.entries.map { it.button })
        assertEquals(listOf(true, false, true, false), Direction.entries.map { it.spoken })
        assertEquals(listOf("Spanish", "English", "French", "English"), Direction.entries.map { it.heard })
        assertEquals(listOf("English", "Spanish", "English", "French"), Direction.entries.map { it.target })
        assertEquals(listOf(Direction.ES_EN, Direction.EN_ES), Language.SPANISH.directions)
        assertEquals(listOf(Direction.FR_EN, Direction.EN_FR), Language.FRENCH.directions)
        assertNull(Direction.of("fr"))
    }

    @Test fun spanishIsTheDefaultLanguage() {
        assertEquals(Language.SPANISH, Language.DEFAULT)
        assertEquals(Language.SPANISH, TranslateUi().language)
        assertEquals(listOf(Direction.ES_EN, Direction.EN_ES), TranslateUi().language.directions)
        assertEquals(Language.FRENCH, Language.of("fr"))
        assertEquals(Language.SPANISH, Language.of("es"))
        assertNull(Language.of("de"))
        // The Spanish screen's line, word for word.
        assertEquals("No network, no account: Spanish speech becomes an English voice, English becomes Spanish text.", intro(Language.SPANISH))
        assertEquals("No network, no account: French speech becomes an English voice, English becomes French text.", intro(Language.FRENCH))
    }

    @Test fun choosingALanguageClearsTheLastTurnButNotDuringOne() {
        val last = TranslateUi(ready = true).startTurn(Direction.ES_EN).on(Event.Heard("Son 23 euros en total.", 5080.0, 700.0)).on(Event.Done(spoken()))
        val fr = last.withLanguage(Language.FRENCH)
        assertEquals(Language.FRENCH, fr.language)
        assertNull(fr.turn)
        assertEquals(listOf("", "", "", ""), listOf(fr.heard, fr.translation, fr.result, fr.breakdown))
        assertEquals(true, fr.ready)
        val running = TranslateUi(ready = true).startTurn(Direction.ES_EN)
        assertEquals(running, running.withLanguage(Language.FRENCH))
        val listening = TranslateUi(ready = true, listening = Direction.ES_EN)
        assertEquals(listening, listening.withLanguage(Language.FRENCH))
    }

    @Test fun aFinishedTurnShowsItsTranslationFirst() {
        // The translation keeps the top of the scrolling part in both directions; the seconds and the models are under that
        // part, so a finished turn shows the three together.
        val started = TranslateUi(ready = true, language = Language.FRENCH).startTurn(Direction.FR_EN)
        assertEquals(emptyList<TurnCard>(), started.cards())
        val heard = started.on(Event.Heard("L'hôtel Bellevue est à cinq minutes à pied, juste à côté de la pharmacie.", 4420.0, 1900.0))
        assertEquals(listOf(TurnCard.HEARD), heard.cards())
        val said = heard.on(Event.Translation("The Bellevue Hotel")).on(Event.Done(TurnRecord(Direction.FR_EN, "file:f05.wav", hangoverMs = 300, audioMs = 4420.0).apply {
            this.heard = "L'hôtel Bellevue est à cinq minutes à pied, juste à côté de la pharmacie."
            translation = "The Bellevue Hotel is five minutes' walk away, right next to the pharmacy."
            transcribeMs = 1900.0; firstChunkMs = 2600.0; firstAudioMs = 2880.0
        }))
        assertEquals(listOf(TurnCard.TRANSLATION, TurnCard.HEARD), said.cards())
        assertEquals("The Bellevue Hotel is five minutes' walk away, right next to the pharmacy.", said.translation)
        assertEquals("French speech to English voice in 3.2 s", said.result)
        val written = TranslateUi(ready = true, language = Language.FRENCH).startTurn(Direction.EN_FR)
            .on(Event.Heard("There are three of us.", 2000.0, 900.0)).on(Event.Translation("Nous sommes trois."))
            .on(Event.Done(TurnRecord(Direction.EN_FR, "file:m02.wav", hangoverMs = 300, audioMs = 2000.0).apply {
                this.heard = "There are three of us."; translation = "Nous sommes trois."; transcribeMs = 900.0; translateDoneMs = 1400.0
            }))
        assertEquals(listOf(TurnCard.TRANSLATION, TurnCard.HEARD), written.cards())
        val nothing = TranslateUi(ready = true).startTurn(Direction.ES_EN).on(Event.NothingHeard("too short (40 ms of voice)"))
        assertEquals(emptyList<TurnCard>(), nothing.cards())
        assertEquals(emptyList<TurnCard>(), TranslateUi(ready = true).cards())
    }

    @Test fun frenchCountsAsSpanishDoes() {
        val said = TurnRecord(Direction.FR_EN, "mic", hangoverMs = 800, audioMs = 5000.0).apply {
            heard = "Ça fait dix-huit euros en tout."; translation = "That's eighteen euros in total."
            transcribeMs = 700.0; firstSentenceMs = 1700.0; translateDoneMs = 1720.0; firstAudioMs = 2100.0
        }
        assertEquals("French speech to English voice in 2.9 s", result(said))
        assertTrue(turnLine(said, "asr:cpu,mt:gpu,tts:cpu", "none", null).startsWith("TURN dir=fr-en input=mic "))
        val written = TurnRecord(Direction.EN_FR, "mic", hangoverMs = 800, audioMs = 5000.0).apply {
            heard = "Excuse me."; translation = "Excusez-moi."; transcribeMs = 600.0; translateDoneMs = 1500.0
        }
        assertEquals("English speech to French text in 2.3 s", result(written))
        assertEquals("(800 ms end of speech + 600 ms hearing + 900 ms translating)", breakdown(written))
        assertTrue(turnLine(written, "asr:cpu,mt:gpu,tts:cpu", "none", null).startsWith("TURN dir=en-fr input=mic "))
    }
}
