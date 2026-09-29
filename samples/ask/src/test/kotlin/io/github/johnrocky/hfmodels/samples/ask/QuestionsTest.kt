package io.github.johnrocky.hfmodels.samples.ask

import org.junit.Assert.assertEquals
import org.junit.Test

class QuestionsTest {
    /** The five questions rebuilt from each chart alone equal the ones stored with it: text, options, expected. */
    @Test
    fun questionsRebuildFromTheChartAlone() {
        val charts = Fixtures.charts()
        assertEquals(24, charts.size)
        for (c in charts) {
            assertEquals(c.spec.id, Questions.IDS, c.questions.map { it.id })
            assertEquals(c.spec.id, c.questions, Questions.of(c.spec))
        }
        println("${charts.size} charts, ${charts.sumOf { it.questions.size }} questions rebuilt")
    }

    /** For the first three charts the kept questions equal the reference record's (its `second` question is not asked). */
    @Test
    fun questionsMatchTheReferenceRecord() {
        val charts = Fixtures.charts()
        for ((k, ref) in Fixtures.referenceCharts().withIndex()) {
            @Suppress("UNCHECKED_CAST")
            val refQuestions = (ref["questions"] as List<Map<String, Any?>>).associateBy { it["id"] as String }
            for (q in Questions.of(charts[k].spec)) {
                val r = requireNotNull(refQuestions[q.id]) { "${ref["id"]}: no ${q.id}" }
                assertEquals(r["question"], q.text)
                assertEquals(r["options"], q.options)
                assertEquals((r["expected"] as Double).toInt(), q.expected)
            }
        }
    }
}
