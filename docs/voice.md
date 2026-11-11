# Voice (0.2.0)

The third thing the SDK runs: the phone hears a request, does it with its own tools and answers aloud, with no network.

```kotlin
// implementation("io.github.john-rocky.hfmodels:hfmodels-voice:0.3.0")   (+ android.uniquePackageNames=false, see docs/api.md "Voice")
val asr = HfModels(app).fromPretrained(ModelRef("litert-community/Zipformer-medium-CR-CTC-LiteRT"), Transcribe)
val tts = HfModels(app).fromPretrained(ModelRef("litert-community/kitten-tts-nano-0.8"), Speak)
val chat = HfModels(app).fromPretrained(ModelRef("litert-community/gemma-4-E2B-it-litert-lm"), Tasks.Chat)
val loop = VoiceLoop(asr, chat, tts, PhoneTools.all(app), VoiceLoopConfig(player = SpeechPlayer()))
loop.listen(MicSource().chunks()).collect { e -> show(e) }   // per utterance: Heard, Thinking, ToolCalled, Speaking, Done
```

The app declares the permissions in its manifest: `RECORD_AUDIO` for `MicSource`, and `com.android.alarm.permission.SET_ALARM`, `READ_CALENDAR` and `WRITE_CALENDAR` for `PhoneTools`; the modules declare none of them.

- Hears: the microphone in 20 ms chunks, an energy endpointer that ends an utterance after 800 ms of silence, and Zipformer CTC (English; one 16 s window per utterance) on LiteRT's `CompiledModel`, on the GPU.
- Acts: the transcript goes to the chat model with the phone's tools (`PhoneTools`: the time, alarms and timers in the Clock app, events in the app's own calendar). The runtime parses Gemma 4's calls, the app runs them (the runtime never does), and the model answers. A tool says only what it could check: the alarm is "set" once Android reports it as the next alarm, "requested" when another alarm at that minute or earlier comes first; a timer is always "requested", because Android has no public API to read the Clock app's timers.
- Says: KittenTTS nano speaks the answer sentence by sentence while it streams, on the CPU. After an action it says the tool's result ("Alarm set for 07:30 (Morning Alarm)"), not the model's words about it: for "Wake me up at six fifteen" Gemma 4 E2B once called `set_alarm(hour = 16, minute = 15)` and said "6:15" (`litertlm/results/2026-10-03-1637-…`, c02).

Both speech repos carry `hfmodels.json`: Zipformer from commit fa063a88, Kitten from 2c5b198f, which also added the four G2P files under `g2p/`. The bundled catalog pins those two commits. The first load by id resolves the repo's branch over the network, reads its `hfmodels.json`, downloads the files the store lacks, checks their sha256 and binds the id to that commit; later loads use the binding and the stored files, with or without a network. `samples/voice` pins the catalog's commits instead. On the Galaxy S26 below, on 2026-10-05, `TranscribeDeviceTest` and `SpeakDeviceTest` loaded both ids by id over LTE, which bound them to those commits, and again in airplane mode through the binding; `samples/voice`, in airplane mode, loaded both at the pinned commits from the catalog's entries and answered a typed "What time is it?" aloud after `get_current_datetime` (`litert/results/2026-10-05-0957-…`, `…-0958-…`; `litertlm/results/2026-10-05-1005-…`). The phone downloaded none of their files: the apps' stores already held them from earlier runs (the store keeps a file under its sha256). The download ran on a Mac's JVM instead, where the SDK's resolver and store planned both ids and fetched their files from the Hub, 2 for Zipformer `medium_fp16` and 8 for Kitten `fp32`, every sha256 matching (`litertlm/results/2026-10-05-0952-mac-jvm-live-hub-resolver-store.log`). `VoiceDeviceCheck` and the requests that set an alarm or a timer or use the calendar have not run at these commits.

Every run below predates those commits: it passed a development descriptor with the same variants and files explicitly at the commit before (7732ad6c, d4662d89), imported every speech file from a local copy and checked its sha256 (the G2P files too, which the Kitten repo did not carry then), and downloaded nothing from the Hub. The files' sha256 are the ones the current commits list. On 2026-10-05 the gate ran again at those commits, pinned, in airplane mode, with the repos' own `hfmodels.json`: both Zipformer variants on the GPU and on the CPU, both Kitten variants with the predictor without XNNPACK and the speed priors. All six passed (`litert/results/2026-10-05-*`; their numbers are in `tested-runtime-matrix.json`); the tables keep the 2026-10-03 numbers.

