package io.github.johnrocky.hfmodels.litertlm

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
    suspend fun createConversation(config: ConversationConfig = ConversationConfig()): ChatSession
}

/** Per-call generation knobs. [maxOutputTokens] is passed to the runtime's own output cap. */
data class GenerationOptions(val maxOutputTokens: Int = 256) {
    init { require(maxOutputTokens > 0) { "maxOutputTokens must be positive" } }
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
