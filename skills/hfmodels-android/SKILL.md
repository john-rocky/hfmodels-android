---
name: hfmodels-android
description: Add an on-device LLM chat (offline after one download, no API key) to an Android app that already exists, using the hfmodels SDK on LiteRT-LM - one Gradle dependency, a five-line call that takes a Hugging Face model id, streaming, Stop that stops the model, Release that frees it, and the device check that proves it. Use for asks like "add offline chat to my Android app", "run Qwen / Gemma / LFM on the phone", "local assistant with no cloud", "LiteRT-LM on Android", "use this Hugging Face model in my app". Converting or quantizing a model is out of scope.
---

# On-device chat in an existing Android app with hfmodels

An integration is done when three things hold, in this order:

1. the app builds with the dependency line, `HfModels(context).fromPretrained(ModelRef("<owner>/<repo>"), Tasks.Chat)` is the only model-loading code, and no model file is in git or in the APK;
2. **the app answers a fixed prompt on a connected device** (the SDK's `Ready` event, then a streamed reply);
3. the lifecycle is wired: load off the main thread (it is a `suspend` function), Stop cancels the collecting coroutine, `closeAndJoin()` on release / teardown, and a cancelled session is replaced with `createConversation()` before the next turn.

Scope: an app that already exists, a model that is registered (its repo carries `hfmodels.json`, or it is in the bundled catalog). Anything else fails with a typed `ModelException`; `docs/errors.md` says what to do.

## Step 0: versions and the API, from the files, never from memory

- Dependency: `io.github.john-rocky.hfmodels:hfmodels-litertlm:0.1.0` from Maven Central (newest: <https://central.sonatype.com/artifact/io.github.john-rocky.hfmodels/hfmodels-litertlm>; inside the SDK repository the pins are `gradle.properties` and the verified combinations `tested-runtime-matrix.json`). The Kotlin package is `io.github.johnrocky.hfmodels` (no hyphen; the Maven group has one).
- It brings LiteRT-LM 0.16.1 and kotlinx-coroutines 1.11.0 as `api` dependencies — do not add or pin them yourself, and do not add `com.google.ai.edge.litert:litert` unless the app uses LiteRT's CompiledModel (that AAR needs `android.uniquePackageNames=false` on AGP 9).
- `docs/api.md` is the complete public surface with imports and signatures, including the four runtime types an app touches (`Contents`, `Content`, `ConversationConfig`, `Message`). Read it instead of unzipping the sources jar or running `javap` on the runtime — there is nothing else to find.

Ids that work today without touching the model repo: `catalog/entries/*.json` (Qwen2.5-1.5B-Instruct q8, LFM2.5-1.2B-Instruct, LFM2.5-VL-1.6B, gemma-4-E2B-it, gemma-4-E4B-it — see the README table for which profiles were verified on which device). Thinking models (Qwen3, DeepSeek-R1-Distill, `*-Thinking`) are not in the catalog and 0.1.0 has no thinking-channel handling: if the user asks for one, say so and offer a catalogued instruct model instead of forcing it through `descriptorJson`.

## Traps: your training data is stale here

- `org.tensorflow:tensorflow-lite*` and Interpreter-style APIs are the TFLite era; LLM inference is `com.google.ai.edge.litertlm` and the SDK wraps it.
- Applying `org.jetbrains.kotlin.android` on AGP 9 or newer is a hard error; Kotlin is built in.
- `Engine`, `Conversation`, `sendMessageAsync` are behind the SDK. Do not call them directly: the runtime's Flow drops `trySend` results and has an empty `awaitClose`, so a cancelled collector alone does not stop decoding; the SDK's `ChatSession.stream` does.
- A cancelled session is not reusable (the runtime documents the state as poisoned). After Stop, `createConversation()` again; keep your own transcript if you want history (`initialMessages`).
- R8: the SDK's consumer rules keep what the runtime's JNI looks up by name. Do not add `-keep` rules for the runtime yourself, and do not strip the SDK's.
- Guessing a newer version when resolution fails: a missing artifact more likely means a wrong coordinate (`io.github.john-rocky.hfmodels`, hyphen) or a missing `mavenCentral()`. Re-read Step 0.

## Step 1: decide

1. Confirm the target is an Android app (`com.android.application`), `minSdk >= 31` (raise it if lower; the SDK declares 31), arm64.
2. Pick the id. Default: `litert-community/Qwen2.5-1.5B-Instruct` (1.6 GB, text). A VLM (image + text): `litert-community/LFM2.5-VL-1.6B`.
3. Backend: leave `BackendPolicy.Auto` (the descriptor's default profile). `Require(GPU)` only when the user asked; a GPU first load compiles kernels for up to a minute on a Pixel 8a.
4. Model delivery: the first `fromPretrained` downloads the file into the app's private storage (1.6 GB takes about 3 minutes on Wi-Fi). **Development shortcut**: if the exact file is already on this machine (`ls ~/.cache/huggingface/hub/models--<owner>--<name>/snapshots/*/`), push it right after the first install and the SDK imports it instead of downloading (it hashes the copy; a wrong file is ignored and downloaded):
   ```sh
   adb push <path>/<file>.litertlm /sdcard/Android/data/<applicationId>/files/     # after the app is installed
   ```
   Start the push in the background while you write code. Do not `adb shell mkdir` a subdirectory for it.

## Step 2: integrate

1. Add the dependency (through the project's version catalog if it has one). Repositories: `google()` and `mavenCentral()` (plus the local Maven repo when this session says so).
2. Load once, off the main thread, and show `LoadEvent`s:
   ```kotlin
   val models = HfModels(applicationContext)
   val chat = models.fromPretrained(ModelRef("litert-community/Qwen2.5-1.5B-Instruct"), Tasks.Chat) { e -> status.text = e.toString() }
   val session = chat.createConversation(ConversationConfig(systemInstruction = Contents.of("You are a helpful assistant.")))
   ```
3. Send: `session.stream(Contents.of(prompt)).collect { m -> append(m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }) }` — chunks are incremental: append, never replace.
4. Stop: cancel the collecting `Job`. Then `createConversation()` again before the next send (check `session.state == SessionState.READY`).
5. Release: `withContext(NonCancellable) { chat.closeAndJoin() }` when the screen goes away or the user asks.
6. Errors: catch `ModelException`, show `"${e.code}: ${e.reason}"`, and act per `docs/errors.md`.

A Compose app needs only this ViewModel (the `Log.i("e1", …)` of the reply is the test hook Step 3 greps; any tag works as long as you grep the same one):

```kotlin
import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import io.github.johnrocky.hfmodels.HfModels
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.ModelRef
import io.github.johnrocky.hfmodels.Tasks
import io.github.johnrocky.hfmodels.litertlm.ChatModel
import io.github.johnrocky.hfmodels.litertlm.ChatSession
import io.github.johnrocky.hfmodels.litertlm.SessionState
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val models = HfModels(app)
    private var chat: ChatModel? = null
    private var session: ChatSession? = null
    private var job: Job? = null
    var status by mutableStateOf("Not loaded"); private set
    var transcript by mutableStateOf(""); private set
    var generating by mutableStateOf(false); private set
    val ready get() = chat != null

    fun load() = viewModelScope.launch {
        try {
            chat = models.fromPretrained(ModelRef("litert-community/Qwen2.5-1.5B-Instruct"), Tasks.Chat) { e -> status = e.toString() }
            status = "Ready (" + chat!!.info.profileId + ")"
        } catch (e: ModelException) { status = "${e.code}: ${e.reason}" }
    }

    fun send(prompt: String) {
        val model = chat ?: return
        transcript += "You: $prompt\nModel: "; generating = true
        job = viewModelScope.launch {
            val reply = StringBuilder()
            try {
                val s = session?.takeIf { it.state == SessionState.READY }
                    ?: model.createConversation(ConversationConfig(systemInstruction = Contents.of("You are a helpful assistant."))).also { session = it }
                s.stream(Contents.of(prompt)).collect { m ->
                    val t = m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                    reply.append(t); transcript += t
                }
                Log.i("e1", "prompt=\"$prompt\" reply=\"${reply.toString().trim()}\"")
            } catch (e: ModelException) { transcript += "[${e.code}: ${e.reason}]" }
            finally { transcript += "\n\n"; generating = false }
        }
    }

    fun stop() { job?.cancel() }   // cancelling the collector stops the model on the native side

    override fun onCleared() {
        val c = chat; chat = null
        GlobalScope.launch { withContext(NonCancellable) { c?.closeAndJoin(); models.closeAndJoin() } }
    }
}
```

Screen: a Load button + status text, a scrolling transcript, an input row with Send / Stop. **In an edge-to-edge Compose app (targetSdk 35+) put the input row inside a column with `Modifier.imePadding()`; otherwise the keyboard covers the Send button** (and an `adb shell input tap` on it lands on the keyboard).

`samples/chat/src/main/kotlin/.../MainActivity.kt` is the same pattern with plain Views.

## Step 3: verify, in this order

1. `./gradlew assembleDebug` must pass.
2. On a connected device (`export ANDROID_SERIAL=<serial>`): install, push the model if you have it (Step 1.4), open the chat screen and press Load. Watch `adb logcat -s hfmodels` — the SDK prints one line per stage (`download …` / `side-loaded …` / `ready … profile=cpu prepare_ms=…`); poll that instead of dumping the UI — in a foreground loop (`until adb logcat -d -s hfmodels | grep -q ready; do sleep 5; done`), never by parking the wait in a background task and ending your turn. Then type the prompt and press Send. When driving the UI from adb: `input text 'What%sis%s17%s+%s25?%sAnswer%sbriefly.'`, then hide the keyboard (`adb shell input keyevent 4` while the keyboard is up) before tapping Send at the bounds from `uiautomator dump`, and read the reply from your own `e1` log line.
3. No device: say so. Report "build verified; device check not run" and hand over the exact steps. Never present an unverified integration as verified.

## Troubleshooting

| Symptom | Cause, fix |
|---|---|
| `The 'org.jetbrains.kotlin.android' plugin is no longer required` | standalone Kotlin plugin on AGP 9: remove it |
| `Could not resolve io.github.john-rocky.hfmodels:...` | repository missing (`mavenCentral()` / the local repo this session names), or a guessed version or group: re-read Step 0 |
| `MODEL_NOT_REGISTERED` | the id has no `hfmodels.json` and no catalog entry: pick a catalogued id or ask the publisher |
| `STORAGE_FULL` before any download | not enough free space for the file plus 256 MiB: free space, or `models.evict(plan)` |
| `SESSION_INVALIDATED` on the turn after Stop | expected: `createConversation()` again |
| model keeps decoding after Stop | the runtime flow was collected directly: use `ChatSession.stream` |
| every launch loads slowly | the compile cache is in `cacheDir/hfmodels`; do not clear it between launches |
| app killed while loading | not enough free RAM for the bundle: pick a smaller variant or model |
