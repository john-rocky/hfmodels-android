# Public API, complete

Everything an app calls, with the exact imports. This page is the whole surface: the sources jar and the runtime AAR add nothing an app needs, so there is no reason to unzip or `javap` them. The Maven group is `io.github.john-rocky.hfmodels` (hyphen); the Kotlin package is `io.github.johnrocky.hfmodels` (no hyphen). Members marked **0.1.1** are in `main` and in 0.1.1 or newer; 0.1.0 (Maven Central) does not have them.

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

One `HfModels` per process (it owns one model slot: loading a second model closes the first). Hold it in the `Application` or a ViewModel; construct it with the application context.

```kotlin
data class ModelRef(val repoId: String, val revision: String? = null, val variant: String? = null)   // "owner/name"; revision = branch, tag or commit; variant = an id from the descriptor
object Tasks { val Chat: Task<ChatModel> }                                                              // text generation and image-text-to-text
```

## Options and events

```kotlin
data class LoadOptions(
    val backendPolicy: BackendPolicy = BackendPolicy.Auto,      // Auto = the descriptor's default profile, then its fallbacks
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

`HfModelsVersion.SDK_VERSION` (also `info.sdkVersion`). Maven Central: <https://central.sonatype.com/artifact/io.github.john-rocky.hfmodels/hfmodels-litertlm>. Inside this repository the runtime pins are `gradle.properties`; the verified combinations are `tested-runtime-matrix.json`. Released: 0.1.0. In `main`: 0.1.1-SNAPSHOT (thinking channels, `Message.text`, `GenerationOptions` with `null` defaults, the drop-in device check `samples/chat/src/androidTest/.../ChatDeviceCheck.kt`).
