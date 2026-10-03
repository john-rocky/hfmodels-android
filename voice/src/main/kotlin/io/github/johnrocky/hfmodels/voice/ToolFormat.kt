package io.github.johnrocky.hfmodels.voice

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.tool
import org.json.JSONObject

/**
 * How a model's tool calls travel. The bundle decides: LiteRT-LM parses the calls itself for the
 * model types it knows (`LlmMetadata.llm_model_type` function_gemma, gemma4, and qwen3 with JSON
 * inside `<tool_call>`) and leaves them in the text for the others. Whatever the format, calls the
 * runtime did parse ([com.google.ai.edge.litertlm.Message.toolCalls]) are run too.
 */
sealed interface ToolFormat {
    /**
     * The runtime's parser: the tools go in `ConversationConfig.tools`, the calls come back in `Message.toolCalls`.
     * Call markup left in the text (`<tool_call`, `<|tool_call`, `<start_function_call>`: a bundle whose calls the
     * runtime does not parse, such as LFM2.5's) is never shown and fails the turn instead of being said.
     */
    object Runtime : ToolFormat {
        override fun toString() = "runtime"
    }

    /**
     * Qwen XML in the text ([QwenXmlToolCalls]; Qwen3-Coder-style bundles such as Agents-A1): the tool list
     * and `enable_thinking` go to the chat template through `extraContext`, as phone-agent does it, and the
     * app parses the calls. A malformed call fails the turn instead of running.
     */
    object QwenXml : ToolFormat {
        override fun toString() = "qwenxml"
    }

    /**
     * LFM2's pythonic calls in the text ([LfmPythonicToolCalls]; LFM2 / LFM2.5 bundles of model type
     * `generic_model`, whose calls LiteRT-LM 0.16.1 does not parse): the tools are declared as for [Runtime]
     * (the chat template lists them), and the app parses the calls. A malformed call fails the turn instead of running.
     */
    object LfmPythonic : ToolFormat {
        override fun toString() = "lfm"
    }
}

/** One call to run, whichever way it arrived ([byRuntime]: in `Message.toolCalls`, so it is answered in the runtime's shape). */
internal data class ParsedCall(val name: String, val args: Map<String, Any?>, val byRuntime: Boolean = false)

/** A finished model turn: the calls to run, the text without them, or why the turn cannot go on. */
internal data class ParsedTurn(val calls: List<ParsedCall>, val said: String, val error: String?)

/** Greedy decoding (phone-agent's): the same prompt on the same model state gives the same answer. */
private val GREEDY = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0)

internal fun ToolFormat.conversationConfig(tools: List<VoiceTool>, system: String, thinking: Boolean): ConversationConfig = when (this) {
    ToolFormat.Runtime, ToolFormat.LfmPythonic -> ConversationConfig(systemInstruction = Contents.of(system), tools = tools.map { tool(OpenApiToolAdapter(it)) }, samplerConfig = GREEDY)
    ToolFormat.QwenXml -> ConversationConfig(
        systemInstruction = Contents.of(system), samplerConfig = GREEDY,
        extraContext = mapOf("tools" to tools.map { it.descriptionMap() }, "enable_thinking" to thinking),
    )
}

/** The turn's calls: the runtime's first (any format: a bundle the runtime parses may still be given a text format), then the text's. */
internal fun ToolFormat.parse(text: String, runtimeCalls: List<ToolCall>): ParsedTurn {
    val byRuntime = runtimeCalls.map { ParsedCall(it.name, it.arguments, byRuntime = true) }
    return when (this) {
        ToolFormat.Runtime -> if (RUNTIME_MARKUP.any { text.contains(it) }) ParsedTurn(emptyList(), text.trim(), "tool call markup the runtime did not parse is in the text (this bundle may need format lfm or qwenxml)")
            else ParsedTurn(byRuntime, text.trim(), null)
        ToolFormat.QwenXml -> if (QwenXmlToolCalls.hasUnparsedMarkup(text)) ParsedTurn(emptyList(), text.trim(), "malformed or incomplete tool call in the text")
            else ParsedTurn(byRuntime + QwenXmlToolCalls.parse(text).map { ParsedCall(it.name, it.args) }, QwenXmlToolCalls.withoutCalls(text), null)
        ToolFormat.LfmPythonic -> if (LfmPythonicToolCalls.hasUnparsedMarkup(text)) ParsedTurn(emptyList(), text.trim(), "malformed or incomplete tool call in the text")
            else ParsedTurn(byRuntime + LfmPythonicToolCalls.parse(text).map { ParsedCall(it.name, it.args) }, LfmPythonicToolCalls.withoutCalls(text), null)
    }
}

/**
 * The answer to one call. The runtime's FC-format processors (FunctionGemma, Gemma 4) render an object
 * response as `name{result:...}` and would print a bare string's JSON wrapper fields too, so [ToolFormat.Runtime]
 * and [ToolFormat.LfmPythonic] (whose template prints it as JSON) send `{"result": text}`; [ToolFormat.QwenXml] sends the
 * text, as phone-agent does.
 */
internal fun ToolFormat.response(name: String, result: String): Content = when (this) {
    ToolFormat.Runtime, ToolFormat.LfmPythonic -> Content.ToolResponse(name, mapOf("result" to result))
    ToolFormat.QwenXml -> Content.ToolResponse(name, result)
}

/**
 * How much of a turn's streamed text can be shown (or spoken) now: everything before the first call
 * markup, holding back an end that may be the start of it. The runtime keeps its own calls out of the text.
 */
internal fun ToolFormat.visibleLength(text: String): Int = when (this) {
    ToolFormat.Runtime -> RUNTIME_MARKUP.minOf { beforeMarkup(text, it) }
    ToolFormat.QwenXml -> beforeMarkup(text, "<tool_call")
    ToolFormat.LfmPythonic -> beforeMarkup(text, LfmPythonicToolCalls.OPEN)
}

/**
 * A [VoiceTool] declared to LiteRT-LM. The runtime never runs it: hfmodels passes `automaticToolCalling = false`,
 * and [ToolRunner] runs the calls itself, so [execute] being called is a bug.
 */
internal class OpenApiToolAdapter(private val tool: VoiceTool) : OpenApiTool {
    override fun getToolDescriptionJsonString(): String = JSONObject(tool.functionMap()).toString()

    override fun execute(paramsJsonString: String): String =
        throw IllegalStateException("the runtime called ${tool.name} itself; hfmodels runs tools in ToolRunner (automaticToolCalling is off)")
}
