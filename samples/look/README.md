# samples/look

Take a photo, pick one or tap an example, type a question about it, and watch the answer arrive: a vision-language model reads the photo on the phone and answers in text, and the line under the answer says when its first token came, how fast the rest followed, on which backend, and whether the load before it made any network request. The model is [`litert-community/MiniCPM-V-4.7-1B`](https://huggingface.co/litert-community/MiniCPM-V-4.7-1B) through LiteRT-LM: the `vl_int8` file (2,210,907,248 bytes; decoder and vision encoder on the GPU) on a phone with 10 GiB of memory or more, the `vl_int8vis` file (1,698,202,736 bytes; int8 vision encoder, CPU) below that.

## Run it

1. `python3 samples/look/tools/fetch_examples.py` puts the three example photos into `src/main/assets/examples/`. They are not in the repository; the script downloads each one from its source and checks its sha256 (`tools/examples.json`).
2. `./gradlew :samples:look:installDebug`
3. Open "hfmodels look". It loads the model as it starts: the first time from the Hub (2.2 GB for `vl_int8`, its sha256 checked), after that from the phone.
4. Tap an example under the photo (or Pick a photo, or Take a photo), tap a question under the box (or type one), and Ask.

To skip the download in step 3, start the app once with `adb shell am start -n io.github.johnrocky.hfmodels.samples.look/.MainActivity --es network offline` (it creates the folder below and stops at `OFFLINE_CACHE_MISS`), push the file with `adb push MiniCPM-V-4.7-1B-VL_int8.litertlm /sdcard/Android/data/io.github.johnrocky.hfmodels.samples.look/files/` (`MiniCPM-V-4.7-1B-VL_int8vis.litertlm` on a phone under 10 GiB), and start it again: the pushed copy is hashed and imported.

The app loads the model by its id at Hub commit `8298b1bd5531737290a09da5f7a66402e49e7a7d` (`ModelRef(…, revision = …)`), with the descriptor in `assets/minicpm-v-4.7-1b.hfmodels.json`, generated from `catalog/specs/litert-community__MiniCPM-V-4.7-1B.json` by `tools/hfmodels_descriptor.py`. `--es variant vl_int8vis` picks the other file.

## The screen

Top to bottom: the state (LOADING, READY, ● ASKING, DONE, STOPPED, FAILED) and what is happening; your photo; the three examples, Pick a photo and Take a photo; the question box and three example questions; the backend (auto, gpu, cpu), Ask and Stop; the answer as it streams, in large type; the numbers line; the model, variant, profile, phone, runtime and SDK versions.

Pick a photo opens the system photo picker and Take a photo the phone's camera app; neither needs a permission (the camera app writes its shot into the app's cache through a FileProvider). A tap on an example takes the same steps as a picked photo. The photo on screen is the JPEG the model gets. A tap on an example question puts it in the box, which stays editable.

The numbers line reads `first token <seconds> s · <rate> tok/s · <backend> · on-device, offline`:

- first token: from calling `stream` to the first chunk that carries text. The load and the opening of the conversation happen before READY and are not in it.
- tok/s: (chunks − 1) / (time from the first to the last chunk). Every chunk with text carried one token with this model on LiteRT-LM 0.16.1: the answers of 2026-10-14 on a Pixel 8a's CPU and a Galaxy S26's GPU, counted with the model's Hugging Face tokenizer, gave 142 chunks = 142 tokens, 44 = 44, 43 = 43 and 48 = 48. The record keeps the chunk counts.
- GPU or CPU: the backend the language model was initialized on (`PreparedModelInfo.components`), and the vision encoder's after it when that one differs ("GPU, vision CPU").
- offline: the load before the answer ran with `NetworkPolicy.Offline`, so the SDK made no request. Without it the line says only "on-device".

The model writes Markdown. The screen drops the `**`, `#` and `- ` marks and sets the bold parts in bold (`AnswerText.kt`); the record keeps the text as the model wrote it. While the answer fits in its box, its first line stays at the top; once it does not, the box follows the last line.

The answer is drawn at most once per frame: the pieces that arrive between two frames are drawn together, and once more when the answer is done. The times on the numbers line are taken in `Looker`'s stream callback as each piece arrives, apart from the drawing.

## One conversation per loaded model

