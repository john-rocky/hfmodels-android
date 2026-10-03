package io.github.johnrocky.hfmodels.voice

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.Role
import com.google.ai.edge.litertlm.ToolCall
import io.github.johnrocky.hfmodels.InputKind
import io.github.johnrocky.hfmodels.PreparedModelInfo
import io.github.johnrocky.hfmodels.litertlm.ChatModel
import io.github.johnrocky.hfmodels.litertlm.ChatSession
import io.github.johnrocky.hfmodels.litertlm.GenerationOptions
import io.github.johnrocky.hfmodels.litertlm.SessionState
import io.github.johnrocky.hfmodels.litertlm.ThinkingInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The loop on a scripted model: what the runner sends, runs and streams. LiteRT-LM 0.16.1 ships Java 21
 * class files, so the scenarios (which build its Message and ToolCall) run on a Java 21 test JVM and are
 * skipped on an older one; this class itself names none of its types, so JUnit can list it anywhere.
 */
class ToolRunnerTest {
    @Test fun runtimeCallsRunAndTheirResultsGoBackAsOneToolMessage() = scenario { runtimeCallsRunAndTheirResultsGoBackAsOneToolMessage() }
    @Test fun qwenXmlCallsAreParsedFromTheTextAndTheMarkupIsNeverShown() = scenario { qwenXmlCallsAreParsedFromTheTextAndTheMarkupIsNeverShown() }
    @Test fun aMalformedQwenXmlCallFailsTheTurnAndRunsNothing() = scenario { aMalformedQwenXmlCallFailsTheTurnAndRunsNothing() }
    @Test fun anUnknownToolIsAnsweredWithAnErrorAndTheModelGoesOn() = scenario { anUnknownToolIsAnsweredWithAnErrorAndTheModelGoesOn() }
    @Test fun aModelThatKeepsCallingStopsAtTheRoundLimit() = scenario { aModelThatKeepsCallingStopsAtTheRoundLimit() }
    @Test fun theTextAfterAnLfmCallBlockStreamsBeforeTheCallRuns() = scenario { theTextAfterAnLfmCallBlockStreamsBeforeTheCallRuns() }
    @Test fun callsTheRuntimeParsedRunUnderATextFormatToo() = scenario { callsTheRuntimeParsedRunUnderATextFormatToo() }
    @Test fun markupTheRuntimeLeftInTheTextFailsTheTurnUnshown() = scenario { markupTheRuntimeLeftInTheTextFailsTheTurnUnshown() }

    @Test fun theVisibleTextStopsBeforeMarkupOrAPieceOfIt() {
        assertEquals(6, beforeMarkup("Sure. <tool_call>x", "<tool_call"))
        assertEquals(6, beforeMarkup("Sure. <to", "<tool_call"))
        assertEquals(5, beforeMarkup("Sure.", "<tool_call"))
        // A "<" that cannot start the opener any more is shown.
        assertEquals(5, beforeMarkup("a < b", "<tool_call"))
    }

    @Test fun theUnshownTextIsWhatTheTurnSaidBeyondTheStreamedPrefix() {
        val lfm = "<|tool_call_start|>[set_alarm(hour=7)]<|tool_call_end|>I am setting an alarm."
        assertEquals("I am setting an alarm.", unshownText(lfm, 0, "I am setting an alarm."))
        assertEquals("Done.", unshownText("Sure. <|tool_call_start|>[f()]<|tool_call_end|>Done.", 6, "Sure. Done."))
        // Everything was streamed already (the runtime's text), trailing space or not.
        assertEquals("", unshownText("All set. ", 9, "All set."))
        assertEquals("", unshownText("All set.", 8, "All set."))
        // A held-back "<" that was not markup after all.
        assertEquals("<", unshownText("a <", 2, "a <"))
    }

    private fun scenario(block: ToolRunnerScenarios.() -> Unit) {
        assumeLiteRtLmClasses()
        ToolRunnerScenarios().block()
    }
}

/** LiteRT-LM 0.16.1's classes are Java 21 bytecode (class file 65); a Java 17 test JVM cannot load them. */
internal fun assumeLiteRtLmClasses() = assumeTrue(
    "LiteRT-LM 0.16.1 ships Java 21 class files; run on a Java 21 test JVM (-Dorg.gradle.java.home=<jdk 21>)",
    (System.getProperty("java.specification.version")?.toIntOrNull() ?: 0) >= 21,
)

