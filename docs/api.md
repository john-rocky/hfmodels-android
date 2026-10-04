# Public API, complete

Everything an app calls, with the exact imports. This page is the whole surface: the sources jar and the runtime AAR add nothing an app needs, so there is no reason to unzip or `javap` them. The Maven group is `io.github.john-rocky.hfmodels` (hyphen); the Kotlin package is `io.github.johnrocky.hfmodels` (no hyphen). Members marked **0.1.1** arrived in 0.1.1; 0.1.0 does not have them.

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
// runtime types that cross the SDK boundary (LiteRT-LM 0.16.1, brought in as an `api` dependency)
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.Channel                   // 0.1.1: only if you set ConversationConfig.channels yourself
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

Rules the runtime imposes and the SDK enforces: one generation at a time per model (a second `stream` while one runs fails with `MODEL_BUSY`); a flow collected twice fails with `STREAM_ALREADY_COLLECTED`; after a cancel the session is `INVALID` and the next turn needs `createConversation()` again (`SESSION_INVALIDATED` otherwise); a collector that falls more than 1,024 chunks / 8 MiB behind ends with `SLOW_CONSUMER` after the native side is cancelled.

## Runtime types an app touches (LiteRT-LM 0.16.1)

```kotlin
Contents.of(text: String): Contents                 // the usual prompt
Contents.of(vararg parts: Content): Contents        // text + image: Contents.of(Content.Text("What is in this picture?"), Content.ImageBytes(jpegOrPngBytes))
Contents.of(parts: List<Content>): Contents
val Contents.contents: List<Content>
class Content.Text(val text: String) : Content
class Content.ImageBytes(val bytes: ByteArray) : Content   // only when InputKind.IMAGE is in enabledInputs (UNSUPPORTED_INPUT otherwise)

ConversationConfig(systemInstruction: Contents = ..., initialMessages: List<Message> = emptyList(), ..., channels: List<Channel>? = null, thinkingConfig: ThinkingConfig? = null)   // named arguments; the rest at their defaults
Message.user(text: String); Message.model(text: String); Message.system(text: String)                     // for initialMessages (your own transcript after a cancel)
val Message.role: Role            // SYSTEM, USER, MODEL, TOOL
val Message.contents: Contents
val Message.channels: Map<String, String>   // the piece of a declared channel's content this chunk carries, keyed by name ("thought" for every catalogued model); incremental like the text, so append; empty on chunks that carry none
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

A decision model answers typed questions about a state without generating text: one forward per question (one per request on a family that packs a request's questions), probabilities back. The request and answer forms are the `/v1/systemone` ones (`state`, `questions` with `type` / `instructions` / `criteria`; answers with `choice` / `score` / `noul`, `probabilities`, `confidence`), so a request written for a server is handed to the phone unchanged. The models are decision encoders run by LiteRT's `CompiledModel` (`com.google.ai.edge.litert`, classic `.tflite`): the `laya` family (`convaiinnovations/laya`, English and multilingual; `litert-community/laya-LiteRT`) and, from 0.1.3, the `julia` family (`SupersonicLabs/Julia-1`, mmBERT-small, multilingual; `litert-community/Julia-1-LiteRT`), the `gliner2_decide` family (`fastino/GLiNER2.5-Decide`, DeBERTa-v3-large, English; `litert-community/GLiNER2.5-Decide-LiteRT`), the `gliclass` family (`knowledgator/gliclass-edge-v3.0`, ModernBERT, English; `litert-community/GLiClass-Edge-v3.0-LiteRT`) and the `deberta_decision` family (the DeBERTa-v3-large decision checkpoint of Mithril, formerly Kotoba Cloud, English; `litert-community/Open-Decision-DeBERTa-v3-Large-LiteRT`). This lives in its own module because the litert AAR adds about 9 MB of native code and needs `android.uniquePackageNames=false` on AGP 9 (litert 2.2.0 and litert-api 2.2.0 share a namespace); a chat-only app does not pay for it.

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
sealed class Answer {                                                       // toMap(): the JSON object in the publisher's form (laya rounds to 4 decimals, the other families do not)
    data class Choice(choice: String, probabilities: Map<String, Double>, confidence: Double, extras: Map<String, Any?>)
    data class Score(score: Double, legend: Map<String, String>, probabilities: Map<String, Double>, confidence: Double, extras: …)
    data class Noul(noul: Double, confidence: Double, extras: …)             // extras: model-defined fields (laya: "action": {"act_probability"}; julia and deberta_decision: "max_probability" on choice / score; gliner2_decide and gliclass: none)
}
data class Decisions(answers: Map<String, Answer>, model: String, timing: DecisionTiming, stateTokens: Int, truncated: Boolean) { fun toJson(): String }
data class DecisionTiming(stateMs: Double, questionMs: List<Double>, totalMs: Double)   // one call's wall clock on the device, not a benchmark
```

