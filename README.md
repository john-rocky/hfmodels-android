# hfmodels for Android

An independent, third-party library: give it a Hugging Face model id; get a model you can chat with, on the phone, offline after one download. Not affiliated with Google, Hugging Face or the model publishers.

```kotlin
val models = HfModels(applicationContext)
val chat = models.fromPretrained(ModelRef("litert-community/gemma-4-E2B-it-litert-lm"), Tasks.Chat) { event -> show(event) }
val session = chat.createConversation(ConversationConfig(systemInstruction = Contents.of("You are a helpful assistant.")))
session.stream(Contents.of("What is 17 + 25? Answer briefly.")).collect { message -> append(message) }
withContext(NonCancellable) { chat.closeAndJoin() }
```

Under the hood it is Google's LiteRT-LM runtime (`com.google.ai.edge.litertlm`). The SDK adds the part the runtime leaves to every app: resolving the id to an immutable commit, downloading with resume and a sha256 check, choosing a backend profile the device can run, initializing, a streaming Flow whose cancel really stops the model, and a release that waits for the native side. The `Contents` / `Content` / `Message` / `ConversationConfig` types are the runtime's own, so nothing has to be unlearned.

**Status: 0.1.2, early.** One device verified (the table below and `tested-runtime-matrix.json`); the API may still move before 1.0. 0.1.2 adds typed decisions (module `hfmodels-litert`: `EncoderDecisions` on the `laya` decision encoders, the section below, and `samples/decide`); main (0.1.3-SNAPSHOT) adds a second decision family, Julia-1 (`litert-community/Julia-1-LiteRT`, its own table in that section), three more (GLiNER2.5-Decide, GLiClass-Edge v3.0 and Open-Decision DeBERTa-v3-Large; the last table of that section) and `samples/promises`, a screen that sorts a conversation's sentences into promises, requests and plans. Main also adds voice, not released yet: a new module `hfmodels-voice` with which the phone hears a request, acts on it with its own tools and answers aloud, offline (the section "Voice", `samples/voice`), and tool calls on `Tasks.Chat`. 0.1.1 added thinking models (their reasoning arrives in `Message.channels`, not in the text), `Message.text`, a resolver that never auto-selects a profile whose only device record is a FAIL, and a drop-in device check for any app (`samples/chat/src/androidTest/.../ChatDeviceCheck.kt`).

## Add it

