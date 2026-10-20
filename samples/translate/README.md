# samples/translate: French or Spanish speech to an English voice, English speech to French or Spanish text, on the phone

With no network and no translation account, someone who speaks only English can be spoken to in French or Spanish: the phone says it in English seconds after they stop, and shows their English answer in French or Spanish, in large type, for the other person to read.

**They speak** at the top chooses the other person's language: **Spanish** (the default) or **French**. Each person has a button: **Parlez français** (or **Habla español**) for them, **Speak English** for you. A tap opens the microphone for one utterance; with no speech in 8 s it ends as "Nothing heard". The turn starts once the voice has stopped for 300 ms (the early cut) and stands when the silence reaches 800 ms; nothing is shown or said before that, and voice coming back first cancels that start while the utterance goes on. French or Spanish becomes an English voice; English becomes French or Spanish text. The screen shows the translation, what was heard, and the seconds from the end of speech to the first English sound (or to the whole French or Spanish text), with their parts.

## Measured on a Galaxy S26 (2026-10-20)

Galaxy S26 (SM-S942Q), Android 16, build BP4A.251205.006.S942QOPS1AZH9, airplane mode, the debug build of commit 86c26d2, 2026-10-20. The speech is macOS voices (French: Amélie, English: Samantha; Spanish: Flo, English: Eddy) played into the app as files: by `--es play` in real time, or through the endpointer at once by the device check. The app on the screen and the device check (instrumentation) are two processes, and their numbers are not compared with each other. Next to each run are the phone's CPU frequency caps (`scaling_max_freq` of its two CPU policies, policy0 and policy6), which were not the same from run to run.

Later that day the same phone, also in airplane mode, ran the debug build of commit add7587, in which a turn that heard nothing ends on "Nothing heard" instead of going back to Ready: the load with no model file (`OFFLINE_CACHE_MISS`), the Model files steps below from `adb shell am start` on (with the files `hf download` had fetched on the Mac that afternoon), f05 with `--ef floor_rms 0.5` (the screen said "Nothing heard"), f05 with the default levels (the seconds line and "Ready"), one tap on Parlez français with no speech ("Nothing heard" after 8 s) and the French check (`RESULT ok=true`: the 20 turns and the 12 other steps passed; the 3.5 s target is not one of its steps). The numbers in this section are from the build of 86c26d2.

The app on the screen, French speech to the first English sound:

| what | seconds | CPU caps |
|---|---|---|
| one sentence (f05 of `fixtures/fr`), the second of three takes: "L'hôtel Bellevue est à cinq minutes à pied, juste à côté de la pharmacie." | 2.9 s: 300 ms end of speech + 1.6 s hearing + 722 ms translating + 251 ms voice | 3.63 / 4.74 GHz |
| the five French sentences of `fixtures/fr`, twice | median 3,491.5 ms | 2.90 / 2.88 GHz before, 2.75 / 2.67 GHz after |

f05 was played three times: 4.4 s (the first turn after a start; hearing 3.0 s), 2.9 s (the row) and 2.7 s; CPU caps 3.63 / 4.74 GHz before the first two, 3.63 / 4.61 GHz before the third.

Over those ten turns, the parts (medians, counted from the early cut): hearing done at 2,049.5 ms, the translation's first token at 2,361.5 ms, the first piece to the speaker at 2,838.5 ms, its first sound at 3,191.5 ms. The first turn after a start spent longer hearing (3.1 s for f01, against 1.8 to 2.6 s for the other nine).

The French check (the device check on `fixtures/fr`, instrumentation), the same ten sentences: median 3,785 ms, above 3.5 s, the target set before the runs. Its CPU caps were not read right before it: 3.63 / 4.61 GHz four minutes before, 2.00 / 1.98 GHz right after. Its parts, medians from the early cut: hearing done at 2,345 ms (within it the audio encoder with the prefill and the first step 1,146 ms, the decoding 1,148 ms), the translation's first token at 2,678 ms, the first piece to the speaker at 3,108 ms, its first sound at 3,485 ms. English to French text: median 3,759 ms.