The measurements are one Galaxy S26 SM-S942Q (Android 16 BP4A.251205.006, LiteRT 2.2.0, LiteRT-LM 0.16.1) on 2026-10-03. The spoken commands are ten synthetic WAVs (macOS `say`, voice Samantha, `tools/voice_fixtures.sh`), not a person; the loop has not been measured through the microphone with a person's voice. Each row is one run of the test or app it names; the logs are under `litert/results/` and `litertlm/results/`.

Transcribe (`TranscribeDeviceTest`, `tools/transcribe_gate.sh`; commit ebaa5bd; thermal status 0):

| variant (graph) | backend | transcript equal to the command (lower case; letters, digits and word breaks; 10 WAVs) | graph run, median of 10 warm calls (min to max) | whole call (fbank, graph, CTC), median | load | log |
|---|---|---|---|---|---|---|
| `small_fp16` (46 MB) | GPU | 2/10 | 29.4 ms (28.9 to 30.4) | 52.8 ms | 2.05 s | `…-1421-…-transcribe-zipformer-small_fp16-gpu.log` |
| `small_fp16` | CPU (4 threads) | 3/10 | 141.8 ms (141.0 to 143.6) | 151.0 ms | 0.18 s | `…-1422-…-transcribe-zipformer-small_fp16-cpu.log` |
| `medium_fp16` (131 MB) | GPU | 4/10 | 37.1 ms (36.3 to 40.8) | 60.6 ms | 2.50 s | `…-1422-…-transcribe-zipformer-medium_fp16-gpu.log` |
| `medium_fp16` | CPU (4 threads) | 5/10 | 187.9 ms (186.4 to 188.4) | 196.6 ms | 0.32 s | `…-1422-…-transcribe-zipformer-medium_fp16-cpu.log` |

The text comes in capitals without punctuation, and the comparison is strict: `medium_fp16` on the GPU wrote "SET AN ALARM FOR SEVEN THIRTY TO MORROW MORNING", "WOULD A TEAM STAND UP ON MY CALENDAR …" for "Put a team standup on my calendar …" and "B IS ON MY CALENDAR TO MORROW" for "What is on my calendar tomorrow?". Every warm repeat gave the same text as the first call.

Speak (`SpeakDeviceTest`, `tools/speak_gate.sh`: ten fixed replies of 34 to 77 characters, voice `expr-voice-2-m`, CPU with 4 threads; the median is over the warm repeat):

| variant (graphs) | XNNPACK | pace | median synthesis (min to max) | real-time factor, median | peak RSS after the load (VmHWM) | same samples on the warm repeat | log |
|---|---|---|---|---|---|---|---|
| `fp32` (64 MB) | all three graphs | graph speed 1.0 | 210.4 ms (174.0 to 365.9) | 0.075 | 626,732 kB | 10/10 | `…-1500-…-speak-kitten-fp32-cpu.log` |
| `fp16` (32 MB) | all three graphs | graph speed 1.0 | 213.5 ms (174.7 to 366.4) | 0.075 | 602,152 kB | 10/10 | `…-1500-…-speak-kitten-fp16-cpu.log` |
| `fp32` | all three graphs | the publisher's (`say.py`) | 288.3 ms (227.0 to 465.3) | 0.076 | 624,404 kB | 10/10 | `…-1550-…-speak-kitten-fp32-cpu.log` |
| `fp32` | prosody and vocoder only (the default) | the publisher's | 301.2 ms (253.6 to 559.6) | 0.086 | 334,360 kB | 10/10 | `…-1550-…-speak-kitten-xnnpackoff-predictor-fp32-cpu.log` |

