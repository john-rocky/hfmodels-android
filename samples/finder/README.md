# samples/finder: one sentence sets five recipe filters

One screen. Type what you feel like eating, for example `Medium spicy vegan dinner without nuts, ready within an hour.`, and tap **Find**. A decision model on the phone answers five questions about the sentence, and the answers become five filter chips: **Meal**, **Diet**, **Time**, **Without** and **Spice**. The list of 60 made-up recipes below them narrows on the spot to the recipes the set filters keep.

An unset chip is a grey outline (`Meal: any`); a set chip is filled with its filter's color (`Meal: dinner`). Each recipe card shows its name and one line of attributes, `dinner · vegan · 40 min · medium · no nuts`, and every attribute that satisfies a set filter is drawn in that filter's color: the colored words say why the recipe is on the list.

The chips are ordinary filters. A tap on one opens a small menu of that filter's values, `any` (or `nothing`) among them. A pick sets the filter, and the list follows. A sentence sets all five at once; the menu sets one by hand or fixes a wrong one.

Under the input, **Try:** offers three sentences; a tap puts one in the input and runs it. Under the chips, one line counts what is left (`3 of 60 recipes`) and one line says what the model did: `5 decisions · 159 ms · GLiNER2.5-Decide s128 · NPU`, the milliseconds of the five decisions together and the backend the model ran on. The answers' probabilities are not on the screen; they are in the log and in the recording mode's result file.

Everything model-related goes through the SDK: `DecisionModels.kt` loads the model, `Finder.kt` holds the five questions and asks them, `Recipes.kt` holds the recipes and the filters, and `MainActivity.kt` drives the screen.

## The five questions

```kotlin
val questions = linkedMapOf(
    "meal" to Question.Choice("Which meal is the request for?", linkedMapOf(
        "any" to "no meal named", "breakfast" to "breakfast", "lunch" to "lunch", "dinner" to "dinner", "dessert" to "dessert")),
    "diet" to Question.Choice("Which diet must the recipes follow?", linkedMapOf(
        "any" to "no diet named", "vegetarian" to "vegetarian", "vegan" to "vegan")),
    "time" to Question.Choice("How much cooking time is allowed?", linkedMapOf(
        "any" to "no time limit named", "under_15" to "under 15 minutes", "under_30" to "under 30 minutes", "under_60" to "under an hour")),
    "exclude" to Question.Choice("Which ingredient must be left out?", linkedMapOf(
        "nothing" to "nothing to leave out", "nuts" to "no nuts", "dairy" to "no dairy", "gluten" to "no gluten")),
    "spice" to Question.Score("How spicy should the food be?", listOf("not mentioned", "mild", "medium", "hot")),
)
model.decide(sentence, questions)   // one call, one forward per question
```

These are the questions of the sieve that picked this feature, word for word: the unit test compares them with the sieve's file (`src/test/resources/questions_v1.json`). The `gliner2_decide` family asks each question in its own forward, as one gliner2 task with the instructions as its prompt and the descriptions as its labels. The descriptions are what the model reads; the keys come back.

A choice's answer is its key. The spice answer is the level with the highest probability (levels 0 to 3 are `any`, `mild`, `medium`, `hot`), not the score's expected level: level 0 means "not mentioned", so an average across the levels is not a level. No threshold is applied. The most likely key is the filter.

## What each filter keeps

- **Meal**: the recipes for that meal.
- **Diet**: `vegetarian` keeps the vegetarian and the vegan recipes; `vegan` keeps the vegan ones only.
- **Time**: `under 15 minutes`, `under 30 minutes` and `under an hour` keep the recipes ready in at most 15, 30 or 60 minutes.
- **Without**: the recipes that do not contain that ingredient (nuts, dairy or gluten).
- **Spice**: the recipes of that level (mild, medium or hot).

An unset filter (`any`, or `nothing` for Without) keeps every recipe. The code is `Filters.keeps` in `Recipes.kt`.

The recipes are `src/main/assets/recipes.json`: 60 made-up entries under common dish names. Each has a meal, a diet (vegan, vegetarian, meat or fish), its minutes, which of nuts, dairy and gluten it contains, and its spice level. The recording's two sentences and the three under Try each leave 3 recipes; the unit test requires 2 to 5.

## How well the questions work

