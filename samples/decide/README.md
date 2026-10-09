# samples/decide: four screens on typed decisions

One decision model, four uses, each with the measured milliseconds on the screen. Everything model-related goes through the SDK (`DecisionModels.kt`); `MainActivity.kt` drives the first three screens, `InboxActivity.kt` the inbox, which has its own launcher icon, **hfmodels inbox**.

| screen | what one tap does | questions per tap |
|---|---|---|
| Voice gate | the platform `SpeechRecognizer` finalizes an utterance (or you type one) -> is it a question / a request for the assistant (choice) and is the assistant addressed (noul) -> `OPEN` or `closed`. Only an open gate would go on to a language model. | 2 |
| Clipboard | the clipboard text (or typed text) and a purpose from the spinner -> what the text holds (choice), whether it carries personal data (noul), which of the purpose's pieces it contains (one noul per piece) -> then the spans to paste from [GLiNER2.5-Small-LiteRT](https://huggingface.co/litert-community/GLiNER2.5-Small-LiteRT) (its published Kotlin host, copied under `gliner/`). | 2 + pieces |
| Query x text | one query against the passages typed one per line -> does the passage answer it (noul), how relevant (4-level score) -> ranked, with the milliseconds per passage. | 2 per passage |
| Inbox | **Sort** -> every unread text on the phone, or every line you pasted: what it needs from you (choice) -> the texts in five bins, the four that ask something of you counted as `Needs you today`. | 1 per text |

## Model files

