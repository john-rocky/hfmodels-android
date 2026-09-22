package io.github.johnrocky.hfmodels.decide

import io.github.johnrocky.hfmodels.PreparedModel

/**
 * A model that answers typed questions about a state without generating text: `decide(state,
 * questions)` returns one [Answer] per question with calibrated probabilities. The request form
 * (`state`, `questions` with `type` / `instructions` / `criteria`) and the answer form are the
 * `/v1/systemone` ones, so the same JSON drives a server and this model.
 *
 * `state` is a `String` (used as it is) or a structured value (`Map<String, Any?>` / `List<Any?>`),
 * which the model serializes the way its publisher's code does ([Json.dumps]). A state longer
 * than the model's window is cut at the end and reported (`Decisions.truncated`); the questions
 * always fit first.
 *
 * `prefill(state)` processes the state once for several rounds of questions; what a backend can
 * share between questions is its own (an encoder shares the serialized, tokenized state; a
 * language model with a scoring API would share the prompt's KV cache). The answers are the same
 * as `decide(state, questions)`.
 *
 * One call at a time per model; a second concurrent call fails with `MODEL_BUSY`.
 */
interface TypedDecisions : PreparedModel {
    val limits: DecisionLimits

    suspend fun decide(state: Any, questions: Map<String, Question>): Decisions

    suspend fun prefill(state: Any): PreparedState

    /** One question, no id: the answer alone. */
    suspend fun decide(state: Any, question: Question): Answer = decide(state, mapOf("q" to question)).answers.getValue("q")
}

/** A state the model has processed; ask it several rounds of questions. Close it when done (it holds no native memory on an encoder backend, but a language-model backend would). */
interface PreparedState : AutoCloseable {
    val model: TypedDecisions
    suspend fun decide(questions: Map<String, Question>): Decisions
    suspend fun decide(question: Question): Answer = decide(mapOf("q" to question)).answers.getValue("q")
    override fun close()
}

/** What this load can take. */
data class DecisionLimits(
    /** The model's sequence window in tokens: instructions, options and state together. */
    val windowTokens: Int,
    /** Tokens reserved for the question head (instructions + options) before the state gets the rest. */
    val headTokens: Int,
    /** Most options a choice / score question may carry (the reference implementation's sequence builder caps each option's text; this is the count). */
    val maxOptions: Int,
    /** Languages the publisher declares for this variant (BCP-47 or "multilingual"); informational. */
    val languages: List<String>,
)
