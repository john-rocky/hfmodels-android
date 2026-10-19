package io.github.johnrocky.hfmodels.samples.translate

import io.github.johnrocky.hfmodels.BackendPolicy
import io.github.johnrocky.hfmodels.BindingSource
import io.github.johnrocky.hfmodels.DescriptorOrigin
import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.InputKind
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.PreparedModelInfo
import io.github.johnrocky.hfmodels.VerificationLevel
import io.github.johnrocky.hfmodels.samples.translate.TranslateLoop.Event
import io.github.johnrocky.hfmodels.speech.Speaker
import io.github.johnrocky.hfmodels.speech.SpeechAudio
import io.github.johnrocky.hfmodels.speech.SpeechTiming
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One turn through [TranslateLoop] with fakes for parakeet, Hy-MT2, Kitten and the loudspeaker. */
class TranslateLoopTest {
    private val info = PreparedModelInfo(
        repoId = "fake/speaker", commit = "0".repeat(40), descriptorSha256 = "", descriptorOrigin = DescriptorOrigin("fake", "none", "-"),
        bindingSource = BindingSource.EXPLICIT_REVISION, variantId = "v", profileId = "cpu", sdkVersion = "test", handlerId = "fake", handlerAbi = 1,
        runtime = "litert", runtimeVersion = "0", declaredInputs = setOf(InputKind.TEXT), enabledInputs = setOf(InputKind.TEXT),
        requestedBackendPolicy = BackendPolicy.Auto, components = emptyMap(), excludedProfiles = emptyList(), fallbackHistory = emptyList(),
        verification = VerificationLevel.UNVERIFIED, notes = emptyList(), files = emptyMap(),
    )

    private inner class FakeSpeaker : Speaker {
        val said = ArrayList<String>()
        override val info = this@TranslateLoopTest.info
        override val voices = listOf("v")
        override val sampleRate = 24000
        override val maxChars = 400
        override suspend fun synthesize(text: String, voice: String?, speed: Float): SpeechAudio {
            delay(5)
            said += text
            return SpeechAudio(FloatArray(text.length * 100) { 0.1f }, sampleRate, SpeechTiming(1.0, 4.0, 5.0, text.length))
        }
        override fun phonemeIds(text: String) = IntArray(0)
        override fun close() {}
        override suspend fun closeAndJoin() {}
    }

    private class FakePlayer : Player {
        val played = ArrayList<Int>()
        var stopped = false
        override var firstWriteAtNanos = 0L
        override suspend fun play(samples: FloatArray) { if (played.isEmpty()) firstWriteAtNanos = System.nanoTime(); played += samples.size; delay(2) }
        override suspend fun drain() {}
        override fun stop() { stopped = true }
    }

    private val voice = FloatArray(16000) { (0.1 * sin(2.0 * PI * 300.0 * it / 16000.0)).toFloat() }

    private fun heard(text: String): suspend (FloatArray) -> ParakeetResult = { delay(10); ParakeetResult(text, 1, 1.0, 5.0, 4.0, 30, 10.0) }

    private fun streamed(vararg deltas: String): (String, String, (Double) -> Unit) -> Flow<String> = { _, _, created -> flow { created(0.5); for (d in deltas) { delay(3); emit(d) } } }

    @Test fun spanishBecomesEnglishSaidSentenceBySentence() = runBlocking {
        val speaker = FakeSpeaker()
        val player = FakePlayer()
        val loop = TranslateLoop(heard("Son 23 euros en total. ¿Quiere una bolsa?"), streamed("A total", " of 23 euros.", " Do you want", " a bag?"), speaker, player)
        val events = loop.turn(Direction.ES_EN, voice, System.nanoTime(), 800, "file").toList()
        assertTrue(events.first() is Event.Heard)
        val done = events.last() as Event.Done
        val r = done.record
        assertEquals("Son 23 euros en total. ¿Quiere una bolsa?", r.heard)
        assertEquals("A total of 23 euros. Do you want a bag?", r.translation)
        // The finished sentence went to the speaker before the translation ended; Kitten's chunks drop the marks.
        assertEquals(listOf("A total of 23 euros,", "Do you want a bag,"), speaker.said)
        assertEquals(listOf("A total of 23 euros,", "Do you want a bag,"), events.filterIsInstance<Event.Speaking>().map { it.sentence })
        assertEquals(2, player.played.size)
        assertEquals(0.5, r.translateCreateMs!!, 0.0)
        assertEquals(4, r.tokens)
        val t = listOf(r.transcribeMs!!, r.translateFirstTokenMs!!, r.firstSentenceMs!!, r.firstSynthMs!!)
        assertEquals(t.sorted(), t)
        assertTrue(r.firstSentenceMs!! < r.translateDoneMs!!)
        assertEquals(r.firstAudioMs!! + 800, r.endOfSpeechToSoundMs!!, 0.0)
        assertTrue(r.errors.isEmpty())
        assertTrue(result(r).startsWith("Spanish speech to English voice in "))
    }

