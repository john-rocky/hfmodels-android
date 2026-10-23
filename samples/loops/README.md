# samples/loops: one question, one loop or two

Type a question, or pick one of eight examples, and two files of the same model answer it side by side, each with its time. The model is NVIDIA's Dory1.0-4B in LiteRT-LM form, [`litert-community/NVIDIA-Nemotron-Labs-Dory1.0-4B`](https://huggingface.co/litert-community/NVIDIA-Nemotron-Labs-Dory1.0-4B) at Hub commit `ff0f6e32`: a looped model, whose shared block of layers runs more than once per token. The repo's `int4` file runs the block twice per token, NVIDIA's default, and its `int4_1loop` file once, with the same int4 weights byte for byte. NVIDIA's card calls this "Elastic Inference Compute": changing how many times the shared block is applied trades compute for accuracy, with no retraining and no model swap.

The model card measured the trade. On one Galaxy S26's GPU (LiteRT-LM v0.18.0, 2026-10-23, 1,024 prompt and 256 generated tokens, the two files alternated) one loop decoded 17.32 tokens per second and two loops 11.08. On GSM8K (100 test questions, Mac GPU, thinking off) one loop solved 86 and two loops 90. This app shows the same trade on your own question, on your phone.

## Run it

1. `./gradlew :samples:loops:installDebug`, then open "hfmodels loops" once (it creates the folder the next line pushes into).
2. Optional, instead of downloading the two files (2.40 GB each) on the phone at their first run:
   ```sh
   adb push NVIDIA-Nemotron-Labs-Dory1.0-4B_int4_1loop.litertlm NVIDIA-Nemotron-Labs-Dory1.0-4B_int4.litertlm \
       /sdcard/Android/data/io.github.johnrocky.hfmodels.samples.loops/files/
   ```
   Each copy is hashed against the catalog's sha256 at its file's first run and copied into the app's store; delete the pushed copies afterwards.
3. Type a question or pick an example, choose **1 loop**, **2 loops** or **Both**, and **Run**.

The model files are not in the APK. The app loads `ModelRef("litert-community/NVIDIA-Nemotron-Labs-Dory1.0-4B", revision = "ff0f6e32…", variant = "int4_1loop" | "int4")`; the SDK's bundled catalog has the entry for that commit, so the repo needs no `hfmodels.json`.

## The screen

Top to bottom: the state (READY, LOADING, ANSWERING, DONE, STOPPED, FAILED) and what is happening; the question box; **Examples**; **1 loop**, **2 loops** or **Both**, and **Think**; **Download missing files**, **Run** and **Stop**; the two columns, 1 loop on the left; the model, its commit, the variant and backend that loaded, the phone, the runtime and SDK versions.

Each column shows its file on one line (variant, size, and whether it is on the phone, a pushed copy not yet checked, or still to download), what the file is doing (loading, downloading with its MB, answering, or a failure's whole sentence), the answer as it streams, and under it:

- the whole answer's time and the tokens per second, e.g. `9.6 s · 17.1 tok/s`;
- `first token 0.42 s · 160 tokens`;
- the load the answer waited for, or `already loaded`;
- for a GSM8K example, the answer's last `#### <number>` line against GSM8K's answer (green when equal).

The model writes Markdown and, when it works a sum, LaTeX. The column shows both as text (`AnswerText.kt`): bold in bold, bullets as `•`, and the math in plain symbols, so `\[ H = \frac{88{,}000}{11} \]` reads `H = 88,000/11` and `$75\text{ g} \times 6$` reads `75 g × 6`. A `$` stays a dollar sign unless it pairs with another around math (`$5 per week × 8 weeks = $40` is left as written). The run's record keeps what the model wrote.

The times are the stream's own clock: the first token from calling `stream` to the first chunk (the prompt's prefill and the first token); the rate as (chunks − 1) / (time from the first chunk to the last), counting each streamed chunk as a token, as `samples/chat` does; the whole answer from calling `stream` to its end. The load is not in them.

## How a run goes

