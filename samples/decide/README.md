# samples/decide: three screens on typed decisions

One decision model, three uses, each with the measured milliseconds on the screen. Everything model-related goes through the SDK (`DecisionModels.kt`); `MainActivity.kt` drives the UI.

| screen | what one tap does | questions per tap |
|---|---|---|
| Voice gate | the platform `SpeechRecognizer` finalizes an utterance (or you type one) -> is it a question / a request for the assistant (choice) and is the assistant addressed (noul) -> `OPEN` or `closed`. Only an open gate would go on to a language model. | 2 |
| Clipboard | the clipboard text (or typed text) and a purpose from the spinner -> what the text holds (choice), whether it carries personal data (noul), which of the purpose's pieces it contains (one noul per piece) -> then the spans to paste from [GLiNER2.5-Small-LiteRT](https://huggingface.co/litert-community/GLiNER2.5-Small-LiteRT) (its published Kotlin host, copied under `gliner/`). | 2 + pieces |
| Query x text | one query against the passages typed one per line -> does the passage answer it (noul), how relevant (4-level score) -> ranked, with the milliseconds per passage. | 2 per passage |

## Model files

The decision model is [litert-community/laya-LiteRT](https://huggingface.co/litert-community/laya-LiteRT) (the `convaiinnovations/laya` checkpoints converted to LiteRT graphs, Apache-2.0), loaded by id: the first **Load** downloads the chosen variant's graph (0.6 to 1.7 GB), tokenizer and config files into the app's private storage and verifies their sha256; later loads are offline. The spinner lists the repo's variants; `ml_s256_fp32` (multilingual, window 256) is the default and the fastest measured on the GPU, the English variants take English states, the `_wfp16` variants (float16 weights, half the download) load on the CPU only (LiteRT 2.2.0's GPU accelerator does not compile them on the measured phone). A copy of a graph already on the computer can be pushed instead of downloaded:

```sh
adb push laya_ml_s256_fp32.tflite /sdcard/Android/data/io.github.johnrocky.hfmodels.samples.decide/files/     # hashed against the descriptor, then imported
```

The extraction model's ten files (about 410 MB) are fetched by `GlinerAssets` on the first **Get extractor** with a sha256 check against the repo's `SHA256SUMS`, or taken from a pushed copy (`adb push <files> /sdcard/Android/data/<applicationId>/files/`).

## Scripted use

`adb shell am start -n io.github.johnrocky.hfmodels.samples.decide/.MainActivity --es variant en_s256_fp32 --es backend gpu` preselects the spinners and presses Load. The three logs on the screens are plain `TextView`s (`voice_log`, `clip_log`, `rank_log`), readable with `uiautomator dump`.

## What the screens showed on 2026-09-21 (Galaxy S26 SM-S942Q, Android 16 BP4A.251205.006, LiteRT 2.2.0, GPU FP32)

- Voice gate, English variant (window 256): "What time does the pharmacy close today" -> OPEN (kind=question 0.92), "Set a timer for ten minutes" -> OPEN (request 0.94), "It was a long day at work and I am tired" -> closed (statement 0.86), "um so yeah anyway" -> closed; 2 questions in 250 ms. The same on the multilingual variant took 113 ms but sorted the pharmacy question as filler: that checkpoint ships no calibration and is weaker on this phrasing; pick the variant per language.
- Clipboard, "fill a shipping form", a 4-sentence order message: kind=order_or_tracking_number, pieces needed: address yes, order number yes; 6 questions in 757 ms (English) / 334 ms (multilingual); spans person / organization / location / product / date in 165 ms.
- Query x text, "When does the store close on Sundays?" against 5 passages: the two passages that answer it ranked first on both variants (0.80 / 0.70 English, 0.79 / 0.71 multilingual); the Japanese passage that also answers it scored 0.19 / 0.27; 5 passages x 2 questions in 1,259 ms (English) / 561 ms (multilingual).

Numbers from one phone on one afternoon, the model loaded and warm; not a benchmark, and not a claim about the model's accuracy beyond these inputs.
