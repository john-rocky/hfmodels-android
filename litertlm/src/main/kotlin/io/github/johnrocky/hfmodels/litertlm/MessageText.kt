package io.github.johnrocky.hfmodels.litertlm

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Message

/**
 * The text parts of a [Message] joined in order ("" when it carries none). Each streamed chunk is
 * incremental, so append `m.text`; content diverted into a channel is not in it (`m.channels`).
 */
val Message.text: String
    get() = contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

/**
 * The runtime's rule for a prompt that already opens a channel (`GetOpenChannelName`, LiteRT-LM
 * 0.16.1 `channel_util.cc`): the channel whose start marker occurs last in [text] with no end marker
 * after it. When it is the thinking channel the model reasons from its first token and the capture
 * starts at once; otherwise the model has to emit the start marker itself.
 */
internal fun openChannelName(text: String, channels: List<ChannelMarkers>): String? {
    var best: String? = null
    var bestPos = -1
    for (c in channels) {
        if (c.start.isEmpty()) continue
        val s = text.lastIndexOf(c.start)
        if (s < 0) continue
        val e = if (c.end.isEmpty()) -1 else text.lastIndexOf(c.end)
        if ((e < 0 || s > e) && s > bestPos) { bestPos = s; best = c.name }
    }
    return best
}

/** A channel's markers without the runtime type, so the rule above is testable on a plain JVM. */
internal data class ChannelMarkers(val name: String, val start: String, val end: String)