- **Both** runs the 1 loop file first, then the 2 loops file. One `HfModels` holds one model, so `Loops.kt` closes one file's engine before it loads the other; the engine of the file that ran last stays loaded for the next run.
- Every question gets a conversation of its own, with no system prompt (`ConversationConfig()`), and the conversation is closed after the answer. The SDK sets no sampler; the runtime's default applies.
- **Think** is off by default: the chat template's no-think form (the prompt ends with `<think></think>`), the form of the model card's GSM8K run. With **Think** on, the model reasons first; its reasoning streams in the thought channel, not in the answer, and the column shows its length.
- The bundle holds 2,048 tokens, question and answer together.
- An answer that ends in a word comes without its final period, from both files (`You will have $120 after 8 weeks`); one that ends in a bold value keeps it (`You will arrive at **9:15**.`). The files' LiteRT-LM metadata lists 16 stop sequences, 13 of them a single mark (`.` `,` `?` `!` `:` `;` `"` `'` `*` `)` `]` `}` or a space) before `<|im_end|>` and a newline, and the runtime this SDK pins, LiteRT-LM 0.16.1, holds a bare period back while it could start one of them and drops it when `<|im_end|>` alone then ends the answer (`**.` is one token that starts none, so it stays). LiteRT-LM 0.17.0 and later keep the held text when a stop ends the answer, so the same files keep the period there.

`Loops.kt` is the whole model side (load, answer, release, the sentences below); `MainActivity.kt` drives the screen; the device check drives `Loops.kt` too.

## The examples

| id | question | from | known answer |
|---|---|---|---|
| `dates` | Extract the dates from: The meeting moved from May 3 to May 17. | the model card's usage example | |
| `recipe` | A recipe for 4 people needs 300 g of flour. How much flour do I need for 6 people? | written for this app | |
| `arrive` | I leave home at 8:40 and the trip takes 35 minutes. What time do I arrive? | written for this app | |
| `reply` | Write a two-sentence reply to a friend who invited me to dinner on Friday: I can come, and I ask what I should bring. | written for this app | |
| `gsm8k-69` | Bailey starts with a certain amount of money. … how much money did Bailey start with? | GSM8K test question 69 | 60 |
| `gsm8k-54` | The Doubtfire sisters are driving home with 7 kittens … How many kittens does the Doubtfire family now have? | GSM8K test question 54 | 40 |
| `gsm8k-98` | Cars have lined up on the motorway. … how many cars drove through the traffic jam in the first 15 minutes? | GSM8K test question 98 | 5 |
| `gsm8k-88` | Marilyn's first record sold 10 times as many copies as Harald's. … how many copies did Harald sell? | GSM8K test question 88 | 8000 |

The GSM8K questions ([openai/gsm8k](https://huggingface.co/datasets/openai/gsm8k), MIT) are word for word, each followed by the instruction the model card's GSM8K run used (`Examples.GSM8K_INSTRUCTION`: solve step by step, then the final answer on its own line as `#### <number>`). They are four of the ten questions, of the card's 100, that the two files answered differently in that run (Mac GPU, thinking off): on 69, 54 and 98 two loops gave the known answer and one loop did not (25, 55 and 2.5), and on 88 one loop did and two loops did not (5,867). The other six: 7, 37, 75 and 95 for two loops; 4 and 12 for one loop. What the phone answered is under Numbers.

## When it does not answer

| what happens | what the column says |
|---|---|
| Run with an empty question box | Type a question first, or pick an example. (in the state line; nothing runs) |
| a file not on the phone, with **Download missing files** off | The 2 loops file is not on this phone and downloads are off. Tick Download missing files (2.40 GB from Hugging Face), or push the file with adb (README). |
| **Stop** during a download | Download stopped at *n* of 2,292 MB. The part stays on the phone; the next run continues from it. |
| the next run after it | Downloading *m* / 2,292 MB (continued from *n* MB) |
| the network fails during a download (three attempts) | The download stopped: *the SDK's reason*. The part stays on the phone; the next run continues from it. |
| not enough free space for a file | Not enough free space for the 2 loops file: *the SDK's reason* |
| **Stop** during an answer | Stopped after *n* tokens. (the answer so far stays in the column) |
| a question longer than the bundle's window | The question is too long: the model reads at most 2,048 tokens, question and answer together. |
| anything else | the SDK's code and reason ([`docs/errors.md`](../../docs/errors.md)) |

A file that does not load ends that file's part of the run and the other file still runs; **Stop** ends the run.

## Recording mode

```sh
adb shell am start -n io.github.johnrocky.hfmodels.samples.loops/.MainActivity \
    --ez autostart true --ei delay 3 --es examples all --es run both --es order 12 --ez think false
adb logcat -s loops
```