internal class ToolRunnerScenarios {
    /** Each stream() call replays the next list of chunks and records the message it was given. */
    private class ScriptedModel(private val turns: List<List<Message>>) : ChatModel {
        val configs = ArrayList<ConversationConfig>()
        val sent = ArrayList<Message>()
        var closedSessions = 0
        override val info: PreparedModelInfo get() = throw UnsupportedOperationException()
        override val enabledInputs = setOf(InputKind.TEXT)
        override val thinking = ThinkingInfo.NONE
        override fun close() {}
        override suspend fun closeAndJoin() {}
        override suspend fun createConversation(config: ConversationConfig): ChatSession {
            configs += config
            return object : ChatSession {
                override val state = SessionState.READY
                override fun stream(message: Message, options: GenerationOptions): Flow<Message> {
                    sent += message
                    return turns[sent.size - 1].asFlow()
                }
                override fun cancel() {}
                override fun close() {}
                override suspend fun closeAndJoin() { closedSessions++ }
            }
        }
    }

    private val recorded = ArrayList<Pair<String, Map<String, Any?>>>()
    private fun tool(name: String, vararg params: String) = object : VoiceTool {
        override val name = name
        override val description = "$name tool"
        override val parameters = params.map { ToolParam(it, "integer", it) }
        override suspend fun call(args: Map<String, Any?>): String {
            recorded += name to args
            return "$name done"
        }
    }
    private val tools = listOf(tool("set_alarm", "hour", "minute"), tool("set_timer", "minutes"))

    private fun text(s: String) = Message.model(Contents.of(s))
    private fun calls(vararg c: ToolCall) = Message.model(Contents.of(emptyList()), c.toList())
    private fun run(model: ChatModel, format: ToolFormat, request: String, maxToolTurns: Int = 4) =
        runBlocking { ToolRunner(model, tools, format, maxToolTurns = maxToolTurns).turn(request).toList() }

    fun runtimeCallsRunAndTheirResultsGoBackAsOneToolMessage() {
        val model = ScriptedModel(listOf(
            listOf(calls(ToolCall("set_alarm", mapOf("hour" to 8, "minute" to 0)), ToolCall("set_timer", mapOf("minutes" to 20)))),
            listOf(text("Alarm set for 8:00. "), text("Timer started.")),
        ))
        val events = run(model, ToolFormat.Runtime, "Set an alarm for eight and a timer for twenty minutes.")
        assertEquals(listOf("set_alarm" to mapOf<String, Any?>("hour" to 8, "minute" to 0), "set_timer" to mapOf<String, Any?>("minutes" to 20)), recorded)
        // The first message is the user's; the second answers both calls in one TOOL message, {"result": text} each.
        assertEquals(Role.USER, model.sent[0].role)
        assertEquals(Role.TOOL, model.sent[1].role)
        val responses = model.sent[1].contents.contents.map { it as Content.ToolResponse }
        assertEquals(listOf("set_alarm", "set_timer"), responses.map { it.name })
        assertEquals(mapOf("result" to "set_alarm done"), responses[0].response)
        // The tools are declared to the runtime, which is not asked to run them (the SDK forces that off).
        assertEquals(2, model.configs.single().tools.size)
        val done = events.last() as ToolEvent.Done
        assertEquals("Alarm set for 8:00. Timer started.", done.reply)
        assertEquals(2, done.timing.turns)
        assertEquals(2, done.timing.toolCalls)
        assertEquals(3, done.timing.chunks)
        assertEquals(listOf("Alarm set for 8:00. ", "Timer started."), events.filterIsInstance<ToolEvent.Text>().map { it.delta })
        assertEquals(listOf("set_alarm done", "set_timer done"), events.filterIsInstance<ToolEvent.ToolCalled>().map { it.result })
        assertTrue(done.timing.firstTokenMs >= 0 && done.timing.replyMs >= done.timing.firstTokenMs)
        assertEquals(1, model.closedSessions)
    }

    fun qwenXmlCallsAreParsedFromTheTextAndTheMarkupIsNeverShown() {
        val model = ScriptedModel(listOf(
            listOf(text("Sure. <tool"), text("_call><function=set_timer><parameter=minutes>10</parameter></function></tool_call>")),
            listOf(text("Your timer is running.")),
        ))
        val events = run(model, ToolFormat.QwenXml, "Start a timer for ten minutes.")
        assertEquals(listOf("set_timer" to mapOf<String, Any?>("minutes" to "10")), recorded)
        assertEquals(listOf("Sure. ", "Your timer is running."), events.filterIsInstance<ToolEvent.Text>().map { it.delta })
        // The tool list goes to the template through extraContext, not to the runtime's parser.
        val config = model.configs.single()
        assertEquals(0, config.tools.size)
        assertEquals(2, (config.extraContext["tools"] as List<*>).size)
        assertEquals("set_timer done", (model.sent[1].contents.contents.single() as Content.ToolResponse).response)
        assertEquals("Your timer is running.", (events.last() as ToolEvent.Done).reply)
    }

