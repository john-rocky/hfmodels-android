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

[litert-community/GLiNER2.5-Decide-LiteRT](https://huggingface.co/litert-community/GLiNER2.5-Decide-LiteRT), variant `s128_wfp16` (a 128-token window, float16 weights; Apache-2.0), on the GPU when the phone has one (the descriptor's default profile) with the CPU as the fallback; an app built with Qualcomm's runtime loads another variant on the NPU first (NPU, below). The app ships the development descriptor (`catalog/dev/litert-community__GLiNER2.5-Decide-LiteRT.hfmodels.json`, added to the assets by `build.gradle.kts`) and passes it as `LoadOptions.descriptorJson` with its commit as the revision. Since 2026-10-04 the model repo carries the three published variants as `hfmodels.json`, and the bundled catalog on main pins them at db801972; the NPU variant is only in the asset. The first conversation downloads the three files (0.93 GB: the graph, the token table, the tokenizer) into the app's private storage and verifies their sha256; later loads are offline. A copy already on the computer can be pushed instead of downloaded:

```sh
adb push gliner25_decide_s128_wfp16.tflite word_embeddings_fp16.bin tokenizer.json /sdcard/Android/data/io.github.johnrocky.hfmodels.samples.promises/files/   # hashed against the descriptor, then imported; delete the copies afterwards
```

A load runs one sentence through the model before the first conversation (`warmup_ms` in the logs: 110 ms on the Galaxy S26 below, against 73 ms per sentence after it), so the first forward after a compile is not timed as the first sentence on the screen.

## Add

The start of the new event comes from fixed rules on the sentence's words, not from the model: `on the 12th`, a weekday, `next <weekday>`, `this weekend`, `next weekend`, `the end of the month`, `tomorrow`, `today`, `tonight`, and a time (`6:30 pm`, `8 am`, `at 6:30`, `at 7`, `to 10`, `from 3`, `noon`; without am / pm, 1 to 7 o'clock reads as the evening). A start that has already passed moves on: tonight to tomorrow night, the 3rd to the 3rd of next month. Without any of them the event starts tomorrow at 9:00. Every event is one hour long. The full rules are in `EventTime.kt`; the new-event screen is where they get corrected.

## Recording mode

```sh
adb shell input keyevent KEYCODE_WAKEUP
adb shell am start -n io.github.johnrocky.hfmodels.samples.promises/.MainActivity --ez autostart true --ei delay 3
adb shell screenrecord --time-limit 30 /sdcard/promises-take1.mp4     # once the display is on
```

The scripted start shows the screen over the lock screen with the display on (a phone asleep behind a secure lock keeps a started app in the background), waits for the model, shows the question for `delay` seconds, sorts the Sample conversation, and at DONE writes `promises-result-<epoch s>.json` to the app's external files dir: the count, the bundles, every sentence's answer, probabilities and milliseconds, the median and p90, the thermal status before and after, the device and its build. The same numbers go to logcat under tag `promises`, followed by `TAP x=… y=…`, the screen position of the first Plans row's Add, for `adb shell input tap` as the recording's last step. A normal start does none of that. `--es backend npu`, `gpu` or `cpu` on the start that creates the screen fixes the backend (NPU, below).

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

`-e backend npu` loads the NPU variant on the NPU (NPU, below). `gpu` and `cpu` load `s128_wfp16` on that backend. Without it, the check loads what the screen loads. Every `RESULT` line names the variant and the profile. `connectedDebugAndroidTest` runs it too, but uninstalls the app afterwards, and the imported model with it.

## What it showed on the Galaxy S26 (2026-10-04)

