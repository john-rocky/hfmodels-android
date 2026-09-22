package io.github.johnrocky.hfmodels.decide

import io.github.johnrocky.hfmodels.ModelException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** `Json.dumps` must produce the bytes Python's `json.dumps(v, ensure_ascii=False)` produces; the expected strings below were generated with Python 3.12. */
class JsonAndQuestionTest {
    @Test fun dumpsMatchesPython() {
        assertEquals("""{"from": "user@acme.com", "subject": "Duplicate charge", "body": "Hi,\nwe were billed twice."}""".replace("\\n", "\\n"),
            Json.dumps(linkedMapOf("from" to "user@acme.com", "subject" to "Duplicate charge", "body" to "Hi,\nwe were billed twice.")).replace("\\n", "\\n"))
        assertEquals("""{"a": [1, 2.5, true, null], "b": {"c": "日本語/slash \"q\""}}""", Json.dumps(linkedMapOf("a" to listOf(1L, 2.5, true, null), "b" to linkedMapOf("c" to "日本語/slash \"q\""))))
        assertEquals("[1.0, 1e-05, 1e+16, 0.1, 123456789.5, -2.0]", Json.dumps(listOf(1.0, 1e-5, 1e16, 0.1, 123456789.5, -2.0)))
        assertEquals("\"tab\\there\\u0001\"", Json.dumps("tab\there\u0001"))
    }

    @Test fun parseKeepsOrderAndRoundTrips() {
        val text = """{"z": 1, "a": {"y": [true, false, null, "x"], "b": -3.25e2}, "m": "éé/"}"""
        val v = Json.parseObject(text)
        assertEquals(listOf("z", "a", "m"), v.keys.toList())
        assertEquals(1L, v["z"])
        assertEquals(-325.0, ((v["a"] as Map<*, *>)["b"] as Double), 0.0)
        assertEquals("éé/", v["m"])
        assertEquals("""{"z": 1, "a": {"y": [true, false, null, "x"], "b": -325.0}, "m": "éé/"}""", Json.dumps(v))
    }

    @Test fun questionsParseInOrderWithBothCriteriaForms() {
        val qs = Question.parseAll("""{
            "department": {"type": "choice", "instructions": "Which department?", "criteria": {"billing": "invoices", "technical": null, "other": ""}},
            "resolution": {"type": "choice", "instructions": "Resolved?", "criteria": ["resolved", "pending"]},
            "urgency": {"type": "score", "instructions": "How urgent?", "criteria": ["not urgent", "soon", "critical"]},
            "refund": {"type": "noul", "instructions": "Refund requested?"},
            "spam": {"type": "noul", "instructions": "Is it spam?", "criteria": {"false": "a real message", "true": "unsolicited advertising"}}
        }""")
        assertEquals(listOf("department", "resolution", "urgency", "refund", "spam"), qs.keys.toList())
        val dep = qs["department"] as Question.Choice
        assertEquals(listOf("billing", "technical", "other"), dep.criteria.keys.toList())
        assertEquals("invoices", dep.criteria["billing"]); assertEquals(null, dep.criteria["technical"]); assertEquals("", dep.criteria["other"])
        assertEquals(mapOf("resolved" to null, "pending" to null), (qs["resolution"] as Question.Choice).criteria)
        assertEquals(3, (qs["urgency"] as Question.Score).criteria.size)
        assertEquals(null, (qs["refund"] as Question.Noul).criteria)
        assertEquals("unsolicited advertising", (qs["spam"] as Question.Noul).criteria!!["true"])
        assertEquals("""{"type": "score", "instructions": "How urgent?", "criteria": ["not urgent", "soon", "critical"]}""", Json.dumps(qs["urgency"]!!.toMap()))
    }

    @Test fun invalidQuestionsAreTypedErrors() {
        try { Question.parseAll("""{"q": {"type": "choice", "instructions": "x", "criteria": {"only": null}}}"""); fail() } catch (e: ModelException) { assertTrue(e.message!!.contains("at least 2")) }
        try { Question.parseAll("""{"q": {"type": "rank", "instructions": "x"}}"""); fail() } catch (e: ModelException) { assertTrue(e.message!!.contains("unknown question type")) }
        try { Json.parse("{\"a\": }"); fail() } catch (e: ModelException) { assertTrue(e.message!!.contains("invalid JSON")) }
    }
}
