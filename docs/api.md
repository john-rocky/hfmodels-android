# Public API, complete

Everything an app calls, with the exact imports. This page is the whole surface: the sources jar and the runtime AAR add nothing an app needs, so there is no reason to unzip or `javap` them. The Maven group is `io.github.john-rocky.hfmodels` (hyphen); the Kotlin package is `io.github.johnrocky.hfmodels` (no hyphen). Members marked **0.1.1** arrived in 0.1.1; 0.1.0 does not have them. Members marked **0.2.0** are on main (0.2.0-SNAPSHOT) and not on Maven Central yet.

```kotlin
import io.github.johnrocky.hfmodels.HfModels
import io.github.johnrocky.hfmodels.ModelRef
import io.github.johnrocky.hfmodels.Tasks
import io.github.johnrocky.hfmodels.LoadOptions
import io.github.johnrocky.hfmodels.LoadEvent
import io.github.johnrocky.hfmodels.BackendPolicy
import io.github.johnrocky.hfmodels.BackendKind
import io.github.johnrocky.hfmodels.NetworkPolicy
import io.github.johnrocky.hfmodels.InputKind
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.ErrorCode
import io.github.johnrocky.hfmodels.PreparedModelInfo
import io.github.johnrocky.hfmodels.litertlm.ChatModel
import io.github.johnrocky.hfmodels.litertlm.ChatSession
import io.github.johnrocky.hfmodels.litertlm.SessionState
import io.github.johnrocky.hfmodels.litertlm.GenerationOptions
import io.github.johnrocky.hfmodels.litertlm.ThinkingInfo   // 0.1.1
import io.github.johnrocky.hfmodels.litertlm.text           // 0.1.1: the Message.text extension
import io.github.johnrocky.hfmodels.litert.EncoderDecisions // 0.1.2, module hfmodels-litert: typed decisions on a LiteRT decision encoder
import io.github.johnrocky.hfmodels.decide.TypedDecisions   // 0.1.2: the decision model (decide / prefill), Question, Answer, Decisions
import io.github.johnrocky.hfmodels.litert.Transcribe       // 0.2.0, module hfmodels-litert: speech to text, the Task for a Transcriber
import io.github.johnrocky.hfmodels.litert.Speak            // 0.2.0, module hfmodels-litert: text to speech, the Task for a Speaker
import io.github.johnrocky.hfmodels.speech.Transcriber      // 0.2.0: also TranscriberLimits, Transcript, TranscriptTiming
import io.github.johnrocky.hfmodels.speech.Speaker          // 0.2.0: also SpeechAudio, SpeechTiming
import io.github.johnrocky.hfmodels.voice.VoiceLoop         // 0.2.0, module hfmodels-voice: also VoiceLoopConfig, Endpointer, SentenceSplitter, MicSource, SpeechPlayer
import io.github.johnrocky.hfmodels.voice.ToolRunner        // 0.2.0: also VoiceTool, ToolParam, ToolArgs, PhoneTools, ToolFormat, ToolEvent, TurnTiming
// runtime types that cross the SDK boundary (LiteRT-LM 0.16.1, brought in as an `api` dependency)
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.Channel                   // 0.1.1: only if you set ConversationConfig.channels yourself
import com.google.ai.edge.litertlm.ToolCall                  // 0.2.0: tools on Chat (also OpenApiTool and the tool() function)
```

## Entry point: `HfModels`

```kotlin
class HfModels(context: Context, options: HfModelsOptions = HfModelsOptions()) : AutoCloseable

suspend fun <M : PreparedModel> fromPretrained(ref: ModelRef, task: Task<M>, options: LoadOptions = LoadOptions(), onProgress: (LoadEvent) -> Unit = {}): M
// the same in three steps
suspend fun <M : PreparedModel> inspect(ref: ModelRef, task: Task<M>, options: LoadOptions = LoadOptions()): ModelPlan<M>   // no download; plan.totalBytes, plan.bytesToDownload, plan.profile.id
suspend fun <M : PreparedModel> download(plan: ModelPlan<M>, onProgress: (LoadEvent) -> Unit = {}): LocalModel<M>
suspend fun <M : PreparedModel> prepare(local: LocalModel<M>, onProgress: (LoadEvent) -> Unit = {}): M
// store management
suspend fun importFile(plan: ModelPlan<*>, fileId: String, source: File): File   // hash + move a file you already have (the adb-push shortcut does this for you)
fun evict(plan: ModelPlan<*>): Long                                             // bytes freed
fun cacheBytes(): Long
fun boundCommit(repoId: String): String?                                        // the commit an id was bound to on its first load
fun unbind(repoId: String): Boolean
suspend fun closeAndJoin()                                                      // closes the prepared model too; close() is the non-suspending request
```

One `HfModels` per process (it owns one model slot: a second `prepare` while a model is open fails with `MODEL_BUSY`; `closeAndJoin()` the open model first). Hold it in the `Application` or a ViewModel; construct it with the application context.

```kotlin
data class ModelRef(val repoId: String, val revision: String? = null, val variant: String? = null)   // "owner/name"; revision = branch, tag or commit; variant = an id from the descriptor
object Tasks { val Chat: Task<ChatModel> }                                                              // text generation and image-text-to-text
// in hfmodels-litert: EncoderDecisions (0.1.2), Transcribe and Speak (0.2.0) are Tasks too
```

## Options and events

```kotlin
data class LoadOptions(
    val backendPolicy: BackendPolicy = BackendPolicy.Auto,      // Auto = the descriptor's default profile, then priority; a profile whose only verification record is FAIL is skipped (0.1.1)
    val requiredInputs: Set<InputKind>? = null,                 // e.g. setOf(InputKind.IMAGE): fail instead of picking a text-only profile
    val networkPolicy: NetworkPolicy = NetworkPolicy.Any,       // Unmetered: DOWNLOAD_POLICY_BLOCKED on metered; Offline: zero requests, OFFLINE_CACHE_MISS if not cached
    val maxDownloadBytes: Long? = null,                         // DOWNLOAD_POLICY_BLOCKED when the plan needs more
    val allowAdditionalArtifacts: Boolean = false,
    val allowUnverified: Boolean = true,
    val contextTokens: Int? = null,                             // override the profile's context_tokens
    val maxImageBytes: Long = 20L * 1024 * 1024,
    val maxImagePixels: Long = 25_000_000L,
    val credentials: CredentialProvider? = null,                // fun interface CredentialProvider { fun tokenFor(repoId: String): String? }
    val descriptorJson: String? = null,                         // a descriptor you supply for a repo that has none
)
sealed class BackendPolicy { object Auto; data class Require(val backend: BackendKind); data class RequireProfile(val profileId: String) }
enum class BackendKind { CPU, GPU, NPU }
enum class NetworkPolicy { Any, Unmetered, Offline }
enum class InputKind { TEXT, IMAGE, AUDIO, VIDEO }

sealed class LoadEvent {                       // delivered on the caller's dispatcher, in this order
    object Resolving
    data class DownloadStarted(val totalBytes: Long)
    data class Downloading(val bytes: Long, val totalBytes: Long)
    object Verifying
    data class Initializing(val profileId: String)
    data class Fallback(val reason: String)     // only when a profile failed and the next one is tried
    data class Ready(val info: PreparedModelInfo)
}
```