Recording mode runs one spec on its own, over the lock screen with the screen kept on and the system bars hidden. The extras: `question` (your text; `adb shell` passes ASCII), `example` (one id), `examples` (`all`, or ids separated by commas), `run` (`both`, `1` or `2`), `order` (`12` or `21`: which file goes first), `think`, `network` (`any`, or `offline` for no request at all), `stop_after_mb` (the app presses its own Stop once that many MB of a download are on the phone), `delay` (seconds before the run). With several questions, every question runs on the first file, then every question on the second (one load per file). `--ez record true` alone shows the screen over the lock screen and keeps it on, for a run driven by taps; the views carry ids (`question`, `run_both`, `run`, `stop`, `answer_1`, `timing_2`, …) that a `uiautomator dump` names.

Every run, from Run or from the harness, writes `loops-run-<epoch s>.json` to `/sdcard/Android/data/io.github.johnrocky.hfmodels.samples.loops/files/`: per answer the file, the question, the text (and `text_shown`, as the column showed it), the reasoning's length, the chunks, `first_token_ms`, `first_text_ms` (the first chunk of answer text, after any reasoning), `tok_s`, `total_ms`, the load it waited for, the backend, the thermal status before and after and, for a GSM8K example, `final_value` and `mark`; per load its time, profile, backend and commit; per stop or failure the column's sentence and the download's bytes; the device and the versions. Logcat tag `loops`: `FILES …`, `RUN …`, one `ANSWER loop=… q=… first_token_ms=… tok_s=… total_ms=… chunks=… mark=… text=…` per answer, `STOPPED …` / `FAILED …` with the sentence, `DONE … json=<path>`.

## The device check

[`src/androidTest/kotlin/io/github/johnrocky/hfmodels/check/LoopsDeviceCheck.kt`](src/androidTest/kotlin/io/github/johnrocky/hfmodels/check/LoopsDeviceCheck.kt) runs `Loops.kt` in the app's process and prints one `RESULT` line per step:

```sh
./gradlew :samples:loops:assembleDebug :samples:loops:assembleDebugAndroidTest
adb install -r samples/loops/build/outputs/apk/debug/loops-debug.apk
adb install -r samples/loops/build/outputs/apk/androidTest/debug/loops-debug-androidTest.apk
adb shell am instrument -w -e class io.github.johnrocky.hfmodels.check.LoopsDeviceCheck io.github.johnrocky.hfmodels.samples.loops.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s hfmodels-check | grep RESULT
```

- `files-1`, `files-2`: where each file is before the check (push both first, or the first loads download them).
- `load-1`, `answer-1`: the 1 loop file loads (`int4_1loop`, the GPU) and answers "What is 17 + 25? Answer briefly." with 42, thinking off, no thought marker in the text.
- `think-1`: the same question with Think on: the reasoning arrives outside the text, the answer still has 42.
- `answer-2`: the 2 loops file loads after the 1 loop engine is closed, and answers with 42.
- `fresh-2`: the same question in a new conversation on the loaded 2 loops engine gives the same text.
- `stop`: a long answer (counting to 400) is stopped after five tokens; the column's sentence is `Stopped after …`, and the next question gets 42.
- `release`: closing returns within 10 s, and closing again does nothing.
- `empty-question`: the sentence for an empty box.
- `missing-file`, `download-stop`, `download-continue`, `download-removed`: the repo's `int8` file, which the check never pushes, stands in for a file that is not on the phone: with downloads off the load ends in `OFFLINE_CACHE_MISS` and the column's sentence; with downloads on the load is stopped after 16 MB (the sentence), run again (it continues from the part kept) and stopped after 32 MB, and the part is removed. `-e download false` skips the download steps.

The last line is `RESULT ok=true …` when every step passed.

## Tests

`./gradlew :samples:loops:testDebugUnitTest` checks what needs no phone: the times from the chunks' arrival, the `####` line and its number against a known answer, the run a launch asks for, the shipped examples (unique ids, the GSM8K instruction, ASCII for `adb`), and the answers as a column shows them, while they stream and at the end (the phone's answers under Numbers and lines of the model card's GSM8K run, byte for byte).

## Numbers

### 2026-10-23: Galaxy S26

Measured on one Galaxy S26 (SM-S942Q, Android 16, build BP4A.251205.006.S942QOPS1AZH9) with litertlm-android 0.16.1 and this sample built at commit edc8fbf, both files pushed with adb (the SDK checked their sha256 and imported them). The records are in [`results/2026-10-23-s26/`](results/2026-10-23-s26/).

