# samples/ask

One picture, five questions, answered on the phone. The app draws a bar chart and asks a vision-language model, [`litert-community/decider-2b-vision-LiteRT`](https://huggingface.co/litert-community/decider-2b-vision-LiteRT) (`int8`, 3,171,081,088 bytes), five multiple-choice questions about it through LiteRT-LM. The picture is sent once. Each answer appears with its time and with whether it matches the chart.

## One conversation per loaded model

The picture goes into the first turn of a conversation, and every further question is a text-only turn of the same conversation. On this model family LiteRT-LM carries a conversation's state into the next conversation on the same loaded model ([google-ai-edge/LiteRT-LM#3165](https://github.com/google-ai-edge/LiteRT-LM/issues/3165)), so the app never opens a second conversation on a loaded model: a second picture closes the model and loads it again.

## The screen

Top to bottom: the state (LOADING, READY, ASKING, DONE) with the answer count and the last answer time; the chart; the question card; the score line; the model, variant, backend profile, phone, runtime and SDK versions.

The chart on screen is the PNG the model gets, decoded and scaled up by the largest whole factor that fits, with nearest-neighbour sampling. At 3x it is 768 px wide; the record's `chart_scale` says which factor the phone used. The white frame is drawn outside the picture, a dark gap away from it. Nothing but the chart is inside the picture.

The question card shows the question and its options. When the letter arrives, the chosen option is highlighted with the answer time on its right, and a mark appears under the options: "matches the chart" in green or "does not match the chart" in red. The score line reads `k of N answers match the chart`.

## One picture

1. Draw the chart at 256x256 (white background, a black baseline, 3 to 5 bars) and encode it as PNG.
2. `chat.createConversation(ConversationConfig())`, once.
3. Turn 1: `session.stream(Contents.of(Content.ImageBytes(png), Content.Text(turn1)), GenerationOptions(maxOutputTokens = 1))`. The text is the context, the question and the options, and ends in `Answer: (`.
4. Turns 2 to 5: `session.stream(Contents.of(Content.Text(turnK)), GenerationOptions(maxOutputTokens = 1))` on the same session. The text starts with the `)` that closes the previous answer, then the next question. It carries no context and no image.
5. `session.closeAndJoin()`.

The first non-whitespace character of each answer is the letter. Anything that is not the letter of an option counts as no answer.

The question shows for 800 ms before it is sent. The answer stays for 1,200 ms before the next question. The time on screen runs from calling `stream` to the first chunk that carries text; the pauses are not in it.

## The questions and the mark

The five questions, in the order asked: which bar is the tallest, which is the shortest, how many bars there are (options 2 to 6), whether one named bar is taller than another, and which bar is on the far left. `Questions.of(chart)` builds them, and their expected answers, from the chart's heights and colours alone.

The app drew the chart, so it knows each answer before it asks. The mark is the app checking the model against the data it drew itself. It is not a benchmark.

`assets/charts.json` holds 24 charts, 8 each with 3, 4 and 5 bars. `tools/make_charts_json.py` writes it from the chart generator's `cases.json` and checks every question against the chart before writing.

## Run it

The model file is not in the APK. The app loads the model at Hub commit `6c024e946bb8489f3faacb2514b0c61b355d1fdb` (`ModelRef(…, revision = …)`), the commit the descriptor in `assets` was generated from. The first Load downloads it from the Hub and checks its sha256. During development a copy pushed into the app's files directory is hashed and imported instead:

```sh
./gradlew :samples:ask:assembleRelease
adb install -r samples/ask/build/outputs/apk/release/ask-release.apk
adb push decider-2b-vision_int8.litertlm /sdcard/Android/data/io.github.johnrocky.hfmodels.samples.ask/files/
```

A normal start has a backend choice (auto, gpu, cpu) and Load, Ask, Stop. Each Ask takes the next chart; after the first, Ask loads the model again before it asks. Recording mode loads, waits `delay` seconds after READY, asks and stays on DONE. It shows over the lock screen with the system bars hidden and keeps the screen on:

```sh
adb shell am start -n io.github.johnrocky.hfmodels.samples.ask/.MainActivity \
    --ez autostart true --ei delay 3 --ei chart 0 --es backend gpu --ei pictures 1
adb logcat -s ask
```

`chart` picks the first chart (0 to 23). `pictures` is how many charts to ask about, one after another; each picture after the first closes the model and loads it again. `--es backend gpu` loads with `BackendPolicy.Require(BackendKind.GPU)`, `cpu` with `Require(BackendKind.CPU)`; without it `BackendPolicy.Auto` picks the descriptor's default profile (`gpu`).

## The record

After each picture (or Stop) the app writes `ask-result-<epoch s>.json` and `frames-<epoch s>/000.png` (the exact PNG bytes the model got) to `/sdcard/Android/data/io.github.johnrocky.hfmodels.samples.ask/files/`:

- run: `picture`, `pictures`, `completed_turns`, `stopped`, `failure`, `backend_policy`, `profile`, `variant`, `model` (repo@commit), `sdk_version`, `runtime_version`, `device` (`Build.MODEL`, `Build.DISPLAY`), `prompt_turn1`
- `chart`: `id`, `n`, `heights`, `colours`; `chart_scale` (screen pixels per chart pixel)
- `questions`, one object per turn: `turn`, `id`, `question`, `options`, `letter`, `raw_text`, `idx` (-1 when no option letter), `expected`, `ok`, `answer_ms`, `stream_ms` (to the end of the stream); turn 1 also has `conversation_ms`
- `matches`: how many answers match the chart
- times: `load_ms` (`load_downloaded` says whether it included a download), `close_ms` (closing the conversation), `total_s` (from opening the conversation to the last answer, pauses included)
- `thermal_before`, `thermal_after` (`PowerManager.currentThermalStatus`)

Logcat tag `ask`: `READY profile=… load_ms=…`, `PICTURE p/N chart=… bars=… scale=…`, one `TURN k id=… letter=… ok=… answer_ms=…` per question, `DONE json=<path>`.

## Tests

`./gradlew :samples:ask:testReleaseUnitTest` checks the parts that do not need a phone against reference files in `src/test/resources/fixtures`: three reference charts pixel for pixel, the questions of all 24 charts rebuilt from the chart alone, the turn texts byte for byte, and the answer parsing.

## Numbers

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
