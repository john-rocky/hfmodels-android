package io.github.johnrocky.hfmodels.samples.translate

import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.SamplerConfig
import io.github.johnrocky.hfmodels.litertlm.ChatModel
import io.github.johnrocky.hfmodels.litertlm.text
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * Hy-MT2-1.8B through hfmodels' `Tasks.Chat`. One request = one new conversation with the card's prompt as its only
 * message: the bundle's chat template makes a second message in the same conversation fail (LiteRT-LM #3166; measured on
 * litert-lm-api 0.16.1 and 0.17.1 on 2026-10-19). Greedy, so the same text gives the same translation.
 */
class Translator(val chat: ChatModel) {
    /** The translation of [text] into [target] as it streams; [onCreated] gets the createConversation milliseconds. */
    fun translate(text: String, target: String, onCreated: (Double) -> Unit = {}): Flow<String> = flow {
        val t0 = System.nanoTime()
        val session = chat.createConversation(ConversationConfig(samplerConfig = GREEDY))
        onCreated((System.nanoTime() - t0) / 1e6)
        try {
            session.stream(Contents.of(translationPrompt(text, target))).collect { m -> m.text.takeIf { it.isNotEmpty() }?.let { emit(it) } }
        } finally {
            withContext(NonCancellable) { session.closeAndJoin() }
        }
    }

    /** The whole translation of [text]. */
    suspend fun translateAll(text: String, target: String): String {
        val b = StringBuilder()
        translate(text, target).collect { b.append(it) }
        return b.toString().trim()
    }

    companion object {
        /** Greedy decoding, as hfmodels-voice's tool turns use it. */
        val GREEDY = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0)
    }
}

/**
 * The translation model's output check at load, for a GPU load: a GPU graph can run without an error and return output
 * that has nothing to do with the input (hfmodels-android memory, an fp16 decoder on the Galaxy S26). Two fixed Spanish
 * sentences must come back as English that names what each one is about, and differently.
 */
object OutputCheck {
    class Probe(val spanish: String, val expect: String)

    val PROBES = listOf(
        Probe("Buenos días, ¿dónde está la estación de tren?", "station"),
        Probe("Muchas gracias por su ayuda.", "thank"),
    )

    /** Null when [outputs] (one per probe, in order) pass; otherwise why not. */
    fun verdict(outputs: List<String>): String? {
        if (outputs.size != PROBES.size) return "expected ${PROBES.size} outputs, got ${outputs.size}"
        for ((p, out) in PROBES.zip(outputs)) {
            if (out.isBlank()) return "empty output for \"${p.spanish}\""
            if (out.length > 200) return "${out.length} characters for \"${p.spanish}\""
            if (REPEAT.containsMatchIn(out)) return "a run of one character in \"${out.take(60)}\""
            if (!out.contains(p.expect, ignoreCase = true)) return "\"${out.take(80)}\" has no \"${p.expect}\""
        }
        if (outputs.distinct().size != outputs.size) return "the same output for different inputs: \"${outputs[0].take(60)}\""
        return null
    }

    private val REPEAT = Regex("""(.)\1{7,}""")
}
