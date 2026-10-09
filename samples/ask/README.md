# samples/ask

Pick a photo on your phone, type a multiple-choice question about it, and get one letter back: the option a vision-language model chose, lit on screen with the time it took. The model is [`litert-community/decider-2b-vision-LiteRT`](https://huggingface.co/litert-community/decider-2b-vision-LiteRT) (`int8`, 3,171,081,088 bytes) through LiteRT-LM, on the phone. On one Galaxy S26's GPU on 2026-10-10 the letter for a photo and its first question came 1.0 to 1.3 s after the question was sent, and for a second question about the same photo 0.4 s; before a photo's first letter the model loads (40 s on the screen) and its conversation opens (7 s).

## Run it

1. `./gradlew :samples:ask:installDebug`, then open "hfmodels ask" once (it creates the folder the next line pushes into).
2. Optional, instead of the 3.2 GB download on the first Ask: `adb push decider-2b-vision_int8.litertlm /sdcard/Android/data/io.github.johnrocky.hfmodels.samples.ask/files/`
3. Pick a photo (in the Galaxy S26's picker: tap the photo, then Done).
4. Type a question, and two to five options in the boxes A to E.
5. Ask.

The model file is not in the APK. The app loads the model by its id at Hub commit `6c024e946bb8489f3faacb2514b0c61b355d1fdb` (`ModelRef(…, revision = …)`), the commit the bundled catalog pins and the descriptor in `assets` was generated from. The first load downloads the file from the Hub and checks its sha256; a copy pushed as in step 2 is hashed and imported instead.

## The screen

Top to bottom: the state (IDLE, LOADING, ASKING, DONE, STOPPED, FAILED), what is happening (the download, the load, opening a conversation) and the last answer time; your photo; Pick a photo and Demo; the question box; five option boxes lettered A to E; a line that says what the last question carried, or why nothing was asked; the model, variant, backend profile, phone, runtime and SDK versions; the backend (auto, gpu, cpu), Ask and Stop.

Pick a photo opens the system photo picker, which needs no storage permission. The photo on screen is the JPEG the model gets: the picked image with its EXIF rotation applied, scaled so its long side is at most 768 px.

Fill the options from A down. When the letter arrives, its option lights up with the time on its right: from calling `stream` to the first chunk that carries text. The line under the options says whether the photo went with the question (a new conversation, with the time it took to open and, when there was one, the load) or the question was a text-only turn about the same photo. Editing a box clears the light.

## One conversation per loaded model

The photo goes into the first turn of a conversation, and every further question about it is a text-only turn of the same conversation. On this model family LiteRT-LM carries a conversation's state into the next conversation on the same loaded model ([google-ai-edge/LiteRT-LM#3165](https://github.com/google-ai-edge/LiteRT-LM/issues/3165)), so the app never opens a second conversation on a loaded model: after a new photo, a Stop or the Demo, the next Ask closes the model and loads it again before it sends the photo (40 s on the S26's GPU), and the line under the options says so. The CPU profile loads in 16 to 18 s, so with the backend set to cpu a new photo's first letter comes sooner (19.5 s against 68.1 s in the device check's process, load and opening included), while each further question takes longer (644 against 385 ms). `Asker.kt` keeps this rule for the screen and the device check alike.

## What one question sends

1. The photo, through `PhotoImport.load`: decoded with `ImageDecoder`, subsampled while it decodes, scaled to a long side of at most 768 px, JPEG quality 90.
2. The first question about it: `chat.createConversation(ConversationConfig())`, then `session.stream(Contents.of(Content.ImageBytes(jpeg), Content.Text(turn1)), GenerationOptions(maxOutputTokens = 1))`.
3. Each further question about the same photo: `session.stream(Contents.of(Content.Text(turnK)), GenerationOptions(maxOutputTokens = 1))` on the same session.

`turn1` carries a context line, the question and the options, and ends in `Answer: (`; `turnK` starts with the `)` that closes the previous answer and carries no context:

```
Context:
This is a visual question about the image.

Question: What is this a photo of?
Options:
(A) a car
(B) a boat
(C) a bicycle
Answer: (
```

The context line is the one every Demo chart carries, so a photo and a chart reach the model in the same turn shape (`Prompt.kt`). The first non-whitespace character of the answer is the letter; anything that is not the letter of an option shows as "No option letter" with the text the model wrote.

## When it does not answer

