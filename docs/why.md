# What the five lines replace

Under the hood it is Google's LiteRT-LM runtime (`com.google.ai.edge.litertlm`). The SDK adds the part the runtime leaves to every app: resolving the id to an immutable commit, downloading with resume and a sha256 check, choosing a backend profile the device can run, initializing, a streaming Flow whose cancel really stops the model, and a release that waits for the native side. The `Contents` / `Content` / `Message` / `ConversationConfig` types are the runtime's own, so nothing has to be unlearned.

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

## What it changed for a coding agent, measured

One phone, one day, one task: on a Pixel 8a (Android 16), with the same host, the same model (`litert-community/Qwen2.5-1.5B-Instruct`, q8) and Claude Fable 5.1 driving Claude Code for three runs per arm, adding an offline chat screen to an existing Compose app took a mean of 339 s from start to the first correct reply on the phone with this SDK and its skill, against 877 s for the same agent given the official LiteRT-LM Android documentation and the on-device-recipes skill in the same hour (61 % less), and against 521 s for that hand-roll arm's best set earlier the same day (35 % less). Writing the code took the same time in both arms; the difference came from what the SDK and the skill carry as procedure and guarantee: how the weight reaches the phone, a `ready` line to wait on, the Compose input row and the Send sequence. Two Codex runs per arm pointed the same way (37 % less). Defects in the final apps on the eight-item checklist below: 0 with the SDK, 2 with the recipe, median 6 across the 328 public repositories. The pre-registered protocol, the transcripts and the numbers per run are in the evaluation record (kept with the author; to be published with the record). These are numbers about one phone and one host, not about anyone else's.
