package io.github.johnrocky.hfmodels.samples.translate

/**
 * Which button: what is heard and what it becomes. Spanish or French to English is said aloud (Kitten speaks English
 * only); English to Spanish or French is shown in large type for the other person to read (no Spanish voice runs on this
 * stack yet, and French is shown the same way).
 */
enum class Direction(val tag: String, val heard: String, val target: String, val button: String, val spoken: Boolean, val language: Language) {
    ES_EN("es-en", "Spanish", "English", "Habla español", spoken = true, Language.SPANISH),
    EN_ES("en-es", "English", "Spanish", "Speak English", spoken = false, Language.SPANISH),
    FR_EN("fr-en", "French", "English", "Parlez français", spoken = true, Language.FRENCH),
    EN_FR("en-fr", "English", "French", "Speak English", spoken = false, Language.FRENCH);

    companion object {
        fun of(tag: String): Direction? = entries.firstOrNull { it.tag == tag }
    }
}

/**
 * The other person's language, the choice at the top of the screen (Spanish unless another is chosen): it gives the two
 * buttons. [label] is the name Hy-MT2's prompt takes (the source card's English language names: "French", "Spanish").
 */
enum class Language(val tag: String, val label: String) {
    SPANISH("es", "Spanish"),
    FRENCH("fr", "French");

    /** Their button: their speech becomes an English voice. */
    val toEnglish: Direction get() = Direction.entries.first { it.language == this && it.spoken }

    /** The English speaker's button: English becomes their language in large type. */
    val fromEnglish: Direction get() = Direction.entries.first { it.language == this && !it.spoken }

    /** The two buttons in the screen's order, theirs first. */
    val directions: List<Direction> get() = listOf(toEnglish, fromEnglish)

    companion object {
        val DEFAULT = SPANISH

        fun of(tag: String): Language? = entries.firstOrNull { it.tag == tag }
    }
}

/**
 * The user message Hy-MT2 is prompted with, the source model card's default translation prompt verbatim
 * (litert-community/Hy-MT2-1.8B and tencent/Hy-MT2-1.8B): the instruction, an empty line, the text. No system message.
 */
internal fun translationPrompt(text: String, target: String): String =
    "Translate the following text into $target. Note that you should **only output the translated result without any additional explanation**:\n\n$text"
