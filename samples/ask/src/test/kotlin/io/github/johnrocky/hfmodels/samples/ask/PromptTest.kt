package io.github.johnrocky.hfmodels.samples.ask

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTest {
    /** Turn 1 and turn 2 of the first three charts are byte-identical to the texts the reference conversation sent. */
    @Test
    fun firstTwoTurnsAreByteIdenticalToTheReference() {
        val charts = Fixtures.charts()
        for (k in 0 until 3) {
            val spec = charts[k].spec
            val turns = Prompt.turns(spec, Questions.of(spec))
            assertArrayEquals("${spec.id} turn 1", Fixtures.bytes("${spec.id}_turn0.txt"), turns[0].toByteArray(Charsets.UTF_8))
            assertArrayEquals("${spec.id} turn 2", Fixtures.bytes("${spec.id}_turn1.txt"), turns[1].toByteArray(Charsets.UTF_8))
        }
    }

    /** All five turn texts equal the reference record's turn texts for the same question ids (its turn 5, `second`, is not asked). */
    @Test
    fun allTurnsMatchTheReferenceRecord() {
        val charts = Fixtures.charts()
        for ((k, ref) in Fixtures.referenceCharts().withIndex()) {
            @Suppress("UNCHECKED_CAST")
            val refIds = (ref["questions"] as List<Map<String, Any?>>).map { it["id"] as String }
            @Suppress("UNCHECKED_CAST")
            val refTurns = ref["turn_texts"] as List<String>
            assertEquals(listOf("tallest", "shortest", "count", "taller", "second", "leftmost"), refIds)
            val spec = charts[k].spec
            val questions = Questions.of(spec)
            val turns = Prompt.turns(spec, questions)
            assertEquals(5, turns.size)
            questions.forEachIndexed { t, q ->
                assertEquals("${spec.id} turn ${t + 1} (${q.id})", refTurns[refIds.indexOf(q.id)], turns[t])
            }
            println("${spec.id}: turn texts ${turns.map { it.length }} chars match")
        }
    }

    @Test
    fun laterTurnsCloseTheAnswerAndCarryNoContext() {
        val spec = Fixtures.charts()[0].spec
        val turns = Prompt.turns(spec, Questions.of(spec))
        assertTrue(turns[0], turns[0].startsWith("Context:\n${spec.context}\n\nQuestion: "))
        for (t in turns.drop(1)) {
            assertTrue(t, t.startsWith(")\n\nQuestion: "))
            assertTrue(t, !t.contains("Context:"))
        }
        for (t in turns) assertTrue(t, t.endsWith("\nAnswer: ("))
    }
}
