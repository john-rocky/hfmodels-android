# Loading, sessions and reasoning models

## Load in steps

```kotlin
val plan = models.inspect(ref, Tasks.Chat, LoadOptions(networkPolicy = NetworkPolicy.Unmetered))  // metadata only, no weights
plan.bytesToDownload; plan.profile.id; plan.excludedProfiles                                       // show, ask
val local = models.download(plan) { e -> progress(e) }                                             // resumable, verified
val chat = models.prepare(local)                                                                    // no network
```

`LoadOptions`: `backendPolicy` (`Auto` / `Require(CPU|GPU)` / `RequireProfile(id)`), `requiredInputs` (`setOf(TEXT, IMAGE)` for a VLM), `networkPolicy` (`Any` / `Unmetered` / `Offline`), `maxDownloadBytes`, `contextTokens`, `credentials` (a token for gated repos; never stored or logged), `descriptorJson` (bring your own descriptor).

## Sessions

- `createConversation(config)` accepts `systemInstruction`, `initialMessages`, `samplerConfig`, `maxOutputToken`, and (0.1.1) `channels` and `thinkingConfig`. Response format and LoRA are refused with `UNSUPPORTED_CONFIGURATION` (they are not silently passed through), and so are tools on 0.1.2. From 0.2.0 the SDK passes `tools` to the runtime with `automaticToolCalling` always off: the calls arrive in `Message.toolCalls`, the app runs them and answers with `stream(Message.tool(…))` on the same session (`docs/api.md`, "Tools").
- `stream(contents)` starts on collect and is collected once. Chunks arrive in order; a collector more than 1,024 chunks / 8 MiB behind ends with `SLOW_CONSUMER` after the native side is stopped — nothing is dropped silently.
- Cancel the collecting coroutine or call `cancel()`: the native generation stops; the session becomes INVALID; open a new conversation for the next turn (pass your transcript as `initialMessages` to keep history).
- One generation at a time per model (`MODEL_BUSY`), one native model per `HfModels` (`MODEL_BUSY` on a second `prepare`).
- A new conversation on the same loaded model is not a fresh state on a model whose layers keep a running state (linear attention / SSM; verified on decider-2b-vision, a Qwen3.5 hybrid) under LiteRT-LM 0.16.1 to 0.17.1: the runtime carries the previous conversation's state over ([google-ai-edge/LiteRT-LM#3165](https://github.com/google-ai-edge/LiteRT-LM/issues/3165)). For one request after another keep one conversation and add turns, or `closeAndJoin()` and load again. Measured in [samples/pong](../samples/pong/README.md) (a new conversation per decision: 33 of 60 answers equal to a fresh state) against [samples/ask](../samples/ask/README.md) (one conversation: 18 of 20 on the GPU, 5 of 5 on the CPU).
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

The device check an agent (or you) runs inside the app to prove the integration on the connected phone, without driving the UI: copy `samples/chat/src/androidTest/kotlin/io/github/johnrocky/hfmodels/check/ChatDeviceCheck.kt` into `app/src/androidTest/kotlin/`, add the two `androidTest` lines it names, and run it with the app's model id. It loads the model in the app's own process, streams one fixed prompt, checks the answer (and that a thinking model's reasoning stayed out of the text), cancels a second turn mid-stream, releases, and prints one `RESULT` line per step under `adb logcat -s hfmodels-check`. For the voice loop (0.2.0) the same kind of file is `samples/voice/src/androidTest/kotlin/io/github/johnrocky/hfmodels/check/VoiceDeviceCheck.kt`: the three loads from the app's store, one spoken command's WAV through the endpointer and the real tools, the alarm against Android's next alarm clock, the network state, the release.
