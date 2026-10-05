---
name: typed-decisions-on-device
description: Answers typed questions (choice, score, yes / no) about a short text on an Android phone with a LiteRT decision encoder, without generating text - one forward pass scores every option you wrote. Use this skill to choose a decision model and a question form for an on-device gate, router or sorter (chat sentences, notifications, tickets), to load one with the hfmodels SDK in five lines, or to run a published LiteRT decision model with its own Kotlin host.
license: Apache-2.0
metadata:
  last-updated: '2026-10-03'
  keywords: [LiteRT, CompiledModel, typed decisions, zero-shot classification, decision model, GLiNER2, GLiClass, DeBERTa-v3, Android]
---

This skill covers decision encoders converted to classic LiteRT graphs (`.tflite`, run by the CompiledModel API, `com.google.ai.edge.litert:litert` 2.2.0) that answer typed questions about a state. The SDK path is [hfmodels-android](https://github.com/john-rocky/hfmodels-android) (module `hfmodels-litert`, `EncoderDecisions`); the host path is each model card's own Kotlin code. For text generation use the `litert-lm` skill; for a general vision or audio model, `litert-runtime`.

## What the models are

A decision encoder reads the state (a sentence, a message, a ticket) together with a question and its options, and returns one score per option from one forward pass; a softmax over the options gives the probabilities. Nothing is generated, so the answer is always one of the options you wrote, and a question costs one forward (Open-Decision: one per request, all of its questions together). Five families run today, each a LiteRT conversion in litert-community with a host-side token lookup (the graph takes embeddings; a float16 table ships beside it):

- `gliner2_decide`: [GLiNER2.5-Decide](https://huggingface.co/litert-community/GLiNER2.5-Decide-LiteRT), DeBERTa-v3-large with gliner2's classification head, English.
- `gliclass`: [GLiClass-Edge v3.0](https://huggingface.co/litert-community/GLiClass-Edge-v3.0-LiteRT), a ModernBERT zero-shot classifier, English.
- `deberta_decision`: [Open-Decision DeBERTa-v3-Large](https://huggingface.co/litert-community/Open-Decision-DeBERTa-v3-Large-LiteRT), a DeBERTa-v3-large decision model with typed questions, English.
- `julia`: [Julia-1](https://huggingface.co/litert-community/Julia-1-LiteRT), mmBERT-small with a decision head, multilingual.
- `laya`: [laya](https://huggingface.co/litert-community/laya-LiteRT), ModernBERT-large (English) and mmBERT-base (multilingual) decision encoders.

## The request

The request and answer forms are the `/v1/systemone` ones, so the same JSON drives a server and the phone:

```json
{
  "state": "Can you bring the folding chairs to the party?",
  "questions": {
    "kind": {"type": "choice", "instructions": "What is this sentence?", "criteria": {
      "nothing": "an opinion, a story, a vague maybe, or something happening right now",
      "promise": "the speaker commits to do something later",
      "request": "the speaker asks the listener to do something",
      "plan": "a time or day agreed to meet or do something"}},
    "urgency": {"type": "score", "instructions": "How urgent is it?", "criteria": ["not urgent", "soon", "today"]},
    "asks_for_code": {"type": "noul", "instructions": "Does it ask for a code, a password or a login link?"}
  }
}
```

A `choice` answers with the winning key, `probabilities` per key and `confidence`; a `score` with the expected level index (a real number), `legend` and `probabilities`; a `noul` with `noul`, the probability that the statement holds.

## Which family when

One Galaxy S26 (SM-S942Q, Android 16, LiteRT 2.2.0), one run per cell, warm, the date in the cell; the milliseconds are the whole call (tokenize, look up, run, decode). Not a benchmark.

| family | strength | one question, median | limits |
|---|---|---|---|
| `gliner2_decide` | a policy written into the labels: on the chat question above it matched 27 of 30 sentences' labels | `s128_wfp16`: 70.4 ms GPU FP32, 257.3 ms CPU (2026-10-03) | English; at most 32 labels; a request longer than the window (128, 256 or 512 tokens) is refused |
| `gliclass` | a 54 MB graph and a 39 MB token table | `s128_fp32`: 11.7 ms GPU FP32, 14.8 ms CPU (2026-10-03) | English; at most 25 labels; matched 10 of 30 on the same chat question |
| `deberta_decision` | many questions about one state share one forward: four questions took 193.1 ms in one call against 746.5 ms in four (GPU) | `s256_wfp16`: 187.2 ms GPU FP32, 654.6 ms CPU (2026-10-03) | English, trained on three domains (its author); the state keeps 256 tokens, and the 256-token graph refuses a long one; matched 19 of 30 with `key: description` options, 22 with bare keys |
| `julia` | multilingual; 2 to 20 options | `s512_fp32`: 114.0 ms GPU FP32 (2026-09-30) | GPU FP32 or CPU only; a long state is cut at the end |
| `laya` | multilingual and English variants, calibrated per question type, an NPU profile | `ml_s256_fp32`: 53.8 ms GPU FP32 (2026-09-21); `ml_s256_wfp16`: 32.6 ms NPU (2026-09-29) | its float16-weight variants run on the CPU or the NPU, not the GPU; a long state is cut at the end |

The chat question is the one in the request above, asked of 30 labelled chat sentences in a sieve on 2026-10-03; in all eight device runs of the first three families the phone gave the answers of the card's Python host on a Mac.

## Write the question so the answer is in the text

1. It works when the answer is written in the state and the option words meet the state's words: what a sentence asks for (a promise, a request, a plan), which line holds a value, a classification by surface words, a policy spelled out in the options.
2. It does not work when the answer lies outside the text: whether something will be useful later, world knowledge, what comes next, how words feel. Change the question, not the model.
3. Put the policy in the options, not in the question: the model scores each option's words against the state. Then measure each option with and without its description on your own sentences; on the chat question above, Open-Decision matched more labels with bare keys (22) than with `key: description` (19).
4. A short state leans toward yes on a yes / no question: ask a choice whose first option is the "nothing" case, or a three-level score.

## With the hfmodels SDK (five lines)

```kotlin
// implementation("io.github.john-rocky.hfmodels:hfmodels-litert:0.2.0") + android.uniquePackageNames=false in gradle.properties
// (these families are on main, 0.2.0; 0.1.2 on Maven Central carries laya only)
val models = HfModels(applicationContext)
val model = models.fromPretrained(ModelRef("litert-community/GLiNER2.5-Decide-LiteRT"), EncoderDecisions)   // download, sha256, compile: GPU FP32, CPU fallback
val q = Question.Choice("What is this sentence?", linkedMapOf("nothing" to "an opinion, a story, a vague maybe, or something happening right now", "promise" to "the speaker commits to do something later", "request" to "the speaker asks the listener to do something", "plan" to "a time or day agreed to meet or do something"))
val a = model.decide("Can you bring the folding chairs to the party?", q) as Answer.Choice   // "request", p 0.59 on the Galaxy S26 GPU
withContext(NonCancellable) { models.closeAndJoin() }
```

Call `decide` off the main thread. A request that does not fit is a `ModelException` with a code (`CONTEXT_LIMIT_EXCEEDED`, `INVALID_INPUT`), never a silent cut on the families that refuse. `samples/promises` in the same repository is a one-screen app on this call.

## With the card's own host (an app without the SDK, a model zoo)

- Create the `CompiledModel` with `GpuOptions(precision = FP32)` on the GPU, or on the CPU. Do not use the GPU's default precision.
- Feed embeddings, not ids: memory-map the card's float16 table and widen each token's row to float32 at every position, padding included.
- Tokenize with the card's Kotlin tokenizer: GLiNER2.5-Decide and Open-Decision use DeBERTa-v3 SentencePiece Unigram with different normalizers, GLiClass-Edge byte-level BPE with a prefix space. Check the ids against the card's captured requests before trusting a generic tokenizer.
- Fill the routing inputs, one row per label or option slot: one-hot at the label marker (GLiNER2.5-Decide, GLiClass-Edge), or 1/len over the question's and the option's text tokens (Open-Decision). Rows past the last label stay zero. GLiNER2.5-Decide's inputs are named `args_0..2`: assign them by shape.
- Read only the first n logits (n labels or options) and take the softmax over them; Open-Decision divides the logits by 1.05 first.
- Each graph has one static window: build the request, then pick the smallest window that holds it.
- With AGP 9 and LiteRT 2.2.0, set `android.uniquePackageNames=false` (litert and litert-api declare the same namespace).

## Build a feature, not a classifier screen

A screen that shows the decisions themselves (a list of sentences sorted into bins) tells a viewer nothing about why they would want it. Put the decisions inside a feature and show the feature's result. Before writing the app, check three things:

1. One sentence says what the person does and what happens: "type what you want, the list's filters set themselves" (`samples/finder`). If the sentence ends in a table of labels, pick another feature.
2. Score the feature's questions before the app exists: 40 made-up inputs with hand-written answers, thresholds written down first (field accuracy ≥ 90 %, every field right on ≥ 75 % of the inputs, a filter set on an input that named none ≤ 5 %, an action taken on chit-chat ≤ 1 in 10). The Mac host of the model's card is enough; the phone gave the same answers on all 200 fields in the finder check.
3. Look at three screenshots (before, after the first input, after the second) before recording: the input, what it set and why each row stayed must be readable without a caption.

The finder sample passed all three on 2026-10-05 and was approved at first sight; the earlier sorted-chat sample had passed an accuracy-only sieve and was rejected. Records: `samples/finder/README.md`, the sieve's `PREREG.md` in the lane's assets.

## Pitfalls

- fp16 changes the answers. The GPU's default precision computes in fp16; each of these cards measured that it changes answers, and on Open-Decision it can make the outputs non-finite. Ask for FP32 explicitly. The NPU computes in fp16 as well.
- Not every family cuts a long state. GLiNER2.5-Decide and GLiClass-Edge refuse a request longer than the window; split a long text into sentences first. Open-Decision keeps the state's first 256 tokens; laya and julia cut the state at the end and report it.
- On the label families the description is the string. GLiNER2.5-Decide and GLiClass-Edge score the description (Open-Decision reads it as the option text); the key is only the answer's id, and a key without a description is a different question.
- Legitimate messages can look like the pattern. A guard question about money, codes, passwords or login links also fires on genuine one-time-code and failed-payment notifications: in the 2026-10-03 sieve on a Mac, models called genuine one-time-code notifications suspicious. Give the legitimate case its own option and count false positives on real legitimate messages before shipping.
- On Open-Decision every question of one call reports the same milliseconds: they shared one forward.
