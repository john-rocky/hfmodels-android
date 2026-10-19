package io.github.johnrocky.hfmodels.samples.translate

/**
 * Which button: what is heard and what it becomes. Spanish to English is said aloud (Kitten speaks English only);
 * English to Spanish is shown in large type for the other person to read (no Spanish voice runs on this stack yet).
 */
enum class Direction(val tag: String, val heard: String, val target: String, val button: String, val spoken: Boolean) {
    ES_EN("es-en", "Spanish", "English", "Habla español", spoken = true),
    EN_ES("en-es", "English", "Spanish", "Speak English", spoken = false);

    companion object {
        fun of(tag: String): Direction? = entries.firstOrNull { it.tag == tag }
    }
}

/**
 * The user message Hy-MT2 is prompted with, the source model card's default translation prompt verbatim
 * (litert-community/Hy-MT2-1.8B and tencent/Hy-MT2-1.8B): the instruction, an empty line, the text. No system message.
 */
internal fun translationPrompt(text: String, target: String): String =
    "Translate the following text into $target. Note that you should **only output the translated result without any additional explanation**:\n\n$text"
