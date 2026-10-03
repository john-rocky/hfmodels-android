package io.github.johnrocky.hfmodels.voice

/**
 * The index of [opener] in [text], or of a trailing piece of it that the next chunk may complete; the
 * text's length when neither is there. (Its own file: the JVM tests load it without LiteRT-LM's classes.)
 */
internal fun beforeMarkup(text: String, opener: String): Int {
    val at = text.indexOf(opener)
    if (at >= 0) return at
    for (k in minOf(opener.length - 1, text.length) downTo 1) if (text.regionMatches(text.length - k, opener, 0, k)) return text.length - k
    return text.length
}