    fun aMalformedQwenXmlCallFailsTheTurnAndRunsNothing() {
        val model = ScriptedModel(listOf(listOf(text("<tool_call>\n{\"name\": \"set_timer\", \"arguments\": {\"minutes\": 10}}\n</tool_call>"))))
        val events = run(model, ToolFormat.QwenXml, "Start a timer for ten minutes.")
        val failed = events.last() as ToolEvent.Failed
        assertTrue(failed.reason, failed.reason.startsWith("malformed or incomplete tool call"))
        assertEquals(emptyList<Any>(), recorded)
        assertEquals(emptyList<ToolEvent>(), events.filterIsInstance<ToolEvent.Text>())
    }

    fun anUnknownToolIsAnsweredWithAnErrorAndTheModelGoesOn() {
        val model = ScriptedModel(listOf(listOf(calls(ToolCall("open_door", emptyMap()))), listOf(text("I cannot do that."))))
        val events = run(model, ToolFormat.Runtime, "Open the door.")
        assertTrue(events.filterIsInstance<ToolEvent.ToolCalled>().single().result.startsWith("Error: unknown tool open_door"))
        assertEquals("I cannot do that.", (events.last() as ToolEvent.Done).reply)
    }

    /** LFM2.5-1.2B-Instruct's own text on a Galaxy S26 (tools gate, 2026-10-03), cut into chunks. */
    fun theTextAfterAnLfmCallBlockStreamsBeforeTheCallRuns() {
        val model = ScriptedModel(listOf(
            listOf(text("<|tool_call_start|>[set_alarm(hour=7, minute="), text("30)]<|tool_call_end|>I am setting an alarm labeled 'Seven thirty' "), text("for 7:30 AM tomorrow.")),
            listOf(text("Done.")),
        ))
        val events = run(model, ToolFormat.LfmPythonic, "Set an alarm for seven thirty tomorrow morning.")
        assertEquals(listOf("set_alarm" to mapOf<String, Any?>("hour" to 7L, "minute" to 30L)), recorded)
        // The sentence after the block comes out as Text before the call runs, without the markup.
        assertEquals(listOf("Text:I am setting an alarm labeled 'Seven thirty' for 7:30 AM tomorrow.", "ToolCalled:set_alarm", "Text:Done.", "Done:Done."),
            events.map { e -> when (e) { is ToolEvent.Text -> "Text:${e.delta}"; is ToolEvent.ToolCalled -> "ToolCalled:${e.name}"; is ToolEvent.Done -> "Done:${e.reply}"; else -> e.toString() } })
    }

    fun callsTheRuntimeParsedRunUnderATextFormatToo() {
        val model = ScriptedModel(listOf(listOf(calls(ToolCall("set_timer", mapOf("minutes" to 10)))), listOf(text("Timer started."))))
        val events = run(model, ToolFormat.LfmPythonic, "Start a timer for ten minutes.")
        assertEquals(listOf("set_timer" to mapOf<String, Any?>("minutes" to 10)), recorded)
        // Answered in the runtime's shape, {"result": text}.
        assertEquals(mapOf("result" to "set_timer done"), (model.sent[1].contents.contents.single() as Content.ToolResponse).response)
        assertEquals("Timer started.", (events.last() as ToolEvent.Done).reply)
    }

    fun markupTheRuntimeLeftInTheTextFailsTheTurnUnshown() {
        // LFM2.5 under format runtime: LiteRT-LM 0.16.1 leaves its calls in the text of a generic_model bundle.
        val model = ScriptedModel(listOf(listOf(text("<|tool_call_start|>[set_alarm(hour=7, minute=30)]"), text("<|tool_call_end|>I am setting an alarm."))))
        val events = run(model, ToolFormat.Runtime, "Set an alarm for seven thirty.")
        val failed = events.last() as ToolEvent.Failed
        assertTrue(failed.reason, failed.reason.startsWith("tool call markup the runtime did not parse"))
        assertEquals(emptyList<ToolEvent>(), events.filterIsInstance<ToolEvent.Text>())
        assertEquals(emptyList<Any>(), recorded)
    }

    fun aModelThatKeepsCallingStopsAtTheRoundLimit() {
        val call = listOf(calls(ToolCall("set_timer", mapOf("minutes" to 1))))
        val model = ScriptedModel(List(5) { call })
        val events = run(model, ToolFormat.Runtime, "Loop.", maxToolTurns = 2)
        val failed = events.last() as ToolEvent.Failed
        assertTrue(failed.reason, failed.reason.contains("after 2 rounds"))
        assertEquals(3, failed.timing.turns)
        assertEquals(2, recorded.size)
    }

}