    @Test fun englishBecomesSpanishTextOnly() = runBlocking {
        val speaker = FakeSpeaker()
        val loop = TranslateLoop(heard("Excuse me."), streamed("Disculpe."), speaker, FakePlayer())
        val events = loop.turn(Direction.EN_ES, voice, System.nanoTime(), 800, "mic").toList()
        val r = (events.last() as Event.Done).record
        assertEquals("Disculpe.", r.translation)
        assertTrue(speaker.said.isEmpty())
        assertTrue(events.none { it is Event.Speaking })
        assertNull(r.firstAudioMs)
        assertTrue(result(r).startsWith("English speech to Spanish text in "))
    }

    @Test fun frenchIsSaidInEnglishAndEnglishBecomesFrenchText() = runBlocking {
        val targets = ArrayList<String>()
        val recording: (String, String, (Double) -> Unit) -> Flow<String> = { _, target, created ->
            targets += target
            flow { created(0.2); emit(if (target == "English") "That's eighteen euros." else "Excusez-moi.") }
        }
        val speaker = FakeSpeaker()
        val loop = TranslateLoop(heard("Ça fait dix-huit euros."), recording, speaker, FakePlayer())
        val said = (loop.turn(Direction.FR_EN, voice, System.nanoTime(), 800, "file").toList().last() as Event.Done).record
        assertEquals("That's eighteen euros.", said.translation)
        assertEquals(1, speaker.said.size)
        assertTrue(speaker.said[0], speaker.said[0].startsWith("That's eighteen euros"))
        assertTrue(result(said).startsWith("French speech to English voice in "))
        val written = (loop.turn(Direction.EN_FR, voice, System.nanoTime(), 800, "mic").toList().last() as Event.Done).record
        assertEquals("Excusez-moi.", written.translation)
        assertEquals(listOf("English", "French"), targets)
        assertEquals(1, speaker.said.size)
        assertNull(written.firstAudioMs)
        assertTrue(result(written).startsWith("English speech to French text in "))
    }

    @Test fun silenceIsNothingHeardAndNotTranscribed() = runBlocking {
        var heardCalls = 0
        val loop = TranslateLoop({ heardCalls++; ParakeetResult("x", 1, 0.0, 0.0, 0.0, 0, 0.0) }, streamed("x"), FakeSpeaker(), FakePlayer())
        val events = loop.turn(Direction.ES_EN, FloatArray(32000), System.nanoTime(), 800, "file").toList()
        assertEquals(2, events.size)
        assertEquals("too short (0 ms of voice)", (events[0] as Event.NothingHeard).reason)
        assertEquals("too short (0 ms of voice)", (events[1] as Event.Done).record.nothingHeard)
        assertEquals(0, heardCalls)
    }

    @Test fun anEmptyTranscriptIsNothingHeard() = runBlocking {
        val events = TranslateLoop(heard(""), streamed("x"), FakeSpeaker(), FakePlayer()).turn(Direction.ES_EN, voice, System.nanoTime(), 800, "file").toList()
        assertTrue(events.any { it is Event.NothingHeard })
        assertEquals("no words in the audio", (events.last() as Event.Done).record.nothingHeard)
    }

    @Test fun aTranslationFailureIsReportedAndTheTurnEnds() = runBlocking {
        val failing: (String, String, (Double) -> Unit) -> Flow<String> = { _, _, created -> flow { created(0.1); emit("Partial"); throw ModelException(ErrorCode.INFERENCE_FAILED, "boom") } }
        val events = TranslateLoop(heard("Hola."), failing, FakeSpeaker(), FakePlayer()).turn(Direction.ES_EN, voice, System.nanoTime(), 800, "file").toList()
        val error = events.filterIsInstance<Event.Error>().single()
        assertEquals("translating", error.stage)
        assertEquals("INFERENCE_FAILED: boom", error.message)
        val r = (events.last() as Event.Done).record
        assertEquals("Partial", r.translation)
        assertEquals(listOf("translating: INFERENCE_FAILED: boom"), r.errors)
    }

    @Test fun withoutASpeakerTheEnglishIsShownNotSaid() = runBlocking {
        val events = TranslateLoop(heard("Hola."), streamed("Hello."), null, FakePlayer()).turn(Direction.ES_EN, voice, System.nanoTime(), 800, "file").toList()
        val r = (events.last() as Event.Done).record
        assertEquals("Hello.", r.translation)
        assertNull(r.firstAudioMs)
        assertEquals("No sound", result(r))
    }

    @Test fun stoppingATurnStopsTheSound() = runBlocking {
        val player = FakePlayer()
        val endless: (String, String, (Double) -> Unit) -> Flow<String> = { _, _, created -> flow { created(0.1); emit("Hello there. And"); awaitCancellation() } }
        val loop = TranslateLoop(heard("Hola."), endless, FakeSpeaker(), player)
        val events = java.util.concurrent.CopyOnWriteArrayList<Event>()
        val job = launch(Dispatchers.Default) { loop.turn(Direction.ES_EN, voice, System.nanoTime(), 800, "mic").collect { events += it } }
        while (events.none { it is Event.Translation }) delay(5)
        job.cancelAndJoin()
        assertTrue(player.stopped)
        assertFalse(events.any { it is Event.Done })
    }
}