Before this app was written, the five questions were scored on 40 made-up requests with hand-written answers, 200 answers in all (round 18 of the typed-decisions sieve; the requests and answers are `src/androidTest/assets/f1_filter.jsonl`). GLiNER2.5-Decide `s128_wfp16` on a Mac (the model card's Python host, LiteRT 2.1.6 on the CPU, 2026-10-04) gave the hand-written answer on 191 of the 200. On 31 of the 40 requests all five answers were right. Of the 123 answers that should have stayed unset, 4 set a filter. The Mac host's answers and probabilities are `src/androidTest/assets/gliner_f1_v1.jsonl`.

The nine misses, by kind:

| kind | misses | the requests |
|---|---:|---|
| minutes read wrong | 3 | "in 10 minutes" set no time limit; "less than half an hour" set under an hour; "20 minutes tops" set under 15 minutes |
| a negation missed | 2 | "Nothing spicy" and "not spicy" left Spice unset (mild was expected) |
| no dairy also read as vegan | 2 | "with no dairy" and "Dairy-free breakfast" added Diet: vegan |
| the words are there, the request is not | 2 | "I used to be vegan, but now I eat everything" set Diet: vegan; "My nut allergy test came back clear" set Without: nuts |

On the screen a miss is a wrong chip or a missing one, and the chip's menu sets the right value.

The recording's two sentences and the three under Try are requests of those 40 that the Mac host got right on all five questions (`Finder.SCRIPT` and `Finder.EXAMPLES`; the unit test checks this). They were chosen, not drawn: they show what works.

## Model and files

[litert-community/GLiNER2.5-Decide-LiteRT](https://huggingface.co/litert-community/GLiNER2.5-Decide-LiteRT), loaded by its id with no descriptor of the app's own: `ModelRef("litert-community/GLiNER2.5-Decide-LiteRT", variant = …)`. On the first load the SDK reads the repo's `hfmodels.json` at the head of its main branch (600fe62b) and binds the id to that commit; later loads make no request. The bundled catalog's copy of the descriptor (the same descriptor, pinned at 600fe62b) is used only when the branch head carries none. Without a network the first load cannot bind the id and stops with `OFFLINE_CACHE_MISS`; the screen then says that the first search downloads the model.

The app loads variant `s128_wfp16` (a 128-token window, float16 weights; Apache-2.0) on the GPU, with the CPU as the fallback; with Qualcomm's runtime in the app it loads `s128_npu_wfp16` on the NPU (NPU, below). The first search downloads three files, 0.93 GB in all (the graph, the token table and the tokenizer), into the app's private storage and verifies their sha256. A copy already on the computer can be pushed instead, once the app has started once (its first start makes the directory):

```sh
adb push gliner25_decide_s128_wfp16.tflite word_embeddings_fp16.bin tokenizer.json /sdcard/Android/data/io.github.johnrocky.hfmodels.samples.finder/files/   # hashed against the descriptor, then imported; delete the copies afterwards
```

A load runs one sentence through the five questions before the first search (`warmup_ms` in the log), so the first forward after a compile is not timed as the first search.

## Recording mode

```sh
adb shell input keyevent KEYCODE_WAKEUP
adb shell am start -n io.github.johnrocky.hfmodels.samples.finder/.MainActivity --ez autostart true --ei delay 2
adb shell screenrecord --time-limit 30 /sdcard/finder-take1.mp4     # once the display is on
```

The scripted start shows the screen over the lock screen with the display on, waits for the model, and shows all 60 recipes for `delay` seconds. Then it types two sentences into the input at 35 ms a character, with no keyboard on the screen. After each sentence it waits 0.3 s, presses Find, and leaves the result on the screen for 2.5 s:

1. `Medium spicy vegan dinner without nuts, ready within an hour.` sets all five filters.
2. `A vegan dessert without nuts.` sets Meal, Diet and Without.

At the end (DONE) it writes `finder-result-<epoch s>.json` to the app's external files dir. Per sentence the file holds the five answers, their probabilities, each question's milliseconds and the total, and the recipes left; then the thermal status before and after, the device, its build, the variant and the profile. The same numbers go to logcat under tag `finder`. A normal start does none of that. `--es backend npu`, `gpu` or `cpu` on the start that creates the screen fixes the backend (NPU, below).

## Device check

`src/androidTest/kotlin/io/github/johnrocky/hfmodels/check/FinderDeviceCheck.kt` runs on the connected phone, in the app's process. It loads the model and asks the 40 requests of the sieve through the app's own code (`Finder.ask`). Each of the 200 answers is compared with the hand-written answer and with the Mac host's. Then it checks that the recording's two sentences and the three under Try set the expected filters and leave 2 to 5 recipes, and that release returns.

```sh
./gradlew :samples:finder:assembleDebug :samples:finder:assembleDebugAndroidTest
adb install -r samples/finder/build/outputs/apk/debug/finder-debug.apk
adb install -r samples/finder/build/outputs/apk/androidTest/debug/finder-debug-androidTest.apk
adb shell am instrument -w -e class io.github.johnrocky.hfmodels.check.FinderDeviceCheck -e backend npu \
    io.github.johnrocky.hfmodels.samples.finder.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s hfmodels-check | grep RESULT
```

It prints one `RESULT` line per step under tag `hfmodels-check`, one `RESULT step=diff` line per answer that differs from the Mac host's, and last `RESULT ok=…`. The filter step passes when all 200 answers are the Mac host's, or, with every differing answer listed, when at least 180 of the 200 are the hand-written ones. `-e backend gpu` and `cpu` load `s128_wfp16` on that backend; without `backend` the check loads what the screen loads. `-e network offline` makes no request, so the id must have been bound by an earlier load. `connectedDebugAndroidTest` runs the check too, but uninstalls the app afterwards, and the imported model with it.

## NPU

The model can also run on the Qualcomm NPU (Hexagon HTP) of a Snapdragon phone. Qualcomm's runtime is not in this repository. The APK has to carry it, and the build extracts it at install:

```sh
tools/fetch_npu_libs.sh samples/finder/src/main/jniLibs/arm64-v8a v81    # the Hexagon version of the phone's SoC: SM8550 v73, SM8650 v75, SM8750 v79, SM8850 v81
```

With those files in the app, the screen loads variant `s128_npu_wfp16` on the NPU, and the latency line ends with `· NPU`. That variant is the s128 graph changed for float16 hardware. Its token table and tokenizer are the files of `s128_wfp16`; its graph is another 660 MB file, `gliner25_decide_s128_npu_wfp16.tflite`, downloaded or pushed like the others. The first NPU load compiles the graph on the phone, and later loads read LiteRT's cache.

If the NPU load fails with one of the codes in `DecisionModels.BACKEND_ERRORS`, the screen logs the code and the reason under tag `finder` and loads the same variant on its default profile: the GPU in FP16 with FP32 accumulation, with the CPU as the fallback. Both read the same three files, so nothing more is downloaded. Without the runtime the app loads `s128_wfp16` on the GPU in FP32. `--es backend npu`, `gpu` or `cpu` on the start that creates the screen fixes the backend with no fallback; `gpu` and `cpu` load `s128_wfp16`. A running screen keeps its model, so force-stop the app first.

## What it showed on the Galaxy S26 (2026-10-05)

Galaxy S26 SM-S942Q (SM8850), Android 16 BP4A.251205.006.S942QOPS1AZH9, LiteRT 2.2.0, SDK 0.1.3-SNAPSHOT, Qualcomm's runtime from `tools/fetch_npu_libs.sh` (QAIRT 2.47.0.260601, Hexagon v81). The id was bound to f6f6e9c9 and the files were pushed and imported by the SDK. The phone came warm from another job (thermal status 2, skin 41.0 °C): the device checks started at status 1, the recordings at status 0.

The device checks, offline, on the 40 requests of the sieve (200 answers):

| backend | answers equal to the Mac host's | hand-written answers matched | requests with all five right | filters set that should not be | ms per request (five questions), median / p90 | ms per question, median / p90 | thermal status |
|---|---|---|---|---|---|---|---|
| NPU, `s128_npu_wfp16` | 200/200 | 191/200 | 31/40 | 4/123 | 161.80 / 166.81 | 31.93 / 33.08 | 1 to 1 |
| GPU, `s128_wfp16` | 200/200 | 191/200 | 31/40 | 4/123 | 365.47 / 372.32 | 72.64 / 74.50 | 1 to 2 |

The recording mode, in airplane mode, on the NPU (`s128_npu_wfp16`):

| run | sentence 1: filters as expected, recipes left, ms | sentence 2: filters as expected, recipes left, ms | thermal status |
|---|---|---|---|
| take 1 | 5/5, 3, 162 | 5/5, 3, 163 | 0 to 0 |
| take 2 | 5/5, 3, 159 | 5/5, 3, 159 | 0 to 0 |
| take 3 | 5/5, 3, 159 | 5/5, 3, 160 | 0 to 0 |

The milliseconds are the screen's: the SDK's wall clock for one `decide` call with the five questions, rounded (162.28 and 162.99, 159.36 and 159.48, 159.14 and 160.17 in the result files).

On the NPU every answer was the Mac host's, with the probabilities within 0.0055 of its; on the GPU within 2.3e-6. The nine answers that differ from the hand-written ones are the Mac host's nine misses above. LiteRT logged the whole graph on the NPU (`Replacing 1 out of 1 node(s) with delegate (DispatchDelegate)`, 1874 of 1874 ops in one partition) and the GPU check's graph on the GPU (1780 of 1780 nodes). The app's first load bound the id through the Hub, imported the three pushed files and compiled the graph for the NPU in 20.3 s, outside every table. The device check's load read LiteRT's cache in 0.8 s and ran the warm-up sentence in 183 ms. The recording's two sentences and the three under Try set the expected filters and left 3 recipes each, on both backends. Release took 82 ms (NPU) and 158 ms (GPU).

Numbers from one phone on one night, the model loaded and warm; not a benchmark. The logs and the result files are in `results/2026-10-05-s26/`.

## Limits

- English only: the descriptor declares `en`, and the sieve was English.
- A sentence shares the 128-token window with one question at a time. The questions take 23 to 32 tokens, so a sentence of more than 96 tokens is refused, never cut, and the line asks for a shorter one. The recording's first sentence takes 12.
- Each sentence sets all five filters from scratch: the next sentence does not add to the last one, and it replaces what the menus set.
- The recipes are made up to show the filters. The list is not a recipe database.
