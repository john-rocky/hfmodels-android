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

## Step 0: resolve versions from the repository, never from memory

Read `gradle.properties` (`litertlmVersion`, `coroutinesVersion`) and `tested-runtime-matrix.json` (verified runtime + device rows). The dependency is the SDK's `hfmodels-litertlm` artifact; LiteRT-LM and kotlinx-coroutines 1.11.0 come with it as `api` dependencies — do not add or pin them yourself, and do not add `com.google.ai.edge.litert:litert` unless the app uses LiteRT's CompiledModel (that AAR needs `android.uniquePackageNames=false` on AGP 9).

Ids that work today without touching the model repo: `catalog/entries/*.json` (Qwen2.5-1.5B-Instruct q8, LFM2.5-1.2B-Instruct, LFM2.5-VL-1.6B, gemma-4-E2B-it, gemma-4-E4B-it — see the README table for which profiles were verified on which device).

## Traps: your training data is stale here

- `org.tensorflow:tensorflow-lite*` and Interpreter-style APIs are the TFLite era; LLM inference is `com.google.ai.edge.litertlm` and the SDK wraps it.
- Applying `org.jetbrains.kotlin.android` on AGP 9 or newer is a hard error; Kotlin is built in.
- `Engine`, `Conversation`, `sendMessageAsync` are behind the SDK. Do not call them directly: the runtime's Flow drops `trySend` results and has an empty `awaitClose`, so a cancelled collector alone does not stop decoding; the SDK's `ChatSession.stream` does.
- A cancelled session is not reusable (the runtime documents the state as poisoned). After Stop, `createConversation()` again; keep your own transcript if you want history (`initialMessages`).
- R8: the SDK's consumer rules keep what the runtime's JNI looks up by name. Do not add `-keep` rules for the runtime yourself, and do not strip the SDK's.
- Guessing a newer version when resolution fails: a missing artifact more likely means a wrong coordinate. Re-read `gradle.properties`.

## Step 1: decide

1. Confirm the target is an Android app (`com.android.application`), `minSdk >= 31`, arm64.
2. Pick the id. Default: `litert-community/Qwen2.5-1.5B-Instruct` (1.6 GB, text). A VLM (image + text): `litert-community/LFM2.5-VL-1.6B`.
3. Backend: leave `BackendPolicy.Auto` (the descriptor's default profile). `Require(GPU)` only when the user asked; a GPU first load compiles kernels for up to a minute on a Pixel 8a.

## Step 2: integrate

1. Add the dependency (through the project's version catalog if it has one). Repositories: `google()` and `mavenCentral()` (plus the local Maven repo when this session says so).
2. Load once, off the main thread, and show `LoadEvent`s:
   ```kotlin
   val models = HfModels(applicationContext)
   val chat = models.fromPretrained(ModelRef("litert-community/Qwen2.5-1.5B-Instruct"), Tasks.Chat) { e -> status.text = e.toString() }
   val session = chat.createConversation(ConversationConfig(systemInstruction = Contents.of("You are a helpful assistant.")))
   ```
3. Send: `session.stream(Contents.of(Content.Text(prompt))).collect { m -> append(m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }) }` — chunks are incremental: append, never replace.
4. Stop: cancel the collecting `Job`. Then `createConversation()` again before the next send (check `session.state == SessionState.READY`).
5. Release: `withContext(NonCancellable) { chat.closeAndJoin() }` when the screen goes away or the user asks.
6. Errors: catch `ModelException`, show `"${e.code}: ${e.reason}"`, and act per `docs/errors.md`.

`samples/chat/src/main/kotlin/.../MainActivity.kt` is the whole pattern in one file.

## Step 3: verify, in this order

1. `./gradlew assembleDebug` must pass.
2. On a connected device (`export ANDROID_SERIAL=<serial>`), install, load the id, send `What is 17 + 25? Answer briefly.` and read the answer in the UI or in `adb logcat -s hfmodels`. The first load downloads the file (1.6 GB for Qwen; minutes on Wi-Fi); the second load is offline.
3. No device: say so. Report "build verified; device check not run" and hand over the exact steps. Never present an unverified integration as verified.

## Troubleshooting

| Symptom | Cause, fix |
|---|---|
| `The 'org.jetbrains.kotlin.android' plugin is no longer required` | standalone Kotlin plugin on AGP 9: remove it |
| `Could not resolve io.github.johnrocky.hfmodels:...` | repository missing (`mavenCentral()` / the local repo this session names), or a guessed version: re-read `gradle.properties` |
| `MODEL_NOT_REGISTERED` | the id has no `hfmodels.json` and no catalog entry: pick a catalogued id or ask the publisher |
| `STORAGE_FULL` before any download | not enough free space for the file plus 256 MiB: free space, or `models.evict(plan)` |
| `SESSION_INVALIDATED` on the turn after Stop | expected: `createConversation()` again |
| model keeps decoding after Stop | the runtime flow was collected directly: use `ChatSession.stream` |
| every launch loads slowly | the compile cache is in `cacheDir/hfmodels`; do not clear it between launches |
| app killed while loading | not enough free RAM for the bundle: pick a smaller variant or model |
