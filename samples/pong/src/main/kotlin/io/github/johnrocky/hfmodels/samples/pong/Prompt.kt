package io.github.johnrocky.hfmodels.samples.pong

/**
 * The text that follows the image in every request. The model answers a multiple-choice question
 * with the letter of an option, so the text ends in "Answer: (" and the next token is the letter.
 * No role text and no special tokens: the bundle's chat template passes the text through as it is.
 */
object Prompt {
    const val CONTEXT = "You play Pong (Atari) and control the right paddle. Move the paddle so the ball hits it; " +
        "the ball bounces off paddles and walls. Missing the ball loses a point. The image shows the current game screen."
    const val QUESTION = "What should you do right now?"
    /** In [PongGame.UP], [PongGame.DOWN], [PongGame.STAY] order: option (A) is action 0. */
    val OPTIONS = listOf("move paddle up", "move paddle down", "stay")

    fun build(context: String = CONTEXT, question: String = QUESTION, options: List<String> = OPTIONS): String = buildString {
        append("Context:\n").append(context)
        append("\n\nQuestion: ").append(question)
        append("\nOptions:\n")
        options.forEachIndexed { i, o -> append('(').append('A' + i).append(") ").append(o).append('\n') }
        append("Answer: (")
    }
}
