package io.github.johnrocky.hfmodels.samples.look

/**
 * What the user gave, checked before anything reaches the model: a photo and a question. A refusal is a
 * sentence the screen shows as it is.
 */
object Typed {
    const val MAX_QUESTION_CHARS = 300

    sealed class Result {
        /** The question, trimmed. */
        data class Ready(val question: String) : Result()

        data class Refused(val message: String) : Result()
    }

    const val NO_PHOTO = "Pick a photo or an example first."
    const val NO_QUESTION = "Type a question, or tap one of the examples."
    const val TOO_LONG = "At most $MAX_QUESTION_CHARS characters, please."

    fun check(hasPhoto: Boolean, question: String): Result {
        if (!hasPhoto) return Result.Refused(NO_PHOTO)
        val q = question.trim()
        if (q.isEmpty()) return Result.Refused(NO_QUESTION)
        if (q.length > MAX_QUESTION_CHARS) return Result.Refused(TOO_LONG)
        return Result.Ready(q)
    }
}
