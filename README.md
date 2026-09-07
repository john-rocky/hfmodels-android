# hfmodels for Android

An independent, third-party library: give it a Hugging Face model id; get a model you can chat with, on the phone, offline after one download. Not affiliated with Google, Hugging Face or the model publishers.

```kotlin
val models = HfModels(applicationContext)
val chat = models.fromPretrained(ModelRef("litert-community/Qwen2.5-1.5B-Instruct"), Tasks.Chat) { event -> show(event) }
val session = chat.createConversation(ConversationConfig(systemInstruction = Contents.of("You are a helpful assistant.")))
session.stream(Contents.of(Content.Text("What is 17 + 25? Answer briefly."))).collect { message -> append(message) }
withContext(NonCancellable) { chat.closeAndJoin() }
```

Under the hood it is Google's LiteRT-LM runtime (`com.google.ai.edge.litertlm`). The SDK adds the part the runtime leaves to every app: resolving the id to an immutable commit, downloading with resume and a sha256 check, choosing a backend profile the device can run, initializing, a streaming Flow whose cancel really stops the model, and a release that waits for the native side. The `Contents` / `Content` / `Message` / `ConversationConfig` types are the runtime's own, so nothing has to be unlearned.

**Status: 0.1.0, early.** One device verified (the table below and `tested-runtime-matrix.json`); the API may still move before 1.0.

## Add it

```kotlin
// settings.gradle.kts: repositories google() and mavenCentral()
// app/build.gradle.kts
android { defaultConfig { minSdk = 31 } }
dependencies { implementation("io.github.john-rocky.hfmodels:hfmodels-litertlm:0.1.0") }
```

