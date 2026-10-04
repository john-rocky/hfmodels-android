package io.github.johnrocky.hfmodels.voice

import com.google.ai.edge.litertlm.ConversationConfig
import io.github.johnrocky.hfmodels.InputKind
import io.github.johnrocky.hfmodels.PreparedModelInfo
import io.github.johnrocky.hfmodels.litertlm.ChatModel
import io.github.johnrocky.hfmodels.litertlm.ChatSession
import io.github.johnrocky.hfmodels.litertlm.ThinkingInfo
import io.github.johnrocky.hfmodels.speech.Speaker
import io.github.johnrocky.hfmodels.speech.SpeechAudio
import io.github.johnrocky.hfmodels.speech.Transcriber
import io.github.johnrocky.hfmodels.speech.TranscriberLimits
import io.github.johnrocky.hfmodels.speech.Transcript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The loop's defaults live in one place each, and the loop refuses an endpointer the transcriber cannot take. */
class VoiceLoopConfigTest {
    private fun Endpointer.values() = listOf(sampleRate, startRms, startMs, hangoverMs, maxUtteranceMs, frameMs, preRollMs)

    @Test fun theConfigsDefaultsAreTheEndpointersAndTheRunnersOwn() {
        val config = VoiceLoopConfig()
        assertEquals(Endpointer().values(), config.endpointer.values())
        // The values the config spelled out before it took Endpointer() (and the ones the device runs used).
        assertEquals(listOf(16000, 0.02f, 100, 800, 16000, 20, 300), config.endpointer.values())
        assertEquals(0.02f, Endpointer.DEFAULT_START_RMS)
        assertEquals(4, ToolRunner.MAX_TOOL_TURNS)
        assertEquals(ToolRunner.MAX_TOOL_TURNS, config.maxToolTurns)
    }

    @Test fun aLoopRefusesAnEndpointerThatCutsLongerThanTheTranscribersWindow() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            VoiceLoop(transcriber, chat, speaker, config = VoiceLoopConfig(endpointer = Endpointer(maxUtteranceMs = 20_000)))
        }
        assertEquals("the endpointer cuts utterances of up to 20000 ms; the transcriber takes 16.0 s", e.message)
        // The default cut is the window itself.
        assertTrue(VoiceLoop(transcriber, chat, speaker).config.endpointer.maxUtteranceMs == 16_000)
    }

    private val transcriber = object : Transcriber {
        override val info: PreparedModelInfo get() = throw UnsupportedOperationException()
        override val limits = TranscriberLimits(16000, 16.0, listOf("en"))
        override fun close() {}
        override suspend fun closeAndJoin() {}
        override suspend fun transcribe(pcm: FloatArray): Transcript = throw UnsupportedOperationException()
    }

    private val speaker = object : Speaker {
        override val info: PreparedModelInfo get() = throw UnsupportedOperationException()
        override val voices = listOf("v")
        override val sampleRate = 24000
        override val maxChars = 400
        override fun close() {}
        override suspend fun closeAndJoin() {}
        override fun phonemeIds(text: String) = IntArray(0)
        override suspend fun synthesize(text: String, voice: String?, speed: Float): SpeechAudio = throw UnsupportedOperationException()
    }

    private val chat = object : ChatModel {
        override val info: PreparedModelInfo get() = throw UnsupportedOperationException()
        override val enabledInputs = setOf(InputKind.TEXT)
        override val thinking = ThinkingInfo.NONE
        override fun close() {}
        override suspend fun closeAndJoin() {}
        override suspend fun createConversation(config: ConversationConfig): ChatSession = throw UnsupportedOperationException()
    }
}
