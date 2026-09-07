package io.github.johnrocky.hfmodels.litertlm

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import io.github.johnrocky.hfmodels.BackendKind
import io.github.johnrocky.hfmodels.BackendPolicy
import io.github.johnrocky.hfmodels.HfModels
import io.github.johnrocky.hfmodels.LoadOptions
import io.github.johnrocky.hfmodels.ModelRef
import io.github.johnrocky.hfmodels.Tasks
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M2 on a named device: a thinking model's reasoning is separated into Message.channels by the
 * channel the load applies, a thinking budget cuts it, and disabling the channels puts the markers
 * back into the text (which is what an app without the SDK sees). Instrumentation args:
 *   model    a catalogued thinking model id (default litert-community/LFM2.5-1.2B-Thinking)
 *   backend  cpu | gpu (default cpu)
 * The model is downloaded through the product path if it is not cached (run tools/gate.sh with
 * `-e keep true` on the entry first to reuse its file). One RESULT line per check under "hfmodels-m2".
 */
@RunWith(AndroidJUnit4::class)
class ThinkingDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val ref = ModelRef(args.getString("model") ?: "litert-community/LFM2.5-1.2B-Thinking")
    private val backend = if (args.getString("backend") == "gpu") BackendKind.GPU else BackendKind.CPU

    @Test fun reasoningIsSeparatedCutByTheBudgetAndLeaksWhenDisabled(): Unit = runBlocking {
        val models = HfModels(ctx)
        val t0 = SystemClock.elapsedRealtime()
        val model = models.fromPretrained(ref, Tasks.Chat, LoadOptions(backendPolicy = BackendPolicy.Require(backend)))
        try {
            val th = model.thinking
            Log.i(TAG, "load ms=${SystemClock.elapsedRealtime() - t0} model=${ref.repoId} profile=${model.info.profileId} thinking=${th} notes=${model.info.notes}")
            assertTrue("the load declares a channel", th.channels.isNotEmpty())
            assertTrue("the entry says the model reasons by default", th.reasonsByDefault)
            val markers = th.channels.flatMap { listOf(it.start, it.end) }.map { it.trim() }.filter { it.isNotEmpty() }

            // 1. Default: reasoning in the channel, answer in the text, no marker in the text.
            val s1 = model.createConversation(ConversationConfig(systemInstruction = Contents.of(SYSTEM)))
            val r1 = collect(s1.stream(Contents.of(PROMPT)))
            s1.closeAndJoin()
            val sep = r1.text.contains("42") && r1.thought.isNotBlank() && markers.none { r1.text.contains(it) }
            result("separated", "ok=$sep chunks=${r1.chunks} text_chunks=${r1.textChunks} thought_chunks=${r1.thoughtChunks} thought_chars=${r1.thought.length} " +
                "last_chunk_text_chars=${r1.lastChunkTextChars} last_chunk_has_thought=${r1.lastChunkHasThought} source=${th.source} prefilled=${th.prefilled} tail=${q(th.generationPromptTail ?: "")} reply=${q(r1.text)} thought=${q(r1.thought)}")
            assertTrue("reasoning separated: reply=${q(r1.text)} thought=${r1.thought.length} chars", sep)

            // 2. A thinking budget closes the channel early: the captured reasoning must be shorter and still no marker in the text
            //    (what the model says after a forced close is its own business; a 16-token budget is a mechanism check, not a usage).
            val s2 = model.createConversation(ConversationConfig(systemInstruction = Contents.of(SYSTEM)))
            val r2 = collect(s2.stream(Contents.of(PROMPT), GenerationOptions(thinkingTokenBudget = BUDGET)))
            s2.closeAndJoin()
            // A 1.2B model cut after 16 tokens tends to carry on reasoning in the answer, sometimes re-emitting a marker: that is the
            // model, not the channel, so only the shortening is asserted and the leak is logged.
            val cut = r2.thought.length < r1.thought.length
            result("budget", "ok=$cut budget=$BUDGET thought_chars=${r2.thought.length} vs_unbudgeted=${r1.thought.length} marker_in_text=${markers.any { r2.text.contains(it) }} reply=${q(r2.text)} thought=${q(r2.thought)}")
            assertTrue("budget $BUDGET did not shorten the reasoning (${r2.thought.length} vs ${r1.thought.length})", cut)

            // 3. Channels off: the same model streams its markers and reasoning as text.
            val s3 = model.createConversation(ConversationConfig(systemInstruction = Contents.of(SYSTEM), channels = emptyList()))
            val r3 = collect(s3.stream(Contents.of(PROMPT)))
            s3.closeAndJoin()
            val leaked = markers.any { r3.text.contains(it) } && r3.thought.isEmpty()
            result("disabled", "ok=$leaked chunks=${r3.chunks} thought_chunks=${r3.thoughtChunks} reply=${q(r3.text)}")
            assertTrue("with channels disabled the markers should be in the text: ${q(r3.text)}", leaked)
            assertFalse(r3.text.contains("42") && !markers.any { r3.text.contains(it) })

            // 4. Message.text is the text parts only.
            assertEquals(r1.text, r1.textViaExtension)
            result("summary", "ok=true model=${ref.repoId} commit=${model.info.commit.take(8)} profile=${model.info.profileId} device=${Build.MODEL} build=${Build.DISPLAY} runtime=${model.info.runtime} ${model.info.runtimeVersion}")
        } finally {
            model.closeAndJoin(); models.closeAndJoin()
        }
    }

    private class Collected(val text: String, val textViaExtension: String, val chunks: Int, val textChunks: Int, val thought: String, val thoughtChunks: Int, val lastChunkTextChars: Int, val lastChunkHasThought: Boolean)
    /** Text and channel content are both incremental (0.16.1 streams the thought piece by piece): append. */
    private suspend fun collect(flow: Flow<Message>): Collected {
        val sb = StringBuilder(); val sbExt = StringBuilder(); val th = StringBuilder(); var n = 0; var textChunks = 0; var thoughtChunks = 0; var last: Message? = null
        withTimeout(600_000) {
            flow.collect { m ->
                n++; last = m
                val t = m.contents.contents.filterIsInstance<com.google.ai.edge.litertlm.Content.Text>().joinToString("") { it.text }
                if (t.isNotEmpty()) textChunks++
                sb.append(t); sbExt.append(m.text)
                m.channels.values.firstOrNull()?.let { th.append(it); thoughtChunks++ }
            }
        }
        return Collected(sb.toString(), sbExt.toString(), n, textChunks, th.toString(), thoughtChunks, last?.text?.length ?: -1, last?.channels?.isNotEmpty() == true)
    }
    private fun q(s: String) = "\"" + s.take(160).replace("\n", " ").replace("\"", "'") + "\""
    private fun result(check: String, values: String) { Log.i(TAG, "RESULT check=$check device=${Build.MODEL} litertlm=${BuildConfig.LITERTLM_VERSION} $values") }

    private companion object {
        const val TAG = "hfmodels-m2"
        const val SYSTEM = "You are a helpful assistant."
        const val PROMPT = "What is 17 + 25? Answer briefly."
        const val BUDGET = 16
    }
}