## Chat

```kotlin
interface ChatModel : PreparedModel {
    val info: PreparedModelInfo                         // repoId, commit, variantId, profileId, enabledInputs, components, fallbackHistory, verification, sdkVersion, runtimeVersion
    val enabledInputs: Set<InputKind>                   // what THIS load accepts (the profile's enabled_inputs), not what the model could do
    val thinking: ThinkingInfo                          // 0.1.1: the reasoning channel this load applies (channels empty = none); see "Thinking models"
    suspend fun createConversation(config: ConversationConfig = ConversationConfig()): ChatSession
    fun close()
    suspend fun closeAndJoin()                          // waits for the native side; 10 s cap -> ModelException(NATIVE_STOP_TIMEOUT)
}

interface ChatSession {
    val state: SessionState                             // READY -> GENERATING -> READY; cancel: GENERATING -> CANCELLING -> INVALID; close: CLOSING -> CLOSED
    fun stream(contents: Contents, options: GenerationOptions = GenerationOptions()): Flow<Message>   // cold; collect ONCE; cancelling the collector cancels the native generation
    fun stream(message: Message, options: GenerationOptions = GenerationOptions()): Flow<Message>     // 0.2.0: a message of any role; stream(contents) is stream(Message.user(contents)); Message.tool(...) answers tool calls
    fun cancel()                                        // same as cancelling the collector; no-op when idle
    fun close()
    suspend fun closeAndJoin()
}
data class GenerationOptions(                       // 0.1.0: GenerationOptions(maxOutputTokens: Int = 256)
    val maxOutputTokens: Int? = null,               // null = 256, or 2,048 when thinking.reasonsByDefault (the reasoning counts against the cap)
    val enableThinking: Boolean? = null,            // 0.1.1; false = ask the template for its no-think variant (Qwen3-style hybrids honour it)
    val thinkingTokenBudget: Int? = null,           // 0.1.1; cut the reasoning after this many tokens (needs a declared channel); null = unlimited
)
enum class SessionState { READY, GENERATING, CANCELLING, INVALID, CLOSING, CLOSED }

data class ThinkingInfo(                            // 0.1.1
    val channels: List<Channel>,                    // applied to every conversation unless you pass ConversationConfig.channels (emptyList() disables)
    val source: String,                             // "descriptor" (handler_config.channels) | "bundle" (the file's own LlmMetadata) | "none"
    val prefilled: Boolean?,                        // true = the rendered generation prompt already opens the channel; false = the model emits the start marker itself; null = unknown / no channel
    val generationPromptTail: String?,              // last 48 chars of the rendered generation prompt (newlines as \n), for the record
    val reasonsByDefault: Boolean,                  // the descriptor says the model reasons on every turn unless told not to (handler_config.thinking_default)
)
```

Rules the runtime imposes and the SDK enforces: one generation at a time per model (a second `stream` while one runs fails with `MODEL_BUSY`); a flow collected twice fails with `STREAM_ALREADY_COLLECTED`; after a cancel the session is `INVALID` and the next turn needs `createConversation()` again (`SESSION_INVALIDATED` otherwise); a collector that falls more than 1,024 chunks / 8 MiB behind ends with `SLOW_CONSUMER` after the native side is cancelled. A caller cancelled while `createConversation` runs its native call gets the `CancellationException`, and the conversation made meanwhile is closed (main, 0.2.0; up to 0.1.2 it stayed open, out of reach of the model's close).

**Tools (0.2.0).** `ConversationConfig(tools = …)` reaches the runtime, which declares the tools to the model. The SDK passes `automaticToolCalling = false` whatever the caller set, so the runtime never runs a tool itself: the calls arrive in `Message.toolCalls` of the streamed chunks, the app runs them, and once the flow has completed it answers on the same session.

```kotlin
val session = chat.createConversation(ConversationConfig(systemInstruction = Contents.of(system), tools = listOf(tool(alarmTool))))   // alarmTool: an OpenApiTool
val calls = ArrayList<ToolCall>()
session.stream(Contents.of("Set an alarm for seven thirty.")).collect { m -> calls += m.toolCalls; append(m.text) }
val answers = calls.map { c -> Content.ToolResponse(c.name, mapOf("result" to run(c.name, c.arguments))) }   // one per call
session.stream(Message.tool(Contents.of(answers))).collect { m -> append(m.text) }                           // the reply, or more calls
```

- Which calls the runtime parses depends on the bundle's model type (its `LlmMetadata`): LiteRT-LM 0.16.1 parses FunctionGemma's, Gemma 4's and Qwen3's (JSON inside `<tool_call>`) into `Message.toolCalls` and leaves other models' calls in the text, LFM2.5's `<|tool_call_start|>[…]` among them. `hfmodels-voice` parses the two text forms it knows (`ToolFormat`, "Voice" below), and its `ToolRunner` does this whole round trip for an app.
- `ToolCall.arguments` carries numbers as numbers (`7.0` for an integer parameter). Answer a Gemma 4 or FunctionGemma call with an object (`mapOf("result" to text)`): their call processors also print a bare string's JSON wrapper fields.
- A `Message.tool(…)` whose contents are not all `Content.ToolResponse` fails with `INVALID_INPUT` before any native call, and the session stays READY.
- `extraContext` (values the chat template reads, such as `tools` and `enable_thinking` for a template that lists the tools itself) passes through. `enableResponseFormat` and `loraConfig` are still refused with `UNSUPPORTED_CONFIGURATION`; on 0.1.2 and earlier `tools` is refused too.

## Runtime types an app touches (LiteRT-LM 0.16.1)

```kotlin
Contents.of(text: String): Contents                 // the usual prompt
Contents.of(vararg parts: Content): Contents        // text + image: Contents.of(Content.Text("What is in this picture?"), Content.ImageBytes(jpegOrPngBytes))
Contents.of(parts: List<Content>): Contents
val Contents.contents: List<Content>
class Content.Text(val text: String) : Content
class Content.ImageBytes(val bytes: ByteArray) : Content   // only when InputKind.IMAGE is in enabledInputs (UNSUPPORTED_INPUT otherwise)

ConversationConfig(systemInstruction: Contents = ..., initialMessages: List<Message> = emptyList(), ..., channels: List<Channel>? = null, thinkingConfig: ThinkingConfig? = null)   // named arguments; the rest at their defaults
ConversationConfig(..., tools: List<ToolProvider> = emptyList(), extraContext: Map<String, Any> = emptyMap())   // 0.2.0 passes tools (0.1.2 refuses them); automaticToolCalling is always passed as false
Message.user(text: String); Message.model(text: String); Message.system(text: String)                     // for initialMessages (your own transcript after a cancel)
Message.tool(contents: Contents)                                                                          // 0.2.0: Contents.of(Content.ToolResponse(...), ...), one per call answered
val Message.role: Role            // SYSTEM, USER, MODEL, TOOL
val Message.contents: Contents
val Message.channels: Map<String, String>   // the piece of a declared channel's content this chunk carries, keyed by name ("thought" for every catalogued model); incremental like the text, so append; empty on chunks that carry none
val Message.toolCalls: List<ToolCall>       // 0.2.0: the calls the runtime parsed from this chunk (empty for a bundle whose calls it does not parse)
class ToolCall(val name: String, val arguments: Map<String, Any?>)
class Content.ToolResponse(val name: String, val response: Any)
fun tool(tool: OpenApiTool): ToolProvider   // com.google.ai.edge.litertlm.tool
interface OpenApiTool { fun getToolDescriptionJsonString(): String; fun execute(paramsJsonString: String): String }   // the description is the OpenAI-style `function` object; execute is never called (automaticToolCalling is off)
Channel(channelName: String, start: String, end: String)   // a channel definition, only if you override ConversationConfig.channels
```

Each streamed `Message` is an incremental chunk (append, never replace). The text of a chunk:

```kotlin
val text = m.text                                                                          // 0.1.1 extension (import io.github.johnrocky.hfmodels.litertlm.text)
val text = m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text } // the same on 0.1.0
```

## Thinking models (0.1.1)

A reasoning model streams its thinking between markers (`<think>` … `</think>` for the Qwen3, DeepSeek-R1 and LFM2.5 families; `<|channel>thought` … `<channel|>` for Gemma 4). LiteRT-LM keeps that out of the text only when the conversation has a matching **channel** declared; otherwise the markers and the reasoning arrive as ordinary text. The SDK declares it for you: the catalog entry's `handler_config.channels` (or, failing that, the bundle's own header) is applied to every `createConversation`, and `chat.thinking` reports what was applied and whether the prompt already opens the channel.