That one line brings `hfmodels-core`, `litertlm-android` and `kotlinx-coroutines-android 1.11.0` (the version the runtime's bytecode needs; its POM understates it). Toolchain this was built with: AGP 9.3.1, Gradle 9.7.0, compileSdk 36, JDK 17, Kotlin built into AGP (do not apply the standalone Kotlin plugin). The SDK's manifest declares the GPU's `uses-native-library` entries and `INTERNET` (the first download only); its consumer R8 rules keep what the runtime's JNI looks up by name — with `minifyEnabled true` and nothing else, generation works.

## Ids that load today

A model loads when its repo carries `hfmodels.json` (the publisher's declaration) or when the SDK's bundled catalog has an entry for it. The catalog pins each model to one commit and never re-hosts a file.

| id | variant (default) | profiles | verified on (2026-09-07, Pixel 8a, Android 16 CP1A.260505.005, LiteRT-LM 0.16.1) |
|---|---|---|---|
| `litert-community/Qwen2.5-1.5B-Instruct` | `q8` (1.6 GB, text, 4096 tokens) | `cpu` (default), `gpu` | both answered; first GPU load compiles kernels for about a minute |
| `litert-community/LFM2.5-1.2B-Instruct` | `int4_gpu` (0.7 GB, text); also `int4`, `int8` | `gpu` (default), `cpu` | both answered (`int4_gpu`) |
| `litert-community/LFM2.5-VL-1.6B` | `int4` (1.3 GB, text + image); also `int8` | `gpu` (language on GPU, vision on CPU; default), `cpu` | both answered text and an image question (`int4`) |
| `litert-community/gemma-4-E2B-it-litert-lm` | `default` (2.6 GB, text + image) | `gpu` (default), `gpu_vision_cpu`, `cpu` | all three answered text and an image question |
| `litert-community/gemma-4-E4B-it-litert-lm` | `default` (3.7 GB, text + image) | `gpu` (default), `gpu_vision_cpu`, `cpu` | all three answered; on this 8 GB phone the first chunk took 11-15 s on `gpu` and `cpu` (2.7 s on `gpu_vision_cpu`) |

Each cell is one run of the catalog gate (`tools/gate.sh`): a real download from the Hub with Range resume and a sha256 check, `prepare` on the named profile, one text turn (and one image turn for VLM profiles). The records are in `verification/` and inside the bundled catalog; the log is `litertlm/results/2026-09-07-4C131JEKB15210-0.16.1-a3-gate.log`. One phone, one day: not a promise for other devices.

Development shortcut: a copy of the file pushed into the app's external files directory (`adb push <file> /sdcard/Android/data/<applicationId>/files/`) is hashed and imported instead of downloaded; a file that does not match the descriptor's sha256 is ignored. `adb logcat -s hfmodels` prints one line per stage (`download`, `side-loaded`, `ready … profile=… prepare_ms=…`).

`ModelRef(id, revision = "<commit>", variant = "<id>")` pins more. Without a revision the first successful load binds the id to the commit it resolved, and later loads (also offline) use that binding until you call `unbind`.

## What it changed for a coding agent, measured

One phone, one day, one task: on a Pixel 8a (Android 16), with the same host, the same model (`litert-community/Qwen2.5-1.5B-Instruct`, q8) and Claude Fable 5.1 driving Claude Code for three runs per arm, adding an offline chat screen to an existing Compose app took a mean of 339 s from start to the first correct reply on the phone with this SDK and its skill, against 877 s for the same agent given the official LiteRT-LM Android documentation and the on-device-recipes skill in the same hour (61 % less), and against 521 s for that hand-roll arm's best set earlier the same day (35 % less). Writing the code took the same time in both arms; the difference came from what the SDK and the skill carry as procedure and guarantee: how the weight reaches the phone, a `ready` line to wait on, the Compose input row and the Send sequence. Two Codex runs per arm pointed the same way (37 % less). Defects in the final apps on the eight-item checklist below: 0 with the SDK, 2 with the recipe, median 6 across the 328 public repositories. The pre-registered protocol, the transcripts and the numbers per run are in the evaluation record (kept with the author; to be published with the record). These are numbers about one phone and one host, not about anyone else's.

## What the five lines replace

The same feature written directly on the runtime — the shape found in most public apps that download a `.litertlm` from Hugging Face — and what each part still lacks. Of 328 public Kotlin repositories on GitHub that link the runtime and fetch a `.litertlm` from Hugging Face (code search, shallow clones, static scan, 2026-09-07; a repository counts as having an item when the pattern appears anywhere in its files), 14 % send a Range header, 22 % check a sha256, 28 % rename an atomically written temp file into place, 28 % call `cancelProcess()`, 27 % carry an R8 keep for the runtime, 4 % pin kotlinx-coroutines >= 1.11.0, 42 % dispatch the download or the engine load off the main thread, and 64 % declare `libOpenCL.so`; the median repository lacks 6 of the 8.

```kotlin
// 1. download: URL guessed from the repo name + "resolve/main"; no Range resume, no sha256, a truncated file
//    looks like a model until Engine.initialize() crashes; the token, if any, is forwarded to the CDN redirect
val conn = URL("https://huggingface.co/$repo/resolve/main/$file").openConnection() as HttpURLConnection
conn.inputStream.use { i -> File(filesDir, file).outputStream().use { o -> i.copyTo(o) } }
// 2. backend: hard-coded; the failure mode of GPU on a phone that lacks OpenCL is a crash at the FIRST MESSAGE, not at init
val engine = Engine(EngineConfig(modelPath = path, backend = Backend.GPU(), cacheDir = cacheDir.path)).also { it.initialize() }
val conv = engine.createConversation(ConversationConfig())
// 3. streaming: the runtime's Flow drops trySend results and has an empty awaitClose, so this Job's cancel does not stop decoding
job = scope.launch { conv.sendMessageAsync(prompt).collect { append(it.toString()) } }
// 4. stop: needs cancelProcess() from the app, and the conversation is unusable afterwards (documented as poisoned)
stopButton.setOnClickListener { job.cancel(); conv.cancelProcess() }
// 5. release: close() blocks until the reply in flight ends unless cancelled first; a second close() throws
override fun onDestroy() { conv.close(); engine.close() }
// 6. R8: no proguard.txt in the runtime AAR; release builds abort in JNI (SIGABRT, not an exception) unless the app adds keeps
// 7. the coroutines pin: without kotlinx-coroutines-android >= 1.11.0 the first reply ends in NoSuchMethodError
```

With the SDK: the id resolves to a commit and a descriptor; the file is fetched with Range resume, verified against the descriptor's sha256 and only then moved into place; the profile is chosen from what the device can run and reported; cancel of the collecting coroutine reaches `cancelProcess()`; a cancelled session is marked INVALID instead of silently returning empty replies; `closeAndJoin()` waits for the native side (10 s cap, then `NATIVE_STOP_TIMEOUT` rather than a use-after-free); the keeps and the coroutines pin ship in the AAR. Every failure is a `ModelException(code)` — `docs/errors.md`.

## Load in steps

```kotlin
val plan = models.inspect(ref, Tasks.Chat, LoadOptions(networkPolicy = NetworkPolicy.Unmetered))  // metadata only, no weights
plan.bytesToDownload; plan.profile.id; plan.excludedProfiles                                       // show, ask
val local = models.download(plan) { e -> progress(e) }                                             // resumable, verified
val chat = models.prepare(local)                                                                    // no network
```

`LoadOptions`: `backendPolicy` (`Auto` / `Require(CPU|GPU)` / `RequireProfile(id)`), `requiredInputs` (`setOf(TEXT, IMAGE)` for a VLM), `networkPolicy` (`Any` / `Unmetered` / `Offline`), `maxDownloadBytes`, `contextTokens`, `credentials` (a token for gated repos; never stored or logged), `descriptorJson` (bring your own descriptor).

## Sessions

- `createConversation(config)` accepts `systemInstruction`, `initialMessages`, `samplerConfig`, `maxOutputToken`. Tools, response format, LoRA, channels and thinking are refused with `UNSUPPORTED_CONFIGURATION` in this release (they are not silently passed through).
- `stream(contents)` starts on collect and is collected once. Chunks arrive in order; a collector more than 1,024 chunks / 8 MiB behind ends with `SLOW_CONSUMER` after the native side is stopped — nothing is dropped silently.
- Cancel the collecting coroutine or call `cancel()`: the native generation stops; the session becomes INVALID; open a new conversation for the next turn (pass your transcript as `initialMessages` to keep history).
- One generation at a time per model (`MODEL_BUSY`), one native model per `HfModels` (`MODEL_BUSY` on a second `prepare`).
- `close()` returns at once; `closeAndJoin()` waits, children first, then the Engine. Idempotent.
- `info: PreparedModelInfo` reports commit, descriptor origin, variant, profile, requested / initialized backend per component, and `observed = UNKNOWN` on this runtime version (it exposes no execution report; "initialized on GPU" is not a claim that every op ran there).

## Publishing a model

A repo becomes loadable by id with one file, `hfmodels.json`, generated (not typed) from the files' Hub metadata: `docs/publishing.md`.

## Layout

```
core/        hfmodels-core: ModelRef, HfModels (inspect / download / prepare), descriptor + catalog readers, Hub client, content-addressed store, errors
litertlm/    hfmodels-litertlm: Tasks.Chat, ChatModel / ChatSession on LiteRT-LM, consumer R8 rules, GPU manifest entries
samples/chat the chat screen on the SDK (id in, chat out)
catalog/     specs (curated) -> entries (generated) -> the bundled asset; tools/ generate and gate them
probes/      the runtime coexistence probe (LiteRT-LM + LiteRT in one release APK) and its logs
docs/        errors.md, publishing.md; skills/ the agent procedure; tested-runtime-matrix.json the verified matrix
```

## License

Apache-2.0 for the SDK. Each model keeps its own license (`hfmodels.json` names it); the SDK shows it and does not judge it.
