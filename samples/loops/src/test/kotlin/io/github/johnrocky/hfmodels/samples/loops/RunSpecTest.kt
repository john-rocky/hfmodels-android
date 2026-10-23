package io.github.johnrocky.hfmodels.samples.loops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The run a launch asks for, and the examples the app ships. */
class RunSpecTest {
    @Test
    fun loopsAndOrder() {
        assertEquals(listOf(Loop.ONE, Loop.TWO), RunSpec.loops(null, null))
        assertEquals(listOf(Loop.ONE, Loop.TWO), RunSpec.loops("both", "12"))
        assertEquals(listOf(Loop.TWO, Loop.ONE), RunSpec.loops("both", "21"))
        assertEquals(listOf(Loop.ONE), RunSpec.loops("1", "21"))
        assertEquals(listOf(Loop.TWO), RunSpec.loops("2", null))
        assertEquals("int4_1loop", Loop.ONE.variant)
        assertEquals("int4", Loop.TWO.variant)
    }

    @Test
    fun questionsFromTheLaunch() {
        val own = RunSpec.questions("  How many days are in 3 weeks? ", "dates", "all")
        assertEquals(listOf(RunSpec.Question("How many days are in 3 weeks?", null)), own)
        assertEquals("dates", RunSpec.questions(null, "dates", null).single().example!!.id)
        assertEquals(Examples.ALL.map { it.id }, RunSpec.questions(null, null, "all").map { it.example!!.id })
        assertEquals(listOf("gsm8k-69", "reply"), RunSpec.questions(null, "", "gsm8k-69, reply").map { it.example!!.id })
        val bad = runCatching { RunSpec.questions(null, "nope", null) }.exceptionOrNull()
        assertTrue(bad is IllegalArgumentException && bad.message!!.startsWith("No example 'nope'"))
    }

    @Test
    fun aTypedQuestionCarriesAnExampleOnlyWordForWord() {
        val e = Examples.byId("gsm8k-88")!!
        assertEquals(e, RunSpec.question(" ${e.text}\n").example)
        assertNull(RunSpec.question(e.text.replace("Harald", "Harold")).example)
    }

    @Test
    fun theShippedExamples() {
        assertEquals(Examples.ALL.size, Examples.ALL.map { it.id }.toSet().size)
        val gsm = Examples.ALL.filter { it.id.startsWith("gsm8k-") }
        assertEquals(listOf("60", "40", "5", "8000"), gsm.map { it.gold })
        for (g in gsm) assertTrue(g.id, g.text.endsWith(Examples.GSM8K_INSTRUCTION) && g.text.all { it.code < 128 })
        for (e in Examples.ALL - gsm.toSet()) assertNull(e.id, e.gold)
        assertEquals("Type a question first, or pick an example.", Loops.refusal(" \n "))
        assertNull(Loops.refusal("Hi"))
    }
}