The decision model is [litert-community/laya-LiteRT](https://huggingface.co/litert-community/laya-LiteRT) (the `convaiinnovations/laya` checkpoints converted to LiteRT graphs, Apache-2.0), loaded by id: the first **Load** downloads the chosen variant's graph (0.6 to 1.7 GB), tokenizer and config files into the app's private storage and verifies their sha256; later loads are offline. The spinner lists the repo's variants; `ml_s256_fp32` (multilingual, window 256) is the default and the fastest measured on the GPU, the English variants take English states, the `_wfp16` variants (float16 weights, half the download) load on the CPU only (LiteRT 2.2.0's GPU accelerator does not compile them on the measured phone). A copy of a graph already on the computer can be pushed instead of downloaded:

```sh
adb push laya_ml_s256_fp32.tflite /sdcard/Android/data/io.github.johnrocky.hfmodels.samples.decide/files/     # hashed against the descriptor, then imported
adb push laya_en_s256_fp32.tflite laya_act_head_fp32.tflite tokenizer.json tokenizer_config.json rl_agent_config.json \
    /sdcard/Android/data/io.github.johnrocky.hfmodels.samples.decide/files/     # the inbox's en_s256_fp32: all five of its files
```

The directory exists once the app has run; a push of several files into a missing one stops with "is not a directory".

The extraction model's ten files (about 410 MB) are fetched by `GlinerAssets` on the first **Get extractor** with a sha256 check against the repo's `SHA256SUMS`, or taken from a pushed copy (`adb push <files> /sdcard/Android/data/<applicationId>/files/`).

## NPU

The backend spinner's `npu` runs the decision model on the Qualcomm NPU (Hexagon HTP) of a Snapdragon phone. Qualcomm's runtime is not in this repository and has to be in the APK; the build extracts it at install:

```sh
tools/fetch_npu_libs.sh samples/decide/src/main/jniLibs/arm64-v8a v81    # the Hexagon version of the phone's SoC: SM8550 v73, SM8650 v75, SM8750 v79, SM8850 v81
```

Then pick `ml_s256_wfp16` or `en_s256_fp32` (the variants whose descriptor has an `npu` profile) and `npu`. Without the files the load stops with `NATIVE_MODULE_MISSING` and the names of the missing files. The first NPU load on a phone compiles the graph (76 s for `ml_s256_wfp16` and 65 s for `en_s256_fp32` in the device gate on a Galaxy S26); later loads read LiteRT's cache (0.9 s). This screen's NPU choice was not run on a phone; the device gate ran the same load path.

## Scripted use

`adb shell am start -n io.github.johnrocky.hfmodels.samples.decide/.MainActivity --es variant en_s256_fp32 --es backend gpu` preselects the spinners and presses Load (`--es backend npu` for the NPU). The three logs on the screens are plain `TextView`s (`voice_log`, `clip_log`, `rank_log`), readable with `uiautomator dump`.

## What the screens showed on 2026-09-21 (Galaxy S26 SM-S942Q, Android 16 BP4A.251205.006, LiteRT 2.2.0, GPU FP32)

- Voice gate, English variant (window 256): "What time does the pharmacy close today" -> OPEN (kind=question 0.92), "Set a timer for ten minutes" -> OPEN (request 0.94), "It was a long day at work and I am tired" -> closed (statement 0.86), "um so yeah anyway" -> closed; 2 questions in 250 ms. The same on the multilingual variant took 113 ms but sorted the pharmacy question as filler: that checkpoint ships no calibration and is weaker on this phrasing; pick the variant per language.
- Clipboard, "fill a shipping form", a 4-sentence order message: kind=order_or_tracking_number, pieces needed: address yes, order number yes; 6 questions in 757 ms (English) / 334 ms (multilingual); spans person / organization / location / product / date in 165 ms.
- Query x text, "When does the store close on Sundays?" against 5 passages: the two passages that answer it ranked first on both variants (0.80 / 0.70 English, 0.79 / 0.71 multilingual); the Japanese passage that also answers it scored 0.19 / 0.27; 5 passages x 2 questions in 1,259 ms (English) / 561 ms (multilingual).

Numbers from one phone on one afternoon, the model loaded and warm; not a benchmark, and not a claim about the model's accuracy beyond these inputs.

## Inbox: your unread texts sorted on one tap

Your unread texts sorted into five bins on one tap, on the phone: nothing to do, a code to type in, a delivery, a payment, an appointment. The last four are counted as `Needs you today`. On a Galaxy S26 (GPU, `en_s256_fp32`) on 2026-10-09 one text took a median of 130.6 ms (p90 136.8 ms), and 19 of the 21 labelled texts that are not scams landed in the bin of their label (the device check below).

1. `./gradlew :samples:decide:installDebug`
2. Open **hfmodels inbox**. It loads `litert-community/laya-LiteRT`, variant `en_s256_fp32`, by id: the first open downloads 1.69 GB and checks every file's sha256. To skip the download, push the five files first (Model files, above).
3. Tap **Allow** to list your unread texts, or **Paste texts instead** to list the clipboard's text, one text per line.
4. Tap **Sort**. Each text gets one question; the bins and the milliseconds fill in as it goes.
5. **Stop** ends a sort; **Sort again** and **Paste** start over.

Without READ_SMS the screen first shows a card that says what it reads (`content://sms/inbox` where `read = 0`, nothing else) and that nothing is sent; **Allow** then opens Android's dialog. **Paste** needs no permission: it takes the clipboard's text, one text per line, blank lines dropped, each shown as from `pasted`.

The model gets the text alone; the sender is only shown. The question is `What does this text message need from you?`, with five options, "nothing" first: `nothing you need to do` / `a code to type in` / `a package or a delivery` / `money you have to pay` / `an appointment or a booking`. The import and the sort are in `Inbox.kt`, which the device check calls too.

When something is missing, the screen says so and stays usable:

- READ_SMS refused: the card comes back with a line saying so, and Paste still works. After two refusals Android stops asking; SMS is then allowed in the system settings.
- No unread texts: `Nothing to sort` and a sentence; Paste still works.
- No model and no network: the load stops, and the SDK's code and reason take the line under the pill (`OFFLINE_CACHE_MISS` with `--es network offline`, `NETWORK_ERROR` when the Hub cannot be reached; `docs/errors.md`).
- An empty clipboard: a sentence instead of a list.
- Stop while sorting: the text being answered finishes, the answers so far stay, and Sort starts from the first text again.
- Leaving the screen closes the model.

The device check (`src/androidTest/.../InboxDeviceCheck.kt`) runs the same code in the app's process on the connected phone. Its steps: the load; the 24 labelled texts of `InboxPanel.kt` (it passes at 19 or more of the 21 that are not scams); five pasted lines; the unread count when the app holds READ_SMS (the count only: no text or sender goes to the log); a Stop half-way, then another sort; release and a second close; a variant that is not on the phone, offline; an empty paste. Each step prints one `RESULT` line, and the last line is `RESULT ok=…`:

```sh
./gradlew :samples:decide:assembleDebug :samples:decide:assembleDebugAndroidTest
adb install -r samples/decide/build/outputs/apk/debug/decide-debug.apk && adb install -r samples/decide/build/outputs/apk/androidTest/debug/decide-debug-androidTest.apk
adb shell am instrument -w -e class io.github.johnrocky.hfmodels.check.InboxDeviceCheck -e backend gpu -e network offline io.github.johnrocky.hfmodels.samples.decide.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s hfmodels-check | grep RESULT
```

`-e network offline` makes no request. It needs one earlier online load of the id on that phone, which saves the binding; on a phone without one, leave the argument out.

What the device check printed on 2026-10-09 (Galaxy S26 SM-S942Q, Android 16 BP4A.251205.006, LiteRT 2.2.0, GPU FP32, `en_s256_fp32` at laya-LiteRT 35bf9cd6, offline, thermal status 0 before and after): every step passed. The load took 5.6 s, 5.0 s of it the GPU compile. The 24 panel texts took a median of 130.6 ms per text (p90 136.8 ms, 3.2 s in all), and 19 of the 21 that are not scams landed as labelled. The two others were the same as on 2026-09-28: an overdue invoice went to "nothing you need to do", and a question about a viewing time to "an appointment or a booking". The five pasted lines went to code, delivery, payment, appointment and nothing. A Stop after three texts ended the sort 86 ms later, and the next sort ran. Release took 133 ms; a second close was no error, and a decide after it got `MODEL_CLOSED`. A variant the descriptor does not have got `VARIANT_NOT_FOUND`, and `ml_s256_fp32`, whose files were not on the phone, got `OFFLINE_CACHE_MISS`.

On the screen the same five texts, pasted and sorted, took a median of 127.3 ms each (0.66 s for the five). A refusal in Android's dialog brought the card back with its sentence. The check's log and the screen's result file are in `results/2026-10-09-s26/`. The graph was the one laya-LiteRT published on 2026-09-29 (`683c0374…`); the runs of 2026-09-28 below loaded the one before it (`60cd44a9…`). One phone, one evening; not a benchmark.

The question was chosen on 24 hand-labelled texts with invented names (`InboxPanel.kt`; a text waiting for your reply counts as "nothing", the three scam texts are left out): on a Galaxy S26, GPU, 19 of 21 as labelled with `en_s256_fp32` and 18 of 21 with `ml_s256_fp32` (2026-09-28). Two more questions were measured and left out. A red flag, `Does this message ask you to send money, a code, a password or to open a login link?` (noul), marked every scam but also 57 of the 286 other texts among 300 invented ones on the multilingual variant (one-time passcodes from a bank, "payment failed, update your card"). A sixth option, `a person waiting for your reply`, was never picked by the English variant, and a noul `Does the message ask you a question?` found none of the four panel texts that ask one on either variant.

On 2026-09-28 (Galaxy S26 SM-S942Q, Android 16 BP4A.251205.006, LiteRT 2.2.0, GPU FP32, airplane mode) `en_s256_fp32` sorted 120 invented texts of `probes/smsseed` (seed 7, seeded without the scam kind) in 16.6 s: median 132.5 ms per text, p90 135.0 ms, 7.2 texts per second; 57 with nothing to do, 14 codes, 17 deliveries, 21 payments, 11 appointments. The phone's thermal status went from none to light (skin 35.8 to 40.2 °C) and the time per text stayed between about 128 and 135 ms, one text taking 263 ms; in longer runs on the same phone it doubled for part of the run once the phone was warm (the GPU clock was not read, so the cause is not established).

Against the kind the generator wrote each text as (not a hand label; a text waiting for a reply counts as "nothing") the answer agreed on 109 of 120. Nine texts that need nothing went to "money you have to pay" (receipts, refunds, card payments approved) and one to "an appointment or a booking" (cinema tickets confirmed); one appointment went to "nothing".

Numbers from one phone on one day; not a benchmark, and not a claim about the model's accuracy beyond these texts. The result files are in `results/2026-09-27-s26/`.

## Demo recording

`DemoActivity` is a scripted run of the same API for a screen recording: seven utterances go through the voice gate one after another (only a question or a request opens it), then a question is ranked against five passages twice, then a card with the count and the mean milliseconds per question. Every decision is a real call on the loaded model and the milliseconds are the SDK's own timing; the utterances and passages are in `DemoActivity.kt`.

```sh
adb shell am start -n io.github.johnrocky.hfmodels.samples.decide/.DemoActivity --es variant en_s256_fp32 --es backend gpu   # then tap once; --ez autostart true skips the tap
adb shell screenrecord --time-limit 60 /sdcard/demo.mp4
```

On 2026-09-23 (Galaxy S26, GPU, `en_s256_fp32`) the run gave 34 questions at 129 ms each; all seven gate verdicts and both rankings came out as a reader would expect (the log lines under tag `demo`).

The inbox has three scripted starts for recordings and checks, not for everyday use:

```sh
adb shell am start -n io.github.johnrocky.hfmodels.samples.decide/.InboxActivity \
    --es variant en_s256_fp32 --es backend gpu --ez autostart true --ei delay 3    # Sort 3 s after READY; --ei limit 120: the newest 120 unread only
adb shell am start -n io.github.johnrocky.hfmodels.samples.decide/.InboxActivity \
    --es variant en_s256_fp32 --es backend gpu --ez panel true --ez autostart true  # the 24 labelled texts of InboxPanel.kt
adb shell am start -n io.github.johnrocky.hfmodels.samples.decide/.InboxActivity \
    --es variant en_s256_fp32 --es backend gpu --es paste "<texts, one per line>"   # as Paste, from the extra instead of the clipboard
```

Each is recording mode: it wakes the screen and shows over the lock screen (a phone asleep behind a secure lock keeps the app off the network and out of the foreground). Every DONE then writes the sorted texts with their answers to `inbox-result-<epoch s>.json` (`panel-result-<variant>.json`) in the app's external files dir, with the count, the total, the median and p90 milliseconds per text, every text's time, the bins, the thermal status before and after, the device and the build; the same lines go to logcat under tag `inbox`. A normal start does none of that. `--es network offline` loads without a request. `screenrecord` does not start while the display is off: start the activity first, then the recording.

Record invented texts, never a real inbox. `probes/smsseed` puts invented texts into the phone's SMS store and deletes them afterwards; it needs the SMS role (the phone's default SMS app) for the seconds of a seed or a clear, so it is not for everyday use (its README). Before a recording of seeded texts, check that the phone's own texts are read.