- Hearing (the French check): character error rate 2.2 % in French (8 of 363 characters), 0 % in English (0 of 333), with case, punctuation, symbols and spaces ignored and digits read as words.
- Translations (the French check): 9 of the 10 keep their numbers, names, negations and times. The miss: "platform" became "plateforme" (quai or voie expected).
- The other steps (the French check): all 12 passed: the load, a model file the phone does not have (`OFFLINE_CACHE_MISS` and the screen's words), silence and quiet noise (nothing heard), the input gate on noise (an utterance needs 250 ms at or above the endpointer's start level and an RMS at or above `floor_rms` to be heard; the noise had 0 ms above the start level), 35 s of speech (three parts at the 15 s window), Spanish into both French buttons, Stop in the middle of a turn and the next turn, a 500 ms pause inside a sentence, memory, release.
- Memory (the French check): peak resident set (VmHWM) 231,152 kB before the load, 1,951,000 kB after it, 4,512,864 kB after the 20 turns; `dumpsys meminfo` TOTAL PSS up to 7,164,087 kB.
- Load (the app on the screen; CPU caps 3.63 / 4.74 GHz, read 2.6 minutes before): 18.7 s (`load_ms` 18,650) for the first load, which imports the pushed copies and writes the runtimes' caches; 5.5 s (5,509 ms) for a later one: Qwen3-ASR 0.9 s, Hy-MT2 on the GPU 4.0 s with its output check, Kitten 0.6 s; peak resident set 1,912,588 kB after it.
- Storage: 4,519,030 kB for the model files in the app's store and 2,938,612 kB of runtime caches (Qwen3-ASR's two CPU weight caches, 1,729,166,056 and 1,270,175,264 bytes, and Hy-MT2's GPU program cache, 6,906,224 bytes); Hy-MT2 on the CPU (`--es mt_backend cpu`) writes a CPU weight cache of its own, 1,799,900,824 bytes.
- Spanish (the device check on `fixtures/es`; CPU caps 3.19 / 3.13 GHz at its start, 2.11 / 2.11 GHz right after): end of speech to the first English sound, median 3,713.5 ms; English to Spanish text, median 3,760 ms. Character error rate 4.0 % in Spanish (13 of 321), 0.6 % in English (2 of 333). 8 of the 10 translations keep their numbers, names, negations and times: "23 euros" was heard without the 23, and "Atocha station" as "a Taché station"; "next to the bank" also became "next to the park". All 12 other steps passed, with French into both Spanish buttons.

