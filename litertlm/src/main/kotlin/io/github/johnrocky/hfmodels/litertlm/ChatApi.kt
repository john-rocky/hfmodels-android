package io.github.johnrocky.hfmodels.litertlm

import com.google.ai.edge.litertlm.Channel
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import io.github.johnrocky.hfmodels.PreparedModel
import kotlinx.coroutines.flow.Flow

/**
 * A prepared LiteRT-LM model. Owns the Engine; each [createConversation] returns a session that
 * owns one Conversation. One generation at a time per model; sessions run in series.
 */
interface ChatModel : PreparedModel {
    /** The inputs this load enabled (the profile's `enabled_inputs`), not what the model could do. */
    val enabledInputs: Set<io.github.johnrocky.hfmodels.InputKind>
    /** How this load treats a reasoning channel; [ThinkingInfo.channels] is empty for a model without one. */
    val thinking: ThinkingInfo
    suspend fun createConversation(config: ConversationConfig = ConversationConfig()): ChatSession
}

/**
 * A reasoning ("thinking") channel as this load applies it. Read at `prepare` from the descriptor's
 * `handler_config.channels`, from the bundle's own declaration (`LlmMetadata.channels`), and from the
 * prompt the runtime renders for a first user turn. A declared channel is what makes the runtime
 * divert the reasoning out of the streamed text and into [Message.channels]; without one the
 * reasoning, markers included, is ordinary text.
 */
data class ThinkingInfo(
    /** Applied to every conversation unless the caller sets `ConversationConfig.channels` (an empty list disables them). */
    val channels: List<Channel>,
    /** `descriptor` (`handler_config.channels`), `bundle` (the file's own declaration), or `none`. */
    val source: String,
    /**
     * True when the rendered generation prompt already opens the channel, so the model reasons from
     * its first token and the capture starts there; false when the model has to emit the start
     * marker itself; null when there is no channel or the prompt could not be rendered.
     */
    val prefilled: Boolean?,
    /** The last characters of the rendered generation prompt (the model-turn opener), newlines written as `\n`, for the record; null when not rendered. */
    val generationPromptTail: String?,
    /**
     * True when the descriptor says the model reasons on every turn unless told not to
     * (`handler_config.thinking_default`); false for a model whose channel is declared but idle
     * by default (Gemma 4, LFM2.5 instruct). Decides the default output cap (2,048 vs 256 tokens).
     */
    val reasonsByDefault: Boolean,
) {
    companion object {
        val NONE = ThinkingInfo(emptyList(), "none", null, null, false)
    }
}

/**
 * Per-call generation knobs.
 * - [maxOutputTokens]: the runtime's output cap for this turn. null = 256, or 2,048 when the load
 *   reasons by default (reasoning counts against the cap; a cap hit mid-thought yields an empty answer).
 * - [enableThinking]: null = the model's default; false asks the template to render its no-think
 *   variant (hybrids such as Qwen3 honour it; a model that always thinks ignores it).
 * - [thinkingTokenBudget]: stop the reasoning after this many tokens and close the channel (needs a
 *   declared channel; ignored by the runtime otherwise). null = unlimited.
 */
data class GenerationOptions(
    val maxOutputTokens: Int? = null,
    val enableThinking: Boolean? = null,
    val thinkingTokenBudget: Int? = null,
) {
    init {
        require(maxOutputTokens == null || maxOutputTokens > 0) { "maxOutputTokens must be positive" }
        require(thinkingTokenBudget == null || thinkingTokenBudget >= 0) { "thinkingTokenBudget must be >= 0" }
    }
}

/** `READY -> GENERATING -> READY`; cancel: `GENERATING -> CANCELLING -> INVALID`; close: `CLOSING -> CLOSED`. */
enum class SessionState { READY, GENERATING, CANCELLING, INVALID, CLOSING, CLOSED }

interface ChatSession {
    val state: SessionState

    /**
     * Starts generating when collected; collect once. Chunks are incremental [Message]s in order and
     * none is dropped: a collector that falls more than 1,024 chunks / 8 MiB behind ends the stream
     * with SLOW_CONSUMER after the native side is cancelled. Cancelling the collector cancels the
     * native generation. After a cancel the session is INVALID; open a new one for the next turn.
     */
    fun stream(contents: Contents, options: GenerationOptions = GenerationOptions()): Flow<Message>

    /** Stop the generation in flight (no-op when idle). The flow completes with the chunks delivered so far. */
    fun cancel()

    /** Request shutdown; returns at once. */
    fun close()

    /** Wait until the native conversation is released. Idempotent. */
    suspend fun closeAndJoin()
}
