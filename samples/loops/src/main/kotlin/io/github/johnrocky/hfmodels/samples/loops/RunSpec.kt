package io.github.johnrocky.hfmodels.samples.loops

/**
 * What one run asks: the questions, which files in which order, Think, whether a missing file may be downloaded, and
 * the recording harness's own Stop. The screen's Run builds one from the boxes; recording mode from the launch extras
 * (MainActivity's KDoc, README.md).
 */
data class RunSpec(
    val questions: List<Question>,
    /** The files in the order they run. Every question runs on the first, then every question on the second. */
    val loops: List<Loop>,
    val think: Boolean,
    /** No request at all: a file that is not on the phone ends that file's part of the run with a sentence. */
    val offline: Boolean,
    /** The harness presses Stop once a download has this many MB of its file on the phone; null: never. */
    val stopAfterMb: Int? = null,
) {
    /** A question; [example] is the shipped example whose text it is (its known answer marks the reply), or null. */
    data class Question(val text: String, val example: Example?)

    companion object {
        /** `run` both | 1 | 2 and `order` 12 | 21 (which file goes first when both run). */
        fun loops(run: String?, order: String?): List<Loop> {
            val both = if (order == "21") listOf(Loop.TWO, Loop.ONE) else listOf(Loop.ONE, Loop.TWO)
            return when (run) {
                null, "", "both" -> both
                "1" -> listOf(Loop.ONE)
                "2" -> listOf(Loop.TWO)
                else -> throw IllegalArgumentException("run '$run': use both, 1 or 2")
            }
        }

        /** A typed question: it carries an example only when its text is a shipped example's, word for word. */
        fun question(text: String): Question {
            val t = text.trim()
            return Question(t, Examples.ALL.firstOrNull { it.text == t })
        }

        /**
         * The questions of the launch extras: `question` (your text), else `example` (one id), else `examples` (`all`, or
         * ids separated by commas). Throws [IllegalArgumentException] with the sentence for the screen.
         */
        fun questions(question: String?, example: String?, examples: String?): List<Question> = when {
            !question.isNullOrBlank() -> listOf(question(question))
            !example.isNullOrBlank() -> listOf(shipped(example))
            examples == "all" -> Examples.ALL.map { Question(it.text, it) }
            !examples.isNullOrBlank() -> examples.split(',').map { shipped(it.trim()) }
            else -> throw IllegalArgumentException("Nothing to ask: pass question, example or examples.")
        }

        private fun shipped(id: String): Question {
            val e = Examples.byId(id) ?: throw IllegalArgumentException("No example '$id' (have: ${Examples.ALL.joinToString(", ") { it.id }}).")
            return Question(e.text, e)
        }
    }
}
