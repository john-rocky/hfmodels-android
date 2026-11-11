# Repository layout

```
core/        hfmodels-core: ModelRef, HfModels (inspect / download / prepare), descriptor + catalog readers, Hub client, content-addressed store, errors
litertlm/    hfmodels-litertlm: Tasks.Chat, ChatModel / ChatSession on LiteRT-LM (tool calls from 0.2.0), consumer R8 rules, GPU manifest entries
litert/      hfmodels-litert: EncoderDecisions / TypedDecisions on LiteRT CompiledModel (decision encoders), Transcribe (Zipformer CTC) and Speak (KittenTTS on the Interpreter API), the host-side parity tests, the device gates; results/ their logs
voice/       hfmodels-voice: VoiceLoop (Endpointer, SentenceSplitter, MicSource, SpeechPlayer) and the tools (ToolRunner, VoiceTool, PhoneTools); androidTest/ the speech, tools and loop gates
samples/chat the chat screen on the SDK (id in, chat out; under each answer its first-token time and tok/s; a Think before answering box for a model that reasons on every turn); its androidTest/ holds the drop-in ChatDeviceCheck
samples/decide four screens on typed decisions (voice gate, clipboard, query x passages, your unread texts or pasted ones sorted on one tap) with the milliseconds on screen; its androidTest/ holds the inbox's device check
samples/voice one microphone button over VoiceLoop with the phone's real tools, the time from the end of speech to the first sound on screen; its androidTest/ holds the drop-in VoiceDeviceCheck
samples/promises a conversation sorted sentence by sentence into You promised / They asked you / Plans on GLiNER2.5-Decide, the milliseconds on screen; its androidTest/ holds a device check, results/ the Galaxy S26 records
samples/eg2search hold a button and say what is in a photo: EmbeddingGemma 2 740M finds it in your own album, added through the photo picker (LiteRT-LM 0.18.0 directly, not an SDK task); its androidTest/ holds a device check
samples/ask  your photo (the system photo picker) and your question with two to five options, answered on the phone with one letter, further questions about the same photo as text-only turns; Demo: a bar chart the app draws and five questions marked against its data; its androidTest/ holds a device check, results/ the Galaxy S26 records
samples/pong a Pong the phone plays from its own frames, a new conversation per decision; its README says why the answers after the first are unreliable on this model (LiteRT-LM#3165); results/ the records
samples/translate one button per person, offline: French or Spanish speech becomes an English voice, English speech becomes French or Spanish text (Qwen3-ASR-1.7B through the sample's own descriptor and handler, not an SDK task; Hy-MT2-1.8B by id; KittenTTS), the seconds from the end of speech on screen; its androidTest/ holds a device check, fixtures/ its sentences and the script that makes the WAVs
samples/loops one question, two files of the same model side by side: NVIDIA's Dory1.0-4B with its shared block of layers run once (int4_1loop) or twice (int4) per token, each answer with its first-token time, tokens per second and seconds; examples include GSM8K questions marked against their known answers; its androidTest/ holds a device check, results/ the Galaxy S26 records
catalog/     specs (curated) -> entries (generated) -> the bundled asset; dev/ the development descriptors (the decision graphs, FunctionGemma); proposals/ the hfmodels.json drafts for model repos; tools/ generate and gate them
probes/      the runtime coexistence probe (LiteRT-LM + LiteRT in one release APK) and its logs
docs/        api.md (the complete surface), errors.md, publishing.md; skills/ the agent procedure (hfmodels-android) and a draft on choosing and running decision models (typed-decisions-on-device); tested-runtime-matrix.json the verified matrix
```