| what happens | what the screen says |
|---|---|
| Ask without a photo | Pick a photo first. |
| Ask with an empty question | Type a question. |
| fewer than two options | Type at least two options, A and B. |
| an empty box between two options | Fill the options from A down, with no empty box between two filled ones. |
| two options that read the same | Two options read the same; make each one different. |
| the load fails | the SDK's code and reason ([`docs/errors.md`](../../docs/errors.md)); for the device check's revision that is not on the phone, loaded offline: MODEL_NOT_REGISTERED: litert-community/decider-2b-vision-LiteRT@00000000 has no hfmodels.json and the catalog has no entry for that commit. … |
| a file that is not an image | Could not read that photo: DecodeException: Failed to create image decoder with message 'unimplemented'Input contained an error. |
| a very large photo | nothing: it is subsampled while it decodes (6000 x 4500 came back as 768 x 576 in 48 ms in the device check) |
| a photo stored sideways with an EXIF rotation | nothing: it reaches the model upright (a 400 x 300 JPEG with orientation 6 came back as 300 x 400) |
| Stop during an answer | Stopped. That conversation is closed; the next Ask loads the model again and sends the photo with the question. |

`--es network offline` on `adb shell am start` passes `NetworkPolicy.Offline` to the load: it makes no request, and a model that is not on the phone fails with `OFFLINE_CACHE_MISS` ([`docs/errors.md`](../../docs/errors.md)) instead of trying the download.

## The device check

[`src/androidTest/kotlin/io/github/johnrocky/hfmodels/check/AskDeviceCheck.kt`](src/androidTest/kotlin/io/github/johnrocky/hfmodels/check/AskDeviceCheck.kt) runs in the app's process through the app's own code (`Asker`, `PhotoImport`, `Typed`) and prints one `RESULT` line per step:

```sh
./gradlew :samples:ask:assembleDebug :samples:ask:assembleDebugAndroidTest
adb install -r samples/ask/build/outputs/apk/debug/ask-debug.apk && adb install -r samples/ask/build/outputs/apk/androidTest/debug/ask-debug-androidTest.apk
adb push decider-2b-vision_int8.litertlm a01_red_bicycle.jpg /sdcard/Android/data/io.github.johnrocky.hfmodels.samples.ask/files/   # after opening the app once
adb shell am instrument -w -e class io.github.johnrocky.hfmodels.check.AskDeviceCheck -e backend gpu -e network offline io.github.johnrocky.hfmodels.samples.ask.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s hfmodels-check | grep RESULT
```

The photo is a CC0 photo of a red bicycle by Bernard Spragg on Flickr (`https://live.staticflickr.com/3954/15655615295_3a46e83728_b.jpg`; the 1023 x 728 copy the check ran on is `a01_red_bicycle.jpg` of [`probes/eg2demo/fixtures/album.json`](../../probes/eg2demo/fixtures/album.json), sha256 `4147220d…`). The check puts it into MediaStore, as a camera would, and reads it back through `PhotoImport`, the picker's path.

- `load`: the model loads (`-e backend gpu`: the GPU profile), from the app's store or the pushed copy.
- `chart`: chart_00 and its first question, the Demo's first turn. The letter must match the chart's data.
- `photo`: "What is this a photo of?" with a car, a boat and a bicycle: a letter A to C, on a new load (the chart held the last conversation). Whether it is the bicycle is recorded, not required: the model's answer is not the app's to guarantee.
- `follow-up`: "What colour is the bicycle?" as a text-only turn of the same conversation: a letter.
- `stop`: a third question cancelled before its letter; the next question opens a new conversation on a new load and gets a letter.
- `release`: close, and close again, without an exception.
- the failure paths, each a sentence for the screen instead of a crash: `missing-model`, `no-photo`, `empty-question`, `one-option`, `big-photo`, `rotated-photo`, `bad-photo`.

The last line is `RESULT ok=true …` when every step passed. `-e keep_photo true` leaves the photo in MediaStore (under Pictures/hfmodels-ask-check) for the picker; `-e class io.github.johnrocky.hfmodels.check.AskCheckPhotos` removes it.

## Demo

Demo is the first version of this sample: the app draws one of its 24 bar charts and asks five questions about it in one conversation, the chart with question 1 and text-only turns after it, and marks each answer against the data it drew ("matches the chart"). Demo again takes the next chart, on a new load. The app knows each answer before it asks, so the mark is the app checking the model against its own data, not a benchmark. `Questions.of(chart)` builds the questions and their answers from the chart's heights and colours; `tools/make_charts_json.py` writes `assets/charts.json` (8 charts each with 3, 4 and 5 bars) and checks every question against its chart.