- **State**: a `String` is used as it is; a `Map<String, Any?>` / `List<Any?>` is serialized the way the publisher's code does it (`Json.dumps`: Python `json.dumps(…, ensure_ascii=False)` form, key order kept, `/` unescaped) so the phone tokenizes the same bytes the publisher's tests did. `org.json` is not used on this path (it escapes `/` and loses key order on the JVM).
- **Fit**: what happens to a long state is the family's. `laya` and `julia` budget the question head (instructions + options) first; the state gets the rest of the window and is cut at the end when longer (`Decisions.truncated = true`, `stateTokens` = what was kept). A question whose options do not fit the head fails with `CONTEXT_LIMIT_EXCEEDED`. This is both publishers' non-strict mode; when nothing is cut the token ids equal their strict mode's exactly, and Julia-1's Python runtime, which rejects any cut by default, would have rejected exactly the calls the SDK reports as truncated. Julia-1 scores 2 to 20 options per question (`limits.maxOptions`); more, or an empty option text, is `INVALID_INPUT`. `gliner2_decide` and `gliclass` cut nothing: a request whose labels, instructions and state together exceed the window fails with `CONTEXT_LIMIT_EXCEEDED` (`details["tokens"]`), as their publishers' hosts refuse it, and a variant with a larger window takes it. `deberta_decision` cuts only the state, to its first 256 tokens as its author's Collator does (`truncated`); a request that still does not fit fails the same way. On these three `limits.maxOptions` is what one forward holds (32 labels, 25 labels, 128 options); more is `INVALID_INPUT`.
- **Families**: each is a port of its publisher's code, checked against the publisher's own ids and answers; an app writes the same `Question`s for all five. `laya` and `julia` share the sequence (`CLS <type> question: <instructions> SEP [MASK] option … SEP <state> SEP`, one marker per option, at most 48 tokens of option text); what differs is the option text and the decoding. `laya` renders `key: description`, `level i: text`, `false: … / true: …`, applies the checkpoint's temperature per question type and option count, rounds to 4 decimals, and reports the act head as `extras["action"]`. `julia` renders the criteria description itself (a choice key stands for itself only when it has no description; `false` / `true` literally when a noul has no criteria), applies no temperature (its runtime ships no calibration), reports the full softmax unrounded and `extras["max_probability"]` on choice and score, and has no act head.

  The other three score option strings: labels on `gliner2_decide` and `gliclass`, options on `deberta_decision`. A choice's description is the string the model reads (its key when it has none), and the key is the answer's id. A score's levels are read as written. A noul is the pair `no` / `yes`, or its criteria's `false` / `true` texts when given; `deberta_decision` always reads `no` / `yes`, its author's schema. The instructions are the prompt. `gliner2_decide` makes one question one gliner2 task (`answer`, the instructions as its prompt), takes at most 32 labels and decodes with a single-label float32 softmax at T=1; the window (128, 256 or 512 tokens) is the variant's. `gliclass` builds `<<LABEL>>label …<<SEP>>`, the prompt, then the text, with the instructions plus one space as the prompt so the text starts a word; the pipeline accepts that prompt string, but none of the card's captured requests has a prompt ending in a space, so the parity checks do not cover the space. It takes at most 25 labels and decodes with a single-label softmax at T=1. `deberta_decision` keeps its author's form: the questions of one `decide` go into one forward (`[CLS] [STATE] state [Q] instructions [OPT] option … [SEP]`), split in question order when they do not fit. Its softmax divides the logits by 1.05, the author's calibration; a choice takes 2 to 255 options and a score 2 to 10 levels, at most 128 options per forward; `extras["max_probability"]` on choice and score is the author's `confidence`. None of the three rounds or has an act head. `confidence` is the SDK's field on all five (1 minus the normalized entropy; `max(p, 1 - p)` for noul).