Galaxy S26 SM-S942Q (SM8850), Android 16 BP4A.251205.006.S942QOPS1AZH9, LiteRT 2.2.0, SDK 0.1.3-SNAPSHOT, the model at commit db801972 on the GPU (the descriptor's default profile, FP32), its three files pushed and imported by the SDK the day before. Every run in airplane mode.

| run | answers as labelled | ms per sentence, median / p90 | the 16 sentences | sentences per second | thermal status, skin |
|---|---|---|---|---|---|
| device check (GPU, offline) | 16/16 | 72.80 / 81.37 | 1,187 ms | 13.48 | none to none, skin not read |
| recording mode, take 1 (Add, ends on the app chooser) | 16/16 | 74.23 / 77.60 | 1,220 ms | 13.12 | none to none, 32.5 to 32.5 °C |
| recording mode, take 2 (Add, ends on the new-event screen) | 16/16 | 73.23 / 74.40 | 1,202 ms | 13.31 | none to none, 31.6 to 32.0 °C |
| recording mode, take 3 (Add, ends on the new-event screen) | 16/16 | 73.14 / 73.80 | 1,195 ms | 13.39 | none to none, 31.7 to 32.0 °C |
| recording mode, take 4 (no Add) | 16/16 | 73.43 / 73.91 | 1,212 ms | 13.20 | none to none, 32.0 to 32.2 °C |

Milliseconds per sentence are the SDK's wall clock for one `decide` call: tokenizing the sentence, building the sequence, looking up the embeddings, the graph and the decoding. In the device check every answer was the Mac host's, with the probabilities within 1.73e-6 of its; the load compiled the graph in 3,885 ms and ran the warm-up sentence in 110 ms; the 95-word sentence, 163 tokens with the question, came back `too long`; release took 85 ms. In recording mode the sentences per second include the screen's work between sentences. On this phone two apps take a new event (Calendar and Outlook), so Add first shows the system's app chooser; takes 2 and 3 pick Calendar for this one time and end on its new-event screen, the sentence as the title, 12 October 7 to 8 pm. Nothing was saved. Numbers from one phone on one night, the model loaded and warm; not a benchmark, and not a claim about the model's accuracy beyond these 16 sentences. The device check's log and the four result files are in `results/2026-10-04-s26/`; the first run, on 2026-10-03 with an earlier build of this sample, is in `results/2026-10-03-s26/`.

## NPU

The model can also run on the Qualcomm NPU (Hexagon HTP) of a Snapdragon phone; this sample ran it on the Galaxy S26 (SM8850). Qualcomm's runtime is not in this repository. The APK has to carry it, and the build extracts it at install:

```sh
tools/fetch_npu_libs.sh samples/promises/src/main/jniLibs/arm64-v8a v81    # the Hexagon version of the phone's SoC: SM8550 v73, SM8650 v75, SM8750 v79, SM8850 v81
```

With those files in the app, the screen loads variant `s128_npu_wfp16` on the NPU, and the latency line ends with `· NPU`. That variant is the s128 graph rewritten for the Qualcomm HTP; its token table and tokenizer are the files of `s128_wfp16`. The first NPU load compiles the graph on the phone (19.3 s on the Galaxy S26 below); later loads read LiteRT's cache (1.1 s in the device check).

If the NPU load fails with one of the codes in `DecisionModels.BACKEND_ERRORS`, the screen logs the code and the reason under tag `promises` and loads the same variant on its default profile: the GPU, with the CPU as the fallback. Both read the same three files, so nothing more is downloaded. Without the runtime the app loads `s128_wfp16` on the GPU, as before.

`--es backend npu`, `gpu` or `cpu` on the start that creates the screen fixes the backend, with no fallback; `gpu` and `cpu` load `s128_wfp16`. A running screen keeps its model, so force-stop the app first.

The graph of `s128_npu_wfp16` is not on the Hub at the commit the descriptor pins. Push it, and the SDK imports it after checking its sha256 against the descriptor:

```sh
adb push gliner25_decide_s128_npu_split_fc1_wfp16.tflite /sdcard/Android/data/io.github.johnrocky.hfmodels.samples.promises/files/   # delete the copy afterwards
```

### What it showed on the Galaxy S26 (2026-10-04)

Galaxy S26 SM-S942Q (SM8850), Android 16 BP4A.251205.006.S942QOPS1AZH9, LiteRT 2.2.0, SDK 0.1.3-SNAPSHOT, Qualcomm's runtime from `tools/fetch_npu_libs.sh` (QAIRT 2.47.0.260601, Hexagon v81), the NPU variant's graph pushed and imported by the SDK. One build with the runtime ran every row; the GPU row is its `-e backend gpu` check. Every run in airplane mode.

| run | backend | answers as labelled | ms per sentence, median / p90 | the 16 sentences | sentences per second | thermal status, skin |
|---|---|---|---|---|---|---|
| device check (offline) | NPU, `s128_npu_wfp16` | 16/16 | 30.53 / 30.95 | 484 ms | 33.04 | none to none, 35.9 to 35.9 °C |
| device check (offline) | GPU, `s128_wfp16` | 16/16 | 75.19 / 77.50 | 1,192 ms | 13.43 | none to none, 35.9 to 36.1 °C |
| recording mode, take 1 (Add, ends on the new-event screen) | NPU, `s128_npu_wfp16` | 16/16 | 31.72 / 32.40 | 534 ms | 29.94 | none to none, 35.7 to 35.8 °C |
| recording mode, take 2 (Add, ends on the new-event screen) | NPU, `s128_npu_wfp16` | 16/16 | 31.56 / 32.35 | 530 ms | 30.20 | none to none, 35.7 to 35.7 °C |
| recording mode, take 3 (Add, ends on the new-event screen) | NPU, `s128_npu_wfp16` | 16/16 | 31.24 / 31.92 | 525 ms | 30.45 | none to none, 35.6 to 35.7 °C |
| recording mode, take 4 (no Add) | NPU, `s128_npu_wfp16` | 16/16 | 31.26 / 31.92 | 528 ms | 30.30 | none to none, 35.6 to 35.5 °C |

Milliseconds per sentence are the SDK's wall clock for one `decide` call, as above. On the NPU every answer was the Mac host's, with the probabilities within 0.0048 of its; on the GPU within 1.73e-6. LiteRT logged the whole graph on the NPU (`Replacing 1 out of 1 node(s) with delegate (DispatchDelegate)`) and the GPU check's graph on the GPU (1780 of 1780 nodes). The first load compiled the graph in 19.3 s, outside the table; the device check's load read the cache in 1.1 s and ran the warm-up sentence in 58 ms. Takes 1 to 3 pick Calendar in the app chooser for this one time and end on its new-event screen; nothing was saved. The logs and the result files are in `results/2026-10-04-s26-npu/`.

## Limits

- English: the descriptor declares `en`, and the sieve was English. Other languages were not measured.
- One sentence and the question share the 128-token window: the question takes 57 of them (the sieve's sentences took 64 to 75 with it). A longer sentence is shown with a `too long` chip and stays out of the bundles; it is never cut.
- No speaker separation. The bundle titles are written for the common case, the owner's promises and the other side's requests, but the model only sees the sentence: a promise from the other side lands under You promised, and a request the owner made lands under They asked you.
- Dates and times come from the rules above, not from the model.
- The sender rule is a pattern, not a parser: a name of four words or more stays in the sentence, so does a time stamp without brackets (a WhatsApp export's `10/3/26, 5:01 PM - Name: ...`), and a `Note:` or `Re:` at the start of a line is taken for a sender.