Recording mode runs the Demo alone, over the lock screen with the system bars hidden and the screen kept on: it loads, waits `delay` seconds after READY, asks and stays on DONE. `chart` picks the first chart (0 to 23), `pictures` how many to ask about one after another (each after the first on a new load), `backend` gpu or cpu:

```sh
adb shell am start -n io.github.johnrocky.hfmodels.samples.ask/.MainActivity \
    --ez autostart true --ei delay 3 --ei chart 0 --es backend gpu --ei pictures 1
adb logcat -s ask
```

After each chart (or Stop) the app writes `ask-result-<epoch s>.json` and `frames-<epoch s>/000.png` (the exact PNG bytes the model got) to `/sdcard/Android/data/io.github.johnrocky.hfmodels.samples.ask/files/`: per question the letter, the raw text, whether it matches the chart, `answer_ms` and `stream_ms`; per run `load_ms`, `conversation_ms`, `close_ms`, `total_s`, the profile, the versions, the device and the thermal status before and after. Logcat tag `ask`: `READY profile=… load_ms=…`, `PICTURE p/N chart=…`, one `TURN k id=… letter=… ok=… answer_ms=…` per question, `DONE json=<path>`; for your photo `PHOTO w=… h=…` and `ASK first_turn=… letter=… answer_ms=…`.

## Tests

`./gradlew :samples:ask:testDebugUnitTest` checks the parts that do not need a phone: against the reference files in `src/test/resources/fixtures`, three reference charts pixel for pixel, the questions of all 24 charts rebuilt from the chart alone, the turn texts byte for byte and the answer parsing; and what the screen refuses before the model, the photo's turn text and the photo's scaled size (`TypedTest`).

## Numbers

### 2026-10-10: your photo

Measured on one Galaxy S26 (SM-S942Q, Android 16, build BP4A.251205.006.S942QOPS1AZH9) with litertlm-android 0.16.1 and the `int8` file pushed into the app's files directory: on the GPU profile first by the device check, then on the screen, and last by the device check on the CPU profile. The logs are in [`results/2026-10-10-s26/`](results/2026-10-10-s26/). The phone came from another run at thermal status 2; the CPU check started and ended at 1.

| where | question | options | letter | answer ms | opening the conversation | load before it |
|---|---|---|---|---|---|---|
| device check, GPU | chart_00: Which bar is the tallest? | the red / green / purple bar | B, matches the chart | 982.6 | 7,889 ms | 79.6 s, with the import |
| device check, GPU | What is this a photo of? | a car / a boat / a bicycle | C | 1,027.7 | 7,013 ms | 60.1 s |
| device check, GPU | What colour is the bicycle? (the same photo, text-only) | blue / red / green | B | 385.4 | none | none |
| device check, GPU | What is this a photo of?, after the Stop | a car / a boat / a bicycle | C | 1,097.5 | 7,159 ms | 59.9 s |
| the screen, GPU | What is this a photo of? | a car / a boat / a bicycle | C | 1,299 | 6,875 ms | 40.3 s |
| the screen, GPU | What colour is the bicycle? (the same photo, text-only) | blue / red / green | B | 401 | none | none |
| device check, CPU | chart_00: Which bar is the tallest? | the red / green / purple bar | B, matches the chart | 2,962.9 | 2,413 ms | 23.7 s |
| device check, CPU | What is this a photo of? | a car / a boat / a bicycle | C | 1,780.6 | 1,174 ms | 16.5 s |
| device check, CPU | What colour is the bicycle? (the same photo, text-only) | blue / red / green | B | 643.8 | none | none |
| device check, CPU | What is this a photo of?, after the Stop | a car / a boat / a bicycle | C | 1,778.9 | 1,075 ms | 18.0 s |

The photo went to the model as a 768 x 547 JPEG of 223,031 bytes (from 1023 x 728), read through `PhotoImport` in 28.6 ms.

