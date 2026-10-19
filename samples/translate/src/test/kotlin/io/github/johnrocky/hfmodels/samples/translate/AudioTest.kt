package io.github.johnrocky.hfmodels.samples.translate

import io.github.johnrocky.hfmodels.voice.Endpointer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Cutting audio: the endpointer over a file, the pieces of audio longer than a window, the input gate, the WAV reader.
 * The parity tests read the fixtures and the Python host's output from `-Dtranslate.fixturesRoot` (not in this repository:
 * `fixtures/wav/`, the host's utterances `fixtures/frontend/<id>.utt.f32` and its 5 s pieces `mac_preview_data/asr_5s.json`);
 * without it they are skipped.
 */
class AudioTest {
    private fun tone(seconds: Double, amp: Double, hz: Double = 300.0) = FloatArray((seconds * 16000).toInt()) { (amp * sin(2.0 * PI * hz * it / 16000.0)).toFloat() }

    @Test fun aFileGivesTheUtterancesTheMicrophoneWould() {
        // 0.3 s quiet, 1 s voice, 1 s quiet, 0.6 s voice: two utterances, each from its pre-roll to the end of its hangover.
        val pcm = FloatArray(4800) + tone(1.0, 0.1) + FloatArray(16000) + tone(0.6, 0.1)
        val u = utterancesOf(pcm, Endpointer())
        assertEquals(2, u.size)
        // 300 ms pre-roll + 1 s + 800 ms hangover = 2.1 s (frames of 320 samples).
        assertEquals(33600, u[0].size)
        assertTrue(u[1].size in 22400..27200)
    }

    @Test fun silenceAndQuietNoiseGiveNoUtterance() {
        assertEquals(0, utterancesOf(FloatArray(80000), Endpointer()).size)
        val rng = java.util.Random(7)
        val noise = FloatArray(80000) { (rng.nextGaussian() * 0.003).toFloat() }
        assertEquals(0, utterancesOf(noise, Endpointer()).size)
    }

    @Test fun audioWithinTheWindowIsOnePiece() {
        assertEquals(listOf(0 until 80000), pieces(FloatArray(80000) { 0.1f }, 80000))
        assertEquals(listOf(0 until 100), pieces(FloatArray(100), 80000))
    }

    @Test fun longerAudioIsCutAtItsQuietestFrameBeforeTheWindowEnds() {
        // 7 s of voice with a 100 ms pause at 3.9 s: the 5 s window's search (2.5 to 5 s) finds the pause.
        val pcm = tone(7.0, 0.1)
        for (i in 62400 until 64000) pcm[i] = 0f
        val p = pieces(pcm, 80000)
        assertEquals(2, p.size)
        assertEquals(0, p[0].first)
        val cut = p[0].last + 1
        assertTrue("cut at $cut", cut in 62400 until 64000)
        assertEquals(cut, p[1].first)
        assertEquals(pcm.size - 1, p[1].last)
    }

    @Test fun theInputGateNeedsVoiceAndLevel() {
        assertFalse(InputGate.check(FloatArray(16000), 16000, 0.02f).heard)
        assertEquals("too short (0 ms of voice)", InputGate.check(FloatArray(16000), 16000, 0.02f).reason)
        assertFalse(InputGate.check(tone(0.2, 0.1), 16000, 0.02f).heard)
        assertTrue(InputGate.check(tone(1.0, 0.1), 16000, 0.02f).heard)
        // 300 ms of voice in 20 s of silence: long enough, too quiet overall (RMS 0.0035 < 0.005).
        val sparse = FloatArray(320000).also { tone(0.3, 0.04).copyInto(it, 1000) }
        val v = InputGate.check(sparse, 16000, 0.02f)
        assertFalse(v.heard)
        assertTrue(v.reason, v.reason.startsWith("too quiet"))
        assertEquals(300, v.voicedMs)
    }

    @Test fun wavRoundTrip() {
        val f = File.createTempFile("translate", ".wav").apply { deleteOnExit() }
        val pcm = floatArrayOf(0f, 0.5f, -0.5f, 0.25f)
        Wav.write(f, pcm, 16000)
        val (back, rate) = Wav.read(f)
        assertEquals(16000, rate)
        assertArrayEquals(floatArrayOf(0f, 16383 / 32768f, -16383 / 32768f, 8191 / 32768f), back, 0f)
    }

    @Test fun theFixturesEndpointLikeThePythonHostAndCutLikeIts5sPieces() {
        val root = System.getProperty("translate.fixturesRoot").orEmpty()
        assumeTrue("translate.fixturesRoot / TRANSLATE_FIXTURES_ROOT not set: the fixture parity is skipped", root.isNotEmpty())
        val five = JSONObject(File(root, "mac_preview_data/asr_5s.json").readText()).getJSONObject("files")
        var n = 0
        for (id in listOf("e01", "e02", "e03", "e04", "e05", "n01", "n02", "n03", "n04", "n05", "long_35s", "fr_input")) {
            val (pcm, rate) = Wav.read(File(root, "fixtures/wav/$id.wav"))
            assertEquals(16000, rate)
            val utterance = utterancesOf(pcm, Endpointer(maxUtteranceMs = 30_000)).first()
            val b = File(root, "fixtures/frontend/$id.utt.f32").readBytes()
            val py = FloatArray(b.size / 4).also { ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
            assertArrayEquals("$id utterance", py, utterance, 0f)
            val ranges = pieces(utterance, 80000).map { it.first to it.last + 1 }
            val pyRanges = five.getJSONObject(id).getJSONArray("pieces").let { a -> (0 until a.length()).map { a.getJSONObject(it).getInt("start") to a.getJSONObject(it).getInt("end") } }
            assertEquals("$id 5 s pieces", pyRanges, ranges)
            n++
        }
        println("endpointer and piece parity: $n files")
        // The silent and the noise file: no utterance for either host.
        for (id in listOf("silence_5s", "noise_5s")) assertEquals(id, 0, utterancesOf(Wav.read(File(root, "fixtures/wav/$id.wav")).first, Endpointer(maxUtteranceMs = 30_000)).size)
    }
}
