package io.github.johnrocky.hfmodels.decide

/**
 * The answer to one [Question], in the `/v1/systemone` answer form. `probabilities` are the model's
 * calibrated option probabilities (they sum to 1); `confidence` is 1 minus the normalized entropy for
 * choice / score, and `max(p, 1-p)` for noul. `extras` carries model-specific fields the publisher
 * defines (for example an `action` object); they are written into the JSON as they are.
 */
sealed class Answer {
    abstract val confidence: Double
    abstract val extras: Map<String, Any?>

    data class Choice(
        val choice: String,
        val probabilities: Map<String, Double>,
        override val confidence: Double,
        override val extras: Map<String, Any?> = emptyMap(),
    ) : Answer()

    /** `score` is the expected level index (a real number); `legend` maps each index to its text. */
    data class Score(
        val score: Double,
        val legend: Map<String, String>,
        val probabilities: Map<String, Double>,
        override val confidence: Double,
        override val extras: Map<String, Any?> = emptyMap(),
    ) : Answer()

    /** `noul` is the probability that the statement holds. */
    data class Noul(
        val noul: Double,
        override val confidence: Double,
        override val extras: Map<String, Any?> = emptyMap(),
    ) : Answer()

    /** The JSON object form, ordered like the reference implementation writes it. */
    fun toMap(): Map<String, Any?> = LinkedHashMap<String, Any?>().also { o ->
        when (this) {
            is Choice -> { o["type"] = "choice"; o["choice"] = choice; o["probabilities"] = LinkedHashMap(probabilities); o["confidence"] = confidence }
            is Score -> { o["type"] = "score"; o["score"] = score; o["legend"] = LinkedHashMap(legend); o["probabilities"] = LinkedHashMap(probabilities); o["confidence"] = confidence }
            is Noul -> { o["type"] = "noul"; o["noul"] = noul; o["confidence"] = confidence }
        }
        o.putAll(extras)
    }
}

/** Wall-clock milliseconds of one decision, measured on the device (not a benchmark: one call, warm or cold as it came). */
data class DecisionTiming(
    /** Serializing and tokenizing the state, once per `decide` / `prefill`. */
    val stateMs: Double,
    /** Per question: building the sequence and running the model, in question order. */
    val questionMs: List<Double>,
    /** From the call to the answers. */
    val totalMs: Double,
)

/** The answers to one state's questions, plus what the model did to produce them. */
data class Decisions(
    /** question id -> answer, in the order the questions were given. */
    val answers: Map<String, Answer>,
    /** The model id (`owner/name`) that answered. */
    val model: String,
    val timing: DecisionTiming,
    /** Tokens the state occupied in each question's sequence; `truncated` when the model's window cut it. */
    val stateTokens: Int,
    val truncated: Boolean,
) {
    /** `{"model": …, "answers": {…}}` in the `/v1/systemone` response form. */
    fun toJson(): String = Json.dumps(linkedMapOf("model" to model, "answers" to answers.mapValues { it.value.toMap() }))
}