The eight examples ran twice in recording mode, thinking off: once with the 1 loop file first and once with the 2 loops file first, after the phone had cooled back to thermal status 0. Every answer's text was the same in both runs. Each file's times below are from the run in which it went first, from thermal status 0 (one loop stayed at 0 over its eight answers, two loops reached 2); the records hold the other run's.

| example | 1 loop: answer | s | tok/s | 2 loops: answer | s | tok/s |
|---|---|---|---|---|---|---|
| `dates` | May 3, May 17 | 1.1 | 10.9 | May 3 / May 17 (on two lines) | 1.6 | 9.3 |
| `recipe` | 450 g (300 g × 1.5) | 4.1 | 15.2 | 450 g (75 g per person × 6) | 10.5 | 9.7 |
| `arrive` | 9:15 | 1.0 | 17.3 | 10:15 | 2.2 | 8.1 |
| `reply` | I can't wait to see you! What should I bring | 1.1 | 17.3 | I'd love to come! What should I bring | 2.4 | 7.6 |
| `gsm8k-69` | `#### 60` = GSM8K's | 7.9 | 16.2 | `#### 60` = GSM8K's | 15.6 | 7.4 |
| `gsm8k-54` | `#### 55` (GSM8K's is 40) | 19.2 | 16.5 | `#### 40` = GSM8K's | 28.0 | 10.9 |
| `gsm8k-98` | `#### 5` = GSM8K's | 19.7 | 15.0 | `#### 5` = GSM8K's | 23.4 | 10.8 |
| `gsm8k-88` | `#### 8000` = GSM8K's | 8.3 | 14.2 | `#### 8000` = GSM8K's | 19.1 | 10.2 |

The median of the eight rates is 15.68 tok/s for one loop and 9.48 for two: 0.905 and 0.856 of the model card's 17.32 and 11.08, a ratio of 1.65 against the card's 1.56. In the other run, where each file went second, the medians were 14.82 and 9.59. The first token came after 0.45 to 0.89 s with one loop and 0.71 to 2.30 s with two. In these runs a load took 12.9 to 20.4 s, the first load of a pushed copy included.

The rates count streamed chunks as tokens. On these answers the model's tokenizer gives 1.09 to 1.60 times as many tokens as chunks for the same text (median 1.11; `May 3, May 17` is 8 tokens and came in 5 chunks), so counted in tokens the medians above are 18.6 and 11.2 tok/s.

On the phone one loop gave GSM8K's answer on 69, 98 and 88, two of which (69 and 98) it had missed in the model card's Mac run; two loops gave it on all four, 88 included, which it had missed on the Mac. Of the everyday questions, two loops answered the arrival time wrong (10:15 for 8:40 plus 35 minutes) and one loop right. Greedy answers to single questions show where the two files part, not which one is better; the card's 100 GSM8K questions are that measure (86 for one loop, 90 for two).

Your own question, typed into the box through `adb shell input text` right after these runs (thermal status 3): "A book has 380 pages. If I read 25 pages a day, how many days do I need to finish it?" One loop: 15.2 days, so 16 full days (6.3 s, 15.9 tok/s, first token 0.79 s). Two loops: 15.2 days, so 16 days (9.6 s, 11.1 tok/s, first token 1.01 s).

The failure paths, through the app with only the 1 loop file on the phone:

- downloads off (`--es network offline`): the 1 loop column answered (7.3 s); the 2 loops column said "The 2 loops file is not on this phone and downloads are off. Tick Download missing files (2.40 GB from Hugging Face), or push the file with adb (README)."
- downloads on, the app's Stop at 100 MB: the 2 loops file came from the Hub at about 19 MB/s, then "Download stopped at 100 of 2,292 MB. The part stays on the phone; the next run continues from it."
- the next run: the SDK continued from the part (`download … resume from 105257839` in its log) until the Stop at 160 MB, with the same sentence.

In each the app stayed up and showed the sentence in the column, and Run was available again (the state line read DONE after the first, STOPPED after the other two). The device check passed every step (`results/2026-10-23-s26/check.log`): both files on the GPU, 42 with Think off and on (688 characters of reasoning first), the same text from a new conversation, `Stopped after 5 tokens.` with `1, 2, 3` kept, release in 0.9 s, and the int8 stand-in's sentences, its continued download (from 17,503,495 bytes) and its removal.

One phone, one day; not a benchmark.
