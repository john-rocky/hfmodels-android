package io.github.johnrocky.hfmodels.litert

import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.decide.Answer
import io.github.johnrocky.hfmodels.decide.DecisionLimits
import io.github.johnrocky.hfmodels.decide.Question
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * decide()'s loop without a model ([DecisionRun]): the default plan (one forward per question, what laya, julia,
 * gliner2_decide and gliclass keep) and a packed plan (one forward for several questions, what deberta_decision does):
 * which scores each question decodes, the answer order, the per-question time and the state report.
 */
class DecisionRunTest {
    /** Option i of a question scores as given by the runner; decode reports the scores it saw as the probabilities. */
    private class Family(private val packed: Boolean) : DecisionContract {
        override val family = "test"
        override val limits = DecisionLimits(64, 64, 16, emptyList())
        override val padId = 0
        override val head: Head? = null
        override val notes = emptyList<String>()
        override fun stateIds(state: Any) = intArrayOf(7, 7, 7)
        override fun forward(questionId: String, q: Question, stateIds: IntArray): Forward {
            val n = (q as Question.Choice).criteria.size
            return Forward(IntArray(stateIds.size + n), FloatArray(0), IntArray(n) { it }, stateIds.size, false)
        }
        override fun plan(questions: Map<String, Question>, stateIds: IntArray): List<Batch> {
            if (!packed) return super.plan(questions, stateIds)
            var from = 0
            val planned = questions.map { (id, q) -> val n = (q as Question.Choice).criteria.size; Planned(id, q, from until from + n).also { from += n } }
            return listOf(Batch(Forward(IntArray(stateIds.size + from), FloatArray(0), IntArray(from) { it }, stateIds.size, true), planned, 0.0))
        }
        override fun signature(hostLookup: Boolean, inputShape: (String) -> List<Int>?, outputShape: (String) -> List<Int>?) = Signature("t", "a", "r", listOf("o"))
        override fun scores(f: Forward, output: (String) -> FloatArray) = output("o")
        override fun decode(q: Question, scores: FloatArray, headLogits: FloatArray?): Answer {
            val keys = (q as Question.Choice).criteria.keys.toList()
            return Answer.Choice(keys[0], LinkedHashMap(keys.indices.associate { keys[it] to scores[it].toDouble() }), 1.0)
        }
    }

    private val questions = linkedMapOf(
        "a" to Question.Choice("q", "a0", "a1"),
        "b" to Question.Choice("q", "b0", "b1", "b2"),
        "c" to Question.Choice("q", "c0", "c1"),
    )

    /** Each forward's scores: 100 x the forward's index + the slot. */
    private fun runner(): (Forward) -> Pair<FloatArray, FloatArray?> {
        var calls = 0
        return { f -> val k = calls++; FloatArray(f.reads.size) { 100f * k + it } to null }
    }

    private fun scoresOf(a: Answer) = (a as Answer.Choice).probabilities.values.toList()

    @Test fun defaultPlanIsOneForwardPerQuestion() {
        val c = Family(packed = false)
        assertEquals(listOf(listOf("a"), listOf("b"), listOf("c")), c.plan(questions, c.stateIds("s")).map { b -> b.questions.map { it.id } })
        val d = DecisionRun.decide(c, c.stateIds("s"), questions, "m", 1.5, System.nanoTime(), runner())
        assertEquals(listOf("a", "b", "c"), d.answers.keys.toList())
        assertEquals(listOf(0.0, 1.0), scoresOf(d.answers.getValue("a")))
        assertEquals(listOf(100.0, 101.0, 102.0), scoresOf(d.answers.getValue("b")))
        assertEquals(listOf(200.0, 201.0), scoresOf(d.answers.getValue("c")))
        assertEquals(3, d.timing.questionMs.size)
        assertEquals(1.5, d.timing.stateMs, 0.0)
        assertEquals(3, d.stateTokens)
        assertEquals(false, d.truncated)
    }

    @Test fun packedPlanSlicesOneForward() {
        val c = Family(packed = true)
        val d = DecisionRun.decide(c, c.stateIds("s"), questions, "m", 0.0, System.nanoTime(), runner())
        assertEquals(listOf("a", "b", "c"), d.answers.keys.toList())
        assertEquals(listOf(0.0, 1.0), scoresOf(d.answers.getValue("a")))
        assertEquals(listOf(2.0, 3.0, 4.0), scoresOf(d.answers.getValue("b")))
        assertEquals(listOf(5.0, 6.0), scoresOf(d.answers.getValue("c")))
        // One forward: every question carries its time.
        assertEquals(1, d.timing.questionMs.toSet().size)
        assertEquals(true, d.truncated)
    }

    @Test fun noQuestionsAndAnIncompletePlanAreRefused() {
        try { DecisionRun.decide(Family(false), intArrayOf(), emptyMap(), "m", 0.0, 0L, runner()); fail("no questions accepted") } catch (e: ModelException) { assertEquals(ErrorCode.INVALID_INPUT, e.code) }
        val partial = object : DecisionContract by Family(false) {
            override fun plan(questions: Map<String, Question>, stateIds: IntArray) = Family(false).plan(questions, stateIds).take(1)
        }
        try { DecisionRun.decide(partial, intArrayOf(1), questions, "m", 0.0, 0L, runner()); fail("an incomplete plan accepted") } catch (e: IllegalStateException) { assertEquals(true, e.message!!.contains("1 of 3")) }
    }
}
