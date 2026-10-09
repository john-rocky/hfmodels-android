package io.github.johnrocky.hfmodels.samples.ask

/**
 * What the user typed, checked before anything reaches the model. The screen has one question box and
 * five option boxes lettered A to E; a refusal is a sentence the screen shows as it is.
 */
object Typed {
    const val MIN_OPTIONS = 2
    const val MAX_OPTIONS = 5

    sealed class Result {
        /** The question and the options, trimmed, in the order of their letters. */
        data class Ready(val question: String, val options: List<String>) : Result()

        data class Refused(val message: String) : Result()
    }

    const val NO_PHOTO = "Pick a photo first."
    const val NO_QUESTION = "Type a question."
    const val TOO_FEW = "Type at least two options, A and B."
    const val GAP = "Fill the options from A down, with no empty box between two filled ones."
    const val TOO_MANY = "At most five options, A to E."
    const val SAME = "Two options read the same; make each one different."

    /**
     * [options] are the boxes from A down. Empty boxes after the last filled one are ignored; an empty box
     * before it would shift the letters the model reads away from the letters on screen, so it is refused.
     */
    fun check(hasPhoto: Boolean, question: String, options: List<String>): Result {
        if (!hasPhoto) return Result.Refused(NO_PHOTO)
        val q = question.trim()
        if (q.isEmpty()) return Result.Refused(NO_QUESTION)
        val boxes = options.map { it.trim() }
        val filled = boxes.subList(0, boxes.indexOfLast { it.isNotEmpty() } + 1)
        if (filled.any { it.isEmpty() }) return Result.Refused(GAP)
        if (filled.size < MIN_OPTIONS) return Result.Refused(TOO_FEW)
        if (filled.size > MAX_OPTIONS) return Result.Refused(TOO_MANY)
        if (filled.map { it.lowercase() }.toSet().size != filled.size) return Result.Refused(SAME)
        return Result.Ready(q, filled)
    }
}
