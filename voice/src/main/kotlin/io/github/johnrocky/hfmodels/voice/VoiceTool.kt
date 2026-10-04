package io.github.johnrocky.hfmodels.voice

import org.json.JSONObject

/**
 * A function the model may call during [ToolRunner.turn]. The runner declares [name], [description]
 * and [parameters] to the model, runs [call] when the model asks for it, and sends the returned text
 * back as the tool's response; an exception from [call] goes back as `Error: <message>`.
 */
interface VoiceTool {
    val name: String
    val description: String
    val parameters: List<ToolParam>

    /**
     * Whether the tool changes something on the phone (an alarm, a timer, an event) rather than reads it. [VoiceLoop]
     * says an action's result instead of the model's words about it ([VoiceLoopConfig.speakActionResults]).
     */
    val isAction: Boolean get() = false

    /** [args]: the model's arguments by name. A number may arrive as a number or as a string; [ToolArgs] reads either. */
    suspend fun call(args: Map<String, Any?>): String
}

/** One parameter of a [VoiceTool]: [type] is a JSON-schema scalar type, `string`, `integer`, `number` or `boolean`. */
data class ToolParam(val name: String, val type: String, val description: String, val required: Boolean = true) {
    init {
        require(type in TYPES) { "type '$type' must be one of $TYPES" }
    }

    companion object {
        val TYPES = setOf("string", "integer", "number", "boolean")
    }
}

/**
 * The OpenAI-style declaration, the shape a chat template renders with `tool | tojson` and the shape
 * phone-agent's `PhoneTools.descriptions()` builds:
 * `{"type": "function", "function": {"name", "description", "parameters": {"type": "object", "properties", "required"}}}`.
 */
fun VoiceTool.descriptionJson(): JSONObject = JSONObject(descriptionMap())

/** [descriptionJson] as plain maps and lists: what LiteRT-LM converts for `ConversationConfig.extraContext` (it would stringify a JSONObject). */
internal fun VoiceTool.descriptionMap(): Map<String, Any> = linkedMapOf("type" to "function", "function" to functionMap())

/** The `function` object alone: what LiteRT-LM's `OpenApiTool.getToolDescriptionJsonString()` returns (the runtime wraps it). */
internal fun VoiceTool.functionMap(): Map<String, Any> = linkedMapOf(
    "name" to name,
    "description" to description,
    "parameters" to linkedMapOf(
        "type" to "object",
        "properties" to parameters.associateTo(LinkedHashMap()) { p -> p.name to linkedMapOf("type" to p.type, "description" to p.description) },
        "required" to parameters.filter { it.required }.map { it.name },
    ),
)

/** Lenient readers for [VoiceTool.call]: the runtime hands numbers over as numbers, a parsed text form as strings. */
object ToolArgs {
    fun string(args: Map<String, Any?>, key: String): String = args[key]?.toString() ?: throw IllegalArgumentException("missing $key")

    fun stringOrNull(args: Map<String, Any?>, key: String): String? = args[key]?.toString()

    /**
     * `7`, `7.0` and `"7"` are 7. A number that is not whole (`1.5`), not finite (`"NaN"`) or outside Int is an
     * [IllegalArgumentException], so the model is told instead of the tool running with the number cut short.
     */
    fun int(args: Map<String, Any?>, key: String): Int {
        val v = args[key] ?: throw IllegalArgumentException("missing $key")
        val d = (if (v is Number) v.toDouble() else v.toString().trim().toDoubleOrNull()) ?: throw IllegalArgumentException("$key '$v' is not a number")
        require(d.isFinite() && d == Math.floor(d)) { "$key '$v' must be a whole number" }
        require(d >= Int.MIN_VALUE && d <= Int.MAX_VALUE) { "$key '$v' is out of range" }
        return d.toInt()
    }
}