**First model: [LFM2.5-1.2B-Instruct](https://huggingface.co/litert-community/LFM2.5-1.2B-Instruct).**
Use the published **0.1.1** SDK for this walkthrough. Start on a physical **Pixel 8a
(8 GB RAM), Android 16 / API 36, build CP1A.260505.005**, the configuration verified below.
The SDK's installation floor is Android 12 / API 31, `arm64-v8a`; other devices and OS
versions are outside this walkthrough's device verification.

Install Android SDK platform 36 and use JDK 17, AGP 9.3.1 and Gradle 9.7.0 (the existing
[sample](samples/chat) uses these versions). The published AAR requires **compileSdk 36**
as well as **minSdk 31**. Have Wi-Fi for the first download: **736,220,768 bytes**.
The download preflight requires the file size plus **256 MiB** of free storage; leave
additional space for the runtime's compile cache. The 8 GB RAM device is a tested target,
not a measured minimum: peak memory and the minimum free RAM/storage for this model have
not been measured by this check.

```kotlin
// settings.gradle.kts: repositories google() and mavenCentral()
// app/build.gradle.kts
android {
    compileSdk = 36
    defaultConfig {
        minSdk = 31
        ndk { abiFilters += "arm64-v8a" }
    }
}
dependencies { implementation("io.github.john-rocky.hfmodels:hfmodels-litertlm:0.1.2") }
```

That one line brings `hfmodels-core`, `litertlm-android` and `kotlinx-coroutines-android 1.11.0` (the version the runtime's bytecode needs; its POM understates it). Toolchain this was built with: AGP 9.3.1, Gradle 9.7.0, compileSdk 36, JDK 17, Kotlin built into AGP (do not apply the standalone Kotlin plugin). The SDK's manifest declares the GPU's `uses-native-library` entries and `INTERNET` (the first download only); its consumer R8 rules keep what the runtime's JNI looks up by name — with `minifyEnabled true` and nothing else, generation works.

Check the resolved versions in an existing app before running it:

```sh
./gradlew :app:dependencies --configuration debugRuntimeClasspath
```

This walkthrough uses `hfmodels-core` **0.1.1**, `hfmodels-litertlm` **0.1.1**,
`litertlm-android` **0.16.1** and `kotlinx-coroutines-android` / `core-jvm` **1.11.0**.
If the report selects different versions, reconcile the app's existing pins first.
These versions come from the [published SDK POM](https://repo1.maven.org/maven2/io/github/john-rocky/hfmodels/hfmodels-litertlm/0.1.1/hfmodels-litertlm-0.1.1.pom)
and AAR, checked on 2026-09-09. The sample's Maven dependency graph also resolves
Gson **2.13.2**, Kotlin reflect / stdlib **2.2.21**, and AndroidX Activity **1.10.1**.
The repository's development `SDK_VERSION` may be a
SNAPSHOT; installing 0.1.1 does not install development code or a newer catalog.

### First reply, with the model and backend pinned

Call this suspend function from your app's coroutine. It downloads and verifies the file,
initializes, appends the streamed text to Logcat, and releases the model even on failure
or cancellation. The existing [chat screen](samples/chat/src/main/kotlin/io/github/johnrocky/hfmodels/samples/chat/MainActivity.kt)
shows the UI and lifecycle wiring.

```kotlin
import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import io.github.johnrocky.hfmodels.BackendPolicy
import io.github.johnrocky.hfmodels.HfModels
import io.github.johnrocky.hfmodels.LoadOptions
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.ModelRef
import io.github.johnrocky.hfmodels.Tasks
import io.github.johnrocky.hfmodels.litertlm.text
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

suspend fun firstReply(context: Context) {
    val models = HfModels(context.applicationContext)
    try {
        val chat = models.fromPretrained(
            ModelRef(
                "litert-community/LFM2.5-1.2B-Instruct",
                revision = "f45d8d8abe93bff4026efee20fa483150ce8e687",
                variant = "int4_gpu",
            ),
            Tasks.Chat,
            LoadOptions(backendPolicy = BackendPolicy.RequireProfile("gpu")),
        ) { event -> Log.i("first-reply", event.toString()) }
        val session = chat.createConversation(
            ConversationConfig(systemInstruction = Contents.of("You are a helpful assistant.")),
        )
        val answer = StringBuilder()
        session.stream(Contents.of("What is 17 + 25? Answer briefly.")).collect { message ->
            answer.append(message.text)
            Log.i("first-reply", answer.toString())
        }
    } catch (e: ModelException) {
        Log.e("first-reply", "${e.code}: ${e.reason}")
        throw e
    } finally {
        withContext(NonCancellable) { models.closeAndJoin() }
        Log.i("first-reply", "Released")
    }
}
```

The pinned repo's [`hfmodels.json`](https://huggingface.co/litert-community/LFM2.5-1.2B-Instruct/blob/f45d8d8abe93bff4026efee20fa483150ce8e687/hfmodels.json)
selects `LFM2.5-1.2B-Instruct_int4_gpu.litertlm`, SHA-256
`36f7f0221bcc42c75291da1d7e3422901024a5b06b9bfa3c02d7feface04f70a`.
The GPU language profile uses a 4,096-token context and the default 256-token output cap.
This pins the same file and profile as the device record; it does not follow later card
edits or the bundled catalog's older fallback revision. A GPU failure is reported by
`RequireProfile("gpu")`; a CPU retry is a separate configuration.

In Android Studio Logcat, select your app and the `first-reply` / `hfmodels` tags.
Success is a `Ready` event, a nonempty streamed answer containing **42**, and **Released**.
The recorded answer was **`17 + 25 equals 42.`**; wording and chunk count may vary.
For Stop, cancel the coroutine collecting `stream`, then create a new conversation before
the next turn. The existing [ChatDeviceCheck](samples/chat/src/androidTest/kotlin/io/github/johnrocky/hfmodels/check/ChatDeviceCheck.kt)
also checks Stop and release in the app's process; its invocation is in [the existing procedure](skills/hfmodels-android/SKILL.md#step-3-verify-in-this-order).
That general-purpose check accepts a model id and resolves it at run time; use the pinned
`ModelRef` above in it when comparing against this walkthrough.

**Verification:** the [2026-09-08 raw device log](https://github.com/john-rocky/hfmodels-android/blob/6410dfc48c53d13364ab0b911f3d790e6ef8585b/litertlm/results/2026-09-08-4C131JEKB15210-0.16.1-device-check.log)
records the HF download, SHA-256 check, `Ready` on GPU, the answer, Stop (`INVALID`) and
release, all passing on the Pixel 8a above. It used release code `046da93` via the sample's
project dependency; the published 0.1.1 sources and runtime/build settings match that code.
The later release catalog adds verification records. These documentation changes reuse
that run; they do not constitute another device run or an independent user's success.
The function above was compiled verbatim in the existing sample against Maven Central
0.1.1 on 2026-09-09; that compilation did not run the app or repeat native inference.
The runtime reports GPU initialization but no per-operation execution report. This check
does not establish offline restart, other phones, OS versions, long conversations or speed.

**If it fails:** start with [`docs/errors.md`](docs/errors.md). Check storage for
`STORAGE_FULL`, Wi-Fi for `NETWORK_ERROR`, the resolved coroutines version for
`NoSuchMethodError`, and the merged GPU native-library entries / free RAM for initialization
failure. For help, [open an SDK issue](https://github.com/john-rocky/hfmodels-android/issues)
with device + Android build, the four dependency versions above, model revision/variant,
selected profile, and the first error or the `Ready` / answer / release lines. Remove tokens
and private prompts. The model card is the place for file/descriptor mismatches.

## Ids that load today

A model loads when its repo carries `hfmodels.json` (the publisher's declaration) or when the SDK's bundled catalog has an entry for it. The catalog pins each model to one commit and never re-hosts a file. The bundled catalog is public: <https://raw.githubusercontent.com/john-rocky/hfmodels-android/main/core/src/main/assets/hfmodels/catalog.json> (entries and their curated specs under `catalog/`).

| id | variant (default) | profiles | verified on a Pixel 8a (Android 16 CP1A.260505.005, LiteRT-LM 0.16.1; 2026-09-07 for the first five rows, 2026-09-08 for the rest) |
|---|---|---|---|
| `litert-community/Qwen2.5-1.5B-Instruct` | `q8` (1.6 GB, text, 4096 tokens) | `cpu` (default), `gpu` | both answered; first GPU load compiles kernels for about a minute |
| `litert-community/LFM2.5-1.2B-Instruct` | `int4_gpu` (0.7 GB, text); also `int4`, `int8` | `gpu` (default), `cpu` | both answered (`int4_gpu`) |
| `litert-community/LFM2.5-VL-1.6B` | `int4` (1.3 GB, text + image); also `int8` | `gpu` (language on GPU, vision on CPU; default), `cpu` | both answered text and an image question (`int4`) |
| `litert-community/gemma-4-E2B-it-litert-lm` | `default` (2.6 GB, text + image) | `gpu` (default), `gpu_vision_cpu`, `cpu` | all three answered text and an image question |
| `litert-community/gemma-4-E4B-it-litert-lm` | `default` (3.7 GB, text + image) | `gpu` (default), `gpu_vision_cpu`, `cpu` | all three answered; on this 8 GB phone the first chunk took 11-15 s on `gpu` and `cpu` (2.7 s on `gpu_vision_cpu`) |
| `litert-community/Qwen3-0.6B` | `int4` (0.3 GB, text); also `mixed_int4` — thinking | `gpu` (default), `cpu` | both answered; the reasoning (515 / 979 chars) arrived in `channels["thought"]`, none of it in the text |
| `litert-community/LFM2.5-VL-450M` | `int4` (0.4 GB, text + image); also `int8` | `gpu` (language on GPU, vision on CPU; default), `cpu` | both answered text and an image question |
| `litert-community/LFM2.5-1.2B-Thinking` | `int4_gpu` (0.7 GB, text); also `int4`, `int8_gpu`, `int8` — thinking | `gpu` (default), `cpu` | both answered; reasoning (426 / 420 chars) in the channel |
| `litert-community/sarashina2.2-1b-instruct-v0.1` | `int4` (0.9 GB, text, Japanese / English); also `int8` | `gpu` (default), `cpu` | both answered |
| `litert-community/Qwen3-1.7B` | `int4` (1.0 GB, text); also `int8` — thinking | `gpu` (default), `cpu` | both answered; reasoning (402 / 478 chars) in the channel |
| `litert-community/DeepSeek-R1-Distill-Qwen-1.5B` | `q8` (1.8 GB, text, 4096 tokens) — thinking, prompt pre-opens the channel | `gpu` (default), `cpu` | both answered; reasoning (167 / 215 chars) in the channel; first GPU load 52 s |
| `litert-community/granite-4.1-3b` | `int4` (2.2 GB, text); also `int8` | `gpu` (default), `cpu` | both answered |
| `litert-community/Ministral-3-3B-Instruct-2512` | `q4_block32` (2.3 GB, text, 4096 tokens) | `gpu` (default), `cpu` | both answered |
| `litert-community/LFM2.5-VL-3B` | `int4` (2.4 GB, text + image); also `int8` | `gpu` (language on GPU, vision on CPU; default), `cpu` | both answered text and an image question |
| `litert-community/Qwen3-4B-Instruct-2507` | `mixed_int4` (2.7 GB, text) | `gpu` (default), `cpu` | both answered; first chunk 4.5 s on `gpu`, 26 s on `cpu` |
| `litert-community/Qwen3-4B` | `mixed_int4` (2.7 GB, text) — thinking | `gpu` (default), `cpu` | both answered; reasoning (436 / 430 chars) in the channel; first chunk 3.9 s on `gpu`, 20 s on `cpu` |
| `litert-community/Qwen3.5-0.8B` | `int8` (1.0 GB, text); also `vl_int8` (text + image) | `cpu` (default), `gpu` | `cpu` answered (first chunk 1.8 s); `gpu` killed the process during initialize on this 8 GB phone (recorded as FAIL, never auto-selected) |
| `litert-community/Falcon-H1-1.5B-Instruct` | `int8` (1.7 GB, text) | `cpu` (default), `gpu` | `cpu` answered (first chunk 3.5 s); `gpu` ended the process before any result (FAIL, never auto-selected) |

Each cell is one run of the catalog gate (`tools/gate.sh`): a real download from the Hub with Range resume and a sha256 check, `prepare` on the named profile, one text turn (and one image turn for VLM profiles; for a thinking model the turn also has to put its reasoning in `channels` and none of the markers in the text). The records are in `verification/` and inside the bundled catalog; the logs are `litertlm/results/2026-09-07-4C131JEKB15210-0.16.1-a3-gate.log` and `…/2026-09-08-…-a3-gate.log`. One phone, one day: not a promise for other devices. "Thinking" rows reason on every turn by default and get a 2,048-token output cap. A profile whose only record is a FAIL is skipped by `BackendPolicy.Auto` and stays available to `Require(GPU)` / `RequireProfile` for a phone with more memory; the two Qwen3.5 int8 bundles above 1 GB (2B, 4B) have specs but no entry yet, because their GPU run died the same way and their CPU weight cache (about 3.4 times the file) did not fit the free space of the gate phone.

Development shortcut: a copy of the file pushed into the app's external files directory (`adb push <file> /sdcard/Android/data/<applicationId>/files/`) is hashed and imported instead of downloaded; a file that does not match the descriptor's sha256 is ignored. `adb logcat -s hfmodels` prints one line per stage (`download`, `side-loaded`, `ready … profile=… prepare_ms=…`).

`ModelRef(id, revision = "<commit>", variant = "<id>")` pins more. Without a revision the first successful load binds the id to the commit it resolved, and later loads (also offline) use that binding until you call `unbind`.

## Typed decisions (0.1.2)

The second thing the SDK runs: a decision model that answers typed questions about a state without generating text. One forward per question (one per request on a family that packs a request's questions); the answer is a probability per option, not a sentence to parse. The request and answer forms are the `/v1/systemone` ones (`state`, `questions` with `type` / `instructions` / `criteria`; answers with `choice` / `score` / `noul`, `probabilities`, `confidence`), so a request written for a server is handed to the phone unchanged.

```kotlin
// implementation("io.github.john-rocky.hfmodels:hfmodels-litert:0.1.2")   (+ android.uniquePackageNames=false, see docs/api.md)
val model = models.fromPretrained(ModelRef("<owner>/<name>"), EncoderDecisions)
val d = model.decide(
    mapOf("subject" to "Duplicate charge", "body" to "Please refund the duplicate charge."),
    mapOf(
        "department" to Question.Choice("Which department should handle this request?", linkedMapOf("billing" to "invoices, payments, refunds", "technical" to "bugs, outages", "other" to "everything else")),
        "urgency" to Question.Score("How urgent is this request?", listOf("not urgent", "soon", "critical deadline or blocking issue")),
        "refund_requested" to Question.Noul("Does the user explicitly request a refund?"),
    ),
)
(d.answers["department"] as Answer.Choice).choice        // billing (p=0.68)
(d.answers["refund_requested"] as Answer.Noul).noul      // 0.91
d.timing.questionMs                                       // per question, on this phone
```

The first models are the `laya` decision encoders (`convaiinnovations/laya`, Apache-2.0; ModernBERT-large for English, mmBERT-base for 100+ languages) converted to LiteRT graphs and run by `CompiledModel` (the `hfmodels-litert` module; `docs/api.md`, "Typed decisions"). The host side (the tokenizer, the sequence, the calibration and rounding) is a port of the publisher's code and is checked against it: 209 English and 201 multilingual captured rows give the same token ids and marker positions, and decode the captured logits to the same rounded answers (`litert/src/test/.../LayaParityTest.kt`, run with the publisher's tokenizer files). What the phone then computes is compared with the publisher's official `predict` on the same rows, and with it again on the 144 rows of a public decision fixture (SemIf `authored144`, three-option choice questions), in the device gate (`tools/decide_gate.sh`, one log per run under `litert/results/`).

| variant (window) | file | Galaxy S26 SM-S942Q, Android 16 BP4A.251205.006, LiteRT 2.2.0, 2026-09-21: one question, GPU FP32 | CPU (4 threads) | captured rows: argmax / max Δp | authored144: agreement with the official answers / accuracy of both | log |
|---|---|---|---|---|---|---|
| `ml_s256_fp32` multilingual (256) | 1.29 GB | median 54 ms (min 52, p90 57 in the first 60 rows; 55 / 117 over all 201 rows as the phone warmed) | 222 ms | 201/201 identical / 0.0001 | 144/144 / 0.590 | `…-1412-…-ml_s256_fp32-gpu.log`, `…-1440-…`, `…-1421-…-cpu.log` |
| `en_s256_fp32` English (256) | 1.68 GB | median 127 ms (min 120, p90 129 in 60 rows; 133 / 290 over 140 rows) | not run | 140/140 (rows within 256 tokens) / 0.0000 | 144/144 / 0.611 | `…-1414-…-en_s256_fp32-gpu.log`, `…-1442-…` |
| `en_s512_fp32` English (512) | 1.69 GB | median 442 ms (min 291, p90 573 in 60 rows; 493 / 612 over 209 rows) | not run | 209/209 / 0.0001 | 144/144 / 0.611 | `…-1415-…-en_s512_fp32-gpu.log`, `…-1443-…` |
| `en_s512_wfp16` English (512, float16 weights) | 843 MB | does not compile on the GPU (the accelerator leaves its 130 DEQUANTIZE / EMBEDDING_LOOKUP nodes to the CPU, then fails: `litert/results/…-1411-…-en_s512_wfp16-gpu.log`) | 1,326 ms | 30/30 / 0.0005 | 24/24 / 0.708 (24 rows) | `…-1417-…-en_s512_wfp16-cpu.log` |

Each cell is one run of the gate in one process: side-load and sha256 check, tokenizer load, compile, the captured rows one question at a time (the median is over those forwards, warm), then four questions about one state seven times, then the 144 fixture rows, then release. Four questions about one state cost four forwards on an encoder: `decide(state, 4 questions)` and four `decide(state, 1 question)` took the same time within noise (the shared part, tokenizing the state, is about 2 ms), so `prefill` is an API for the language-model path that is not in this release rather than a saving here. Later runs in the same session were slower (the p90 column; the phone reported thermal status 1 at 40 °C by the end): one phone, one afternoon, not a benchmark. Accuracy on the fixture is the publisher's model's, reproduced on the phone, and it is what a 400M-parameter zero-shot encoder does on three-option evidence questions; the authored144 rows are not what the model was trained for.

**On the NPU (0.1.3).** An app that packages Qualcomm's runtime (`tools/fetch_npu_libs.sh`) loads a variant's `npu` profile with `BackendPolicy.Require(BackendKind.NPU)`; `Auto` keeps the default profile (`docs/api.md`, "NPU"). The same S26 on 2026-09-29, on the graphs laya-LiteRT publishes since that day, and on GLiNER2.5-Decide-LiteRT's NPU files on 2026-10-04 (s128) and 2026-10-05 (s256):

| variant (window) | NPU: one question | captured rows: argmax / max Δp | NPU compile: first / from LiteRT's cache | log |
|---|---|---|---|---|
| `ml_s256_wfp16` multilingual (256, float16 weights; no GPU profile) | median 32.6 ms (p90 34.1) | 201/201 / 0.0068 | 76.0 s / 0.93 s | `…-2348-…-ml_s256_wfp16-npu.log`, `…-2349-…-npu-cached.log` |
| `en_s256_fp32` English (256) | median 65.3 ms (p90 67.2) | 140/140 / 0.0054 | 65.2 s / not measured | `…-2349-…-en_s256_fp32-npu.log` |
| `s128_npu_wfp16` GLiNER2.5-Decide (128, float16 weights; the graph changed for float16 hardware) | median 27.5 ms (p90 29.3) | 42/42 / 0.0055 | 32.4 s / 0.68 s | `…-1015-…-s128_npu_wfp16-npu.log`, `…-1027-…-cached-s128_npu_wfp16-npu.log` |
| `s256_npu_wfp16` GLiNER2.5-Decide (256, float16 weights; the graph changed for float16 hardware) | median 128.3 ms (p90 132.0) | 42/42 / 0.0063 | 305.3 s / 1.27 s | `…-1022-…-s256_npu_wfp16-npu.log`, `…-1029-…-cached-s256_npu_wfp16-npu.log` |

An NPU row counts only when LiteRT's log shows the whole graph as one NPU node (`Replacing 1 out of 1 node(s) with delegate (DispatchDelegate)`): when its NPU compile fails, LiteRT 2.2.0 runs the graph on the CPU without an error. The GPU rows above were re-run on the new bytes the same night: `ml_s256_fp32` 201/201, max Δp 0.0001; `en_s256_fp32` 140/140, max Δp 0.0000 (`tested-runtime-matrix.json`; the phone was at thermal status 1 to 2, so those times are not comparable with the table's). GLiNER2.5-Decide's s512 graph changed for float16 hardware (`s512_fp16hw_wfp16`) has no `npu` profile: on 2026-10-05 its NPU compile on this phone aborted 151 s in with `Scudo ERROR: internal map failure (error desc=Out of memory)` (`…-1032-…-jit-s512_fp16hw_wfp16-npu.log`).

The second family (main, 0.2.0-SNAPSHOT) is Julia-1 (`SupersonicLabs/Julia-1`, Apache-2.0; mmBERT-small with a decision head, 144M parameters, multilingual), converted to LiteRT in the host-lookup form ([litert-community/Julia-1-LiteRT](https://huggingface.co/litert-community/Julia-1-LiteRT): the graph takes the looked-up token embeddings, the token table ships as a float16 file, one graph per window) and loaded by id: its repo carries `hfmodels.json` (from commit 8f36857c) and the bundled catalog pins the same descriptor at b92a0d21, so a pinned or offline load needs no Hub read. Its option rendering and decoding are its publisher's, not laya's (`docs/api.md`, "Families"; an app writes the same `Question`s). The SDK's token ids and marker positions equal the publisher's `sequence()` on all 2,100 rows it answered (the 2,000 questions of the LocalLLaMA/typed-decisions test split plus the 100 parity cases of Julia-1-ONNX), and on the phone every answer had the publisher's argmax with every probability within 0.0077 of the publisher's CPU FP32 runtime (the float16 table's rounding; the same maximum the publisher's card measured on a Mac and in its own gate app, and no row over 0.01). The device's correct counts on the typed-decisions questions are the publisher's to the row. Three consecutive runs of the same gate form (`JuliaDecisionsDeviceTest`, `GATE_TEST=JuliaDecisionsDeviceTest tools/decide_gate.sh`), 2026-09-30, the phone warming from thermal status 0 to 2 (battery 35 to 44 °C) across them:

| variant (window) | files | Galaxy S26 SM-S942Q, Android 16 BP4A.251205.006, LiteRT 2.2.0, 2026-09-30: one question, GPU FP32 | CPU (4 threads) | rows vs the publisher's runtime: argmax / max Δp | typed-decisions correct, device = publisher (choice / score / noul) | log |
|---|---|---|---|---|---|---|
| `s512_fp32` (512) | 185 MB graph + 197 MB table + 34 MB tokenizer | median 114 ms (min 59, p90 123 over 2,065 rows; 62 in the first 200 rows, status 0 → 1) | median 256 ms (p90 257, 300 rows, status 2) | GPU 2,065/2,065 / 0.0077; CPU 300/300 / 0.0049 | 414/586, 534/786, 477/593 (the 1,965 questions within 512 tokens; 35 longer ones are reported as truncated) | `…-1102-…-decide-julia1-s512_fp32-gpu.log`, `…-1118-…-cpu.log` |
| `s1024_fp32` (1024) | 189 MB graph + the same table and tokenizer | median 292 ms (min 159, p90 307 over 2,100 rows, status 1 → 2) | not run | 2,100/2,100 / 0.0077 | 426/600, 542/800, 483/600 (all 2,000; the publisher's own CPU FP32 reproduction gives the same three numbers) | `…-1106-…-decide-julia1-s1024_fp32-gpu.log` |

Each cell is one run in one process: side-load and sha256 check, tokenizer load (34 MB, 256,000 tokens), compile (1.3 s GPU / 0.1 s CPU at 512; 2.4 s GPU at 1024), the publisher's sequences for every row, then every row through `decide(state, question)` (the median is over those calls, warm: sequence, host lookup of the 512 or 1,024 embedding rows at about 1 to 2 ms, write, run, readback), then four questions about one state five times (453 ms batched vs 464 ms one by one on the GPU at 512: four forwards either way), then release. GPU FP32 or CPU only: the publisher measured that fp16 arithmetic (GPU default precision, the NPU) changes this model's answers, so the descriptor declares no NPU profile and asks for FP32 on the GPU.

Three more families came in on main (0.1.3-SNAPSHOT), each a model whose LiteRT conversion and Android host are already published in litert-community: GLiNER2.5-Decide (`fastino/GLiNER2.5-Decide`, Apache-2.0; DeBERTa-v3-large with gliner2's classification head; [litert-community/GLiNER2.5-Decide-LiteRT](https://huggingface.co/litert-community/GLiNER2.5-Decide-LiteRT)), GLiClass-Edge v3.0 (`knowledgator/gliclass-edge-v3.0`, Apache-2.0; a ModernBERT zero-shot classifier; [litert-community/GLiClass-Edge-v3.0-LiteRT](https://huggingface.co/litert-community/GLiClass-Edge-v3.0-LiteRT)) and Open-Decision DeBERTa-v3-Large (the DeBERTa-v3-large decision checkpoint of Mithril, formerly Kotoba Cloud, Apache-2.0; [litert-community/Open-Decision-DeBERTa-v3-Large-LiteRT](https://huggingface.co/litert-community/Open-Decision-DeBERTa-v3-Large-LiteRT)). All three are English host-lookup graphs (the float16 token table ships beside the graph) and load by id from the bundled catalog; their repos carry `hfmodels.json` (`docs/api.md`, "Descriptor", names the commits). They score strings: a choice's description, not its key, is what the model reads, and the key comes back as the answer (`docs/api.md`, "Families"). GLiNER2.5-Decide and GLiClass-Edge refuse a request longer than the window instead of cutting it. Open-Decision cuts only the state, at 256 tokens, and answers all the questions of one `decide` in one forward, as its author's code does.

The host side is checked against each card's own host on the JVM (`GlinerDecideParityTest`, `GliclassParityTest` and `DebertaDecisionParityTest` under `litert/src/test/`, run with the card's files), and three different things are compared. The requests the SDK assembles equal the ones captured from the publisher's code (token ids, label or span positions and the smallest window; for GLiClass-Edge also the request string): 84/84 for GLiNER2.5-Decide, 552/552 for GLiClass-Edge, 1,809/1,809 for Open-Decision. The publisher's captured logits, decoded by the SDK, give the publisher's answers: 137/137 tasks, 552/552 requests and 439/439 questions; this checks the decoding, not the graph. The request `decide()` builds from a `Question` equals the card host's on 180 of 180 question forms for GLiClass-Edge and for Open-Decision; there the expected answers follow the same mapping rule, so only the ids and positions are an independent check. What the graph computes is measured on the phone, one run of the device gate per row (`GATE_TEST=GlinerDecideDeviceTest`, `GliclassDeviceTest` or `DebertaDecisionDeviceTest tools/decide_gate.sh <variant> <backend>`):

| family, variant (window) | backend | files | compile | the card's gate requests through the graph: argmax as the reference / max Δp | Galaxy S26 SM-S942Q, Android 16 BP4A.251205.006, LiteRT 2.2.0, 2026-10-03: one question, median / p90 | log |
|---|---|---|---|---|---|---|
| `gliner2_decide` `s128_wfp16` (128) | GPU FP32 | 660 MB graph + 262 MB table + 8.3 MB tokenizer | 3,439 ms | 42/42 / 0.00026 | 70.4 / 73.5 ms | `…-1653-…-decide-gliner-s128_wfp16-gpu.log` |
| | CPU (4 threads) | the same | 1,381 ms | 42/42 / 0.00026 | 257.3 / 262.6 ms | `…-1654-…-decide-gliner-s128_wfp16-cpu.log` |
| `gliner2_decide` `s256_wfp16` (256) | GPU FP32 | 711 MB graph + the same table and tokenizer | 3,716 ms | 42/42 / 0.00024 | 183.9 / 239.9 ms | `…-1655-…-decide-gliner-s256_wfp16-gpu.log` |
| | CPU (4 threads) | the same | 1,517 ms | 42/42 / 0.00024 | 551.9 / 553.9 ms | `…-1656-…-decide-gliner-s256_wfp16-cpu.log` |
| `gliner2_decide` `s128_npu_wfp16` (128), 2026-10-04 | NPU (Qualcomm HTP) | 660 MB graph + the same table and tokenizer | 32,397 ms (first) / 677 ms (cache) | 42/42 / 0.0055 | 27.5 / 29.3 ms | `…-1015-…-decide-gliner-s128_npu_wfp16-npu.log`, `…-1027-…-cached-s128_npu_wfp16-npu.log` |
| | GPU FP16 with FP32 accumulation | the same | 3,104 ms | 42/42 / 0.0028 | 62.6 / 66.4 ms | `…-1021-…-decide-gliner-fp16acc32-s128_npu_wfp16-gpu.log` |
| `gliner2_decide` `s256_npu_wfp16` (256), 2026-10-05 | NPU (Qualcomm HTP) | 711 MB graph + the same table and tokenizer | 305,269 ms (first) / 1,267 ms (cache) | 42/42 / 0.0063 | 128.3 / 132.0 ms | `…-1022-…-decide-gliner-s256_npu_wfp16-npu.log`, `…-1029-…-cached-s256_npu_wfp16-npu.log` |
| | GPU FP16 with FP32 accumulation | the same | 4,991 ms | 42/42 / 0.0033 | 148.0 / 151.2 ms | `…-1029-…-decide-gliner-s256_npu_wfp16-gpu.log` |
| `gliner2_decide` `s512_fp16hw_wfp16` (512), 2026-10-05 | GPU FP16 with FP32 accumulation | 811 MB graph + the same table and tokenizer | 3,876 ms | 42/42 / 0.0033 | 471.0 / 1,243.8 ms | `…-1018-…-decide-gliner-cold-s512_fp16hw_wfp16-gpu.log` |
| `gliclass` `s128_fp32` (128) | GPU FP32 | 54 MB graph + 39 MB table + 3.6 MB tokenizer | 729 ms | 152/152 / 0.0015 | 11.7 / 12.6 ms | `…-1937-…-decide-gliclass-s128_fp32-gpu.log` |
| | CPU (4 threads) | the same | 37 ms | 152/152 / 0.0015 | 14.8 / 16.8 ms | `…-1938-…-decide-gliclass-s128_fp32-cpu.log` |
| `deberta_decision` `s256_wfp16` (256) | GPU FP32 | 713 MB graph + 262 MB table + 8.7 MB tokenizer | 5,162 ms | 371/371 questions of 143 requests / 0.00069 | 187.2 / 191.5 ms | `…-1938-…-decide-opendecision-s256_wfp16-gpu.log` |
| | CPU (4 threads) | the same | 1,862 ms | 371/371 / 0.00069 | 654.6 / 733.5 ms | `…-1939-…-decide-opendecision-s256_wfp16-cpu.log` |

Each row is one run in one process: side-load with a sha256 check (each GPU run imported the files the app did not have yet, the CPU runs reused them), tokenizer load, compile, the card's device-gate requests through the graph, then the 30 chat sentences of the sieve that chose the demo below through `decide(text, question)`, then four questions about one state five times, then release. The references differ by card: GLiNER2.5-Decide's 42 requests per window against the official gliner2 fp32 logits, GLiClass-Edge's 152 shareable requests against the official pipeline, and the 143 of Open-Decision's 160 gate requests that fit 256 tokens, packed as its author packs them, against the author's implementation. The one-question column is over the 30 sentences, warm, with the question as the sieve asked it: the descriptions as labels, and `key: description` as each of Open-Decision's options. On those sentences every run gave the answers of the card's Python host on a Mac; the largest probability difference was 2.95e-6 in the GPU FP32 and CPU rows and 0.0047 in the rows of the graphs changed for float16 hardware. The sieve's own labels were matched on 27 of 30 by GLiNER2.5-Decide, on 10 by GLiClass-Edge (10 also with the keys alone) and on 19 by Open-Decision (22 with the keys alone); the phone's count equals the Mac's in every run. Four questions about one state cost four forwards on GLiNER2.5-Decide and GLiClass-Edge (on the GPU, 281 ms in one call against 285 ms in four, and 38 against 40 ms) and one forward on Open-Decision (193 ms against 746 ms for four separate calls on the GPU, 734 against 2,930 ms on the CPU). The s256 GLiNER2.5-Decide runs and the Open-Decision runs warmed the phone (thermal status 0 to 2, and 0 to 1), and the s256 GPU p90 sits far from its median for a reason not looked into: one phone, one evening, not a benchmark. GLiNER2.5-Decide `s512_wfp16`, GLiClass-Edge `s256_fp32` and Open-Decision `s512_wfp16` ran the same gate on 2026-10-04 and passed (42/42 on the GPU; 152/152 on the GPU and on the CPU; 420/420 questions of 160 requests on the GPU; `…-1030-…`, `…-1029-…` and `…-1037-…` logs). Each of the three cards measured that the GPU's default precision changes answers, so every descriptor asks for FP32 on the GPU, except for GLiNER2.5-Decide's three graphs changed for float16 hardware (`s128_npu_wfp16`, `s256_npu_wfp16`, `s512_fp16hw_wfp16`): their `gpu` profiles ask for FP16 with FP32 accumulation (the rows above; on `s128_npu_wfp16` plain FP16 changed one near-tie decision, 41/42, `…-1028-…-fp16-s128_npu_wfp16-gpu.log`). The `s512_fp16hw_wfp16` run went from thermal status 0 to 2; the first 10 of its 30 sentences took 470.6 ms and the last 10 took 1,201.7 ms (medians).

`samples/promises` is one screen on GLiNER2.5-Decide. A conversation from the share sheet, the clipboard or a built-in sample is cut into sentences; each sentence gets one choice question (nothing, promise, request or plan) and lands in a bundle (You promised, They asked you, Plans) whose rows open the phone's new-event screen. The milliseconds per sentence are on the screen. Its device check (`PromisesDeviceCheck.kt`, the same Galaxy S26, GPU, offline, 2026-10-03) put the 16 sample sentences where their labels expect (16/16) at 77.80 ms per sentence (median; p90 78.92), 1,238 ms for all 16; a 95-word sentence, 163 tokens with the question, came back `too long`, and release took 138 ms. With Qualcomm's runtime in the APK it loads `s128_npu_wfp16` on the NPU: on 2026-10-04 the device check put 16/16 where expected at 30.53 ms per sentence (median; p90 30.95), 484 ms for all 16, and the same build's GPU check (`s128_wfp16`) 16/16 at 75.19 ms. Its README has the recording runs and the limits.

`samples/decide` is three screens on this API with the milliseconds on screen: a voice gate (each utterance the recognizer finalizes: is it a question or a request for the assistant), the clipboard before a paste (what the text holds, which pieces the chosen purpose needs, then the spans from GLiNER2.5-Small-LiteRT), and a query against passages (does it answer, how relevant, ranked). Its README has what the screens showed. The graphs, tokenizer and config files are [litert-community/laya-LiteRT](https://huggingface.co/litert-community/laya-LiteRT), loaded by id (its `hfmodels.json` names the variants above plus `ml_s512_fp32` and `ml_s256_wfp16`; their device rows are in `tested-runtime-matrix.json`); `catalog/dev/` keeps the development descriptor the device gate uses to side-load local conversion outputs. Language-model bundles as a decision backend (scoring a prompt's continuations after one prefill) are not in this release: the LiteRT-LM Kotlin API exposes no scoring or checkpoint call; `Tasks.Chat` remains the way to use them.

## Voice (0.2.0, main)

The third thing the SDK runs: the phone hears a request, does it with its own tools and answers aloud, with no network. Main only; not on Maven Central yet.

```kotlin
// implementation("io.github.john-rocky.hfmodels:hfmodels-voice:0.2.0")   (main; + android.uniquePackageNames=false, see docs/api.md "Voice")
val asr = HfModels(app).fromPretrained(ModelRef("litert-community/Zipformer-medium-CR-CTC-LiteRT"), Transcribe)
val tts = HfModels(app).fromPretrained(ModelRef("litert-community/kitten-tts-nano-0.8"), Speak)
val chat = HfModels(app).fromPretrained(ModelRef("litert-community/gemma-4-E2B-it-litert-lm"), Tasks.Chat)
val loop = VoiceLoop(asr, chat, tts, PhoneTools.all(app), VoiceLoopConfig(player = SpeechPlayer()))
loop.listen(MicSource().chunks()).collect { e -> show(e) }   // per utterance: Heard, Thinking, ToolCalled, Speaking, Done
```

- Hears: the microphone in 20 ms chunks, an energy endpointer that ends an utterance after 800 ms of silence, and Zipformer CTC (English; one 16 s window per utterance) on LiteRT's `CompiledModel`, on the GPU.
- Acts: the transcript goes to the chat model with the phone's tools (`PhoneTools`: the time, alarms and timers in the Clock app, events in the app's own calendar). The runtime parses Gemma 4's calls, the app runs them (the runtime never does), and the model answers. A tool says only what it could check: the alarm is "set" once Android reports it as the next alarm, "requested" when another alarm at that minute or earlier comes first; a timer is always "requested", because Android has no public API to read the Clock app's timers.
- Says: KittenTTS nano speaks the answer sentence by sentence while it streams, on the CPU. After an action it says the tool's result ("Alarm set for 07:30 (Morning Alarm)"), not the model's words about it: for "Wake me up at six fifteen" Gemma 4 E2B once called `set_alarm(hour = 16, minute = 15)` and said "6:15" (`litertlm/results/2026-10-03-1637-…`, c02).

Both speech repos carry `hfmodels.json`: Zipformer from commit fa063a88, Kitten from 2c5b198f, which also added the four G2P files under `g2p/`. The bundled catalog pins those two commits. The first load by id resolves the repo's branch over the network, reads its `hfmodels.json`, downloads the files the store lacks, checks their sha256 and binds the id to that commit; later loads use the binding and the stored files, with or without a network. `samples/voice` pins the catalog's commits instead. On the Galaxy S26 below, on 2026-10-05, `TranscribeDeviceTest` and `SpeakDeviceTest` loaded both ids by id over LTE, which bound them to those commits, and again in airplane mode through the binding; `samples/voice`, in airplane mode, loaded both at the pinned commits from the catalog's entries and answered a typed "What time is it?" aloud after `get_current_datetime` (`litert/results/2026-10-05-0957-…`, `…-0958-…`; `litertlm/results/2026-10-05-1005-…`). The phone downloaded none of their files: the apps' stores already held them from earlier runs (the store keeps a file under its sha256). The download ran on a Mac's JVM instead, where the SDK's resolver and store planned both ids and fetched their files from the Hub, 2 for Zipformer `medium_fp16` and 8 for Kitten `fp32`, every sha256 matching (`litertlm/results/2026-10-05-0952-mac-jvm-live-hub-resolver-store.log`). `VoiceDeviceCheck` and the requests that set an alarm or a timer or use the calendar have not run at these commits.

Every run below predates those commits: it passed a development descriptor with the same variants and files explicitly at the commit before (7732ad6c, d4662d89), imported every speech file from a local copy and checked its sha256 (the G2P files too, which the Kitten repo did not carry then), and downloaded nothing from the Hub. The files' sha256 are the ones the current commits list. On 2026-10-05 the gate ran again at those commits, pinned, in airplane mode, with the repos' own `hfmodels.json`: both Zipformer variants on the GPU and on the CPU, both Kitten variants with the predictor without XNNPACK and the speed priors. All six passed (`litert/results/2026-10-05-*`; their numbers are in `tested-runtime-matrix.json`); the tables keep the 2026-10-03 numbers.

The measurements are one Galaxy S26 SM-S942Q (Android 16 BP4A.251205.006, LiteRT 2.2.0, LiteRT-LM 0.16.1) on 2026-10-03. The spoken commands are ten synthetic WAVs (macOS `say`, voice Samantha, `tools/voice_fixtures.sh`), not a person; the loop has not been measured through the microphone with a person's voice. Each row is one run of the test or app it names; the logs are under `litert/results/` and `litertlm/results/`.

Transcribe (`TranscribeDeviceTest`, `tools/transcribe_gate.sh`; commit ebaa5bd; thermal status 0):

| variant (graph) | backend | transcript equal to the command (lower case; letters, digits and word breaks; 10 WAVs) | graph run, median of 10 warm calls (min to max) | whole call (fbank, graph, CTC), median | load | log |
|---|---|---|---|---|---|---|
| `small_fp16` (46 MB) | GPU | 2/10 | 29.4 ms (28.9 to 30.4) | 52.8 ms | 2.05 s | `…-1421-…-transcribe-zipformer-small_fp16-gpu.log` |
| `small_fp16` | CPU (4 threads) | 3/10 | 141.8 ms (141.0 to 143.6) | 151.0 ms | 0.18 s | `…-1422-…-transcribe-zipformer-small_fp16-cpu.log` |
| `medium_fp16` (131 MB) | GPU | 4/10 | 37.1 ms (36.3 to 40.8) | 60.6 ms | 2.50 s | `…-1422-…-transcribe-zipformer-medium_fp16-gpu.log` |
| `medium_fp16` | CPU (4 threads) | 5/10 | 187.9 ms (186.4 to 188.4) | 196.6 ms | 0.32 s | `…-1422-…-transcribe-zipformer-medium_fp16-cpu.log` |

The text comes in capitals without punctuation, and the comparison is strict: `medium_fp16` on the GPU wrote "SET AN ALARM FOR SEVEN THIRTY TO MORROW MORNING", "WOULD A TEAM STAND UP ON MY CALENDAR …" for "Put a team standup on my calendar …" and "B IS ON MY CALENDAR TO MORROW" for "What is on my calendar tomorrow?". Every warm repeat gave the same text as the first call.

Speak (`SpeakDeviceTest`, `tools/speak_gate.sh`: ten fixed replies of 34 to 77 characters, voice `expr-voice-2-m`, CPU with 4 threads; the median is over the warm repeat):

| variant (graphs) | XNNPACK | pace | median synthesis (min to max) | real-time factor, median | peak RSS after the load (VmHWM) | same samples on the warm repeat | log |
|---|---|---|---|---|---|---|---|
| `fp32` (64 MB) | all three graphs | graph speed 1.0 | 210.4 ms (174.0 to 365.9) | 0.075 | 626,732 kB | 10/10 | `…-1500-…-speak-kitten-fp32-cpu.log` |
| `fp16` (32 MB) | all three graphs | graph speed 1.0 | 213.5 ms (174.7 to 366.4) | 0.075 | 602,152 kB | 10/10 | `…-1500-…-speak-kitten-fp16-cpu.log` |
| `fp32` | all three graphs | the publisher's (`say.py`) | 288.3 ms (227.0 to 465.3) | 0.076 | 624,404 kB | 10/10 | `…-1550-…-speak-kitten-fp32-cpu.log` |
| `fp32` | prosody and vocoder only (the default) | the publisher's | 301.2 ms (253.6 to 559.6) | 0.086 | 334,360 kB | 10/10 | `…-1550-…-speak-kitten-xnnpackoff-predictor-fp32-cpu.log` |

The 15:00 runs (commit d0130aa, thermal status 0) predate the speed priors: the graph got speed 1.0, a faster pace than the publisher's `say.py` (0.8 for this voice), so their replies are shorter (126 frames for the first one against 149). The 15:50 runs (thermal status 1) apply the priors, as the SDK does now, and differ only in XNNPACK on the predictor; they gave the same frames. The real-time factor is synthesis time over audio length. The SDK turns text into symbols without espeak, so its ids are not espeak's: on the publisher's three bench sentences none matched (43, 68 and 133 symbols against espeak's 41, 63 and 138, the 15:00 logs). The synthesizer alone, given the publisher's own ids, produced the publisher's durations (111, 163 and 281 frames; the samples differ from the publisher's Mac run by up to 0.83; `…-1501-…-kitten-synth-fp32-cpu.log`). After the ten replies the peak (VmHWM) of the two 15:50 runs was 869,020 kB with XNNPACK on all three graphs and 614,304 kB with the default (`step=memory after_speak` in their logs).

The loop with Gemma 4 E2B (`litert-community/gemma-4-E2B-it-litert-lm`, `default`, GPU), Zipformer `medium_fp16` on the GPU and Kitten `fp32` on the CPU, the ten commands. A command succeeds when the expected calls were made with the expected arguments. Times are in ms from the end of the utterance, medians over the ten commands in the first two rows and one command's values in the last two; for a WAV the utterance ends with the endpointer's 800 ms of silence, so a speaker hears the first sound 800 ms later than the column says:

| input | run | succeeded | transcript | model's first token | first sentence ready | first sound | log |
|---|---|---|---|---|---|---|---|
| typed text | `VoiceLoopDeviceTest`, tools that record and do nothing, no player (first sound = the first sentence synthesized); commit 7fcaae9 | 9/10 | 0 | 624 | 1,615 | 2,273 | `…-1638-…-voiceloop-text-gemma-4-E2B-…-gpu-runtime.log` |
| the WAVs, each with 1 s of silence, through the endpointer | the same | 8/10 | 61 | 816 | 1,215 | 1,870 | `…-1637-…-voiceloop-wav-gemma-4-E2B-…-gpu-runtime.log` |
| one typed command, "Set an alarm for seven thirty tomorrow morning." | `samples/voice` in airplane mode, the real tools and the loudspeaker (first sound = the player's first write); commit 1f4ab93 | 1/1; Android then reported the next alarm at 07:30 | 0 | 708 | 1,301 | 1,602 | `…-1712-…-voicesample-say-airplane.log` |
| the same command's WAV | `VoiceDeviceCheck` in the sample's process, airplane mode, the real tools and the loudspeaker | 1/1 (heard, calls, network none) | 88 | 819 | not recorded | 1,797 | `…-1713-…-voicecheck-airplane.log` |

The rows from commit 7fcaae9 predate two rules the loop has now: it said the model's words after an action (it now says the action's result) and passed Zipformer's capitals to the model as they came (it now passes a sentence, `VoiceLoopConfig.normalizeTranscript`); those rows were not run again. Their misses: with audio, c02 set the alarm at 16:15 and c08, heard as "WOULD A TEAM STAND UP …", asked for the date instead of adding the event; typed, c10 set 09:30 for "half past nine tonight". In the `VoiceDeviceCheck` row the 07:30 alarm of the row above was already Android's next alarm, so that run does not show a new alarm being set.

The chat models tried for the loop (`ToolsDeviceTest`, `tools/tools_gate.sh`: the ten commands as text through `ToolRunner` with tools that record and do nothing, the transcriber and the speaker loaded in the same process; commit 70ec224):

| chat model (variant, backend; how its calls travel) | succeeded | further calls | median first token / reply | the first command again on the same load | first sound of that command | thermal status | log |
|---|---|---|---|---|---|---|---|
| `litert-community/gemma-4-E2B-it-litert-lm` (`default`, GPU; parsed by the runtime) | 10/10 | 5 | 650 / 1,728 ms | the same calls and reply | 1,478 ms | 0 | `…-1601-…-tools-gemma-4-E2B-…-gpu-runtime.log` |
| `litert-community/Qwen3-1.7B` (`int4`, GPU; parsed by the runtime; no reasoning asked) | 8/10: c01 set 23:30, c06 read today's calendar | 3 | 1,875 / 3,761 ms | the same calls and reply | 5,962 ms | 0 → 2 | `…-1601-…-tools-Qwen3-1.7B-int4-gpu-runtime.log` |
| `litert-community/LFM2.5-1.2B-Instruct` (`int4_gpu`, GPU; LFM2's call text, parsed by `hfmodels-voice`) | 8/10: c02 no call, c10 set 17:00 | 6 | 871 / 2,850 ms | a different call and reply ("7:30 PM") | 3,377 ms | 2 | `…-1602-…-tools-LFM2.5-1.2B-Instruct-int4_gpu-gpu-lfm.log` |

"Further calls" are calls other than the expected ones, such as `get_current_datetime` before `set_alarm`; they do not fail a command. The repeat runs on a new conversation of the same loaded model, as every turn does; on LFM2.5 it gave another answer, and this run does not show whether the previous conversation's state carried over (LiteRT-LM#3165). `samples/voice` runs the first row with `medium_fp16` on the GPU and Kitten `fp32` on the CPU; its README has the screen and the airplane-mode procedure, and `samples/voice/src/androidTest/.../VoiceDeviceCheck.kt` is the drop-in check for an app. One phone, one day: not a promise for other devices, other voices or a real room.

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

- `createConversation(config)` accepts `systemInstruction`, `initialMessages`, `samplerConfig`, `maxOutputToken`, and (0.1.1) `channels` and `thinkingConfig`. Response format and LoRA are refused with `UNSUPPORTED_CONFIGURATION` (they are not silently passed through), and so are tools on 0.1.2. Main (0.2.0) passes `tools` to the runtime with `automaticToolCalling` always off: the calls arrive in `Message.toolCalls`, the app runs them and answers with `stream(Message.tool(…))` on the same session (`docs/api.md`, "Tools").
- `stream(contents)` starts on collect and is collected once. Chunks arrive in order; a collector more than 1,024 chunks / 8 MiB behind ends with `SLOW_CONSUMER` after the native side is stopped — nothing is dropped silently.
- Cancel the collecting coroutine or call `cancel()`: the native generation stops; the session becomes INVALID; open a new conversation for the next turn (pass your transcript as `initialMessages` to keep history).
- One generation at a time per model (`MODEL_BUSY`), one native model per `HfModels` (`MODEL_BUSY` on a second `prepare`).
- A new conversation on the same loaded model is not a fresh state on a model whose layers keep a running state (linear attention / SSM; verified on decider-2b-vision, a Qwen3.5 hybrid) under LiteRT-LM 0.16.1 to 0.17.1: the runtime carries the previous conversation's state over ([google-ai-edge/LiteRT-LM#3165](https://github.com/google-ai-edge/LiteRT-LM/issues/3165)). For one request after another keep one conversation and add turns, or `closeAndJoin()` and load again. Measured in [samples/pong](samples/pong/README.md) (a new conversation per decision: 33 of 60 answers equal to a fresh state) against [samples/ask](samples/ask/README.md) (one conversation: 18 of 20 on the GPU, 5 of 5 on the CPU).
- `close()` returns at once; `closeAndJoin()` waits, children first, then the Engine. Idempotent.
- `info: PreparedModelInfo` reports commit, descriptor origin, variant, profile, requested / initialized backend per component, and `observed = UNKNOWN` on this runtime version (it exposes no execution report; "initialized on GPU" is not a claim that every op ran there).

## Thinking models (0.1.1)

A reasoning model (Qwen3, DeepSeek-R1-Distill, LFM2.5-Thinking; Gemma 4 when thinking is enabled) streams its thinking between markers. The runtime keeps that out of the text only when the conversation declares a matching channel, and it does not declare one on its own unless the bundle's header does. The SDK declares it from the catalog entry (`handler_config.channels`) on every `createConversation`, so:

```kotlin
val chat = models.fromPretrained(ModelRef("litert-community/LFM2.5-1.2B-Thinking"), Tasks.Chat)
chat.thinking                        // channels=[thought <think>..</think>] source=descriptor prefilled=false reasonsByDefault=true
session.stream(Contents.of("What is 17 + 25? Answer briefly.")).collect { m ->
    answer += m.text                                            // the answer only
    m.channels["thought"]?.let { reasoning += it }              // the reasoning, streamed piece by piece like the text
}
session.stream(prompt, GenerationOptions(thinkingTokenBudget = 64))   // cut the reasoning short
chat.createConversation(ConversationConfig(channels = emptyList()))   // off: markers back in the text
```

A model that reasons by default gets a 2,048-token output cap (the reasoning counts against it); everything else keeps 256. The SDK reads the bundle's header (two small reads, no weights) to know the declared channel and, at `prepare`, renders one prompt through the runtime to know whether the generation prompt already opens it (`prefilled`), because a start marker that the template pre-opens and one the model has to emit itself are handled differently by the runtime. Which entries were verified to separate their reasoning on a device is in the table above.

## For coding agents

`AGENTS.md` is the entry point, `docs/api.md` the complete public surface with imports, `docs/errors.md` the error table, and `skills/hfmodels-android/SKILL.md` the procedure with its three finish conditions (builds; answers a fixed prompt on a connected device; Stop / Release / re-create wired). To give an agent the procedure inside your app's repository:

```sh
mkdir -p .claude/skills/hfmodels-android && curl -fsSL https://raw.githubusercontent.com/john-rocky/hfmodels-android/main/skills/hfmodels-android/SKILL.md -o .claude/skills/hfmodels-android/SKILL.md
```

`.claude/skills/` is where Claude Code loads project skills; an agent that reads `AGENTS.md`-style files instead can be pointed at the same URL. `llms.txt` lists every document above as an absolute URL.

The device check an agent (or you) runs inside the app to prove the integration on the connected phone, without driving the UI: copy `samples/chat/src/androidTest/kotlin/io/github/johnrocky/hfmodels/check/ChatDeviceCheck.kt` into `app/src/androidTest/kotlin/`, add the two `androidTest` lines it names, and run it with the app's model id. It loads the model in the app's own process, streams one fixed prompt, checks the answer (and that a thinking model's reasoning stayed out of the text), cancels a second turn mid-stream, releases, and prints one `RESULT` line per step under `adb logcat -s hfmodels-check`. For the voice loop (main) the same kind of file is `samples/voice/src/androidTest/kotlin/io/github/johnrocky/hfmodels/check/VoiceDeviceCheck.kt`: the three loads from the app's store, one spoken command's WAV through the endpointer and the real tools, the alarm against Android's next alarm clock, the network state, the release.

## Publishing a model

A repo becomes loadable by id with one file, `hfmodels.json`, generated (not typed) from the files' Hub metadata: `docs/publishing.md`.

## Layout

```
core/        hfmodels-core: ModelRef, HfModels (inspect / download / prepare), descriptor + catalog readers, Hub client, content-addressed store, errors
litertlm/    hfmodels-litertlm: Tasks.Chat, ChatModel / ChatSession on LiteRT-LM (tool calls from 0.2.0), consumer R8 rules, GPU manifest entries
litert/      hfmodels-litert: EncoderDecisions / TypedDecisions on LiteRT CompiledModel (decision encoders), Transcribe (Zipformer CTC) and Speak (KittenTTS on the Interpreter API), the host-side parity tests, the device gates; results/ their logs
voice/       hfmodels-voice: VoiceLoop (Endpointer, SentenceSplitter, MicSource, SpeechPlayer) and the tools (ToolRunner, VoiceTool, PhoneTools); androidTest/ the speech, tools and loop gates
samples/chat the chat screen on the SDK (id in, chat out); its androidTest/ holds the drop-in ChatDeviceCheck
samples/decide three screens on typed decisions (voice gate, clipboard, query x passages) with the milliseconds on screen
samples/voice one microphone button over VoiceLoop with the phone's real tools, the time from the end of speech to the first sound on screen; its androidTest/ holds the drop-in VoiceDeviceCheck
samples/promises a conversation sorted sentence by sentence into You promised / They asked you / Plans on GLiNER2.5-Decide, the milliseconds on screen; its androidTest/ holds a device check, results/ the Galaxy S26 records
samples/ask  one bar chart the app draws and five questions about it in one conversation (the picture with question 1, then text-only turns), each answer marked against the data; results/ the Galaxy S26 records
samples/pong a Pong the phone plays from its own frames, a new conversation per decision; its README says why the answers after the first are unreliable on this model (LiteRT-LM#3165); results/ the records
catalog/     specs (curated) -> entries (generated) -> the bundled asset; dev/ the development descriptors (the decision graphs, FunctionGemma); proposals/ the hfmodels.json drafts for model repos; tools/ generate and gate them
probes/      the runtime coexistence probe (LiteRT-LM + LiteRT in one release APK) and its logs
docs/        api.md (the complete surface), errors.md, publishing.md; skills/ the agent procedure (hfmodels-android) and a draft on choosing and running decision models (typed-decisions-on-device); tested-runtime-matrix.json the verified matrix
```

## License

Apache-2.0 for the SDK. Each model keeps its own license (`hfmodels.json` names it); the SDK shows it and does not judge it.
