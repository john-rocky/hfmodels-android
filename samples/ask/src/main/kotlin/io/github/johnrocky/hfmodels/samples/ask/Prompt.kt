package io.github.johnrocky.hfmodels.samples.ask

/**
 * The text of each turn. The model answers a multiple-choice question with the letter of an option,
 * so every turn ends in "Answer: (" and the next token is the letter. The first turn carries the
 * context and follows the image; a later turn starts with the ")" that closes the previous answer,
 * then the next question, so the conversation reads as one run of answered questions. No role text
 * and no special tokens: the bundle's chat template passes the text through as it is.
 */
object Prompt {
    fun first(context: String, q: Question): String = "Context:\n$context" + block(q)

    fun next(q: Question): String = ")" + block(q)

    /** The turn texts for [questions] about [spec], in order. */
    fun turns(spec: ChartSpec, questions: List<Question>): List<String> =
        questions.mapIndexed { k, q -> if (k == 0) first(spec.context, q) else next(q) }

    private fun block(q: Question): String = buildString {
        append("\n\nQuestion: ").append(q.text)
        append("\nOptions:\n")
        q.options.forEachIndexed { i, o -> append('(').append('A' + i).append(") ").append(o).append('\n') }
        append("Answer: (")
    }
}
