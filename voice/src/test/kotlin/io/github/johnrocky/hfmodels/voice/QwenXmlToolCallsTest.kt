package io.github.johnrocky.hfmodels.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** phone-agent's QwenXmlToolCalls behaviour, kept by the port: complete blocks run, anything else stays visible. */
class QwenXmlToolCallsTest {
    private val alarm = "<tool_call>\n<function=set_alarm>\n<parameter=hour>\n7\n</parameter>\n<parameter=minute>\n30\n</parameter>\n</function>\n</tool_call>"

    @Test fun aCompleteBlockIsOneCallWithTrimmedStringArguments() {
        val text = "Sure. $alarm"
        val calls = QwenXmlToolCalls.parse(text)
        assertEquals(1, calls.size)
        assertEquals("set_alarm", calls[0].name)
        assertEquals(mapOf("hour" to "7", "minute" to "30"), calls[0].args)
        assertEquals(alarm, calls[0].raw)
        assertEquals("Sure.", QwenXmlToolCalls.withoutCalls(text))
        assertFalse(QwenXmlToolCalls.hasUnparsedMarkup(text))
    }

    @Test fun twoBlocksAreTwoCallsInOrderAndTheTextAroundThemStays() {
        val timer = "<tool_call><function=set_timer><parameter=minutes>20</parameter></function></tool_call>"
        val text = "One. $alarm Two. $timer Done."
        assertEquals(listOf("set_alarm", "set_timer"), QwenXmlToolCalls.parse(text).map { it.name })
        assertEquals("One.  Two.  Done.", QwenXmlToolCalls.withoutCalls(text))
    }

    @Test fun anUnclosedBlockIsNotACallAndIsReported() {
        val text = "<tool_call>\n<function=set_alarm>\n<parameter=hour>7</parameter>"
        assertEquals(emptyList<QwenXmlToolCalls.Call>(), QwenXmlToolCalls.parse(text))
        assertTrue(QwenXmlToolCalls.hasUnparsedMarkup(text))
    }

    @Test fun anOpenerInsideABlockLeavesTheBrokenPrefixAndRecoversTheNextBlock() {
        val text = "<tool_call><function=set_timer><parameter=minutes>5 $alarm"
        val calls = QwenXmlToolCalls.parse(text)
        assertEquals(listOf("set_alarm"), calls.map { it.name })
        assertTrue(QwenXmlToolCalls.withoutCalls(text).startsWith("<tool_call><function=set_timer>"))
        assertTrue(QwenXmlToolCalls.hasUnparsedMarkup(text))
    }

    @Test fun malformedBodiesAreNeverCalls() {
        for (body in listOf(
            "<function=set alarm></function>",                                                           // not an identifier
            "<function=set_alarm><parameter=hour>7</parameter><parameter=hour>8</parameter></function>", // a key twice
            "<function=set_alarm><parameter=hour>7<function=x></parameter></function>",                  // markup in a value
            "<function=set_alarm><parameter=hour>7</parameter></function> trailing",                     // text after the function
            "{\"name\": \"set_alarm\", \"arguments\": {\"hour\": 7}}",                                   // Qwen3's JSON form
        )) {
            val text = "<tool_call>$body</tool_call>"
            assertEquals(body, emptyList<QwenXmlToolCalls.Call>(), QwenXmlToolCalls.parse(text))
            assertTrue(body, QwenXmlToolCalls.hasUnparsedMarkup(text))
        }
    }
}
