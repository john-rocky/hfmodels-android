package io.github.johnrocky.hfmodels.samples.ask

/**
 * The text of each turn. The model answers a multiple-choice question with the letter of an option,
 * so every turn ends in "Answer: (" and the next token is the letter. The first turn carries the
 * context and follows the image; a later turn starts with the ")" that closes the previous answer,
 * then the next question, so the conversation reads as one run of answered questions. No role text
 * and no special tokens: the bundle's chat template passes the text through as it is.
 */
object Prompt {
    /**
     * The context line of every chart in assets/charts.json. It says nothing about charts, so the first
     * turn about a photo carries it too: a photo and a chart reach the model in the same turn shape.
     */
    const val CONTEXT = "This is a visual question about the image."

    fun first(context: String, q: Question): String = first(context, q.text, q.options)

    fun next(q: Question): String = next(q.text, q.options)

    fun first(context: String, question: String, options: List<String>): String = "Context:\n$context" + block(question, options)

    fun next(question: String, options: List<String>): String = ")" + block(question, options)

    /** The turn texts for [questions] about [spec], in order. */
    fun turns(spec: ChartSpec, questions: List<Question>): List<String> =
        questions.mapIndexed { k, q -> if (k == 0) first(spec.context, q) else next(q) }

    private fun block(question: String, options: List<String>): String = buildString {
        append("\n\nQuestion: ").append(question)
        append("\nOptions:\n")
        options.forEachIndexed { i, o -> append('(').append('A' + i).append(") ").append(o).append('\n') }
        append("Answer: (")
    }
}
