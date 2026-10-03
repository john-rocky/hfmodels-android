# samples/voice: talk to the phone, it acts and answers aloud

One screen, one microphone button. Speak; the phone hears it (Zipformer, GPU), acts on it with its own tools through `VoiceLoop` (Gemma 4 E2B, GPU: the alarm lands in the Clock app, the timer starts, the event goes into the app's own "Phone Agent" calendar) and answers aloud (Kitten, CPU).
The screen shows what was heard, each tool call with the phone's answer, what was said, and "Reply in 2.6 s": the time from the end of speech to the first sound, with its parts (the endpointer's 800 ms of silence, hearing, thinking, voice).
After an action the phone says the tool's own result ("Alarm set for 07:30 (Morning Alarm)"), not the model's words about it (`VoiceLoopConfig.speakActionResults`); the model's words are shown under it when they differ.
Everything runs on the phone; it works in airplane mode. `ViewModel` and screen: `VoiceViewModel.kt`, `VoiceScreen.kt`; the screen state from the loop's events: `VoiceUi.kt`.

## Build and permissions

```sh
./gradlew :samples:voice:installDebug
P=io.github.johnrocky.hfmodels.samples.voice
adb shell pm grant $P android.permission.RECORD_AUDIO
adb shell pm grant $P android.permission.READ_CALENDAR
adb shell pm grant $P android.permission.WRITE_CALENDAR
```

`SET_ALARM` is granted at install. Without the grants the app asks on its first start.

## Model files

| role | id | variant | backend |
|---|---|---|---|
| transcriber | `litert-community/Zipformer-medium-CR-CTC-LiteRT` | `medium_fp16` | GPU |
| speaker | `litert-community/kitten-tts-nano-0.8` | `fp32` | CPU |
| chat model with tools | `litert-community/gemma-4-E2B-it-litert-lm` | `default` | GPU |

Each load is pinned to a commit, so a phone without a network loads from the app's store. The transcriber's and the speaker's repos carry no `hfmodels.json` yet: the app ships their development descriptors (`catalog/dev`, as assets) and passes them as `LoadOptions(descriptorJson = …)`; once the repos carry one, the ids alone load them. The chat model is in the bundled catalog. The first load downloads the files (2.59 GB of them the chat model) into the app's private storage and verifies their sha256. The speaker's four `g2p/` files (`dp_g2p_matcha_fp16.tflite`, `g2p_dict.txt.gz`, `g2p_meta.json`, `symbols.json`) are not in its repo yet (checked 2026-10-03): until they are, a load of the speaker needs them side-loaded as below.

Development shortcut: copies in the app's external files dir are hashed and imported instead of downloaded. The side-load looks at file names only, so the speaker's `g2p/` files go there flat. The copies must belong to the app: on the Galaxy S26 a copy made by `adb shell` (owner `shell`) was unreadable to the app (`EACCES`) and the load went to the network; a copy made as the app (`run-as`, debug build) was imported.

```sh
P=io.github.johnrocky.hfmodels.samples.voice
adb shell mkdir -p /data/local/tmp/voice
adb push zipformer_ctc_fp16.tflite tokens.txt kitten_predictor.tflite kitten_prosody.tflite kitten_vocoder.tflite voices.npz \
  dp_g2p_matcha_fp16.tflite g2p_dict.txt.gz g2p_meta.json symbols.json gemma-4-E2B-it.litertlm /data/local/tmp/voice/
adb shell "run-as $P sh -c 'mkdir -p /sdcard/Android/data/$P/files && cp /data/local/tmp/voice/* /sdcard/Android/data/$P/files/'"
```

The next load logs `side-loaded <file> … (sha256 verified)` (`adb logcat -d -s hfmodels`); the copies can go after it.

## Launch extras

Debug builds only: a release build ignores them.

