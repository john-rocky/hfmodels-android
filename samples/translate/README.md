# samples/translate: Spanish speech to an English voice, English speech to Spanish text, on the phone

No network and no translation account: someone speaks Spanish to you, the phone says it in English; you answer in English, the phone shows it in Spanish in large type for them to read. One tap per person: **Habla español** (the Spanish speaker's button) and **Speak English** (yours). A tap opens the microphone for one utterance; 800 ms of silence ends it (or a second tap). The screen shows what was heard, the translation, and the seconds from the end of speech to the first English sound (Spanish to English) or to the whole Spanish text (English to Spanish), with their parts.

Draft (2026-10-19): built and unit-tested on a Mac; not run on a phone yet. The numbers and the "What it did" section come from the first phone run.

## Three models

| role | id @ commit | file | size | where it runs | license |
|---|---|---|---|---|---|
| hearing, both languages | `mlboydaisuke/Parakeet-TDT-0.6B-v3-LiteRT` @ af580dff | `parakeet_tdt_0.6b_v3_30s_i8_stateful.tflite` | 630 MB | CPU, LiteRT Interpreter (XNNPACK, 4 threads) | CC-BY-4.0 (NVIDIA parakeet-tdt-0.6b-v3) |
| its vocabulary | `nvidia/parakeet-tdt-0.6b-v3` @ 541d1f99 | `tokenizer.json` | 1.2 MB | | CC-BY-4.0 |
| translation | `litert-community/Hy-MT2-1.8B` @ 0d2c5681 | `Hy-MT2-1.8B_int8.litertlm` | 1.83 GB | GPU after an output check at load, else CPU (LiteRT-LM through `Tasks.Chat`) | Apache-2.0 (Tencent Hy-MT2-1.8B) |
| English voice | `litert-community/kitten-tts-nano-0.8` @ 2c5b198f | `fp32`: 8 files | 94 MB | CPU (`Speak`) | Apache-2.0 (KittenML) |

- **parakeet-tdt-0.6b-v3** (NVIDIA, 25 European languages; it finds the language itself and writes punctuation and capitals) in the 30 s export: one window holds any utterance up to 30 s. The 5 s files of `litert-community/parakeet-tdt-0.6b-v3` are not used: decoded window by window, the model itself returns nothing for about a third of the windows that start inside speech (NeMo on LibriSpeech, edge-llm-bench 2026-09-25), and these sentences are 4 to 7 s. Neither repo has an `hfmodels.json`: the sample passes descriptors of its own (`src/main/assets/descriptors/`, `LoadOptions.descriptorJson`) with its own handlers (`Parakeet.kt`), so the SDK still downloads with resume, checks the sha256, imports a pushed copy and loads offline. The host does what the `.tflite` leaves to it: NeMo's log-mel (`ParakeetFrontEnd.kt`, pre-emphasis 0.97, a 400-sample Hann window in a 512-point FFT, librosa's Slaney filterbank, per-bin normalization over the whole zero-padded window), greedy TDT decoding (`TdtGreedy.kt`: the stateless `decode` for the first four tokens, then `decode_1`, at most 10 symbols per frame, stopped 4 encoder frames after the audio ends) and the vocabulary (`ParakeetVocab.kt`). The Interpreter API rather than `CompiledModel`: the 30 s graph's decoder writes the joint for all 375 encoder frames at every step (12.3 MB), and the Interpreter lets the loop read the one row it needs in place.
- **Hy-MT2-1.8B** (Tencent, 33 languages, a translation model, not a chat model) by id from the SDK's bundled catalog, which pins it at 0d2c5681 for LiteRT-LM 0.16.1. One request, one new conversation with the model card's prompt (`Translate the following text into English. Note that you should **only output the translated result without any additional explanation**:` then an empty line and the text): a second message in the same conversation fails (LiteRT-LM #3166). Greedy. On the GPU, two fixed Spanish sentences are translated at load and must come back as English about a station and a thank-you; otherwise the model is loaded on the CPU, and the failure is remembered for this phone build, runtime and model (`--es mt_backend gpu` tries again).
- **Kitten TTS nano 0.8** says the English sentence by sentence while the rest is still being translated. Before it, the English is rewritten into what its front-end can say (`EnglishSpeech.kt`: "I’m" -> "I'm", "3:15 p.m." -> "three fifteen PM", "€23.50" -> "23 euros 50", "21st", "Mr.", accents). There is no Spanish voice: no fast Spanish text-to-speech runs from Kotlin on this stack yet, so English to Spanish is text.

## Build and permissions

```sh
./gradlew :samples:translate:installDebug
adb shell pm grant io.github.johnrocky.hfmodels.samples.translate android.permission.RECORD_AUDIO
```

Without the grant the app asks on its first start.

## Model files

The first load downloads the files (2.55 GB) into the app's private storage and checks each sha256; later loads, with or without a network, use the store. Development shortcut: copies in the app's external files dir are hashed and imported instead of downloaded (`adb logcat -s hfmodels` prints `side-loaded <file> … (sha256 verified)`); they can go after that load. The copies must belong to the app (a copy made by `adb shell` is unreadable to it on a Galaxy S26), so they go in through `run-as`:

```sh
P=io.github.johnrocky.hfmodels.samples.translate
hf download mlboydaisuke/Parakeet-TDT-0.6B-v3-LiteRT parakeet_tdt_0.6b_v3_30s_i8_stateful.tflite --revision af580dff28a80150057d54bb33a0e01025ce4bce --local-dir m
hf download nvidia/parakeet-tdt-0.6b-v3 tokenizer.json --revision 541d1f99c6b0c3cd0b11a95167540bb8edefd82b --local-dir m
hf download litert-community/Hy-MT2-1.8B Hy-MT2-1.8B_int8.litertlm --revision 0d2c5681276f71b61c4f2a14fbb9791341b28017 --local-dir m
hf download litert-community/kitten-tts-nano-0.8 kitten_predictor.tflite kitten_prosody.tflite kitten_vocoder.tflite voices.npz g2p/dp_g2p_matcha_fp16.tflite g2p/g2p_dict.txt.gz g2p/g2p_meta.json g2p/symbols.json --revision 2c5b198f36e0b40a098585da7601bd752ff432d5 --local-dir m
adb shell am start -n $P/.MainActivity      # makes the external files dir; close it again
adb shell mkdir -p /data/local/tmp/translate-models
adb push m/*.tflite m/*.litertlm m/tokenizer.json m/voices.npz m/g2p/* /data/local/tmp/translate-models/
adb shell "run-as $P sh -c 'cp /data/local/tmp/translate-models/* /sdcard/Android/data/$P/files/'"
adb shell rm -r /data/local/tmp/translate-models
```

The side-load matches file names, so the files go flat (Kitten's `g2p/` files too).

## Launch extras

Debug builds only: a release build ignores them.

| extra | what it does |
|---|---|
| `--ez autoload true` | load the three models now |
| `--es play <wav>` | this WAV (16 kHz mono 16-bit; a path, or a name in the external files dir) as one press of the button of `--es dir` (`es-en`, the default, or `en-es`), through the endpointer in real time (`--ez realtime false`: at once) |
| `--es record <name>` | each turn under `<external files>/record/<name>/<turn>/`: `utterance.wav` (16 kHz, what was heard), `reply.wav` (24 kHz, the English as played), `events.json` (every event on the phone's clocks, each English chunk's write) |
| `--ef start_rms 0.01` | the endpointer's start level (default 0.02, a voice toward the phone; a loudspeaker across a desk needs less) |
| `--ef floor_rms 0.005` | the input gate's RMS floor: below it, or with less than 250 ms of voice, an utterance is "Nothing heard" |
| `--es mt_backend gpu\|cpu` | where Hy-MT2 runs (default: the GPU after its output check, unless it failed on this phone before) |

With any of these extras the screen shows over the keyguard, turns the display on and keeps it on (scripted mode). While the screen is not visible, the microphone and any turn in progress stop.

## Logs

`adb logcat -s translate`:
- `READY …` once the three models are loaded: each one's id, commit and backend, the output check, the load seconds, the runtimes, the network, the process's peak memory (`vmhwm_kb`).
- `LOAD FAILED model="…" code=… reason="…"` when one did not load (the screen says the same).
- `TURN …` per turn: `dir`, `input` (`mic`, `mic-press` when a tap ended it, `file:<name>`), `heard`, `translation`, `spoken`, then milliseconds from the cut of the utterance: `ms_transcribe` (with `asr_feature_ms`, `asr_encode_ms`, `asr_decode_ms`, `asr_steps`), `ms_translate_create` (the new conversation), `ms_translate_first_token`, `ms_translate_done`, `ms_first_sentence` (the first English sentence finished), `ms_first_synth`, `ms_first_audio` (the loudspeaker's first write); `hangover_ms` (the endpointer's 800 ms of silence) and the two totals, `ms_end_of_speech_to_sound` = `hangover_ms` + `ms_first_audio` (the screen's "Spanish speech to English voice in N.N s") and `ms_end_of_speech_to_text` = `hangover_ms` + `ms_translate_done` ("English speech to Spanish text"); `tokens`, `backend`, `rms`, `voiced_ms`, `nothing_heard`, `errors`, `network` (none in airplane mode), `vmhwm_kb`.
- `mic_level …` once a second while a button listens: the second's RMS and its loudest 20 ms, for the input gate's floor.

## Device check

`src/androidTest/kotlin/io/github/johnrocky/hfmodels/check/TranslateDeviceCheck.kt`: in the app's own process, the three loads from the store, the ten fixed sentences through the endpointer and the turn twice (20 turns, the transcripts beside the Mac's), and the failure paths: silence and quiet noise (nothing heard), the input gate on the noise, 35 s of speech (cut at the 30 s window), French into both buttons (recorded), a model file the store does not have (the screen's words), Stop in the middle of a turn and the next turn, the memory, the release. It prints `RESULT` lines under `adb logcat -s hfmodels-check` and a summary with the median from the end of speech to the first English sound. The fixtures (16 kHz mono 16-bit WAVs, `sentences.tsv`, `mac_asr_30s.tsv`) go to `/data/local/tmp/translate/` (argument `fixtures`).

```sh
./gradlew :samples:translate:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.johnrocky.hfmodels.check.TranslateDeviceCheck
adb logcat -d -s hfmodels-check | grep RESULT
```

## JVM tests

`./gradlew :samples:translate:testDebugUnitTest`: the prompt against the card word for word, the front-end against librosa's filterbank and the Python host's features of a synthetic sweep (`src/test/resources/parakeet/`), the greedy loop on a scripted joint, the vocabulary, the cuts, the input gate, the English rewrite and the sentence cutter, the screen's seconds and the TURN line, and a whole turn with fakes. With `TRANSLATE_FIXTURES_ROOT` (the fixture WAVs and the Python host's dumps; not in this repository) and `TRANSLATE_PARAKEET_ROOT` (a dir with the model's `tokenizer.json` as `parakeet_tokenizer.json`) three more compare the twelve fixture utterances with the Python host: the endpointer's cut, the 5 s pieces, the features (largest difference 2.4e-7) and the stop frames, and the text of the host's token ids; without them those three are skipped and say so.

## License

The sample is Apache-2.0 like the SDK. Speech recognition: NVIDIA parakeet-tdt-0.6b-v3, CC-BY-4.0 (the screen says so). Translation: Tencent Hy-MT2-1.8B, Apache-2.0. Voice: KittenML kitten-tts-nano-0.8, Apache-2.0. Model files are never in the APK.