- Reading the reasoning: it streams like the text, one piece per chunk, under the channel name — `m.channels["thought"]?.let { reasoning.append(it) }`; `m.text` is the answer only (measured on 0.16.1: 124 of 127 chunks of a 1.2B model's turn carried a piece of the thought, 3 carried text).
- Cap: a model with `thinking.reasonsByDefault` gets a 2,048-token output cap by default because the reasoning counts against it (256 for every other model); a cap hit inside the reasoning yields an empty answer, so raise `maxOutputTokens` for long tasks instead of lowering it.
- Budget: `GenerationOptions(thinkingTokenBudget = n)` closes the channel after `n` reasoning tokens. Use hundreds, not tens: a 1.2B model cut after 16 tokens carried on reasoning inside the answer (measured). `enableThinking = false` renders the template's no-think variant where the model has one (Qwen3 hybrids); a model that always thinks ignores it.
- Off: `createConversation(ConversationConfig(channels = emptyList()))` disables the channels for that conversation; the markers then appear in `m.text` (what an app without the SDK sees).
- Which models: the README table marks the entries whose reasoning was verified to arrive in `channels` on a device; `catalog/entries/*.json` carry `thinking_default` for the ones that reason on every turn.
- Cost: a load with a channel renders one prompt through the runtime at `prepare` (a throwaway conversation, no token generated) to fill `prefilled` / `generationPromptTail`.

## Typed decisions (0.1.2, module `hfmodels-litert`)

A decision model answers typed questions about a state without generating text: one forward per question, calibrated probabilities back. The request and answer forms are the `/v1/systemone` ones (`state`, `questions` with `type` / `instructions` / `criteria`; answers with `choice` / `score` / `noul`, `probabilities`, `confidence`), so a request written for a server is handed to the phone unchanged. The models are decision encoders run by LiteRT's `CompiledModel` (`com.google.ai.edge.litert`, classic `.tflite`): the `laya` family (`convaiinnovations/laya`, English and multilingual; `litert-community/laya-LiteRT`) and, from 0.2.0, the `julia` family (`SupersonicLabs/Julia-1`, mmBERT-small, multilingual; `litert-community/Julia-1-LiteRT`). This lives in its own module because the litert AAR adds about 9 MB of native code and needs `android.uniquePackageNames=false` on AGP 9 (litert 2.2.0 and litert-api 2.2.0 share a namespace); a chat-only app does not pay for it.

```kotlin
// app/build.gradle.kts: implementation("io.github.john-rocky.hfmodels:hfmodels-litert:0.1.2")   // brings hfmodels-core and litert 2.2.0
// gradle.properties:   android.uniquePackageNames=false
import io.github.johnrocky.hfmodels.litert.EncoderDecisions      // the Task, where Tasks.Chat goes
import io.github.johnrocky.hfmodels.decide.TypedDecisions        // the model
import io.github.johnrocky.hfmodels.decide.Question
import io.github.johnrocky.hfmodels.decide.Answer
import io.github.johnrocky.hfmodels.decide.Decisions
import io.github.johnrocky.hfmodels.decide.PreparedState
import io.github.johnrocky.hfmodels.decide.DecisionLimits
import io.github.johnrocky.hfmodels.decide.Json                  // the publisher-compatible JSON reader / writer

val model: TypedDecisions = models.fromPretrained(ModelRef("<owner>/<name>"), EncoderDecisions)
val questions = mapOf(
    "department" to Question.Choice("Which department should handle this request?", linkedMapOf("billing" to "invoices, payments, refunds", "technical" to "bugs, outages", "other" to "everything else")),
    "urgency" to Question.Score("How urgent is this request?", listOf("not urgent", "soon", "critical deadline or blocking issue")),
    "refund_requested" to Question.Noul("Does the user explicitly request a refund?"),
)
val d: Decisions = model.decide(mapOf("subject" to "Duplicate charge", "body" to "Please refund the duplicate charge."), questions)
(d.answers["department"] as Answer.Choice).choice            // "billing"; .probabilities, .confidence
(d.answers["urgency"] as Answer.Score).score                  // 1.26 (expected level index); .legend, .probabilities
(d.answers["refund_requested"] as Answer.Noul).noul           // 0.91 = P(the statement holds)
d.timing.questionMs; d.stateTokens; d.truncated; d.toJson()  // {"model": …, "answers": {…}} in the response form
val same = Question.parseAll(jsonText)                        // the questions object of a request, order kept
```

```kotlin
interface TypedDecisions : PreparedModel {
    val limits: DecisionLimits                                              // windowTokens, headTokens, maxOptions, languages
    suspend fun decide(state: Any, questions: Map<String, Question>): Decisions
    suspend fun decide(state: Any, question: Question): Answer                 // one question, no id
    suspend fun prefill(state: Any): PreparedState                          // the state once, several rounds of questions
}
interface PreparedState : AutoCloseable { suspend fun decide(questions: Map<String, Question>): Decisions; suspend fun decide(question: Question): Answer }

sealed class Question {                                                     // toMap(); Question.fromMap(o); Question.parseAll(json)
    data class Choice(instructions: String, criteria: Map<String, String?>)  // pick one key; a value describes it; a JSON list of keys is accepted too
    data class Score(instructions: String, criteria: List<String>)           // ordered levels, index 0 lowest
    data class Noul(instructions: String, criteria: Map<String, String>? = null)   // does it hold? optional "false" / "true" descriptions
}
sealed class Answer {                                                       // toMap(): the JSON object in the publisher's form (laya rounds to 4 decimals, julia does not)
    data class Choice(choice: String, probabilities: Map<String, Double>, confidence: Double, extras: Map<String, Any?>)
    data class Score(score: Double, legend: Map<String, String>, probabilities: Map<String, Double>, confidence: Double, extras: …)
    data class Noul(noul: Double, confidence: Double, extras: …)             // extras: model-defined fields (laya: "action": {"act_probability"}; julia: "max_probability" on choice / score)
}
data class Decisions(answers: Map<String, Answer>, model: String, timing: DecisionTiming, stateTokens: Int, truncated: Boolean) { fun toJson(): String }
data class DecisionTiming(stateMs: Double, questionMs: List<Double>, totalMs: Double)   // one call's wall clock on the device, not a benchmark
```

- **State**: a `String` is used as it is; a `Map<String, Any?>` / `List<Any?>` is serialized the way the publisher's code does it (`Json.dumps`: Python `json.dumps(…, ensure_ascii=False)` form, key order kept, `/` unescaped) so the phone tokenizes the same bytes the publisher's tests did. `org.json` is not used on this path (it escapes `/` and loses key order on the JVM).
- **Fit**: the question head (instructions + options) is budgeted first, the state gets the rest of the window and is cut at the end when longer (`Decisions.truncated = true`, `stateTokens` = what was kept). A question whose options do not fit the head fails with `CONTEXT_LIMIT_EXCEEDED`. This is both publishers' non-strict mode; when nothing is cut the token ids equal their strict mode's exactly, and Julia-1's Python runtime, which rejects any cut by default, would have rejected exactly the calls the SDK reports as truncated. Julia-1 scores 2 to 20 options per question (`limits.maxOptions`); more, or an empty option text, is `INVALID_INPUT`.
- **Families**: the sequence is the same for both (`CLS <type> question: <instructions> SEP [MASK] option … SEP <state> SEP`, one marker per option, at most 48 tokens of option text); what differs is the option text and the decoding, each a port of the publisher's code checked against its own ids and answers. `laya` renders `key: description`, `level i: text`, `false: … / true: …`, applies the checkpoint's temperature per question type and option count, rounds to 4 decimals, and reports the act head as `extras["action"]`. `julia` renders the criteria description itself (a choice key stands for itself only when it has no description; `false` / `true` literally when a noul has no criteria), applies no temperature (its runtime ships no calibration), reports the full softmax unrounded and `extras["max_probability"]` on choice and score, and has no act head. `confidence` is the SDK's field on both (1 minus the normalized entropy; `max(p, 1 - p)` for noul).
- **What `prefill` shares**: what the backend can. An encoder shares the serialized, tokenized state (about 2 ms on the measured phone) and still runs one forward per question, so `prefill(state).decide(qs)` and `decide(state, qs)` cost the same; a language-model backend with a scoring API would share the prompt's KV cache. The answers are identical either way. The measured numbers are in the README table and `litert/results/`.
- **Rules**: one `decide` at a time per model (`MODEL_BUSY` otherwise); `close()` / `closeAndJoin()` as for chat; `info.notes` carries `compile_ms` and the accelerator the graph was created with (`observed` stays UNKNOWN: litert 2.2.0 reports no per-op execution target).
- **NPU** (0.2.0): the Qualcomm HTP (Hexagon NPU), compiled on the phone by LiteRT (JIT) and cached in the app's cache dir. Qualcomm's license lets its runtime ship only inside an application, so the AAR does not carry it: the app packages it, extracted to its native library dir, and asks for the NPU by name.
  ```sh
  tools/fetch_npu_libs.sh app/src/main/jniLibs/arm64-v8a v81     # Hexagon version by SoC: SM8550 v73, SM8650 v75, SM8750 v79, SM8850 v81 (several at once is fine)
  ```
  ```kotlin
  // app/build.gradle.kts: android { packaging { jniLibs { useLegacyPackaging = true } } }
  val model = models.fromPretrained(ModelRef("litert-community/laya-LiteRT", variant = "ml_s256_wfp16"), EncoderDecisions,
      LoadOptions(backendPolicy = BackendPolicy.Require(BackendKind.NPU)))
  ```
  The variants with an `npu` profile are `ml_s256_wfp16` (multilingual) and `en_s256_fp32` (English). `Auto` keeps the descriptor's default profile and never picks an NPU profile without a PASS record, so an app without the runtime loads as before. `NATIVE_MODULE_MISSING` lists the files the app lacks; `UNSUPPORTED_CONFIGURATION` names a SoC that LiteRT 2.2.0 has no Qualcomm path for; a failed compile is `INITIALIZATION_FAILED`, and `Require` does not fall back. The first load on a phone compiles for tens of seconds (`info.notes`, `compile_ms`); later loads read the cache. The act head runs on the CPU. When the NPU compile of a graph fails, LiteRT 2.2.0 runs that graph on the CPU without an error, and its Kotlin API does not say which happened (`observed` stays UNKNOWN); the device gate counts an NPU run only with LiteRT's log line `Replacing 1 out of 1 node(s) with delegate (DispatchDelegate)`. The script takes the dispatch library and compiler plugin from the LiteRT v2.2.0 release and the rest from QAIRT 2.47.0.260601, the version that release pins.
- **Descriptor**: task `decide`, runtime `litert`, handler `litert.typed_decisions` ABI 1, profile components keyed `inference` (`gpu` with `fallback_profiles: ["cpu"]`, then `cpu`; `npu` for the Qualcomm HTP); `handler_config`: `family` (`laya` default, or `julia`), `window`, `head_tokens`, `hidden`, `files` (`main`, `tokenizer`, and per family `act_head` / `config` / `tokenizer_config` for laya, `table` for a host-lookup graph), `special_tokens` (`{cls, sep, mask, pad, unk}` by text, instead of `tokenizer_config`), `table_dtype` (`float16` default, or `float32`), `gpu_precision` (`fp32` default), `cpu_threads`, `languages`. With `files.table` the graph takes `inputs_embeds`: the SDK memory-maps the `[vocab, hidden]` table and looks the row of every token up on the host (the form the LiteRT model zoo publishes; a float16 table rounds a float32 checkpoint's embeddings, Julia-1's card measures the effect at most 0.0077 on a probability). `litert-community/laya-LiteRT` carries such a descriptor in its repo (six variants: multilingual / English, windows 256 / 512, fp32 / float16 weights); `litert-community/Julia-1-LiteRT` (variants `s512_fp32`, the default, and `s1024_fp32`; float16 table; GPU FP32 or CPU, no NPU: the publisher measured that fp16 arithmetic changes its answers) carries the same descriptor in its repo (`hfmodels.json`, from commit 8f36857c) and in the bundled catalog (`catalog/entries/litert-community__Julia-1-LiteRT.json`, pinned at b92a0d21); `catalog/dev/` keeps a development descriptor the device gate uses to side-load local conversion outputs.
- **Language-model bundles** (`.litertlm`) as a decision backend: not in this release. The Kotlin API of LiteRT-LM 0.16.1 / 0.17.1 exposes no scoring or checkpoint call (its C API and C++ do); the record and the reproduction are in the repository's development notes. `Tasks.Chat` stays the way to use those bundles.

## Voice (0.2.0, modules `hfmodels-litert` + `hfmodels-voice`)

The phone hears a request, acts on it with its own tools and answers aloud, offline. Three models, each loaded by its own `HfModels` (one client holds one native model; the clients share the store): a transcriber (`Transcribe`, speech to text), the chat model with tools (`Tasks.Chat`) and a speaker (`Speak`, text to speech). `Transcribe` and `Speak` are in `hfmodels-litert` (their interfaces, `Transcriber` and `Speaker`, in core's `speech` package); the loop and the tools are in `hfmodels-voice`, which depends on `hfmodels-litert` and `hfmodels-litertlm` as `api`, so its one line brings all four modules, and like every `hfmodels-litert` app the app needs `android.uniquePackageNames=false` on AGP 9. Main only (0.2.0-SNAPSHOT); Maven Central has 0.1.2, without any of it. The speech models are `litert-community/Zipformer-medium-CR-CTC-LiteRT` and `litert-community/kitten-tts-nano-0.8` (both English). Their repos carry no `hfmodels.json` and the bundled catalog has no entry for them, so a load passes the development descriptor from `catalog/dev/` as `LoadOptions(descriptorJson = …)` (without it: `MODEL_NOT_REGISTERED`); a repo that carries `hfmodels.json` loads by its id alone, and these two do not yet. The speaker's descriptor also lists four G2P files its repo does not carry at that commit (copies of `litert-community/Matcha-TTS` files and the LiteRT `text_to_speech_streaming` sample's symbol list), so today those are side-loaded (`samples/voice/README.md`) and the load pins the descriptor's commit ("Descriptor" below). The device runs (README, "Voice") imported every speech file from a local copy (sha256-checked against the descriptor); a download from the Hub with these descriptors is not in the records. The chat model is any `ChatModel` (`VoiceLoopConfig.toolFormat` says how its calls travel); the measured one is `litert-community/gemma-4-E2B-it-litert-lm` from the bundled catalog.

```kotlin
// app/build.gradle.kts: implementation("io.github.john-rocky.hfmodels:hfmodels-voice:0.2.0")   // main; brings hfmodels-litert (LiteRT 2.2.0), hfmodels-litertlm, hfmodels-core
// gradle.properties:   android.uniquePackageNames=false
// app/src/main/assets: the two catalog/dev descriptors (samples/voice adds catalog/dev as an assets dir)
// AndroidManifest.xml: RECORD_AUDIO (MicSource); com.android.alarm.permission.SET_ALARM, READ_CALENDAR, WRITE_CALENDAR (PhoneTools); the modules declare none of them
import io.github.johnrocky.hfmodels.litert.Speak
import io.github.johnrocky.hfmodels.litert.Transcribe
import io.github.johnrocky.hfmodels.voice.MicSource
import io.github.johnrocky.hfmodels.voice.PhoneTools
import io.github.johnrocky.hfmodels.voice.SpeechPlayer
import io.github.johnrocky.hfmodels.voice.VoiceLoop
import io.github.johnrocky.hfmodels.voice.VoiceLoopConfig

fun asset(name: String) = app.assets.open(name).bufferedReader().use { it.readText() }
val asrModels = HfModels(app); val ttsModels = HfModels(app); val llmModels = HfModels(app)   // one native model per client
val asr = asrModels.fromPretrained(ModelRef("litert-community/Zipformer-medium-CR-CTC-LiteRT", revision = "7732ad6c15ec43402968d5ae04acfa7a204027f5", variant = "medium_fp16"),
    Transcribe, LoadOptions(descriptorJson = asset("litert-community__Zipformer-medium-CR-CTC-LiteRT.hfmodels.json")))
val tts = ttsModels.fromPretrained(ModelRef("litert-community/kitten-tts-nano-0.8", revision = "d4662d891f9bf54b3d93432610d0d296d229e026", variant = "fp32"),
    Speak, LoadOptions(descriptorJson = asset("litert-community__kitten-tts-nano-0.8.hfmodels.json")))
val chat = llmModels.fromPretrained(ModelRef("litert-community/gemma-4-E2B-it-litert-lm"), Tasks.Chat)
val player = SpeechPlayer(tts.sampleRate)
val loop = VoiceLoop(asr, chat, tts, PhoneTools.all(app), VoiceLoopConfig(player = player))
loop.listen(MicSource().chunks()).collect { e -> show(e) }        // hands-free: per utterance Heard, Thinking, ToolCalled…, Speaking…, Done, then Listening
loop.turn("Set a timer for ten minutes.").collect { e -> show(e) } // typed: the transcriber is skipped
// teardown: loop.closeAndJoin(); player.close(); llmModels / ttsModels / asrModels.closeAndJoin() (each closes its model; the loop owns neither the models nor the player)
```

The speech models (`hfmodels-litert`; the interfaces in `io.github.johnrocky.hfmodels.speech`):

```kotlin
object Transcribe : Task<Transcriber>    // descriptor task "transcribe", handler litert.transcribe
object Speak : Task<Speaker>             // descriptor task "speak", handler litert.speak

interface Transcriber : PreparedModel {
    val limits: TranscriberLimits
    suspend fun transcribe(pcm: FloatArray): Transcript    // mono at limits.sampleRate, in [-1, 1]; one window per call, shorter audio is padded
}
data class TranscriberLimits(val sampleRate: Int, val windowSeconds: Double, val languages: List<String>)   // zipformer_ctc: 16000, 16.0, [en] (informational)
data class Transcript(val text: String, val timing: TranscriptTiming)                                     // zipformer_ctc writes capitals, no punctuation
data class TranscriptTiming(val featureMs: Double, val inferenceMs: Double, val totalMs: Double)          // host fbank; the graph run with its readback; the whole call

interface Speaker : PreparedModel {
    val voices: List<String>                 // the descriptor's order; voices[0] is the default
    val sampleRate: Int                      // of SpeechAudio.samples: 24000 for kitten
    val maxChars: Int                        // one call's limit in code points: 400 for kitten
    suspend fun synthesize(text: String, voice: String? = null, speed: Float = 1f): SpeechAudio   // one sentence or short chunk; speed 1 = the publisher's pace for the voice, 2 = twice that
    fun phonemeIds(text: String): IntArray   // the symbol ids the synthesizer gets, with the 0 at each end (tests, display); blocks while the G2P graph runs
}
data class SpeechAudio(val samples: FloatArray, val sampleRate: Int, val timing: SpeechTiming)          // mono, in [-1, 1]
data class SpeechTiming(val g2pMs: Double, val synthMs: Double, val totalMs: Double, val frames: Int)    // frames: 40 Hz acoustic frames, 600 samples each at 24 kHz, before the end trim
```

The loop (`hfmodels-voice`, package `io.github.johnrocky.hfmodels.voice`):

```kotlin
class VoiceLoop(val transcriber: Transcriber, val chat: ChatModel, val speaker: Speaker, val tools: List<VoiceTool> = emptyList(), val config: VoiceLoopConfig = VoiceLoopConfig()) : AutoCloseable {
    fun turn(utterance: FloatArray): Flow<Event>   // one finished utterance: mono at the transcriber's rate, in [-1, 1], at most its window
    fun turn(text: String): Flow<Event>            // typed: the transcriber is skipped (Heard carries the text, 0 ms)
    fun listen(audio: Flow<FloatArray>): Flow<Event>   // consecutive chunks (MicSource.chunks()) through config.endpointer; a turn per utterance
    suspend fun closeAndJoin()                     // stops every turn and listen in progress and the sound, and waits; the models stay open
    override fun close()                           // closeAndJoin without the wait

    sealed class Event {                           // a turn: Heard, Thinking, then ToolCalled / Speaking as they happen, Error when something failed, Done last
        object Listening                                                                        // listen takes audio: before the first utterance and after each turn
        data class Heard(val text: String, val audioMs: Double, val transcribeMs: Double)       // the text as the model gets it; blank text ends the turn (Heard, Done)
        object Thinking                                                                         // the model is answering, tools included
        data class ToolCalled(val name: String, val args: Map<String, Any?>, val result: String, val ms: Double)
        data class Speaking(val sentence: String, val synthMs: Double, val firstAudioMs: Double?)   // firstAudioMs on the turn's first sentence only
        data class Done(val timing: TurnTiming)                                                 // always last, unless the turn was cancelled
        data class Error(val code: ErrorCode?, val message: String)                            // code for a ModelException, null otherwise; the turn goes on to Done
    }
    data class TurnTiming(                         // ms from the end of the utterance (the turn's start for text); null = did not happen
        val transcribeMs: Double, val firstTokenMs: Double?, val firstSentenceMs: Double?, val firstAudioMs: Double?,
        val replyMs: Double, val speakMs: Double?, val totalMs: Double,
        val toolCalls: Int, val llmTurns: Int,
        val heard: String, val reply: String, val spoken: String,   // the model's input; its text (said or not, without call markup); what was said
    )
    companion object { fun defaultSystemInstruction(now: String): String }   // ToolRunner's, plus: one or two short spoken sentences, no markdown, no lists
}

data class VoiceLoopConfig(
    val toolFormat: ToolFormat = ToolFormat.Runtime,
    val voice: String? = null,                                    // null = speaker.voices[0]
    val speed: Float = 1f,
    val systemInstruction: ((now: String) -> String)? = null,     // null = VoiceLoop.defaultSystemInstruction; now = "Saturday, 2026-10-03 15:04"
    val maxToolTurns: Int = ToolRunner.MAX_TOOL_TURNS,            // 4
    val thinking: Boolean = false,                                // ask a model with a reasoning channel to reason
    val endpointer: Endpointer = Endpointer(),                    // its defaults: 300 ms pre-roll, 800 ms hangover, 16,000 ms at most
    val player: SpeechPlayer? = null,                             // null = synthesize only, no sound
    val emptyReplyText: String = "Sorry, I did not get that.",
    val failureText: String = "Sorry, I could not finish that.",
    val speakActionResults: Boolean = true,                       // see "What is said" below
    val normalizeTranscript: Boolean = true,                      // "SET AN ALARM" goes to the model as "Set an alarm."
)

class Endpointer(val sampleRate: Int = 16000, val startRms: Float = DEFAULT_START_RMS, val startMs: Int = 100, val hangoverMs: Int = 800, val maxUtteranceMs: Int = 16000, val frameMs: Int = 20, val preRollMs: Int = 300) {
    fun feed(chunk: FloatArray): List<Event>       // consecutive chunks of any size; state kept between calls; not thread-safe
    fun flush(): Event.Utterance?                  // the open utterance at stream end; resets
    fun reset()
    sealed class Event { object SpeechStart; data class Utterance(val pcm: FloatArray) }   // from the pre-roll to the end of the hangover, never longer than maxUtteranceMs
    companion object { const val DEFAULT_START_RMS = 0.02f }   // a voice spoken toward the phone; sound from a speaker needs less
}
object SentenceSplitter { fun split(text: String, maxChars: Int = 400): List<String> }   // KittenTTS 0.8.1's chunk_text, plus 。！？
class MicSource(val sampleRate: Int = 16000, val chunkMs: Int = 20, val source: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION) {
    fun chunks(): Flow<FloatArray>                 // collecting opens an AudioRecord on its own thread; cancelling stops and releases it; ends with IllegalStateException when the recorder is gone or 400 reads in a row return nothing (about 2 s)
}
class SpeechPlayer(val sampleRate: Int = 24000) : AutoCloseable {   // one AudioTrack, USAGE_ASSISTANT, written in 200 ms slices
    suspend fun play(samples: FloatArray)          // returns once written (about 1 s may still be queued)
    suspend fun drain()                            // waits until played (its length + 2 s at most), then pauses
    fun stop()                                     // drops what is queued; from any thread
    val firstWriteAtNanos: Long                    // System.nanoTime() when the current (or last) run's first write began; 0 before any
    override fun close()
}
```

Tools (`hfmodels-voice`):

```kotlin
interface VoiceTool {
    val name: String
    val description: String
    val parameters: List<ToolParam>
    val isAction: Boolean get() = false             // changes something on the phone (an alarm, a timer, an event) rather than reads it
    suspend fun call(args: Map<String, Any?>): String   // the text sent back; an exception goes back as "Error: <message>"
}
data class ToolParam(val name: String, val type: String, val description: String, val required: Boolean = true)   // type: one of ToolParam.TYPES (string, integer, number, boolean)
fun VoiceTool.descriptionJson(): JSONObject        // {"type": "function", "function": {"name", "description", "parameters": {...}}}
object ToolArgs { fun string(args: Map<String, Any?>, key: String): String; fun stringOrNull(args: Map<String, Any?>, key: String): String?; fun int(args: Map<String, Any?>, key: String): Int }   // 7, 7.0 and "7" are 7

object PhoneTools {                                 // the real thing on this phone
    const val CALENDAR_NAME = "Phone Agent"
    fun all(context: Context): List<VoiceTool>      // get_current_datetime, get_calendar_events, set_alarm, add_calendar_event, set_timer
    suspend fun phoneState(context: Context): String   // Android's next alarm and the app calendar's events for tomorrow, for a screen after a run
}
class ClockTool : VoiceTool                         // get_current_datetime
class AlarmTool(context: Context) : VoiceTool       // set_alarm(hour, minute, label): action; confirmed by AlarmManager.nextAlarmClock ("Alarm set for 07:30 (Wake Up)")
class TimerTool(context: Context) : VoiceTool       // set_timer(minutes, label): action; not confirmable, so "Timer requested: 10 min (Tea)"
class CalendarTool(context: Context) { val read: VoiceTool; val add: VoiceTool }   // get_calendar_events(date); add_calendar_event(title, start, end, location): action

sealed interface ToolFormat {                       // how a model's calls travel; the bundle decides
    object Runtime                                  // ConversationConfig.tools in, Message.toolCalls out (FunctionGemma, Gemma 4, Qwen3 JSON calls)
    object QwenXml                                  // Qwen3-Coder-style XML calls in the text; tools and enable_thinking go to the template through extraContext
    object LfmPythonic                              // LFM2 / LFM2.5 <|tool_call_start|>[set_alarm(hour=7, ...)]<|tool_call_end|> in the text
}
class ToolRunner(chat: ChatModel, tools: List<VoiceTool>, format: ToolFormat,
                 systemInstruction: (now: String) -> String = { ToolRunner.defaultSystemInstruction(it) }, maxToolTurns: Int = MAX_TOOL_TURNS, thinking: Boolean = false) {
    fun turn(text: String): Flow<ToolEvent>         // one request; its own conversation, closed at the end; cancelling the collector cancels the model
    companion object { const val MAX_TOOL_TURNS = 4; fun defaultSystemInstruction(now: String): String }   // MAX_TOOL_TURNS: model rounds that may call tools before the request fails
}
sealed interface ToolEvent {                        // in order; exactly one Done or Failed last
    data class Thinking(val delta: String)          // a piece of the reasoning channel
    data class Text(val delta: String)              // a piece of any model turn's text, without call markup
    data class ToolCalled(val name: String, val args: Map<String, Any?>, val result: String, val ms: Double, val turn: Int = 0)
    data class Done(val reply: String, val timing: TurnTiming)
    data class Failed(val reason: String, val timing: TurnTiming, val code: ErrorCode? = null)   // a malformed call, too many rounds, or the model's error
}
data class TurnTiming(val firstTokenMs: Double, val replyMs: Double, val chunks: Int, val chars: Int, val toolCalls: Int, val turns: Int, val decodeMs: Double)   // the runner's (not VoiceLoop.TurnTiming); -1 firstTokenMs = no chunk
object QwenXmlToolCalls { const val OPEN = "<tool_call>"; fun parse(text: String): List<Call>; fun withoutCalls(text: String): String; fun hasUnparsedMarkup(text: String): Boolean }   // Call(name, args: Map<String, String>, raw)
object LfmPythonicToolCalls { const val OPEN = "<|tool_call_start|>"; const val CLOSE = "<|tool_call_end|>"; fun parse(text: String): List<Call>; fun withoutCalls(text: String): String; fun hasUnparsedMarkup(text: String): Boolean }   // Call(name, args: Map<String, Any?>)
```

- **One at a time**: one `transcribe` per transcriber and one `synthesize` per speaker (`MODEL_BUSY` otherwise). The native calls of every LiteRT model in the process (transcriber, speaker, decision models) run on one shared thread, one after another. `VoiceLoop` runs its turns one at a time (a second `turn` waits for the first) and one `listen` per loop (a second fails with `IllegalStateException`). While a turn runs, `listen` drops the microphone's chunks (no barge-in), and the endpointer starts over after the turn. The constructor checks that the endpointer's rate is the transcriber's, that its `maxUtteranceMs` fits the transcriber's window, and that the player's rate is the speaker's (`IllegalArgumentException` otherwise).
- **Window and chunk**: a transcriber call takes at most `limits.windowSeconds` of audio (16 s for `zipformer_ctc`; consecutive windows are not handled) and at least one 400-sample frame; outside that it is `INVALID_INPUT`. The loop's `Endpointer` cuts an utterance at `maxUtteranceMs` (16,000), so `listen` stays inside the window; the loop refuses an endpointer that would cut a longer one. A speaker call takes at most `maxChars` code points (400 for kitten); empty text, or text with nothing to say (punctuation only), is `INVALID_INPUT`. `SentenceSplitter.split(text, speaker.maxChars)` cuts longer text as `chunk_text` of KittenTTS 0.8.1, the front-end this model shipped with, does it (at every run of `.!?`, so "7.30" and "Dr. Smith" are cut too); the loop says each chunk as soon as the model's text has passed its sentence mark, and skips a chunk the speaker finds nothing to say in.
- **What is said**: the model's text of every turn, without call markup and markdown, until a tool with `isAction = true` has run; from then on (`speakActionResults`, default true) each action's result instead of the model's words, so what is said is what the phone did ("Alarm set for 07:30 (Morning Alarm)"). The measured reason: for "Wake me up at six fifteen" Gemma 4 E2B called `set_alarm(hour = 16, minute = 15)` and said "6:15" (`litertlm/results/2026-10-03-1637-…`, c02). An `Error:` result is said as "Sorry, " and the reason. A model that says nothing after its calls gets its last round's action results said (a read's result is data, not said); when nothing at all was said, `emptyReplyText`; when the request failed (the round limit, a malformed call, the model's or the transcriber's error), `failureText` after what was said, with an `Event.Error`. `TurnTiming.reply` keeps the model's words and `spoken` what was said.
- **Conversations**: each turn opens its own conversation (through `ToolRunner`) with greedy sampling and closes it at the end; no history is kept between turns. On a model whose layers keep a running state (linear attention, SSM, short convolutions) a new conversation on the same loaded model is not a fresh state under LiteRT-LM 0.16.1 to 0.17.1 (google-ai-edge/LiteRT-LM#3165): compare its answers with a fresh load before trusting the turns after the first.
- **Cancel and release**: cancelling a turn's collector stops the model's generation (its conversation is closed), the synthesis after the sentence in progress, and the sound. The loop owns neither the three models nor the player: `loop.closeAndJoin()` stops the turns and the sound, then the app closes the player and `closeAndJoin()`s the models.
- **The Clock app has to be able to start**: `set_alarm` and `set_timer` send `AlarmClock` intents with `EXTRA_SKIP_UI`, which start the Clock app's activity. Android 10 and later drop an activity start from an app that has no visible activity, without an exception (a locked Galaxy S26: `BAL_BLOCK`, result code 102), so run the loop with the app on screen (`samples/voice`'s scripted mode, in a debug build, shows its screen over the keyguard for this; the sample stops the microphone and the request in progress while its screen is not visible). `set_alarm` then waits up to 1.5 s for `AlarmManager.nextAlarmClock` to report the requested time ("Alarm set for 07:30 (Wake Up)") and returns an `Error:` result when it does not. Android reports one next alarm, so when an alarm at the same minute or an earlier one is already next, the result says what Android reports instead, neither success nor an error: "Alarm requested for 07:30 (Wake Up). Android's next alarm is Sun 06:45, so this one could not be confirmed." ("was already Sun 07:30" for the same minute). `set_timer` cannot be confirmed (Android has no public API to read the Clock app's timers), so it says "Timer requested: 10 min (Tea)". The times in the results are written in ASCII digits (`Locale.US`) whatever the phone's locale; a date or time the calendar tools cannot read goes back to the model as "Error: bad date '…' (use YYYY-MM-DD)" or "… (use YYYY-MM-DD HH:MM)". The calendar tools use the app's own local calendar "Phone Agent", created on first use; account calendars are never read or written.
- **Permissions**: the modules' manifests declare none of these. `MicSource` needs `RECORD_AUDIO`, granted before collecting (without it the flow ends with `IllegalStateException`); `PhoneTools` needs `com.android.alarm.permission.SET_ALARM` and `READ_CALENDAR` / `WRITE_CALENDAR`.
- **Timing**: every `…Ms` is one call's wall clock on the device, not a benchmark. `VoiceLoop.TurnTiming` counts from the end of the utterance as the endpointer cut it, which includes its hangover (800 ms of silence by default): a speaker hears the first sound `hangoverMs + firstAudioMs` after they stop talking. With a player, `firstAudioMs` is the player's first write; without one, the end of the first sentence's synthesis. The measured numbers are in the README tables and `tested-runtime-matrix.json` (`transcribe`, `speak`, `voice_loop`).
- **Descriptor**: core 0.2.0 adds the tasks `transcribe` and `speak` and the file roles `voices` and `lexicon`. Both handlers are runtime `litert`, ABI 1, with profile components keyed `inference`.
  - `litert.transcribe`: profiles `gpu` (`fallback_profiles: ["cpu"]`) and `cpu`; an `npu` component is `UNSUPPORTED_CONFIGURATION`. Files: the graph (role `model`) and `tokens.txt` (role `tokenizer`). `handler_config`: `family` (`zipformer_ctc`, the only one), `files` (`main`, `tokens`: file ids), `sample_rate` (16000, the only rate the front-end is defined for), `window_seconds` (16; the graph's fbank input must be `window_seconds × 100` frames), `blank_id` (0), `cpu_threads` (4), `gpu_precision` (`default`, what the LiteRT model zoo app runs, or `fp32`), `languages`. A graph or `tokens.txt` that does not fit that contract fails the load with `INITIALIZATION_FAILED` (`stage=contract` or `tokens`) and is not retried on another profile.
  - `litert.speak`: `cpu` only (another component is `UNSUPPORTED_CONFIGURATION`). Files: the predictor, prosody, vocoder and G2P graphs (role `model`), `voices.npz` (`voices`), the G2P dictionary, its `g2p_meta.json` and `symbols.json` (`lexicon`). `handler_config`: `family` (`kitten`, the only one), `files` (`predictor`, `prosody`, `vocoder`, `voices`, `g2p_model`, `g2p_dict`, `g2p_meta`, `symbols`), `sample_rate` (24000), `voices` (names in `voices.npz`) and `default_voice` (must be the first of them), `style_rows` (400) and `style_dim` (256), `speed_priors` (voice: factor on `speed`), `xnnpack` (graph: on or off; default the predictor off, prosody and vocoder on), `cpu_threads` (4), `tail_trim` (5000) and `min_samples` (1200), `max_chars` (400), `languages`.
  - `catalog/dev/litert-community__Zipformer-medium-CR-CTC-LiteRT.hfmodels.json` (commit 7732ad6c; `small_fp16`, 46 MB, and `medium_fp16`, 131 MB, the default; `large_fp16` is published and not listed) and `catalog/dev/litert-community__kitten-tts-nano-0.8.hfmodels.json` (commit d4662d89; `fp32`, 94 MB with the G2P files, the default, and `fp16`, 63 MB). Their `revision` field is read by `tools/hfmodels_descriptor.py` only, so pin that commit in `ModelRef`, as the samples do: an unpinned first load compares the descriptor with the Hub's file list, and the speaker's fails there (`METADATA_MISMATCH`, the G2P files are not in the repo); a pinned one uses the side-loaded files and fetches only what is missing.
- **`zipformer_ctc`**: a Zipformer CTC encoder as one fixed-shape graph on `CompiledModel` (GPU at the default precision, or the CPU with 4 threads). The host computes icefall's front-end, the 80-mel log filterbank of `torchaudio.compliance.kaldi.fbank` (dither 0, snip_edges false, povey window, 20 Hz to 400 Hz below Nyquist; the PCM stays in [-1, 1], no CMN) over the whole 16 s window (1,600 frames; the padding is masked by four attention biases), then greedy CTC over the frames that carry audio. The text comes in capitals without punctuation ("SET AN ALARM FOR SEVEN THIRTY TO MORROW MORNING"). Ported from the LiteRT model zoo app's Zipformer code (google-ai-edge/litert-samples c18346a8); a JVM test checks the mel bank and the window against the zoo app's files.
- **`kitten`**: KittenTTS nano 0.8 (StyleTTS2 + ISTFTNet, 24 kHz), three graphs with a dynamic length on LiteRT's Interpreter API (`org.tensorflow.lite.Interpreter`), because the predictor and prosody graphs keep their fused LSTM state in variable tensors that `CompiledModel` does not load. Each graph is resized to the call's shapes and its variable tensors reset before every run, so a call never starts from the previous one's state. The published vocoder's input placeholders disagree with each other and XNNPACK refuses them, so the vocoder is opened from a copy-on-write mapping of its file with the input shapes rewritten to those of one frame (the verified file on disk stays as it is). The predictor runs without XNNPACK by default: on the Galaxy S26 (fp32) the peak RSS after the load was 334,360 kB instead of 624,404 kB and the median synthesis 301 ms instead of 288 ms, with the same frames. Text becomes symbols without espeak at run time, as the LiteRT `text_to_speech_streaming` sample does it: an espeak en-us IPA dictionary (274,927 words) first, the DeepPhonemizer graph (on `CompiledModel`, CPU) for the words it lacks; runs of capitals are spelled out ("GPU"), numbers read as words, the word "I" given its letter name. So the ids are not espeak's: none of the publisher's three bench sentences got espeak's ids (43, 68 and 133 symbols against 41, 63 and 138), while the synthesizer alone, on the publisher's own ids, gave the publisher's durations (111, 163 and 281 frames). `speed` times the voice's `speed_priors` factor goes to the graph, as the publisher's `say.py` does (0.8 for most voices); the end of each chunk is trimmed as the pip package does (5,000 samples, at least 1,200 kept).

## Errors

```kotlin
class ModelException(val code: ErrorCode, message: String, val retryable: Boolean = false, val details: Map<String, String> = emptyMap(), cause: Throwable? = null) : Exception
val ModelException.reason: String   // the message without the "CODE: " prefix; e.message carries the prefix
```

`ErrorCode` has 31 values; `errors.md` maps each to its cause and fix. Cancellation of your coroutine stays a `CancellationException`, never a `ModelException`.

## Logcat, tag `hfmodels`

One INFO line per stage, greppable from a script (`adb logcat -s hfmodels`):

```
resolve <owner/name>: commit=<8 hex> via <EXPLICIT_REVISION|SAVED_BINDING|RESOLVED_BRANCH|CATALOG_DEFAULT_BINDING>, descriptor=<repo>@<8 hex> ...
download <file> [resume from <bytes>] <- <url>
downloaded <file>: <bytes> bytes, sha256 ok [(resumed from <bytes>)]
side-loaded <file> from <path> (sha256 verified)         # the adb-push shortcut took effect
imported <file>: <bytes> bytes, sha256 ok, from <path>   # importFile()
ready <owner/name>@<8 hex> variant=<id> profile=<id> ... prepare_ms=<n>
tokens <file>: <n> pieces                                 # a transcriber's tokens.txt (0.2.0)
kitten lexicon: <n> symbols, <n> dictionary words, <n> voices   # a speaker's G2P (0.2.0)
```

Failures are WARN/ERROR lines under the same tag with the `ErrorCode`. The runtime's own lines are under its tags (`LiteRT-LM`, `litert`); a JNI abort is in the crash buffer under `DEBUG` (`adb logcat -b crash`).

## Version

`HfModelsVersion.SDK_VERSION` (also `info.sdkVersion`). Maven Central: <https://central.sonatype.com/artifact/io.github.john-rocky.hfmodels/hfmodels-litertlm>. Inside this repository the runtime pins are `gradle.properties`; the verified combinations are `tested-runtime-matrix.json`. Released: 0.1.2 (typed decisions, module `hfmodels-litert`), 0.1.1 (thinking channels, `Message.text`, `GenerationOptions` with `null` defaults, the FAIL-record rule in `Auto`, the drop-in device check `samples/chat/src/androidTest/.../ChatDeviceCheck.kt`) and 0.1.0. Main is 0.2.0-SNAPSHOT (Julia-1, the NPU, tools on `Tasks.Chat`, voice in the new module `hfmodels-voice`); it reaches Maven Central only with a release (`docs/releasing.md`).