Speech longer than 15 s is cut at 15 s (the speech model's window), and each part is a turn. Such a turn's seconds count from the 15 s mark plus the endpointer's 800 ms, not from when the person stopped: 5.2 s on the screen for the first 15 s of a 35 s French recording (the app on the screen; CPU caps not read right before it).

## Three models

| role | id @ commit | file | size | where it runs | license |
|---|---|---|---|---|---|
| hearing, both languages | [`litert-community/Qwen3-ASR-1.7B`](https://huggingface.co/litert-community/Qwen3-ASR-1.7B) @ 184ec0f1 | `Qwen3-ASR-1.7B-15s.litertlm` | 2.70 GB | CPU, LiteRT-LM 0.16.1 (the language model and the audio encoder, 4 threads each) | Apache-2.0 (from `Qwen/Qwen3-ASR-1.7B-hf`) |
| translation | [`litert-community/Hy-MT2-1.8B`](https://huggingface.co/litert-community/Hy-MT2-1.8B) @ 0d2c5681 | `Hy-MT2-1.8B_int8.litertlm` | 1.83 GB | GPU after an output check at load, else CPU (LiteRT-LM 0.16.1 through `Tasks.Chat`) | Apache-2.0 (from `tencent/Hy-MT2-1.8B`) |
| English voice | [`litert-community/kitten-tts-nano-0.8`](https://huggingface.co/litert-community/kitten-tts-nano-0.8) @ 2c5b198f | `fp32`: 8 files | 94 MB | CPU, LiteRT 2.2.0 (`Speak`) | Apache-2.0 (from `KittenML/kitten-tts-nano-0.8-fp32`) |

- **Qwen3-ASR-1.7B** in its 15 s file: one message holds one utterance of up to 15 s, and the button fixes the language (`language French<asr_text>` in the same message as the audio, the model card's form; the answer is then the transcript). The repo has no `hfmodels.json`: the sample passes a descriptor of its own (`src/main/assets/descriptors/`, `LoadOptions.descriptorJson`) with its own handler (`Qwen3Asr.kt`), so the SDK still checks the sha256, imports a pushed copy and loads offline. hfmodels' `Tasks.Chat` takes no audio in this release, so the handler builds LiteRT-LM's `Engine` itself, on the runtime the SDK links. The first load writes the CPU weight caches into the app's cache dir.
- **Hy-MT2-1.8B** (a translation model, not a chat model) by id from the SDK's bundled catalog, which pins it at 0d2c5681 for LiteRT-LM 0.16.1. One request, one new conversation with the model card's prompt (`Translate the following text into English. Note that you should **only output the translated result without any additional explanation**:`, an empty line, the text): a second message in the same conversation fails (LiteRT-LM #3166). Greedy. On the GPU, two fixed Spanish sentences are translated at load and must come back as English about a station and a thank-you; otherwise the model loads on the CPU, and the failure is kept for this phone build, runtime and model. On the Galaxy S26 the check passed at every GPU load of the runs above (`gpu_check="pass"`); a load asked for on the CPU (`--es mt_backend cpu`) skips it.
- **Kitten TTS nano 0.8** says the English piece by piece while the rest is still being translated: the first piece ends at the first comma, semicolon or colon after four words, the next ones at sentence ends. Before that the English is rewritten into what its front-end can say (`EnglishSpeech.kt`: "I’m" -> "I'm", "3:15 p.m." -> "three fifteen PM", "€23.50" -> "23 euros 50", "21st", "Mr.", accents). There is no French or Spanish voice in this sample: Kitten speaks English only, so English to French or Spanish is shown as text.

## Build and install

```sh
./gradlew :samples:translate:assembleDebug
adb install -r samples/translate/build/outputs/apk/debug/translate-debug.apk
adb shell pm grant io.github.johnrocky.hfmodels.samples.translate android.permission.RECORD_AUDIO
```

## Model files

The app loads the three models from its own storage (`files/hfmodels`). Before it downloads anything, a load looks in the app's external files dir for a copy of each file with the right name and size, and hashes and imports it instead (`HfModels.download` → `sideLoadAll`, with or without a network); `adb logcat -s hfmodels` prints `side-loaded <file> from <path> (sha256 verified)` for each. On the Galaxy S26 this ran offline, in airplane mode. Online, the first load downloads the files it has no copy of (4.62 GB in all), on any network, mobile data included (`NetworkPolicy.Any`). That path has not run on a phone with this sample: the Galaxy S26 here had no Wi-Fi. Offline with no copy, the load stops with `OFFLINE_CACHE_MISS` and the screen says so. The copies have to belong to the app, so they go in through `run-as`:

```sh
P=io.github.johnrocky.hfmodels.samples.translate
hf download litert-community/Qwen3-ASR-1.7B Qwen3-ASR-1.7B-15s.litertlm --revision 184ec0f1ef8c8b5254100e95ec713b5557fb91de --local-dir m
hf download litert-community/Hy-MT2-1.8B Hy-MT2-1.8B_int8.litertlm --revision 0d2c5681276f71b61c4f2a14fbb9791341b28017 --local-dir m
hf download litert-community/kitten-tts-nano-0.8 kitten_predictor.tflite kitten_prosody.tflite kitten_vocoder.tflite voices.npz g2p/dp_g2p_matcha_fp16.tflite g2p/g2p_dict.txt.gz g2p/g2p_meta.json g2p/symbols.json --revision 2c5b198f36e0b40a098585da7601bd752ff432d5 --local-dir m
adb shell am start -n $P/.MainActivity      # makes the app's external files dir
adb shell am force-stop $P
adb shell mkdir -p /data/local/tmp/translate-models
adb push m/*.tflite m/*.litertlm m/voices.npz m/g2p/* /data/local/tmp/translate-models/
adb shell "run-as $P sh -c 'cp /data/local/tmp/translate-models/* /sdcard/Android/data/$P/files/'"
adb shell rm -r /data/local/tmp/translate-models
adb shell am start -n $P/.MainActivity --ez autoload true
```

The side-load matches file names, so the files go flat (Kitten's `g2p/` files too). While they are imported the phone holds them twice, with the caches the first load writes: 11,976,626 kB on the Galaxy S26 (the copies 4,518,952 kB, the store 4,519,038 kB, the caches 2,938,636 kB). Once the screen says Ready, the copies can go:

```sh
adb shell "run-as $P sh -c 'cd /sdcard/Android/data/$P/files && rm *.litertlm *.tflite voices.npz g2p_* symbols.json'"
```

## Launch extras

For scripted runs of the debug build (`adb shell am start -n $P/.MainActivity <extras>`); the person at the phone uses the buttons.

| extra | what it does |
|---|---|
| `--ez autoload true` | loads the three models now (`READY …` when done) |
| `--es pair fr\|es` | the other person's language, as the choice at the top (`language fr`) |
| `--es play <wav>` | plays this WAV (16 kHz mono 16-bit, a path on the phone) as one press of a button, through the endpointer in real time, so the turn starts at the early cut as for a voice (`early=true hangover_ms=300`); with `--ez realtime false` the WAV goes in at once and the turn starts at the endpointer's cut (`early=false hangover_ms=800`) |
| `--es dir fr-en\|en-fr\|es-en\|en-es` | the button the play presses (without it, the other person's); a direction of the other language switches the screen to it |
| `--es record <name>` | writes each turn under `<external files>/record/<name>/<turn>/`: `utterance.wav` (16 kHz, what was heard), `reply.wav` (24 kHz, the English as it was played), `events.json` (every event on the phone's clocks) |
| `--ef start_rms 0.01` | the endpointer's start level (default 0.02) (`levels start_rms=0.01 floor_rms=0.005`) |
| `--ef floor_rms 0.005` | the input gate's RMS floor: an utterance below it is not heard (with 0.5, f05 gave `nothing_heard="too quiet (rms 0.1739)"`, no translation and no seconds, and the screen said "Nothing heard") |
| `--es mt_backend gpu\|cpu` | where Hy-MT2 runs, read at the load: `cpu` gives `mt_backend=CPU`; `gpu` loads it on the GPU after its output check (`gpu_check="pass"`) |

## Logs

`adb logcat -s translate`:
- `extras …` for a start with extras (the values it read), after `language …`, `record …` or `levels …` when an extra sets them.
- `READY …` once the three models are loaded: each one's id, commit and backend, the output check, the load milliseconds, the runtimes, the network, the process's peak memory (`vmhwm_kb`).
- `LOAD FAILED model="…" code=… reason="…"` when one did not load (the screen says the same).
- `TURN …` per turn, one line:
  - what was heard and said: `dir`, `input` (`mic`, `file:<name>`), `heard`, `translation`, `spoken`;
  - how the turn started: `spec_ms` (the early cut's 300 ms), `early` (the turn started at the early cut), `discarded_early` (early starts cancelled before it because the voice came back), `hangover_ms` (300 for an early turn, else 800);
  - milliseconds from the cut the turn started from (the early cut, or the endpointer's 800 ms cut): `ms_gate` (the utterance stood), `ms_transcribe` (with the recognizer's `asr_wav_ms`, `asr_create_ms`, `asr_first_chunk_ms`, `asr_decode_ms`, `asr_chunks`), `ms_translate_create`, `ms_translate_first_token`, `ms_translate_done`, `ms_first_chunk` (the first piece to the speaker, `first_chunk`), `ms_first_sentence`, `ms_first_synth`, `ms_audio_ready`, `ms_first_audio` (the loudspeaker's first write), `ms_total`, and the two totals: `ms_end_of_speech_to_sound` = `hangover_ms` + `ms_first_audio` (`l1` writes the sum; the screen's "French speech to English voice in N.N s") and `ms_end_of_speech_to_text` ("English speech to French text");
  - the rest: `tokens`, `backend`, `audio_ms`, `rms`, `voiced_ms`, `nothing_heard` (why nothing was heard; empty otherwise), `errors`, `network` (none in airplane mode), `vmhwm_kb`.
- `recorded <dir>` after a turn when `--es record` is on.
- `mic_level …` once a second while a button listens: the second's RMS and its loudest 20 ms.

`adb logcat -s hfmodels`: `side-loaded …` per imported file and `qwen3-asr ready: …` (the threads, the initialize and first-conversation milliseconds, the cache dir).

## Device check

`src/androidTest/kotlin/io/github/johnrocky/hfmodels/check/TranslateDeviceCheck.kt`, in the app's own process: the three loads from the app's store (offline when the phone has no network), the ten fixed sentences through the endpointer and the turn twice (20 turns, each starting at the early cut, its gate opened 500 ms later, when the 800 ms hangover would have ended), and the other steps above. It prints `RESULT` lines under the tag `hfmodels-check`, the last one `RESULT ok=true` when every step passed, and a summary with the median from the end of speech to the first English sound. The `dir` column of `sentences.tsv` gives the pair: with `fixtures/fr` the steps run on the French buttons and Spanish goes into both; with `fixtures/es` on the Spanish buttons, with French into both. The model files go in first (above).

The fixtures are made on a Mac by `fixtures/make_fixtures.sh` (macOS `say` voices, ffmpeg, python3): for each pair the ten sentences of `sentences.tsv`, silence, quiet noise, 35 s of speech, a sentence in the other language and `pause_resume.wav`, 16 kHz mono 16-bit, next to `sentences.tsv` and `pause_resume.tsv`. On the Mac that made the fixtures the device checks used (macOS 27.0, ffmpeg 9.0.1), the script gives the same 30 WAVs, byte for byte (`fixtures/SHA256SUMS`). The check also reads `mac_asr.tsv` (id, a transcript to print beside the phone's) when it is there; it is not needed.

```sh
./gradlew :samples:translate:assembleDebug :samples:translate:assembleDebugAndroidTest
adb install -r samples/translate/build/outputs/apk/debug/translate-debug.apk
adb install -r samples/translate/build/outputs/apk/androidTest/debug/translate-debug-androidTest.apk
bash samples/translate/fixtures/make_fixtures.sh fx
adb shell mkdir -p /data/local/tmp/translate
adb push fx/es /data/local/tmp/translate/
adb shell am instrument -w -e class io.github.johnrocky.hfmodels.check.TranslateDeviceCheck -e fixtures /data/local/tmp/translate/es io.github.johnrocky.hfmodels.samples.translate.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s hfmodels-check | grep RESULT
```

For the French rows: `adb push fx/fr /data/local/tmp/translate/` and `-e fixtures /data/local/tmp/translate/fr`.

## JVM tests

`./gradlew :samples:translate:testDebugUnitTest`: 54 tests in 6 classes, none skipped: the prompt against the card word for word, the language each button fixes and the transcript in the recognizer's answer, the endpointer's early cut and its 800 ms on scripted audio, a press at the microphone's pace, the 15 s window, the input gate, the English rewrite and the sentence and clause cuts, no sound before the utterance stands, a whole turn with fakes, the screen's seconds, the TURN line, the order of the cards and the status a turn ends on ("Nothing heard" when nothing was heard).

## License

The sample is Apache-2.0 like the SDK. Speech recognition: Qwen3-ASR-1.7B (Qwen), Apache-2.0. Translation: Hy-MT2-1.8B (Tencent), Apache-2.0. Voice: kitten-tts-nano-0.8 (KittenML), Apache-2.0. Model files are never in the APK; the fixture WAVs are made by the script and are not in this repository.