The model loads when the app starts, and a conversation is opened on it right after: READY. The first question about a photo is that conversation's first turn, the photo and the question together; every further question about the same photo is a text-only turn of the same conversation. On this model family LiteRT-LM carries a conversation's state into the next conversation on the same loaded model ([google-ai-edge/LiteRT-LM#3165](https://github.com/google-ai-edge/LiteRT-LM/issues/3165)), so the app never opens a second conversation on a loaded model: after a new photo or a Stop it closes the model, loads it again and opens a new conversation straight away, and the state line says so. `Looker.kt` keeps this rule for the screen and the device check alike.

## What one question sends

1. The photo, through `PhotoImport`: decoded with `ImageDecoder` (EXIF rotation applied, subsampled while it decodes), scaled so its long side is at most 768 px, JPEG quality 90.
2. Nothing for the conversation: it was opened at READY with `chat.createConversation(ConversationConfig(samplerConfig = SamplerConfig(topK = 1, topP = 0.95, temperature = 1.0)))`. Top-k 1 is the engine's own default for this bundle, which declares no sampler; it is written out so that it stays so.
3. The first question about the photo: `session.stream(Contents.of(Content.ImageBytes(jpeg), Content.Text(question)), GenerationOptions(maxOutputTokens = 256))`.
4. Each further question about the same photo: `session.stream(Contents.of(Content.Text(question)), GenerationOptions(maxOutputTokens = 256))` on the same session.

The question goes as typed, trimmed, at most 300 characters; the app adds no prompt of its own.

## When it does not answer

| what happens | what the screen says |
|---|---|
| Ask without a photo | Choose a photo first: tap an example, pick a photo or take one. |
| Ask with an empty question | Type a question, or tap one of the questions under the box. |
| a question over 300 characters | At most 300 characters, please. |
| the load fails | the SDK's code and reason ([`docs/errors.md`](../../docs/errors.md)); for the device check's revision that is not on the phone, loaded offline: MODEL_NOT_REGISTERED: litert-community/MiniCPM-V-4.7-1B@00000000 has no hfmodels.json and the catalog has no entry for that commit. Add hfmodels.json to the repo (see the publisher guide) or pass a descriptor explicitly. |
| a file that is not an image | Could not read that photo: DecodeException: Failed to create image decoder with message 'unimplemented'Input contained an error. |
| a very large photo | nothing: it is subsampled while it decodes (6000 x 4500 came back as 768 x 576 in the device check) |
| a photo stored sideways with an EXIF rotation | nothing: it reaches the model upright (a 400 x 300 JPEG with orientation 6 came back as 300 x 400) |
| the example photos were not fetched before the build | The example photos are not in this build: run samples/look/tools/fetch_examples.py, then build again. |
| no camera app on the phone | This phone has no camera app to take the photo with. |
| Stop during an answer | Stopped. That conversation is closed; the model loads again for the next question. |

## The device check

[`src/androidTest/kotlin/io/github/johnrocky/hfmodels/check/LookDeviceCheck.kt`](src/androidTest/kotlin/io/github/johnrocky/hfmodels/check/LookDeviceCheck.kt) runs in the app's process through the app's own code (`Looker`, `PhotoImport`, `Typed`, `Stats`) and prints one `RESULT` line per step:

```sh
python3 samples/look/tools/fetch_examples.py
./gradlew :samples:look:assembleDebug :samples:look:assembleDebugAndroidTest
adb install -r samples/look/build/outputs/apk/debug/look-debug.apk && adb install -r samples/look/build/outputs/apk/androidTest/debug/look-debug-androidTest.apk
adb shell am start -n io.github.johnrocky.hfmodels.samples.look/.MainActivity --es network offline   # creates the files dir, no download
adb push MiniCPM-V-4.7-1B-VL_int8.litertlm /sdcard/Android/data/io.github.johnrocky.hfmodels.samples.look/files/
adb shell am instrument -w -e class io.github.johnrocky.hfmodels.check.LookDeviceCheck -e backend gpu -e network offline -e variant vl_int8 io.github.johnrocky.hfmodels.samples.look.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s hfmodels-check | grep RESULT
```

- `load`: the model loads (`-e backend gpu`: the GPU profile; `cpu`, or a profile id such as `gpu_vision_cpu`) and a conversation opens on it, as the screen does at start.
- `photo`: the sign example, put into MediaStore as a camera would and read back through `PhotoImport` (the picker's path), and read from assets (the strip's path): the same bytes. "What does this sign say?" goes into the ready conversation without a load in between and must come back with text. Whether it gives the sign's words is recorded, not required: the model's answer is not the app's to guarantee.
- `follow-up`: "What colour is the sign?" as a text-only turn of the same conversation.
- `stop`: a third question cancelled after its first chunk; its conversation closes, the model loads again, a new conversation opens, and the first question again gets an answer.
- `release`: close, and close again, without an exception.
- the failure paths, each a sentence for the screen instead of a crash: `missing-model`, `no-photo`, `empty-question`, `big-photo`, `rotated-photo`, `bad-photo`.

The last line is `RESULT ok=true …` when every step passed. `-e keep_photo true` leaves the sign in MediaStore (under Pictures/hfmodels-look-check) for the picker; `-e class io.github.johnrocky.hfmodels.check.LookCheckPhotos` removes it.

## Take mode and recording mode

Both show over the lock screen and keep the screen on: behind a lock screen an activity is kept out of the foreground, and a phone driven over adb would answer slowly.

| extra | take mode | recording mode |
|---|---|---|
| `--ez take true` | on: the status bar stays visible, everything from it down to the numbers line sits in the top 9:16 of the screen (1080 x 1920 on a 1080-pixel-wide phone), the versions line below it without the phone's name; the answer box holds 11 lines and the photo takes the room that is left (the sign example's answer took 10 lines in the larger type of the other modes on a Galaxy S26, so in this box it shows whole from its first line); nothing runs by itself | with `--ez autostart true` too: recording mode on the take mode's screen, status bar included |
| `--ez autostart true` | — | on: the system bars are hidden (not with `--ez take true`); after READY and `delay` it does what a tap on the example and a tap on Ask do |
| `--ei delay 3` | — | seconds between READY and the example |
| `--es example sign` | — | `sign`, `object` or `scene` (default `sign`) |
| `--es question "'…'"` | — | the question (default: the example's own); keep the single quotes inside the double ones, or `am` splits it at its spaces |
| `--es backend gpu` | auto, gpu, cpu or a profile id | the same |
| `--es network offline` | offline (no request) or any | the same |
| `--es variant vl_int8` | `vl_int8` or `vl_int8vis` (default: by the phone's memory) | the same |

```sh
adb shell am start -n io.github.johnrocky.hfmodels.samples.look/.MainActivity --ez take true --es backend gpu --es variant vl_int8 --es network offline
adb shell am start -n io.github.johnrocky.hfmodels.samples.look/.MainActivity --ez autostart true --ei delay 3 --es example sign --es question "'What does this sign say?'" --es backend gpu --es network offline --es variant vl_int8
adb logcat -s look
```

Logcat tag `look`: `READY profile=… load_ms=… open_ms=…` once a conversation is open, `BOUNDS` with the screen position of the examples, the question chips, Ask, the answer box, the answer's text as laid out in it (`answer_text`, `answer_lines`, `answer_scroll`: whether it shows whole from its first line) and the numbers line (what a recording taps and crops), `PHOTO source=… w=… h=…`, `ASK first_turn=… first_token_ms=… chunks=… render_count=… chunks_per_s=…`, `DONE json=<path>`.

After every answer, from a button or in recording mode, the app writes `look-result-<epoch s>.json` and `frames-<epoch s>/000.jpg` (the exact JPEG bytes the model got) to `/sdcard/Android/data/io.github.johnrocky.hfmodels.samples.look/files/`: the question; the photo's source, sha256, size and original size; the answer as written and as shown; the numbers line and its backend; `first_token_ms`, `stream_ms`, `chunks`, `chunks_per_s` and every chunk's time; how many times the answer box was drawn (`render_count`, beside `chunks`); whether it was the first turn; `load_ms` and `open_ms` of the load and the opening before it, and whether the question waited for them; the profile, its components and fallbacks, the versions, the phone, the thermal status before and after, and the process's peak memory (VmHWM).

## Tests

`./gradlew :samples:look:testDebugUnitTest` checks the parts that do not need a phone: the first token, the rate and the numbers line (`StatsTest`); the answer as the screen shows it, on the sign answer exactly as the model wrote it and while it streams (`AnswerTextTest`); what the screen refuses before the model, the example questions and photos, that `tools/examples.json` names the same photos as the app, the file each phone loads, and the size each photo is sent at (`TypedTest`).

## Numbers

### 2026-10-15: Galaxy S26, GPU, a cool phone

The same phone, file, profile, photo and question as on 2026-10-14 below, with this version of the app (the answer drawn at most once per frame). Before the first app process the phone had cooled from thermal status 1 with the battery at 39.8 °C to status 0 at 37.3 °C, with the GPU's top clock at 1,300 MHz, in the two minutes before the app was started.

| where | load | opening the conversation | first token | tokens | tok/s | the answer drawn | thermal status |
|---|---|---|---|---|---|---|---|
| the screen, recording mode on take mode's screen (a new process) | 33.9 s | 9,047 ms | 1,204 ms | 43 | 37.0 | 44 times | 1 |
| the screen, take mode, tapped: the sign, the question, Ask (a new process) | 35.0 s | 9,828 ms | 1,315 ms | 43 | 35.6 | 44 times | 1 |
| device check, the first load | 97.8 s | 22,615 ms | 1,974 ms | 43 | 37.2 | no screen | 1 |
| device check, a second question about the same photo, "What colour is the sign?" | none | none | 416 ms | 48 | 35.8 | no screen | 1 |
| device check, after the Stop (a second load in the same process) | 91.5 s | 15,556 ms | 1,596 ms | 43 | 38.0 | no screen | 1 |

All four answers to the sign were the text of 2026-10-14 below, word for word, and the second question's answer was too; each chunk carried one token again (43 = 43 and 48 = 48 by the model's Hugging Face tokenizer), and the take's JPEG and question through LiteRT-LM 0.18.0 on a Mac CPU gave the Mac answer of 2026-10-14 below, word for word. On the screen the tokens came 26 to 31 ms apart. That is slower than one frame, so each token was drawn in a frame of its own: the box was drawn 44 times for 43 tokens, the last time at DONE. Each new process set up the GPU delegate for the decoder again: 29 and 30 s in the two screen processes, 83 s at the device check's first load, in a process the system ran as `foreground` rather than `top-app`. The load raised the thermal status for a while: right after take mode reached READY the phone read status 2 with the GPU's top clock at 726 MHz, and 60 s later, when the recording started, it was back at 1 and 1,300 MHz. The first token came in 1.20 and 1.32 s on the screen and in 1.97 and 1.60 s in the device check of the same hour. The causes of these differences are not established.

One phone, one day; not a benchmark.

### 2026-10-14: Galaxy S26, GPU

Measured on one Galaxy S26 (SM-S942Q, Android 16, build BP4A.251205.006.S942QOPS1AZH9) with litertlm-android 0.16.1 and the `vl_int8` file pushed into the app's files directory, on the GPU profile (the language model and the vision encoder initialized on the GPU, no fallback), offline, in airplane mode. The photo was the sign example with "What does this sign say?"; it went to the model as a 768 x 576 JPEG of 191,815 bytes, the same bytes from the strip and from the picker. The three answers to it, in two processes, were the same text: the sign's words "Please KEEP OFF THE GRASS" in bold, then "It’s a simple, bold warning sign placed in a grassy area to remind people not to stand or walk on the grass."

| where | load | opening the conversation | first token | tokens | tok/s | the answer drawn | thermal status |
|---|---|---|---|---|---|---|---|
| device check, the first load (with hashing and importing the pushed file) | 60.8 s | 14,044 ms | 1,519 ms | 43 | 37.2 | no screen | 1 |
| device check, a second question about the same photo, "What colour is the sign?" | none | none | 350 ms | 48 | 35.7 | no screen | 1 |
| device check, after the Stop (a second load in the same process) | 85.5 s | 16,391 ms | 1,674 ms | 43 | 37.3 | no screen | 1 |
| the screen, recording mode (a new process) | 33.5 s | 9,410 ms | 1,816 ms | 43 | 23.4 | at every token (the version before this one) | 2 |

The device check runs in the app's process without the screen; recording mode shows the answer on the screen as it streams. The version of the app that ran these rows drew the answer again at every token; this version draws it at most once per frame and records the count. The phone came from another run at thermal status 2 with the GPU's top clock held at 726 MHz, and was at 1 with the clock back at 1,300 MHz by the device check. The recording-mode answer came at thermal status 2, and the time between its tokens grew from 35 ms to 41–49 ms; the device check's answer to the same photo and question, at 1, came at 37.2 tok/s. The causes of the difference are not established beyond that.

Each chunk carried one token here too (43 = 43 and 48 = 48 by the model's Hugging Face tokenizer). The decoder's seven signatures (prefill 1024, 256, 64, 16, 4 and 1, decode) and the vision encoder's graph (1,824 nodes) ran on the GPU delegate in full; the vision adapter ran on the CPU (XNNPACK took 24 of its 30 nodes). Setting up the GPU delegate for the decoder took 47 s at the first load and 68 s at the second. Opening a conversation took 9.4 to 16.4 s; it happens before READY and is not in the first token. The app's peak VmHWM was 5,174,308 kB in the device check and 5,367,156 kB on the screen. The second question's answer began "The sign is white with black text."

The same JPEG and question through LiteRT-LM 0.18.0 on a Mac CPU (greedy) gave the sign's words the same way and the same sentence after them up to its last words: "not to stand on or touch the grass", where the phone wrote "not to stand or walk on the grass".

One phone, one day; not a benchmark.

### 2026-10-14: Pixel 8a, CPU, the sample's first version

Measured on one Pixel 8a (Android 16, CP1A.260505.005) with litertlm-android 0.16.1 and the `vl_int8vis` file pushed into the app's files directory, on the CPU profile, offline, with the first version of this sample: its one example, a red bicycle on a lawn, and "What is this a photo of?". That version opened the conversation when Ask was tapped, not at READY. The three answers to that question were the same text and named the red bicycle on a lawn, a tree trunk, a white picket fence and a house; the second question, "What colour is it?", got "The bicycle in the image is red".

| where | load | opening the conversation | first token | tokens | tok/s |
|---|---|---|---|---|---|
| device check, the first load (with hashing and importing the pushed file) | 53.9 s | 5,096 ms | 5,806 ms | 142 | 13.8 |
| device check, a second question about the same photo | none | none | 1,301 ms | 44 | 14.0 |
| device check, after the Stop (a second load) | 32.7 s | 249 ms | 2,961 ms | 142 | 21.3 |
| the screen, recording mode (a new process) | 14.8 s | 164 ms | 3,000 ms | 142 | 18.6 |

The photo went to the model as a 768 x 547 JPEG of 223,031 bytes. The app's peak VmHWM on the screen was 2,523,800 kB; the thermal status was 1 throughout.

One phone, one day; not a benchmark.

## The example photos

Each is CC0 1.0 according to its Openverse record, and none shows a person, a brand or a logo. `tools/fetch_examples.py` downloads the file at the URL in `tools/examples.json` and checks its size and sha256; the app scales it like a picked photo before the model sees it.

| id | shows | file | source page | Openverse record | creator |
|---|---|---|---|---|---|
| `sign` | a white sign on a metal post in a lawn, reading "Please" above "KEEP OFF THE GRASS" | `sign.webp`, 1024 x 768, 522,608 bytes (the URL ends in .jpg; the server sends WebP) | [rawpixel](https://www.rawpixel.com/image/6038398/please-keep-off-the-grass-sign) | [`6fb8a9c2-c6db-4f22-a4d1-c58a115e503d`](https://api.openverse.org/v1/images/6fb8a9c2-c6db-4f22-a4d1-c58a115e503d/) | not named |
| `object` | light wooden chess pieces on a worn wooden chessboard, close up | `object.jpg`, 960 x 640, 525,823 bytes | [StockSnap](https://stocksnap.io/photo/chess-board-NUAITIXILP) | [`0cecc55a-9c56-4702-bfe9-94ff5a5ec2c5`](https://api.openverse.org/v1/images/0cecc55a-9c56-4702-bfe9-94ff5a5ec2c5/) | Kristin Hardwick |
| `scene` | a white lighthouse with a red base on rocks by the sea, dune grass in front, clear sky | `scene.jpg`, 1024 x 683, 234,622 bytes | [Flickr](https://www.flickr.com/photos/66741228@N05/33074457825) | [`bf0677b1-08c2-4d96-8dae-5f59e4640aa8`](https://api.openverse.org/v1/images/bf0677b1-08c2-4d96-8dae-5f59e4640aa8/) | Bonnie Moreland (free images) |

If a URL stops serving the same bytes, the script says so and writes nothing; the Openverse record and the source page are where to find the photo again.

## License

The sample's code is Apache-2.0, as the SDK. The model keeps its own license (Apache-2.0, named in its descriptor). The example photos are CC0 1.0.