| extra | what it does |
|---|---|
| `--ez autoload true` | load the three models now |
| `--ez autolisten true` | open the microphone once they are loaded |
| `--es say "<text>"` | one turn from this text once they are loaded (no microphone) |
| `--ef start_rms 0.01` | the endpointer's start level (default 0.02) |
| `--es record <name>` | each turn under `<external files>/record/<name>/<turn>/`: `utterance.wav` (16 kHz, microphone turns), `reply.wav` (24 kHz, the sentences said, synthesized again after the turn), `events.json` (every event with `System.nanoTime`, `elapsedRealtime` and the wall clock, and the player's first write) |

A running screen takes them again (`singleTop`). With any of these extras the screen shows over the keyguard and turns the display on (scripted mode); a normal launch does not. While the screen is not visible, the microphone and any request in progress stop. Without that, on a locked phone, Android dropped the Clock app's `SET_ALARM` start from the app (`Background activity launch blocked!`, `BAL_BLOCK`, result code 102) and the app's process ran in the background cpuset; the alarm tool now checks Android's next alarm clock and says when the Clock app did not take the alarm. `adb logcat -d -s hfmodels-voice-sample` prints one `TURN` line per turn: what was heard, the calls and their results, the milliseconds, what was said, the model's own reply, Android's next alarm, the network.

The endpointer starts an utterance at `start_rms` 0.02, a voice spoken toward the phone. Quieter sound through a speaker needs a lower threshold: `--ef start_rms 0.01` here, `VoiceLoopConfig(endpointer = Endpointer(startRms = 0.01f))` in code.

## Airplane mode

```sh
adb shell svc power stayon usb; adb shell input keyevent KEYCODE_WAKEUP
adb shell cmd connectivity airplane-mode enable
adb shell am start -n $P/.MainActivity --ez autoload true --es say "'Set an alarm for seven thirty tomorrow morning.'"
adb shell dumpsys alarm | grep -A1 "Next alarm clock information"
adb shell cmd connectivity airplane-mode disable; adb shell svc power stayon false
```

The alarm is real: delete it in the Clock app afterwards (on the Samsung Clock a dismiss by label did not remove it).

## Device check

`src/androidTest/kotlin/io/github/johnrocky/hfmodels/check/VoiceDeviceCheck.kt` is drop-in (its KDoc has the install steps): the three loads from the store, one command's WAV through the endpointer and `turn(pcm)` with the real `PhoneTools`, the alarm against `AlarmManager.nextAlarmClock`, the network state, the release. Keep the APKs installed: uninstalling the app deletes its model store. Android reports one next alarm, so no alarm may be set at or before 07:30 when the check starts (the 07:30 alarm of the block above included: delete it first); otherwise the check stops with `RESULT step=precondition ok=false` and names the alarm Android reports. The network and the cleanup are `RESULT info` lines.

```sh
./gradlew :samples:voice:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.johnrocky.hfmodels.check.VoiceDeviceCheck
adb logcat -d -s hfmodels-check | grep RESULT
```

## What it did on 2026-10-03 (Galaxy S26 SM-S942Q, Android 16, LiteRT 2.2.0, LiteRT-LM 0.16.1, airplane mode)

The scripted turn (`litertlm/results/2026-10-03-1712-RFGL80R6A6H-litertlm0.16.1-voicesample-say-airplane.log`; `dumpsys alarm` then reported the next alarm clock at 2026-10-04 07:30):

```
TURN input=text heard="Set an alarm for seven thirty tomorrow morning." tools=[get_current_datetime()->"Saturday, 2026-10-03 17:12",set_alarm(hour=7, minute=30, label="Morning Alarm")->"Alarm set for 07:30 (Morning Alarm)"] ms_first_audio=1602 hangover_ms=0 ms_end_of_speech_to_sound=1602 ms_transcribe=0 ms_first_token=708 ms_first_sentence=1301 ms_reply=1652 ms_total=5213 spoken="Alarm set for 07:30 (Morning Alarm)" reply="I have set an alarm for seven thirty tomorrow morning." phone="Next alarm (Android): Sun 07:30" network=none
```

The device check (`litertlm/results/2026-10-03-1713-RFGL80R6A6H-litertlm0.16.1-voicecheck-airplane.log`; the alarm of the scripted turn was already set, so `next_alarm_before` is 07:30 too):

```
RESULT step=turn ok=true wav=/data/local/tmp/hfmodels-voice/commands/c01.wav heard="Set an alarm for seven thirty to morrow morning." expect="Set an alarm for seven thirty tomorrow morning." heard_ok=true calls=[get_current_datetime{},set_alarm{hour=7.0, label=Morning Alarm, minute=30.0}] tool_ok=true alarm_set=true next_alarm_before=Sun-07:30 next_alarm_after=Sun-07:30 phone="Next alarm (Android): Sun 07:30" ms_first_audio=1797 hangover_ms=800 ms_total=5404 ms_transcribe=88 ms_first_token=819 speaking=1 errors=[] spoken="Alarm set for 07:30 (Morning Alarm)" reply="I have set an alarm for seven thirty tomorrow morning."
RESULT step=network ok=true network=none airplane_mode=true
RESULT ok=true failed=[] network=none device=SM-S942Q build=BP4A.251205.006.S942QOPS1AZH9
```

The ten fixed commands' WAVs through the same loop with recording tools (`voice` module's `VoiceLoopDeviceTest`), the transcript as it came and as a sentence (`litertlm/results/2026-10-03-1714-…-airplane-raw-…log`, `…-1715-…-airplane-normalize-…log`):

```
RESULT step=summary ok=true ... normalize=false success=9/10 ms_transcribe_median=59 ms_first_token_median=889 ms_first_sentence_median=1365 ms_first_audio_median=1916 ms_reply_median=1873 ms_speak_median=2119 ms_total_median=2119 hangover_ms=800
RESULT step=summary ok=true ... normalize=true success=9/10 ms_transcribe_median=72 ms_first_token_median=818 ms_first_sentence_median=1513 ms_first_audio_median=2060 ms_reply_median=1860 ms_speak_median=2060 ms_total_median=2060 hangover_ms=800
```

Through the microphone it did not run yet: a Mac's speaker at 50 % volume reached `mic_level max_rms=0.0152 start_rms=0.02` at the phone, below the endpointer's start (`litertlm/results/2026-10-03-1716-RFGL80R6A6H-litertlm0.16.1-voicesample-mic-macsay.log`).

Numbers from one phone on one afternoon, the models loaded and warm; not a benchmark.
