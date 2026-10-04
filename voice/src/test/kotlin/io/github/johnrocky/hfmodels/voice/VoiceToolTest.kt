package io.github.johnrocky.hfmodels.voice

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VoiceToolTest {
    private val alarm = object : VoiceTool {
        override val name = "set_alarm"
        override val description = "Sets an alarm on this phone."
        override val parameters = listOf(
            ToolParam("hour", "integer", "Hour in 24-hour time (0-23)."),
            ToolParam("minute", "integer", "Minute (0-59)."),
            ToolParam("label", "string", "Short label shown with the alarm.", required = false),
        )
        override suspend fun call(args: Map<String, Any?>) = "ok"
    }

    @Test fun descriptionJsonIsTheOpenAiFunctionShape() {
        val j = alarm.descriptionJson()
        assertEquals("function", j.getString("type"))
        val f = j.getJSONObject("function")
        assertEquals(setOf("name", "description", "parameters"), f.keys().asSequence().toSet())
        assertEquals("set_alarm", f.getString("name"))
        assertEquals("Sets an alarm on this phone.", f.getString("description"))
        val p = f.getJSONObject("parameters")
        assertEquals("object", p.getString("type"))
        assertEquals(listOf("hour", "minute", "label"), alarm.parameters.map { it.name }.filter { p.getJSONObject("properties").has(it) })
        assertEquals("integer", p.getJSONObject("properties").getJSONObject("hour").getString("type"))
        assertEquals("Minute (0-59).", p.getJSONObject("properties").getJSONObject("minute").getString("description"))
        // Only the required ones; phone-agent marked all of its parameters required.
        val required = p.getJSONArray("required")
        assertEquals(listOf("hour", "minute"), List(required.length()) { required.getString(it) })
    }

    @Test fun theRuntimeGetsTheFunctionObjectAlone() {
        assumeLiteRtLmClasses()
        // LiteRT-LM's ToolManager wraps it in {"type": "function", "function": ...} itself.
        val f = JSONObject(OpenApiToolAdapter(alarm).getToolDescriptionJsonString())
        assertEquals(setOf("name", "description", "parameters"), f.keys().asSequence().toSet())
        assertThrows(IllegalStateException::class.java) { OpenApiToolAdapter(alarm).execute("{}") }
    }

    @Test fun aParameterTypeIsAJsonSchemaScalar() {
        assertThrows(IllegalArgumentException::class.java) { ToolParam("when", "datetime", "x") }
    }

    @Test fun argumentsAreReadLeniently() {
        val args = mapOf<String, Any?>("a" to 7, "b" to "30", "c" to 8.0, "d" to " 9 ", "e" to "seven")
        assertEquals(listOf(7, 30, 8, 9), listOf("a", "b", "c", "d").map { ToolArgs.int(args, it) })
        assertThrows(IllegalArgumentException::class.java) { ToolArgs.int(args, "e") }
        assertThrows(IllegalArgumentException::class.java) { ToolArgs.int(args, "missing") }
        assertEquals("30", ToolArgs.string(args, "b"))
        assertEquals(null, ToolArgs.stringOrNull(args, "missing"))
    }

    @Test fun aNumberThatIsNotWholeIsNotCutShort() {
        // These were 1, 0, 0 and Int.MAX_VALUE; the model sees the message as "Error: <message>".
        val args = mapOf<String, Any?>("a" to 1.5, "b" to -0.5, "c" to "NaN", "d" to "Infinity")
        for ((key, v) in args) {
            val e = assertThrows(IllegalArgumentException::class.java) { ToolArgs.int(args, key) }
            assertEquals("$key '$v' must be a whole number", e.message)
        }
        assertEquals("a '1.0E10' is out of range", assertThrows(IllegalArgumentException::class.java) { ToolArgs.int(mapOf("a" to 1e10), "a") }.message)
        assertEquals(listOf(7, 7, 7), listOf<Any>(7, 7.0, "7").map { ToolArgs.int(mapOf("a" to it), "a") })
    }

    @Test fun theTimerAndTheAlarmRefuseAPartOfAMinuteOrAnHour() {
        // Before, a 1-minute timer started, and hour -0.5 passed the 0-23 check as 00.
        val context = android.content.ContextWrapper(null)
        val timer = assertThrows(IllegalArgumentException::class.java) { runBlocking { TimerTool(context).call(mapOf("minutes" to 1.5, "label" to "Tea")) } }
        assertEquals("minutes '1.5' must be a whole number", timer.message)
        val alarm = assertThrows(IllegalArgumentException::class.java) { runBlocking { AlarmTool(context).call(mapOf("hour" to -0.5, "minute" to 0)) } }
        assertEquals("hour '-0.5' must be a whole number", alarm.message)
    }
}
