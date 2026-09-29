# samples/pong

A Pong the phone plays by looking at its own frames. The right paddle is moved by a vision-language model, [`litert-community/decider-2b-vision-LiteRT`](https://huggingface.co/litert-community/decider-2b-vision-LiteRT) (`int8`, 3,171,081,088 bytes), running on the phone through LiteRT-LM. The left paddle is scripted: it follows the ball. The game is the 160x210 Atari layout.

## The screen

Top to bottom: the state (LOADING, READY, PLAYING, DONE) with the decision count and the last answer time; the field, the game frame scaled up with nearest-neighbour sampling, labelled `script` and `model` above its corners; the decision card, with the question, the three options (the one the model chose is highlighted), the answer time and the 256x256 image the model was sent; the score line; the model, variant, backend profile, phone, runtime and SDK versions.

Nothing is drawn inside the field except the game: the image the model gets is the frame on screen, resized. Between two game states the ball and the paddles glide for 350 ms on screen; the model is only sent the states themselves.

## One decision

1. Render the game state at 160x210, resize it to 256x256 (bicubic, computed the way Pillow's `Image.BICUBIC` computes it), encode it as PNG.
2. Open a new conversation (`chat.createConversation(ConversationConfig())`): one image and one question per request, no history.
3. `session.stream(Contents.of(Content.ImageBytes(png), Content.Text(prompt)), GenerationOptions(maxOutputTokens = 1))`: the image first, then the text, which ends in `Answer: (`.
4. The first non-whitespace character of the answer is the letter: `A` up, `B` down, `C` stay; anything else counts as unparsed and the paddle stays.
5. Close the conversation, move the paddle, advance the ball one step.

The answer time on screen is measured from calling `stream` to the first chunk that carries text.

## Run it

The model file is not in the APK. The app loads the model at Hub commit `6c024e946bb8489f3faacb2514b0c61b355d1fdb` (`ModelRef(…, revision = …)`), the commit the descriptor in `assets` was generated from. The first Load downloads it from the Hub and checks its sha256. During development a copy pushed into the app's files directory is hashed and imported instead:

```sh
./gradlew :samples:pong:assembleRelease
adb install -r samples/pong/build/outputs/apk/release/pong-release.apk
adb push decider-2b-vision_int8.litertlm /sdcard/Android/data/io.github.johnrocky.hfmodels.samples.pong/files/
```

A normal start has a backend choice (auto, gpu, cpu) and Load, Play, Stop. Recording mode loads, waits `delay` seconds after READY, plays `steps` decisions and stays on DONE. It shows over the lock screen and keeps the screen on:

```sh
adb shell am start -n io.github.johnrocky.hfmodels.samples.pong/.MainActivity \
    --ez autostart true --ei delay 3 --ei steps 60 --ei seed 7 --es backend gpu
adb logcat -s pong
```

`--es backend gpu` loads with `BackendPolicy.Require(BackendKind.GPU)`, `cpu` with `Require(BackendKind.CPU)`; without it `BackendPolicy.Auto` picks the descriptor's default profile (`gpu`). `seed` drives the serves.

## The record

At DONE or Stop the app writes `pong-result-<epoch s>.json` and `frames-<epoch s>/NNN.png` (the exact PNG bytes each decision sent) to `/sdcard/Android/data/io.github.johnrocky.hfmodels.samples.pong/files/`:

- run: `steps`, `completed_steps`, `stopped`, `failure`, `seed`, `backend_policy`, `profile`, `variant`, `model` (repo@commit), `sdk_version`, `runtime_version`, `device` (`Build.MODEL`, `Build.DISPLAY`), `prompt`
- times: `load_ms` (`load_downloaded` says whether it included a download), `answer_ms_median`, `answer_ms_p90`, `conversation_ms_median`, `total_s`
- game: `hits`, `misses` (the model's paddle), `left_hits`, `left_misses`, `serves`, `unparsed`
- `thermal_before`, `thermal_after` (`PowerManager.currentThermalStatus`)
- `steps_log`, one object per decision: `step`, `letter`, `raw_text`, `action`, `answer_ms`, `stream_ms` (to the end of the stream), `conversation_ms`, `close_ms`, `state_before` (`ball`, `v`, `paddle`, `left`), `event` (`hit`, `miss`, `left_miss` or null)

Logcat tag `pong`: `READY profile=… load_ms=…`, one `STEP n letter=… action=… answer_ms=… conv_ms=… event=…` per decision, `DONE json=<path>`.

## Tests

`./gradlew :samples:pong:testReleaseUnitTest` checks the parts that do not need a phone against reference files in `src/test/resources/fixtures`: the prompt bytes, the 160x210 frames pixel for pixel, the 256x256 resize, the game physics replayed over an 80-step trace, and the answer parsing.

## Numbers

Measured in the next round.