The first GPU load in the check took 79.6 s: 14.8 s to hash and import the pushed file, then 64.8 s for the engine. The GPU loads after it, inside the check's process, took 60 s; the app on the screen loaded in 40.3 s, as on 2026-09-29 (38.7 to 39.5 s). Opening a conversation on the GPU took 6.9 to 7.9 s, against 4.8 to 5.3 s on 2026-09-29 at thermal status 0. The causes of both differences are not established. On the CPU the loads took 16.5 to 23.7 s and opening a conversation 1.1 to 2.4 s.

The Stop landed 6.2 ms (GPU) and 2.3 ms (CPU) after the third question was sent, while the session was GENERATING: the turn ended without a letter, its conversation was closed, and the next question opened a new conversation on a new load and got its letter. Closing the model took 4.5 s on the GPU and 0.4 s on the CPU; the second close did nothing.

All seven failure paths passed in both checks: `missing-model`, `no-photo`, `empty-question`, `one-option` and `bad-photo` each ended in its sentence of the table above, `big-photo` and `rotated-photo` in the sizes there.

The Demo ran twice on the same phone that day: from its button on chart_00 (5 of 5 match the chart; 885.5 / 236.8 / 248.3 / 320.3 / 244.9 ms; load 38.0 s) and in recording mode on chart_01 (5 of 5; 1,144.0 / 300.2 / 280.7 / 411.5 / 312.8 ms; load 44.0 s). Both records are in the same folder.

One phone, one day; not a benchmark.

### 2026-09-29: the chart demo

Measured on 2026-09-29 on one Galaxy S26 (SM-S942Q, Android 16, build BP4A.251205.006.S942QOPS1AZF2) with litertlm-android 0.16.1 and the `int8` file, side-loaded as above, one picture per run. The records are in [`results/2026-09-29-s26/`](results/2026-09-29-s26/). The last column compares the phone's letters with a run of the same five turns on a Mac with a new engine per chart.

| run | chart | bars | backend | load ms | answer ms, questions 1 to 5 | match the chart | equal to the Mac run |
|---|---|---|---|---|---|---|---|
| r2b_gpu_a | chart_00 | 3 | GPU | 46,589 | 990.5 / 289.9 / 288.9 / 409.1 / 268.6 | 5 of 5 | 5 of 5 |
| r3_take1 | chart_02 | 5 | GPU | 38,706 | 935.4 / 256.3 / 256.5 / 331.0 / 245.8 | 4 of 5 | 4 of 5 |
| r3_take2 | chart_01 | 4 | GPU | 39,477 | 903.2 / 247.7 / 251.2 / 336.2 / 241.1 | 5 of 5 | 5 of 5 |
| r2b_cpu_c | chart_02 | 5 | CPU | 10,497 | 2,113.2 / 1,015.1 / 709.5 / 659.6 / 561.5 | 5 of 5 | 5 of 5 |
| r2b_gpu_c | chart_02 | 5 | GPU | 38,831 | 1,143.5 / 317.4 / 305.0 / 434.8 / 291.5 | 4 of 5 | 4 of 5 |

The first question carries the picture: it took 0.9 to 1.1 s on the GPU and 2.1 s on the CPU. The text-only questions after it took 0.24 to 0.43 s on the GPU and 0.56 to 1.0 s on the CPU.

Creating the conversation took 4.8 to 5.3 s on the GPU and 2.8 s on the CPU. It happens before the first question is shown, so it is not in the answer times.

Loading took 38.7 to 39.5 s on the GPU (46.6 s the first time, which included hashing and importing the pushed file) and 10.5 s on the CPU.

The app's peak VmHWM was 3,347,124 to 4,271,352 kB with the GPU and 3,742,560 kB with the CPU. The phone's lowest MemAvailable during a run was 835,632 to 1,338,432 kB with the GPU and 5,971,500 kB with the CPU.

Each run started at thermal status 0. The status the app read before and after the questions was 2 in three GPU runs, 1 in the fourth, and 0 in the CPU run: the GPU load raises it.

One answer differed between the backends. On chart_02, asked which bar is the tallest, the GPU profile answered "the purple bar" (152 px) in both GPU runs; the CPU profile and the Mac run answered "the blue bar" (180 px), which is the tallest. The other four questions of chart_02 gave the same answers, and the PNG the model got had the same pixels in every run. The cause is not established.

The same request shape on a Mac (LiteRT-LM 0.16.1 Python API, a new engine per chart, CPU) matched the charts' data on 117 of the 120 questions of `assets/charts.json` (tallest 24/24, shortest 23/24, count 22/24, taller 24/24, far left 24/24).

One phone, one day; not a benchmark.
