package io.github.johnrocky.hfmodels.litert

import io.github.johnrocky.hfmodels.decide.Answer
import io.github.johnrocky.hfmodels.decide.Question
import io.github.johnrocky.hfmodels.decide.TypedDecisions

/**
 * The litert-samples Model Zoo row's input and count through this module, for the Zoo-vs-SDK comparison of the
 * typed-decisions lane (r15): the Zoo Text screen's default sentence and question, its four options as key ->
 * description (the description is the string the Zoo's hosts and this module's three encoder families feed), a first
 * run, two warm-ups and ten timed decide() calls, as the Zoo's timing harness does. Each timed call is reported as
 *  - wall: decide() on the caller's thread (the Zoo row's span is its engine's entry to the last readback);
 *  - the module's own timing: stateMs (the text's tokenization), questionMs (forward build, graph call, decode), totalMs;
 *  - lookup: the host embedding lookup inside that call.
 * Then, on the same input outside decide(), ten times each: the tokenizer, the forward build and the graph call
 * (lookup, writes, run and readback on the model thread).
 */
internal object ZooInputBench {
    const val TEXT = "I'll bring the folding chairs over tomorrow morning."
    val QUESTION = Question.Choice("What is this sentence?", linkedMapOf(
        "nothing" to "an opinion, a story, a vague maybe, or something happening right now",
        "promise" to "the speaker commits to do something later",
        "request" to "the speaker asks the listener to do something",
        "plan" to "a time or day agreed to meet or do something",
    ))

    /** One RESULT detail string, numbers unrounded. */
    suspend fun run(m: TypedDecisions, impl: LiteRtDecisionModel, first: Int = 1, warmups: Int = 2, timed: Int = 10): String {
        val c = impl.contract
        val q = mapOf("q" to QUESTION)
        repeat(first + warmups) { m.decide(TEXT, q) }
        val wall = ArrayList<Double>(); val total = ArrayList<Double>(); val state = ArrayList<Double>()
        val question = ArrayList<Double>(); val lookup = ArrayList<Double>()
        var answer: Answer.Choice? = null
        repeat(timed) {
            val t0 = System.nanoTime()
            val d = m.decide(TEXT, q)
            wall += (System.nanoTime() - t0) / 1e6
            total += d.timing.totalMs; state += d.timing.stateMs; question += d.timing.questionMs[0]; lookup += impl.lastLookupMs
            answer = d.answers.getValue("q") as Answer.Choice
        }
        val tokenize = ArrayList<Double>(); val build = ArrayList<Double>(); val graph = ArrayList<Double>()
        var ids = IntArray(0); var forwardTokens = 0
        repeat(timed) {
            var t = System.nanoTime()
            ids = c.stateIds(TEXT)
            tokenize += (System.nanoTime() - t) / 1e6
            t = System.nanoTime()
            val f = c.forward("q", QUESTION, ids)
            build += (System.nanoTime() - t) / 1e6
            forwardTokens = f.ids.size
            t = System.nanoTime()
            impl.scores(f)
            graph += (System.nanoTime() - t) / 1e6
        }
        val a = answer!!
        return "text_tokens=${ids.size} forward_tokens=$forwardTokens window=${m.limits.windowTokens} first=$first warmups=$warmups timed=$timed " +
            "wall_ms_median=${median(wall)} total_ms_median=${median(total)} state_ms_median=${median(state)} question_ms_median=${median(question)} " +
            "lookup_ms_median=${median(lookup)} tokenize_ms_median=${median(tokenize)} forward_build_ms_median=${median(build)} graph_call_ms_median=${median(graph)} " +
            "answer=${a.choice} p=${a.probabilities.entries.joinToString(",") { (k, v) -> "$k:$v" }} wall_ms=${wall.joinToString(",")}"
    }

    /** The middle value (the mean of the two middle values for an even count), as the Zoo harness reports it. */
    private fun median(v: List<Double>): Double {
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