- **What `prefill` shares**: what the backend can. An encoder shares the serialized, tokenized state (about 2 ms on the measured phone; `gliclass` shares the serialized text only, because its prompt and text are tokenized as one string) and still runs its forwards, one per question (`deberta_decision`: one per request), so `prefill(state).decide(qs)` and `decide(state, qs)` cost the same; a language-model backend with a scoring API would share the prompt's KV cache. The answers are identical either way. `DecisionTiming.questionMs` gives each question the time of the forward that answered it, so the questions `deberta_decision` packs into one forward carry the same number and the list can add up to more than `totalMs`. The measured numbers are in the README tables and `litert/results/`.
- **Rules**: one `decide` at a time per model (`MODEL_BUSY` otherwise); `close()` / `closeAndJoin()` as for chat; `info.notes` carries `compile_ms` and the accelerator the graph was created with (`observed` stays UNKNOWN: litert 2.2.0 reports no per-op execution target).
- **NPU** (0.1.3): the Qualcomm HTP (Hexagon NPU), compiled on the phone by LiteRT (JIT) and cached in the app's cache dir. Qualcomm's license lets its runtime ship only inside an application, so the AAR does not carry it: the app packages it, extracted to its native library dir, and asks for the NPU by name.
  ```sh
  tools/fetch_npu_libs.sh app/src/main/jniLibs/arm64-v8a v81     # Hexagon version by SoC: SM8550 v73, SM8650 v75, SM8750 v79, SM8850 v81 (several at once is fine)
  ```
  ```kotlin
  // app/build.gradle.kts: android { packaging { jniLibs { useLegacyPackaging = true } } }
  val model = models.fromPretrained(ModelRef("litert-community/laya-LiteRT", variant = "ml_s256_wfp16"), EncoderDecisions,
      LoadOptions(backendPolicy = BackendPolicy.Require(BackendKind.NPU)))
  ```
  The variants with an `npu` profile are laya-LiteRT's `ml_s256_wfp16` (multilingual) and `en_s256_fp32` (English), and GLiNER2.5-Decide-LiteRT's `s128_npu_wfp16`, the s128 graph changed for float16 hardware (on the same NPU the default s128 graph matched 13 of 42 decisions). `Auto` keeps the descriptor's default profile and never picks an NPU profile without a PASS record, so an app without the runtime loads as before. `NATIVE_MODULE_MISSING` lists the files the app lacks; `UNSUPPORTED_CONFIGURATION` names a SoC that LiteRT 2.2.0 has no Qualcomm path for; a failed compile is `INITIALIZATION_FAILED`, and `Require` does not fall back. The first load on a phone compiles for tens of seconds (`info.notes`, `compile_ms`); later loads read the cache. The act head runs on the CPU. When the NPU compile of a graph fails, LiteRT 2.2.0 runs that graph on the CPU without an error, and its Kotlin API does not say which happened (`observed` stays UNKNOWN); the device gate counts an NPU run only with LiteRT's log line `Replacing 1 out of 1 node(s) with delegate (DispatchDelegate)`. The script takes the dispatch library and compiler plugin from the LiteRT v2.2.0 release and the rest from QAIRT 2.47.0.260601, the version that release pins.
