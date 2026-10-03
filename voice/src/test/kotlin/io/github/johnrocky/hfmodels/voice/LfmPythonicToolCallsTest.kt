package io.github.johnrocky.hfmodels.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** LFM2's pythonic calls; the first two texts are LFM2.5-1.2B-Instruct's own on a Galaxy S26 (tools gate, 2026-10-03). */
class LfmPythonicToolCallsTest {
    @Test fun oneCallWithKeywordArgumentsAndTheTextAfterIt() {
        val text = "<|tool_call_start|>[set_alarm(hour=7, minute=30, label='Seven thirty')]<|tool_call_end|>I am setting an alarm labeled 'Seven thirty' for 7:30 AM tomorrow."
        val calls = LfmPythonicToolCalls.parse(text)
        assertEquals(listOf(LfmPythonicToolCalls.Call("set_alarm", mapOf("hour" to 7L, "minute" to 30L, "label" to "Seven thirty"))), calls)
        assertEquals("I am setting an alarm labeled 'Seven thirty' for 7:30 AM tomorrow.", LfmPythonicToolCalls.withoutCalls(text))
        assertFalse(LfmPythonicToolCalls.hasUnparsedMarkup(text))
    }

    @Test fun twoCallsInOneListAndACallWithoutArguments() {
        val two = "<|tool_call_start|>[set_alarm(hour=8, minute=0, label='Workout'),set_timer(minutes=20, label='Workout')]<|tool_call_end|>"
        assertEquals(listOf("set_alarm", "set_timer"), LfmPythonicToolCalls.parse(two).map { it.name })
        assertEquals(mapOf("minutes" to 20L, "label" to "Workout"), LfmPythonicToolCalls.parse(two)[1].args)
        assertEquals(listOf(LfmPythonicToolCalls.Call("get_current_datetime", emptyMap())), LfmPythonicToolCalls.parse("<|tool_call_start|>[get_current_datetime()]<|tool_call_end|>"))
    }

    @Test fun pythonLiterals() {
        val text = """<|tool_call_start|>[f(a=-3, b=2.5, c=True, d=False, e=None, s="say \"hi\"", t='I\'m')]<|tool_call_end|>"""
        val args = LfmPythonicToolCalls.parse(text).single().args
        assertEquals(mapOf("a" to -3L, "b" to 2.5, "c" to true, "d" to false, "e" to null, "s" to "say \"hi\"", "t" to "I'm"), args)
    }

    @Test fun aSingleCallMayComeWithoutTheBrackets() {
        assertEquals(mapOf("minutes" to 5L), LfmPythonicToolCalls.parse("<|tool_call_start|>set_timer(minutes=5)<|tool_call_end|>").single().args)
    }

    @Test fun malformedOrIncompleteBlocksAreNeverCalls() {
        for (body in listOf(
            "[set_alarm(7, 30)]",                // positional
            "[set_alarm(hour=7, hour=8)]",       // a key twice
            "[set_alarm(hour=seven)]",           // not a literal
            "[set_alarm(label='open)]",          // unterminated string
            "[set_alarm(hour=7)] trailing",      // text after the list
            "[]",                                // no call
        )) {
            val text = "<|tool_call_start|>$body<|tool_call_end|>"
            assertEquals(body, emptyList<LfmPythonicToolCalls.Call>(), LfmPythonicToolCalls.parse(text))
            assertTrue(body, LfmPythonicToolCalls.hasUnparsedMarkup(text))
        }
        val open = "<|tool_call_start|>[set_alarm(hour=7, minute=30)]"
        assertEquals(emptyList<LfmPythonicToolCalls.Call>(), LfmPythonicToolCalls.parse(open))
        assertTrue(LfmPythonicToolCalls.hasUnparsedMarkup(open))
    }
}
