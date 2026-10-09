# samples/eg2search: say what is in a photo, and the phone finds it in your album

Hold the button, say what is in one of your photos ("a red bicycle leaning on a tree"), and that photo comes up first. EmbeddingGemma 2 740M turns the spoken clip and every photo into vectors in one 768-dimensional space on the phone and ranks the album by cosine; nothing is transcribed and nothing leaves the phone. On a Galaxy S26 on 2026-10-09 a spoken query became a vector in 150 to 174 ms in the device check below (114 to 139 ms on the app's own screen for the same clips), and a 36-photo album was indexed in 9.1 s (253 ms a photo).

This sample calls LiteRT-LM's `EmbeddingEngine` directly; it is not an SDK task. hfmodels 0.2.0 has no embedding task, and `EmbeddingEngine` came with LiteRT-LM 0.18.0, which `build.gradle.kts` names (the SDK modules pin 0.16.1). The module compiles with `-Xskip-metadata-version-check`, because the Kotlin of this build (the one AGP 9.3.1 brings) refuses the Kotlin 2.4 metadata of litertlm-android 0.18.0; the SDK modules are left as they are. An app built with Kotlin 2.4 or newer does not need the flag.

## Run it

```sh
hf download litert-community/embeddinggemma-2-740m-litert-lm embeddinggemma-2-740m.litertlm --local-dir .
./gradlew :samples:eg2search:installDebug
adb shell am start -n io.github.johnrocky.hfmodels.samples.eg2search/.MainActivity   # once: the app makes its files dir and shows "Model file missing"
adb push embeddinggemma-2-740m.litertlm /sdcard/Android/data/io.github.johnrocky.hfmodels.samples.eg2search/files/
# on the phone: tap Load model, tap Add photos and pick some, then hold the button and say what is in one of them
```

Push after the first start: the app creates its files dir, and a directory made by `adb shell` is not one the app can open. Coming back to the app after the push loads the model too. The first load compiles the GPU programs (5.4 s on the Galaxy S26); later loads read them from the app's cache (1.0 s).

## What the screen does

- **Add photos** opens the system photo picker (no permission needed). The picked photos are copied into the app's files dir as `album/u<NN>_<name>.jpg`, at most 1024 px on the long side and saved as JPEG without EXIF (no location); then only the new ones are embedded. While photos are embedded the button reads **Stop**: what is done is kept, the rest is embedded on the next start.
- **Remove my photos** deletes those copies. The originals stay in your photo library, and photos pushed with adb stay in the album.
- **Hold to talk** records 16 kHz mono from the microphone while the button is held (at most 10 s). On release the clip goes to the model, and the screen shows the best photo, "audio → vector N ms", its cosine and the next two matches. The recording is not kept.
- The pill says what is happening (LOADING MODEL, INDEXING n/N, READY, LISTENING, EMBEDDING, DONE), the third line which backend runs each stage, the footer how many photos the album has.

`PhotoSearch.kt` holds the model, the index and the search, `Album.kt` the copies, `Mic.kt` the recording, `MainActivity.kt` the screen.

## Model and backends

[litert-community/embeddinggemma-2-740m-litert-lm](https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm), the file `embeddinggemma-2-740m.litertlm` (484,622,336 bytes, sha256 `e7a8a220…`, Apache-2.0 on its card), the one for CPU and GPU. Options: `normalize = true`, `outputSize = 768`; no prefix on audio or photos (the card's prefixes are for text).

| stage | backend |
|---|---|
| audio encoder | CPU |
| backbone (the encoder's output to the 768-d vector, for a clip and for a photo) | GPU |
| photo encoder | GPU |

"audio → vector" on the screen is the audio encoder and the backbone together. The audio encoder stays on the CPU: on the Galaxy S26 on 2026-10-08 a spoken query took 104 ms with it on the CPU and 125 ms on the GPU (median of 43 clips, `probes/eg2demo/ROUND2.md`). `--es backend`, `--es vision_backend` and `--es audio_backend` (`cpu`, `gpu`, `npu`) change them.

## What it showed on the Galaxy S26 (2026-10-09)

The device check, one run (`RESULT ok=true`, 13 of 13 steps):

| step | what it showed |
|---|---|
| load | the first load on this phone: init 5,417 ms (the GPU programs compiled), warm-up 95 ms; the file's sha256 begins `e7a8a2204b91` |
| index | 36 photos embedded in 9.1 s, 253 ms a photo (median; 231 to 265) |
| import | a 2048 × 1408 copy of `a36_sliced_bread.jpg`, put into MediaStore and read back through `Album.importUris`: `u01_eg2search-check-a36-sliced-bread.jpg`, 1024 × 704; the album went from 36 to 37 and the next index embedded that photo only (456 ms with the other 36 checked) |
| import_none | an empty pick left the album at 37 |
| query_q01_a | "a red bicycle leaning on a tree" (2.58 s): a01, cos 0.72, 174 ms |
| query_q08_a | "the cat taking a nap" (1.97 s): a10, cos 0.64, 150 ms |
| query_q13_a | "a snowman in the snow" (2.11 s): a20, cos 0.71, 172 ms |
| no_match | 1.5 s of noise at RMS 0.0034 refused as too quiet, 0.3 s of speech (RMS 0.10) as too short |
| stop | an index of all 37 photos again, cancelled after 2: it returned 310 ms after the cancel with all 37 still searchable, and q01_a still found a01 |
| release | close 195 ms; the second close did nothing; a search after close threw IllegalStateException |
| reload | a second load in the same process: init 2,350 ms, warm-up 212 ms; 37 photos from the cache (none embedded); q01_a found a01 in 286 ms |
| missing_bundle | `BUNDLE_MISSING`, the text naming the file and the push directory |
| permission | with RECORD_AUDIO revoked the microphone path returned `Denied` and its sentence, and recorded nothing |

Galaxy S26 SM-S942Q (SM8850), Android 16 (BP4A.251205.006.S942QOPS1AZH9), litertlm-android 0.18.0, a debug build; the check ran in the app's process under instrumentation, thermal status 0 at the start and 1 at the end. The clips are a macOS voice (Samantha, `fixtures/queries.json`), not a person.

The app on the same phone a minute later, at thermal status 1: it loaded with the GPU programs from its cache (init 1,009 ms, warm-up 98 ms), and the same three clips searched from its screen (`--es run_queries`) took 114, 119 and 139 ms, with the same photos and cosines. A press without a finger (`--ei mic_test_ms 4000 --ez mic_test_play true --es query q01_a.wav`: the phone's speaker, at media volume 8 of 15, playing the clip into its own microphone) recorded 3.95 s at RMS 0.020 and found a01 at cos 0.72 in 180 ms; 1.5 s of the room's silence through the same path came in at RMS 0.0012 and was refused as too quiet. One photo picked in the system photo picker was copied in 25 ms and embedded in 223 ms, and Remove my photos took it out again. Ten minutes later the app, started without the model file, showed "Model file missing"; after the push a tap on Load model loaded it (READY 12 s after the tap, the 36 photos embedded in 9.0 s on the way); started again without the file, sent away with HOME and brought back with the file in place, it loaded by itself (READY 1.6 s after the load began; the app first waits a second to see that the file is not still arriving); and Stop during a first index stopped 40 ms after the tap with 5 of the 36 photos indexed. Numbers from one phone on one night; not a benchmark.

## When something is missing

| what | the screen | where |
|---|---|---|
| no model file | "Model file missing" with the `hf download` and `adb push` lines; **Load model** tries again | `PhotoSearch.open` → `BUNDLE_MISSING` |
| the model does not load | the runtime's error; **Load model** tries again | `INIT_FAILED`, `BAD_BACKEND` |
| microphone permission off | "The microphone is off for this app…"; a tap on that line opens the app's settings | `Mic.record` → `Denied` |
| a press under 0.5 s, or a clip quieter than RMS 0.005 | "too short", "too quiet": the model is not asked | `InputGate` |
| no photos | the button is off and the screen says how to add some | `MainActivity.showAlbum` |
| the picker comes back with nothing | "No photos picked", the album is as it was | `Album.importUris(emptyList())` |
| leaving while photos are embedded, closing twice, loading again | the index stops before the next photo; a second close does nothing; a new load reads the vectors from the cache | `PhotoSearch.index`, `PhotoSearch.close` |

"Too quiet" is decided by loudness, not by a cosine threshold: an embedding search always returns a closest photo, and on the Galaxy S26 a silent clip and a 50 ms press came back as photos at cos 0.63 and 0.68, around the lowest correct answer of 20 spoken queries (0.64, 2026-10-08). The floor 0.005 (−46 dBFS) sits above the louder of two silent recordings made through this app's microphone path on that phone (RMS 0.0034 and 0.0009); `--ef min_rms` changes it.

## Device check

`src/androidTest/kotlin/io/github/johnrocky/hfmodels/check/Eg2DeviceCheck.kt` runs the screen's code in the app's own process on the connected phone and prints one `RESULT step=… ok=…` line per step under tag `hfmodels-check`: load, index, import (a photo through the picker's path), an empty pick, each clip's top-1 against its gold photo, silence and a short press refused, a cancelled index, close twice, a second load, a missing model file and the microphone path without its permission. Put the files there first (after the app's first start):

```sh
F=/sdcard/Android/data/io.github.johnrocky.hfmodels.samples.eg2search/files
adb push embeddinggemma-2-740m.litertlm $F/
adb push probes/eg2demo/fixtures/album/*.jpg $F/album/
adb push probes/eg2demo/fixtures/queries.json probes/eg2demo/fixtures/queries/q01_a.wav probes/eg2demo/fixtures/queries/q08_a.wav probes/eg2demo/fixtures/queries/q13_a.wav $F/queries/
```

Then:

```sh
./gradlew :samples:eg2search:assembleDebug :samples:eg2search:assembleDebugAndroidTest
adb install -r samples/eg2search/build/outputs/apk/debug/eg2search-debug.apk
adb install -r samples/eg2search/build/outputs/apk/androidTest/debug/eg2search-debug-androidTest.apk
adb shell "pm revoke io.github.johnrocky.hfmodels.samples.eg2search android.permission.RECORD_AUDIO; am instrument -w -e class io.github.johnrocky.hfmodels.check.Eg2DeviceCheck io.github.johnrocky.hfmodels.samples.eg2search.test/androidx.test.runner.AndroidJUnitRunner"
adb logcat -d -s hfmodels-check | grep RESULT
```

The permission step needs the microphone permission off, hence the `pm revoke`; the app asks for it again on the next press. The last line is `RESULT ok=true …` when every step passed. The check removes the photo and the MediaStore row it added; `connectedDebugAndroidTest` runs it too, but uninstalls the app and its files afterwards.

## Test files and recording tools

The 36 photos and the clips are not in this repository. `probes/eg2demo/fixtures/album.json` lists the photos (CC0, from Openverse, no people or readable text) and `python3 probes/eg2demo/scripts/fetch_album.py build` downloads them into `fixtures/album/` (needs `requests` and Pillow). `probes/eg2demo/scripts/make_queries.py` makes the clips with macOS `say` and ffmpeg: 20 sentences, one per album subject, in two voices, and 3 that name nothing in the album; `fixtures/queries.json` gives each clip's gold photo.

For recordings, `MainActivity.kt` takes launch extras: `--ez autoplay true --es query q01_a.wav` plays a clip through the speaker and searches with the file, `--es run_queries all` searches with every clip in `files/queries/`, and `--ei mic_test_ms 4000 --ez mic_test_play true --es query q01_a.wav` is a press without a finger: the microphone records while the phone's own speaker plays the clip. Any of them turns on record mode (the screen shows over the lock screen, keeps the display on and keeps a 9:16 frame). The full list is in the class comment.

## NPU

`--es backend npu --es bundle embeddinggemma-2-740m_Qualcomm_SM8850.litertlm` (the bundle for the phone's SoC) loads on the Qualcomm NPU only in an APK that carries Qualcomm's libraries in `src/main/jniLibs/arm64-v8a` (never in this repository); the default is the GPU. On the Galaxy S26 on 2026-10-08 that path stopped inside `EmbeddingEngine.initialize` (SIGBUS with QAIRT 2.47; LiteRT-LM 0.18.0 pins QAIRT 2.50), so it is unverified here.

## Limits

- The closest photo always comes up: a sentence about something that is not in the album still shows a photo (the three clips that name nothing reached cos 0.59 at most on 2026-10-08).
- The clips were synthetic English voices; a person's voice, other languages and other phones were not measured.
- The loudness floor comes from one phone's silence; another phone may need `--ef min_rms`.
- Photos only, no videos. The vectors are kept per model file and photo backend (`files/index_<bundle>_<backend>.json`).
- Numbers from one phone on one night, the model warm; not a benchmark.
