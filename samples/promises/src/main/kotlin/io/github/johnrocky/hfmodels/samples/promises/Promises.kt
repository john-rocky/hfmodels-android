package io.github.johnrocky.hfmodels.samples.promises

import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.decide.Answer
import io.github.johnrocky.hfmodels.decide.Question
import io.github.johnrocky.hfmodels.decide.TypedDecisions

/** The one question every sentence gets, the bundles it fills, and one sentence's answer. */
object Promises {
    /**
     * The round-1 sieve's question B, word for word (litert's GlinerDecideDeviceTest.B_MAIN). The family turns
     * it into one gliner2 task: the instructions are the prompt and the descriptions are the labels, so the
     * descriptions are what the model reads. The model gets the sentence alone; the sender is only shown.
     */
    val QUESTION = Question.Choice(
        "What is this sentence?",
        linkedMapOf(
            "nothing" to "an opinion, a story, a vague maybe, or something happening right now",
            "promise" to "the speaker commits to do something later",
            "request" to "the speaker asks the listener to do something",
            "plan" to "a time or day agreed to meet or do something",
        ),
    )

    /** A sentence that does not fit the 128-token window together with the question (CONTEXT_LIMIT_EXCEEDED): kept and shown, not dropped. */
    const val TOO_LONG = "too long"

    /** The bundles on screen, in order. "nothing" and [TOO_LONG] are counted and folded under them. */
    val BUNDLES = linkedMapOf("promise" to "You promised", "request" to "They asked you", "plan" to "Plans")

    class Verdict(
        val sentence: Sentence,
        /** An option key of [QUESTION], or [TOO_LONG]. */
        val key: String,
        /** Per option key; empty for [TOO_LONG]. */
        val probabilities: Map<String, Double>,
        /** The SDK's wall clock for the call: tokenize, look up, forward, decode (DecisionTiming.totalMs). */
        val ms: Double,
        /** The forward and the decode only (DecisionTiming.questionMs). */
        val questionMs: Double,
        /** The sentence's tokens; for a refused sentence, the whole sequence's. */
        val tokens: Int,
    ) {
        val probability: Double get() = probabilities[key] ?: 0.0
        val decided: Boolean get() = key != TOO_LONG
    }

    /** One sentence, one decide() with [QUESTION]. Blocks the calling thread for the forward: call it off the main thread. */
    suspend fun judge(model: TypedDecisions, s: Sentence): Verdict = try {
        val d = model.decide(s.text, mapOf("q" to QUESTION))
        val a = d.answers.getValue("q") as Answer.Choice
        Verdict(s, a.choice, a.probabilities, d.timing.totalMs, d.timing.questionMs.first(), d.stateTokens)
    } catch (e: ModelException) {
        if (e.code != ErrorCode.CONTEXT_LIMIT_EXCEEDED) throw e
        Verdict(s, TOO_LONG, emptyMap(), 0.0, 0.0, e.details["tokens"]?.toIntOrNull() ?: 0)
    }

    /** The device gate's definitions (GlinerDecideDeviceTest): sorted[n / 2] and sorted[9n / 10]. NaN when empty. */
    fun median(xs: List<Double>): Double = xs.sorted().let { if (it.isEmpty()) Double.NaN else it[it.size / 2] }

    fun p90(xs: List<Double>): Double = xs.sorted().let { if (it.isEmpty()) Double.NaN else it[(it.size * 9) / 10] }
}
