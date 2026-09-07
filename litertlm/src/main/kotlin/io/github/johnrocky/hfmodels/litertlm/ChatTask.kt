package io.github.johnrocky.hfmodels.litertlm

import io.github.johnrocky.hfmodels.Handler
import io.github.johnrocky.hfmodels.Task

/** The chat task bound to the LiteRT-LM handler (`litertlm.conversation`, ABI 1). */
internal object ChatTask : Task<ChatModel> {
    override val id = "chat"
    override val handler: Handler<ChatModel> = LiteRtLmHandler
}
