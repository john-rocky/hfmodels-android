# samples/promises: what was promised, asked and planned, sorted on the phone

One screen. A conversation comes in from the share sheet (any app's Share, then **hfmodels promises**), from the clipboard (**Paste**) or from the built-in **Sample**; every sentence is asked one question by a decision model on the phone, one sentence at a time, and lands in a bundle: **You promised**, **They asked you**, **Plans**. Every row has an **Add** that opens the phone's new-event screen with the sentence as the title; the person saves it. The sentences that need nothing are counted and folded. Sorting starts as soon as the text arrives.

At the top, a state pill (READY, SORTING n/N, DONE) and one latency line: the median milliseconds per sentence, sentences per second, the model and the backend it runs on. In the middle, one sentence large with its answer and the answer's probability (it changes at most once every 1.2 s; the sorting does not wait for it). Before the first conversation the card shows the question and its four options.

Everything model-related goes through the SDK: `DecisionModels.kt` loads, `Promises.kt` holds the question and asks it, `MainActivity.kt` drives the screen.

## The question

```kotlin
Question.Choice("What is this sentence?", linkedMapOf(
    "nothing" to "an opinion, a story, a vague maybe, or something happening right now",
    "promise" to "the speaker commits to do something later",
    "request" to "the speaker asks the listener to do something",
    "plan" to "a time or day agreed to meet or do something",
))
model.decide(sentence, mapOf("q" to question))   // one forward per sentence
```

This is question B of the sieve that picked the model: on its 30 labelled chat sentences GLiNER2.5-Decide answered 27 as labelled, more than any other decision model tried (the card's Python host on a Mac, 2026-10-03; the Galaxy S26 gave the same 27, `correct_device=27/30` in `litert/results/2026-10-03-1653-RFGL80R6A6H-litert2.2.0-decide-gliner-s128_wfp16-gpu.log`). The `gliner2_decide` family turns the question into one gliner2 task with the instructions as its prompt and the descriptions as its labels, so the descriptions are what the model reads. The model gets the sentence alone; the sender is only shown.

## How a conversation is cut

One line at a time. A leading `[...]` (a chat app's time stamp) is dropped. A line that starts with a name of up to three words and a colon followed by a space (`Them: ...`) has the name split off as the sender. The rest is cut after every `.`, `?` or `!` that a space follows. At most 200 sentences are sorted; the pill says `DONE 200 OF <n>` when more were left out.

## Model and files

[litert-community/GLiNER2.5-Decide-LiteRT](https://huggingface.co/litert-community/GLiNER2.5-Decide-LiteRT), variant `s128_wfp16` (a 128-token window, float16 weights; Apache-2.0), on the GPU when the phone has one (the descriptor's default profile) with the CPU as the fallback. The model repo carries no `hfmodels.json` yet, so the app ships the development descriptor (`catalog/dev/litert-community__GLiNER2.5-Decide-LiteRT.hfmodels.json`, added to the assets by `build.gradle.kts`) and passes it as `LoadOptions.descriptorJson` with its commit as the revision; once the bundled catalog carries the model (0.1.3) neither is needed. The first conversation downloads the three files (0.93 GB: the graph, the token table, the tokenizer) into the app's private storage and verifies their sha256; later loads are offline. A copy already on the computer can be pushed instead of downloaded:

```sh
adb push gliner25_decide_s128_wfp16.tflite word_embeddings_fp16.bin tokenizer.json /sdcard/Android/data/io.github.johnrocky.hfmodels.samples.promises/files/   # hashed against the descriptor, then imported; delete the copies afterwards
```

A load runs one sentence through the model before the first conversation (`warmup_ms` in the logs: 105 ms on the Galaxy S26 below, against 78 ms per sentence after it), so the first forward after a compile is not timed as the first sentence on the screen.

## Add

The start of the new event comes from fixed rules on the sentence's words, not from the model: `on the 12th`, a weekday, `next <weekday>`, `this weekend`, `next weekend`, `the end of the month`, `tomorrow`, `today`, `tonight`, and a time (`6:30 pm`, `8 am`, `at 6:30`, `at 7`, `to 10`, `from 3`, `noon`; without am / pm, 1 to 7 o'clock reads as the evening). A start that has already passed moves on: tonight to tomorrow night, the 3rd to the 3rd of next month. Without any of them the event starts tomorrow at 9:00. Every event is one hour long. The full rules are in `EventTime.kt`; the new-event screen is where they get corrected.

## Recording mode

```sh
adb shell input keyevent KEYCODE_WAKEUP
adb shell am start -n io.github.johnrocky.hfmodels.samples.promises/.MainActivity --ez autostart true --ei delay 3
adb shell screenrecord --time-limit 30 /sdcard/promises-take1.mp4     # once the display is on
```

The scripted start shows the screen over the lock screen with the display on (a phone asleep behind a secure lock keeps a started app in the background), waits for the model, shows the question for `delay` seconds, sorts the Sample conversation, and at DONE writes `promises-result-<epoch s>.json` to the app's external files dir: the count, the bundles, every sentence's answer, probabilities and milliseconds, the median and p90, the thermal status before and after, the device and its build. The same numbers go to logcat under tag `promises`, followed by `TAP x=… y=…`, the screen position of the first Plans row's Add, for `adb shell input tap` as the recording's last step. A normal start does none of that.

The Sample is 16 sentences of the sieve's chat fixture (`SampleChat.kt`), word for word, in the order of a chat between `You` and `Them`: 4 promises, 4 requests, 3 plans, 5 that need nothing. They were chosen, not drawn, from the 27 of the 30 that the Mac host answered as labelled; the other three are left out (b03 and b07, promises answered as a plan and as a request, and b24, a vague maybe answered as a plan). The promises are given to the owner and the requests to the other side, so the bundle titles read right, and the conversation opens with a request and ends with a plan, the two sentences the card shows when a phone sorts all 16 within its 1.2 s.

## Device check

`src/androidTest/kotlin/io/github/johnrocky/hfmodels/check/PromisesDeviceCheck.kt` proves on the connected phone, in the app's process, that the model loads, that the Sample lands in the bundles its labels expect, that a sentence too long for the window comes back as `too long`, and that release returns. One `RESULT` line per step under tag `hfmodels-check`.

```sh
./gradlew :samples:promises:assembleDebug :samples:promises:assembleDebugAndroidTest
adb install -r samples/promises/build/outputs/apk/debug/promises-debug.apk
adb install -r samples/promises/build/outputs/apk/androidTest/debug/promises-debug-androidTest.apk
adb shell am instrument -w -e class io.github.johnrocky.hfmodels.check.PromisesDeviceCheck -e backend gpu -e network offline \
    io.github.johnrocky.hfmodels.samples.promises.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s hfmodels-check | grep RESULT
```

`connectedDebugAndroidTest` runs it too, but uninstalls the app afterwards, and the imported model with it.

## What it showed on the Galaxy S26 (2026-10-03)

Galaxy S26 SM-S942Q (SM8850), Android 16 BP4A.251205.006.S942QOPS1AZH9, LiteRT 2.2.0, SDK 0.1.3-SNAPSHOT, the model at commit db801972 on the GPU (the descriptor's default profile, FP32), its three files pushed once and imported by the SDK.

| run | answers as labelled | ms per sentence, median / p90 | the 16 sentences | sentences per second | thermal status, skin |
|---|---|---|---|---|---|
| device check (GPU, offline) | 16/16 | 77.80 / 78.92 | 1,238 ms | 12.92 | none to none, 30.8 to 31.2 °C |
| recording mode, take 1 (airplane mode) | 16/16 | 75.63 / 76.85 | 1,241 ms | 12.89 | none to none, 31.8 to 31.9 °C |
| recording mode, take 2 | 16/16 | 74.54 / 75.02 | 1,223 ms | 13.08 | none to none, 31.4 to 31.6 °C |
| recording mode, take 3 | 16/16 | 74.18 / 75.32 | 1,221 ms | 13.11 | none to none, 31.6 to 31.8 °C |

Milliseconds per sentence are the SDK's wall clock for one `decide` call: tokenizing the sentence, building the sequence, looking up the embeddings, the graph and the decoding. In the device check every answer was the Mac host's, with the probabilities within 1.73e-6 of its; the load compiled the graph in 3,881 ms and ran the warm-up sentence in 105 ms; the 95-word sentence, 163 tokens with the question, came back `too long`; release took 138 ms. In recording mode the sentences per second include the screen's work between sentences. Numbers from one phone on one evening, the model loaded and warm; not a benchmark, and not a claim about the model's accuracy beyond these 16 sentences. The device check's log and the three result files are in `results/2026-10-03-s26/`.

## Limits

- English: the descriptor declares `en`, and the sieve was English. Other languages were not measured.
- One sentence and the question share the 128-token window: the question takes 57 of them (the sieve's sentences took 64 to 75 with it). A longer sentence is shown with a `too long` chip and stays out of the bundles; it is never cut.
- No speaker separation. The bundle titles are written for the common case, the owner's promises and the other side's requests, but the model only sees the sentence: a promise from the other side lands under You promised, and a request the owner made lands under They asked you.
- Dates and times come from the rules above, not from the model.
- The sender rule is a pattern, not a parser: a name of four words or more stays in the sentence, so does a time stamp without brackets (a WhatsApp export's `10/3/26, 5:01 PM - Name: ...`), and a `Note:` or `Re:` at the start of a line is taken for a sender.
