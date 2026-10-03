package io.github.johnrocky.hfmodels.voice

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ToolCall
import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.litertlm.ChatModel
import io.github.johnrocky.hfmodels.litertlm.GenerationOptions
import io.github.johnrocky.hfmodels.litertlm.text
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * One request to a chat model with tools: the model may call [tools] (run here, the results sent
 * back on the same conversation) for up to [maxToolTurns] rounds, then answers in text.
 *
 * ```kotlin
 * val runner = ToolRunner(chat, PhoneTools.all(context), ToolFormat.Runtime)
 * runner.turn("Wake me up at six fifteen.").collect { e -> if (e is ToolEvent.Done) speak(e.reply) }
 * ```
 *
 * Each [turn] opens its own conversation and closes it at the end, so no history is kept between
 * requests. On a model whose layers keep a running state (linear attention, SSM, short convolutions)
 * a new conversation on the same loaded model may still start from the previous one's state under
 * LiteRT-LM 0.16.1 to 0.17.1 (google-ai-edge/LiteRT-LM#3165); compare its answers with a fresh load.
 * One turn at a time per model (the chat model's own rule).
 */
class ToolRunner(
    private val chat: ChatModel,
    private val tools: List<VoiceTool>,
    private val format: ToolFormat,
    /** The system text for a turn, given the local date and time ("Saturday, 2026-10-03 15:04"). */
    private val systemInstruction: (now: String) -> String = { defaultSystemInstruction(it) },
    private val maxToolTurns: Int = 4,
    /** Ask a model with a reasoning channel to reason (its no-think variant otherwise); ignored by a model without one. */
    private val thinking: Boolean = false,
) {
    init {
        require(maxToolTurns >= 1) { "maxToolTurns must be at least 1" }
        require(tools.map { it.name }.toSet().size == tools.size) { "tool names must be unique" }
    }

    /**
     * Runs [text] as one request. The flow ends with exactly one [ToolEvent.Done] or [ToolEvent.Failed]; a
     * [ModelException] from the model ends it with Failed too. Cancelling the collector cancels the model.
     * [ToolEvent.Text] carries every model turn's text without call markup, in order: a turn that calls tools
     * streams the text before its first call as it comes and the rest of it (after or between the calls) once
     * the turn has ended, before its calls run ([VoiceLoop] speaks all of it).
     */
    fun turn(text: String): Flow<ToolEvent> = flow {
        val t0 = System.nanoTime()
        val now = SimpleDateFormat("EEEE, yyyy-MM-dd HH:mm", Locale.US).format(Date())
        var firstTokenNs = -1L
        var chunks = 0
        var chars = 0
        var calls = 0
        var turns = 0
        var decodeNs = 0L
        fun timing() = TurnTiming(
            firstTokenMs = if (firstTokenNs < 0) -1.0 else (firstTokenNs - t0) / 1e6, replyMs = (System.nanoTime() - t0) / 1e6,
            chunks = chunks, chars = chars, toolCalls = calls, turns = turns, decodeMs = decodeNs / 1e6,
        )
        val session = try {
            chat.createConversation(format.conversationConfig(tools, systemInstruction(now), thinking))
        } catch (e: ModelException) {
            emit(ToolEvent.Failed("${e.code}: ${e.reason}", timing(), e.code))
            return@flow
        }
        val options = GenerationOptions(enableThinking = if (chat.thinking.channels.isEmpty()) null else thinking)
        try {
            var message = Message.user(text)
            while (true) {
                turns++
                val buf = StringBuilder()
                var shown = 0
                val runtimeCalls = ArrayList<ToolCall>()
                val sentAt = System.nanoTime()
                var turnLast = -1L
                try {
                    session.stream(message, options).collect { m ->
                        val at = System.nanoTime()
                        turnLast = at
                        if (firstTokenNs < 0) firstTokenNs = at
                        chunks++
                        for (thought in m.channels.values) if (thought.isNotEmpty()) { chars += thought.length; emit(ToolEvent.Thinking(thought)) }
                        val piece = m.text
                        if (piece.isNotEmpty()) {
                            chars += piece.length
                            buf.append(piece)
                            val visible = format.visibleLength(buf.toString())
                            if (visible > shown) { emit(ToolEvent.Text(buf.substring(shown, visible))); shown = visible }
                        }
                        runtimeCalls += m.toolCalls
                    }
                } catch (e: ModelException) {
                    emit(ToolEvent.Failed("${e.code}: ${e.reason}" + if (buf.isEmpty()) "" else " (text so far: ${buf.take(300)})", timing(), e.code))
                    return@flow
                }
                if (turnLast >= 0) decodeNs += turnLast - sentAt
                val all = buf.toString()
                val parsed = format.parse(all, runtimeCalls)
                if (parsed.error != null) {
                    emit(ToolEvent.Failed("${parsed.error}: ${all.take(300)}", timing()))
                    return@flow
                }
                // What the turn said that is not shown yet: an end held back while it might have been markup, and
                // the text after or between the calls (LFM2.5 writes its sentence after the call block).
                unshownText(all, shown, parsed.said).let { if (it.isNotEmpty()) emit(ToolEvent.Text(it)) }
                if (parsed.calls.isEmpty()) {
                    emit(ToolEvent.Done(parsed.said, timing()))
                    return@flow
                }
                if (turns > maxToolTurns) {
                    emit(ToolEvent.Failed("the model still calls tools after $maxToolTurns rounds: ${parsed.calls.joinToString { it.name }}", timing()))
                    return@flow
                }
                val responses = ArrayList<Content>(parsed.calls.size)
                for (c in parsed.calls) {
                    calls++
                    val tc = System.nanoTime()
                    val result = execute(c)
                    emit(ToolEvent.ToolCalled(c.name, c.args, result, (System.nanoTime() - tc) / 1e6, turns))
                    // A call the runtime parsed is answered in the runtime's shape, whatever the text format.
                    responses += (if (c.byRuntime) ToolFormat.Runtime else format).response(c.name, result)
                }
                message = Message.tool(Contents.of(responses))
            }
        } finally {
            withContext(NonCancellable) { session.closeAndJoin() }
        }
    }

    private suspend fun execute(c: ParsedCall): String {
        val tool = tools.firstOrNull { it.name == c.name } ?: return "Error: unknown tool ${c.name} (available: ${tools.joinToString { it.name }})"
        return try { tool.call(c.args) } catch (e: CancellationException) { throw e } catch (e: Exception) { "Error: ${e.message ?: e.javaClass.simpleName}" }
    }

    companion object {
        /** phone-agent's system text (its QWENXML form), with the local date and time. */
        fun defaultSystemInstruction(now: String): String =
            "You are a phone assistant. The current date and time is $now. " +
                "Use the provided tools to read the calendar, set alarms and timers, and add events. " +
                "Use get_current_datetime when you need the current local date and time."
    }
}

/** What [ToolRunner.turn] streams, in order; it ends with one [Done] or [Failed]. */
sealed interface ToolEvent {
    /** A piece of the model's reasoning (a declared channel), incremental. */
    data class Thinking(val delta: String) : ToolEvent

    /** A piece of the model's visible text, incremental, without the call markup the format parses (any model turn's, in order). */
    data class Text(val delta: String) : ToolEvent

    /** A call that ran: its arguments as the model gave them, the text sent back, how long the tool took, and the model turn (from 1) that asked for it. */
    data class ToolCalled(val name: String, val args: Map<String, Any?>, val result: String, val ms: Double, val turn: Int = 0) : ToolEvent

    /** The answer: the last model turn's text without call markup. */
    data class Done(val reply: String, val timing: TurnTiming) : ToolEvent

    /** The request could not finish: a malformed call, too many rounds, or the model's error ([code] set for a [ModelException]). */
    data class Failed(val reason: String, val timing: TurnTiming, val code: ErrorCode? = null) : ToolEvent
}

/**
 * One request's wall clock on the device, not a benchmark. [firstTokenMs]: from [ToolRunner.turn]'s start
 * (the conversation is created inside it) to the first chunk of the first model turn, -1 when none came;
 * [replyMs]: to the end; [decodeMs]: the sum over model turns of the time from sending the turn's message to
 * its last chunk (prefill included; a turn without chunks adds nothing). [chunks] and [chars] count what was
 * streamed (text and reasoning; call markup the runtime parses itself is never streamed).
 */
data class TurnTiming(
    val firstTokenMs: Double,
    val replyMs: Double,
    val chunks: Int,
    val chars: Int,
    val toolCalls: Int,
    val turns: Int,
    val decodeMs: Double,
)
