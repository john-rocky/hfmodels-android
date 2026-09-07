package io.github.johnrocky.hfmodels

import io.github.johnrocky.hfmodels.litertlm.ChatModel
import io.github.johnrocky.hfmodels.litertlm.ChatTask

/** The tasks this build of the SDK can run. `Chat` covers text generation and image-text-to-text. */
object Tasks {
    val Chat: Task<ChatModel> = ChatTask
}