- **Descriptor**: task `decide`, runtime `litert`, handler `litert.typed_decisions` ABI 1, profile components keyed `inference` (`gpu` with `fallback_profiles: ["cpu"]`, then `cpu`; `npu` for the Qualcomm HTP); `handler_config`: `family` (`laya` default, `julia`, `gliner2_decide`, `gliclass` or `deberta_decision`), `window`, `head_tokens` (laya and julia; the other three have no head budget), `hidden`, `files` (`main`, `tokenizer`, and per family `act_head` / `config` / `tokenizer_config` for laya, `table` for a host-lookup graph, which the three new families require), `special_tokens` (`{cls, sep, mask, pad, unk}` by text, instead of `tokenizer_config`; `gliclass` checks the ones it declares against its tokenizer), `label_slots` (labels per forward: `gliner2_decide` 32, `gliclass` 25), `option_slots`, `state_tokens` and `temperature` (`deberta_decision`: 128 options per forward, the state cut at 256 tokens, softmax at 1.05), `table_dtype` (`float16` default, or `float32`), `gpu_precision` (`fp32` default; `fp16_with_fp32_accum`, `fp16` or `default` select LiteRT's other GPU precisions, anything else is `MANIFEST_INVALID`), `pack_questions` (`gliner2_decide`, default false: the questions of one `decide` go into one gliner2 request as its tasks, each named by its question id, in question order; a new forward starts where the labels exceed the slots, the sequence the window, or gliner2 would decode a task with another task's settings; on 30 sentences with four questions each, packing changed 20 of the 120 answers, on a Mac and on the phone alike), `cpu_threads`, `languages`. With `files.table` the graph takes `inputs_embeds`: the SDK memory-maps the `[vocab, hidden]` table and looks the row of every token up on the host (the form the LiteRT model zoo publishes; a float16 table rounds a float32 checkpoint's embeddings, Julia-1's card measures the effect at most 0.0077 on a probability). `litert-community/laya-LiteRT` carries such a descriptor in its repo (six variants: multilingual / English, windows 256 / 512, fp32 / float16 weights); `litert-community/Julia-1-LiteRT` (variants `s512_fp32`, the default, and `s1024_fp32`; float16 table; GPU FP32 or CPU, no NPU: the publisher measured that fp16 arithmetic changes its answers) carries the same descriptor in its repo (`hfmodels.json`, from commit 8f36857c) and in the bundled catalog (`catalog/entries/litert-community__Julia-1-LiteRT.json`, pinned at b92a0d21). The three new families carry a descriptor in their repos (`hfmodels.json`, from 11613095, b6ec1f75 and 6ebf0946 respectively) and in the bundled catalog: `litert-community/GLiNER2.5-Decide-LiteRT` at 310090c3 (`s128_wfp16`, the default, `s256_wfp16`, `s512_wfp16`, and `s128_npu_wfp16`, which that commit added: profiles `npu`, `gpu` at `fp16_with_fp32_accum` (the default) and `cpu`; the repo's `hfmodels.json` from 11613095 names the first three), `litert-community/GLiClass-Edge-v3.0-LiteRT` at 88c90950 (`s128_fp32`, the default, `s256_fp32`) and `litert-community/Open-Decision-DeBERTa-v3-Large-LiteRT` at 7a276235 (`s256_wfp16`, the default, `s512_wfp16`), the last two pinned at the commit before their `hfmodels.json`; every variant except `s128_npu_wfp16` has a `gpu` profile (FP32, falling back to `cpu`) and a `cpu` one, no NPU; `catalog/proposals/` holds the `hfmodels.json` each repo would carry. `catalog/dev/` keeps the development descriptors the device gate uses to side-load published or local files.
- **Language-model bundles** (`.litertlm`) as a decision backend: not in this release. The Kotlin API of LiteRT-LM 0.16.1 / 0.17.1 exposes no scoring or checkpoint call (its C API and C++ do); the record and the reproduction are in the repository's development notes. `Tasks.Chat` stays the way to use those bundles.

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
```

Failures are WARN/ERROR lines under the same tag with the `ErrorCode`. The runtime's own lines are under its tags (`LiteRT-LM`, `litert`); a JNI abort is in the crash buffer under `DEBUG` (`adb logcat -b crash`).

## Version

`HfModelsVersion.SDK_VERSION` (also `info.sdkVersion`). Maven Central: <https://central.sonatype.com/artifact/io.github.john-rocky.hfmodels/hfmodels-litertlm>. Inside this repository the runtime pins are `gradle.properties`; the verified combinations are `tested-runtime-matrix.json`. Released: 0.1.1 (thinking channels, `Message.text`, `GenerationOptions` with `null` defaults, the FAIL-record rule in `Auto`, the drop-in device check `samples/chat/src/androidTest/.../ChatDeviceCheck.kt`); 0.1.0 is the previous release.