The 15:00 runs (commit d0130aa, thermal status 0) predate the speed priors: the graph got speed 1.0, a faster pace than the publisher's `say.py` (0.8 for this voice), so their replies are shorter (126 frames for the first one against 149). The 15:50 runs (thermal status 1) apply the priors, as the SDK does now, and differ only in XNNPACK on the predictor; they gave the same frames. The real-time factor is synthesis time over audio length. The SDK turns text into symbols without espeak, so its ids are not espeak's: on the publisher's three bench sentences none matched (43, 68 and 133 symbols against espeak's 41, 63 and 138, the 15:00 logs). The synthesizer alone, given the publisher's own ids, produced the publisher's durations (111, 163 and 281 frames; the samples differ from the publisher's Mac run by up to 0.83; `…-1501-…-kitten-synth-fp32-cpu.log`). After the ten replies the peak (VmHWM) of the two 15:50 runs was 869,020 kB with XNNPACK on all three graphs and 614,304 kB with the default (`step=memory after_speak` in their logs).

The loop with Gemma 4 E2B (`litert-community/gemma-4-E2B-it-litert-lm`, `default`, GPU), Zipformer `medium_fp16` on the GPU and Kitten `fp32` on the CPU, the ten commands. A command succeeds when the expected calls were made with the expected arguments. Times are in ms from the end of the utterance, medians over the ten commands in the first two rows and one command's values in the last two; for a WAV the utterance ends with the endpointer's 800 ms of silence, so a speaker hears the first sound 800 ms later than the column says:

| input | run | succeeded | transcript | model's first token | first sentence ready | first sound | log |
|---|---|---|---|---|---|---|---|
| typed text | `VoiceLoopDeviceTest`, tools that record and do nothing, no player (first sound = the first sentence synthesized); commit 7fcaae9 | 9/10 | 0 | 624 | 1,615 | 2,273 | `…-1638-…-voiceloop-text-gemma-4-E2B-…-gpu-runtime.log` |
| the WAVs, each with 1 s of silence, through the endpointer | the same | 8/10 | 61 | 816 | 1,215 | 1,870 | `…-1637-…-voiceloop-wav-gemma-4-E2B-…-gpu-runtime.log` |
| one typed command, "Set an alarm for seven thirty tomorrow morning." | `samples/voice` in airplane mode, the real tools and the loudspeaker (first sound = the player's first write); commit 1f4ab93 | 1/1; Android then reported the next alarm at 07:30 | 0 | 708 | 1,301 | 1,602 | `…-1712-…-voicesample-say-airplane.log` |
| the same command's WAV | `VoiceDeviceCheck` in the sample's process, airplane mode, the real tools and the loudspeaker | 1/1 (heard, calls, network none) | 88 | 819 | not recorded | 1,797 | `…-1713-…-voicecheck-airplane.log` |

The rows from commit 7fcaae9 predate two rules the loop has now: it said the model's words after an action (it now says the action's result) and passed Zipformer's capitals to the model as they came (it now passes a sentence, `VoiceLoopConfig.normalizeTranscript`); those rows were not run again. Their misses: with audio, c02 set the alarm at 16:15 and c08, heard as "WOULD A TEAM STAND UP …", asked for the date instead of adding the event; typed, c10 set 09:30 for "half past nine tonight". In the `VoiceDeviceCheck` row the 07:30 alarm of the row above was already Android's next alarm, so that run does not show a new alarm being set.

The chat models tried for the loop (`ToolsDeviceTest`, `tools/tools_gate.sh`: the ten commands as text through `ToolRunner` with tools that record and do nothing, the transcriber and the speaker loaded in the same process; commit 70ec224):

| chat model (variant, backend; how its calls travel) | succeeded | further calls | median first token / reply | the first command again on the same load | first sound of that command | thermal status | log |
|---|---|---|---|---|---|---|---|
| `litert-community/gemma-4-E2B-it-litert-lm` (`default`, GPU; parsed by the runtime) | 10/10 | 5 | 650 / 1,728 ms | the same calls and reply | 1,478 ms | 0 | `…-1601-…-tools-gemma-4-E2B-…-gpu-runtime.log` |
| `litert-community/Qwen3-1.7B` (`int4`, GPU; parsed by the runtime; no reasoning asked) | 8/10: c01 set 23:30, c06 read today's calendar | 3 | 1,875 / 3,761 ms | the same calls and reply | 5,962 ms | 0 → 2 | `…-1601-…-tools-Qwen3-1.7B-int4-gpu-runtime.log` |
| `litert-community/LFM2.5-1.2B-Instruct` (`int4_gpu`, GPU; LFM2's call text, parsed by `hfmodels-voice`) | 8/10: c02 no call, c10 set 17:00 | 6 | 871 / 2,850 ms | a different call and reply ("7:30 PM") | 3,377 ms | 2 | `…-1602-…-tools-LFM2.5-1.2B-Instruct-int4_gpu-gpu-lfm.log` |

"Further calls" are calls other than the expected ones, such as `get_current_datetime` before `set_alarm`; they do not fail a command. The repeat runs on a new conversation of the same loaded model, as every turn does; on LFM2.5 it gave another answer, and this run does not show whether the previous conversation's state carried over (LiteRT-LM#3165). `samples/voice` runs the first row with `medium_fp16` on the GPU and Kitten `fp32` on the CPU; its README has the screen and the airplane-mode procedure, and `samples/voice/src/androidTest/.../VoiceDeviceCheck.kt` is the drop-in check for an app. One phone, one day: not a promise for other devices, other voices or a real room.
