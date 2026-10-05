---
name: hfmodels-android
description: Add an on-device LLM chat (offline after one download, no API key) to an Android app that already exists, using the hfmodels SDK on LiteRT-LM - one Gradle dependency, a five-line call that takes a Hugging Face model id, streaming, Stop that stops the model, Release that frees it, and the device check that proves it. On main (0.2.0, not released) also a voice loop: the phone hears a request (speech to text), acts on it with its own tools (alarm, timer, calendar) and answers aloud (text to speech), offline. Use for asks like "add offline chat to my Android app", "run Qwen / Gemma / LFM on the phone", "local assistant with no cloud", "LiteRT-LM on Android", "use this Hugging Face model in my app", "offline voice assistant that sets alarms", "speech to text / text to speech on the phone". Converting or quantizing a model is out of scope.
---

# On-device chat in an existing Android app with hfmodels

An integration is done when three things hold, in this order:

1. the app builds with the dependency line, `HfModels(context).fromPretrained(ModelRef("<owner>/<repo>"), Tasks.Chat)` is the only model-loading code, and no model file is in git or in the APK;
2. **the app answers a fixed prompt on a connected device** (the SDK's `Ready` event, then a streamed reply);
3. the lifecycle is wired: load off the main thread (it is a `suspend` function), Stop cancels the collecting coroutine, `closeAndJoin()` on release / teardown, and a cancelled session is replaced with `createConversation()` before the next turn.

Scope: an app that already exists, a model that is registered (its repo carries `hfmodels.json`, or it is in the bundled catalog). Anything else fails with a typed `ModelException`; `docs/errors.md` says what to do. Typed decisions (choice / score / noul about a state, no text generated) are a separate task on a separate module, `hfmodels-litert` with `EncoderDecisions`; read the "Typed decisions" section of `docs/api.md` and `samples/decide/README.md` before offering them; the ids are `litert-community/laya-LiteRT` and (0.1.3) `litert-community/Julia-1-LiteRT`, `litert-community/GLiNER2.5-Decide-LiteRT`, `litert-community/GLiClass-Edge-v3.0-LiteRT` and `litert-community/Open-Decision-DeBERTa-v3-Large-LiteRT` (all need the `hfmodels-litert` module and `android.uniquePackageNames=false`; the README tables say which variant and backend were verified). A voice loop (hear, act with the phone's tools, speak) is the module `hfmodels-voice` on main (0.2.0): read the "Voice" section of `docs/api.md` first and follow the **Voice** items below; the finish conditions are the same, with `VoiceDeviceCheck` as the device check.

## Step 0: versions and the API, from the files, never from memory

- Dependency: `io.github.john-rocky.hfmodels:hfmodels-litertlm:0.1.2` from Maven Central (newest: <https://central.sonatype.com/artifact/io.github.john-rocky.hfmodels/hfmodels-litertlm>; inside the SDK repository the pins are `gradle.properties` and the verified combinations `tested-runtime-matrix.json`). The Kotlin package is `io.github.johnrocky.hfmodels` (no hyphen; the Maven group has one).
- It brings LiteRT-LM 0.16.1 and kotlinx-coroutines 1.11.0 as `api` dependencies — do not add or pin them yourself, and do not add `com.google.ai.edge.litert:litert` unless the app uses LiteRT's CompiledModel (that AAR needs `android.uniquePackageNames=false` on AGP 9).
- `docs/api.md` is the complete public surface with imports and signatures, including the four runtime types an app touches (`Contents`, `Content`, `ConversationConfig`, `Message`). Read it instead of unzipping the sources jar or running `javap` on the runtime — there is nothing else to find.
- **Voice**: `io.github.john-rocky.hfmodels:hfmodels-voice` (brings `hfmodels-litert` and `hfmodels-litertlm`; the app needs `android.uniquePackageNames=false`) is not on Maven Central until 0.2.0 is released. Use it only when this session names a local Maven repo or a source dependency that has it; otherwise say that the voice loop is unreleased and stop.

Ids that work today without touching the model repo: `catalog/entries/*.json` — the README table says which profiles were verified on which device; only those rows count as "works". Thinking models (Qwen3, DeepSeek-R1-Distill, `*-Thinking`): 0.1.0 has no thinking-channel handling, so on 0.1.0 say so and offer a catalogued instruct model instead of forcing one through `descriptorJson`; from 0.1.1 the catalog carries them and the reasoning arrives in `m.channels["thought"]` while `m.text` is the answer (`docs/api.md`, "Thinking models" — read it before touching one).

## Traps: your training data is stale here

- `org.tensorflow:tensorflow-lite*` and Interpreter-style APIs are the TFLite era; LLM inference is `com.google.ai.edge.litertlm` and the SDK wraps it.
- Applying `org.jetbrains.kotlin.android` on AGP 9 or newer is a hard error; Kotlin is built in.
- `Engine`, `Conversation`, `sendMessageAsync` are behind the SDK. Do not call them directly: the runtime's Flow drops `trySend` results and has an empty `awaitClose`, so a cancelled collector alone does not stop decoding; the SDK's `ChatSession.stream` does.
- A cancelled session is not reusable (the runtime documents the state as poisoned). After Stop, `createConversation()` again; keep your own transcript if you want history (`initialMessages`).
- R8: the SDK's consumer rules keep what the runtime's JNI looks up by name. Do not add `-keep` rules for the runtime yourself, and do not strip the SDK's.
- Guessing a newer version when resolution fails: a missing artifact more likely means a wrong coordinate (`io.github.john-rocky.hfmodels`, hyphen) or a missing `mavenCentral()`. Re-read Step 0.

## Step 1: decide

1. Confirm the target is an Android app (`com.android.application`), `minSdk >= 31` (raise it if lower; the SDK declares 31), arm64.
2. Pick the id from the current generation. Default: `litert-community/gemma-4-E2B-it-litert-lm` (2.6 GB, text + image, the most-downloaded LiteRT-LM bundle). Small and fast: `litert-community/LFM2.5-1.2B-Instruct` (0.7 GB, text). Both are 2026 models verified on all their profiles (README table). `Qwen2.5-1.5B-Instruct` is in the catalog because the measured comparison used it; do not pick it as a default for a new app.
3. Backend: leave `BackendPolicy.Auto` (the descriptor's default profile; it skips a profile whose only device record is a FAIL). `Require(GPU)` only when the user asked; a GPU first load compiles kernels for up to a minute on a Pixel 8a, and on the 8 GB Pixel 8a the GPU profile of the Qwen3.5 / Falcon-H1 int8 bundles killed the process (the README table says which profiles answered).
4. Model delivery: the first `fromPretrained` downloads the file into the app's private storage (1.6 GB took about 3 minutes on Wi-Fi in our runs; scale by size). **Development shortcut**: if the exact file is already on this machine (`ls ~/.cache/huggingface/hub/models--<owner>--<name>/snapshots/*/`), push it right after the first install and the SDK imports it instead of downloading (it hashes the copy; a wrong file is ignored and downloaded):
   ```sh
   adb push <path>/<file>.litertlm /sdcard/Android/data/<applicationId>/files/     # after the app is installed
   ```
   Start the push in the background while you write code. Do not `adb shell mkdir` a subdirectory for it.
5. **Voice**: three models, each loaded by its own `HfModels` (one client holds one native model): `litert-community/Zipformer-medium-CR-CTC-LiteRT` variant `medium_fp16` with `Transcribe` (GPU), `litert-community/kitten-tts-nano-0.8` variant `fp32` with `Speak` (CPU), and the chat model whose tool calls were measured, `litert-community/gemma-4-E2B-it-litert-lm` (GPU). Both speech repos carry `hfmodels.json` and the bundled catalog pins those commits (Zipformer fa063a88, Kitten 2c5b198f, its four G2P files in the repo), so `ModelRef(id)` loads them; the first load needs the network to resolve the id, and it downloads the files unless they were side-loaded (`samples/voice/README.md`, "Model files"). Tell the user that no load of those commits has run on a device yet: the measured runs used development descriptors at the commits before, with the files imported from local copies. Manifest: `RECORD_AUDIO`, `com.android.alarm.permission.SET_ALARM`, `READ_CALENDAR`, `WRITE_CALENDAR` (the modules declare none; grant the three run-time ones before the first turn).

## Step 2: integrate

1. Add the dependency (through the project's version catalog if it has one). Repositories: `google()` and `mavenCentral()` (plus the local Maven repo when this session says so).
2. Load once, off the main thread, and show `LoadEvent`s:
   ```kotlin
   val models = HfModels(applicationContext)
   val chat = models.fromPretrained(ModelRef("litert-community/gemma-4-E2B-it-litert-lm"), Tasks.Chat) { e -> status.text = e.toString() }
   val session = chat.createConversation(ConversationConfig(systemInstruction = Contents.of("You are a helpful assistant.")))
   ```
3. Send: `session.stream(Contents.of(prompt)).collect { m -> append(m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }) }` — chunks are incremental: append, never replace. (0.1.1: `append(m.text)` with `import io.github.johnrocky.hfmodels.litertlm.text`; a thinking model's reasoning streams piece by piece in `m.channels["thought"]`, never in the text — append it too if you show it.)
4. Stop: cancel the collecting `Job`. Then `createConversation()` again before the next send (check `session.state == SessionState.READY`).
5. Release: `withContext(NonCancellable) { chat.closeAndJoin() }` when the screen goes away or the user asks.
6. Errors: catch `ModelException`, show `"${e.code}: ${e.reason}"`, and act per `docs/errors.md`.
7. **Voice**: after the three loads, `VoiceLoop(asr, chat, tts, PhoneTools.all(app), VoiceLoopConfig(player = SpeechPlayer(tts.sampleRate)))`; hands-free is `loop.listen(MicSource().chunks())`, typed is `loop.turn(text)`; collect off the main thread and render each `VoiceLoop.Event` (`Heard`, `ToolCalled`, `Speaking`, `Done`, `Error`). Stop = cancel the collecting `Job` (it stops the model, the synthesis and the sound). Release: `loop.closeAndJoin()`, `player.close()`, then each client's `closeAndJoin()`; the loop owns neither. Keep the app's screen visible while a turn runs: `set_alarm` / `set_timer` start the Clock app's activity, and Android drops that start from an app it cannot see (the alarm tool then returns an `Error:` result). `samples/voice/src/main/kotlin/.../VoiceViewModel.kt` is the pattern.

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
            chat = models.fromPretrained(ModelRef("litert-community/gemma-4-E2B-it-litert-lm"), Tasks.Chat) { e -> status = e.toString() }
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
2. **The device check, as a test, not by driving the UI** (SDK 0.1.1 or newer; on 0.1.0 skip to 3). Copy the drop-in file into the app and run it against the app's model id:
   ```sh
   mkdir -p app/src/androidTest/kotlin && curl -fsSL https://raw.githubusercontent.com/john-rocky/hfmodels-android/main/samples/chat/src/androidTest/kotlin/io/github/johnrocky/hfmodels/check/ChatDeviceCheck.kt -o app/src/androidTest/kotlin/ChatDeviceCheck.kt
   # app/build.gradle.kts: defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
   #                       dependencies { androidTestImplementation("androidx.test:runner:1.7.0"); androidTestImplementation("androidx.test.ext:junit:1.3.0") }
   export ANDROID_SERIAL=<serial>
   ./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
     -Pandroid.testInstrumentationRunnerArguments.class=io.github.johnrocky.hfmodels.check.ChatDeviceCheck \
     -Pandroid.testInstrumentationRunnerArguments.model=<the id the app loads>
   adb logcat -d -s hfmodels-check | grep RESULT
   ```
   The gradle task fails unless every step passed; the last line is `RESULT ok=true model=… device=…`. It runs in the app's own process and leaves the model file where the app's `fromPretrained` finds it, so a first download happens once. Keep the file in the app (it is the regression test the user will want).
3. On a connected device (`export ANDROID_SERIAL=<serial>`), the app's own screen once: install, push the model if you have it (Step 1.4), open the chat screen and press Load. Watch `adb logcat -s hfmodels` — the SDK prints one line per stage (`download …` / `side-loaded …` / `ready … profile=cpu prepare_ms=…`); poll that instead of dumping the UI — in a foreground loop (`until adb logcat -d -s hfmodels | grep -q ready; do sleep 5; done`), never by parking the wait in a background task and ending your turn. Then type the prompt and press Send. When driving the UI from adb: `input text 'What%sis%s17%s+%s25?%sAnswer%sbriefly.'`, then hide the keyboard (`adb shell input keyevent 4` while the keyboard is up) before tapping Send at the bounds from `uiautomator dump`, and read the reply from your own `e1` log line. When step 2 passed, this is a smoke of the screen wiring only; do not repeat the model checks by hand.
4. No device: say so. Report "build verified; device check not run" and hand over the exact steps. Never present an unverified integration as verified.
5. **Voice**: the device check is `samples/voice/src/androidTest/kotlin/io/github/johnrocky/hfmodels/check/VoiceDeviceCheck.kt`, run like step 2 with `-Pandroid.testInstrumentationRunnerArguments.class=io.github.johnrocky.hfmodels.check.VoiceDeviceCheck`; its KDoc lists the install steps (the descriptors as assets, the calendar grants, a 16 kHz mono WAV of "Set an alarm for seven thirty tomorrow morning." at `/data/local/tmp/hfmodels-voice/commands/c01.wav`). Run it with the screen on and unlocked, and with no alarm at or before 07:30 in the Clock app: Android reports one next alarm, so the check stops first with `RESULT step=precondition ok=false` and names the alarm to turn off or delete. It sets a real 07:30 alarm and asks the Clock app to dismiss it; when the `RESULT info cleanup` line says `left=true`, tell the user which label to turn off or delete. Reading the numbers: `ms_first_audio` counts from the end of the utterance as the endpointer cut it, which includes its 800 ms of silence; the README "Voice" tables are one Galaxy S26 with synthetic voices, not this phone.

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
| Voice: `MODEL_NOT_REGISTERED` for the Zipformer or Kitten id | the load pins a commit older than the repo's `hfmodels.json` (Zipformer 7732ad6c or older, Kitten d4662d89 or older) and passes no descriptor: load by id, or pin the bundled catalog's commit (fa063a88, 2c5b198f) |
| Voice: `MANIFEST_INVALID` "unknown task 'transcribe'" (or `'speak'`) for those ids | an SDK from Maven Central (0.1.2 or older), which has no voice tasks, read the repo's `hfmodels.json`: voice is main only (0.2.0) |
| Voice: "Error: the Clock app did not take the alarm" | the app was not visible (keyguard, background): keep its screen on during the turn |
| Voice: "Alarm requested for … could not be confirmed" | another alarm at that minute or earlier is Android's next alarm, which hides the new one: turn it off or delete it, or check the Clock app's list |
| Voice: `listen` ends with `IllegalStateException` "AudioRecord.read returned …" | the microphone stopped delivering (another app took the input, or 400 reads in a row returned nothing): start `listen` again |
| Voice: `listen` never hears an utterance | the input stays below the endpointer's start level (RMS 0.02): speak toward the phone, or `VoiceLoopConfig(endpointer = Endpointer(startRms = 0.01f))` |
| a thinking model answers with an empty string, or `<think>` shows in the text | 0.1.0 has no channel handling (upgrade to 0.1.1); on 0.1.1 an empty answer is the output cap hit inside the reasoning: raise `GenerationOptions(maxOutputTokens = …)`, never lower it |
