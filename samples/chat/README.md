# samples/chat: a chat screen, and Ask a document

The app has two icons. **hfmodels chat** is the chat screen on the SDK: type a Hugging Face id, load, chat, stop, release. **Ask a document** is one job: paste a long document, ask one question, and [`litert-community/Spark-X2.6-1.7B`](https://huggingface.co/litert-community/Spark-X2.6-1.7B) reads the whole document on the phone and answers. The document does not leave the phone.

## Run Ask a document

The model file needs LiteRT-LM 0.19.0, above the 0.16.1 the SDK modules pin, so this screen needs the build line below. In that build the chat screen's catalog models refuse to load (`RUNTIME_VERSION_MISMATCH`); build without the two `-P` flags for them.

```sh
./gradlew :samples:chat:installDebug -PlitertlmVersion=0.19.0 -PskipKotlinMetadataCheck=true
```

- **The file:** press **Load**. The first load downloads `Spark-X2.6-1.7B_int4-mlp.litertlm` (1.7 GB) from the repo's commit `127d3acb` and checks its sha256. To skip the download, open the screen once and push your own copy; it is hashed and copied into the app's store at the next load, and you can delete the pushed copy afterwards:
  ```sh
  adb push Spark-X2.6-1.7B_int4-mlp.litertlm /sdcard/Android/data/io.github.johnrocky.hfmodels.samples.chat/files/
  ```
- **The thinking level:** **none**, **low** or **high**, picked before **Ask**. The model's reasoning streams in grey above the answer. The file holds 4,096 tokens, document, reasoning and answer together, so a level that reasons longer leaves a long document less room to finish.
- **The example documents:** **Examples** fills both fields with one of three invented documents of about 2,600 tokens and a question about it: a group chat, a coffee machine manual, a work email thread (`src/main/assets/examples.json`). Paste your own text over them.

Under each answer: the document's read time and token count, then the tokens written (reasoning included) and their rate, both from the runtime's own counts (`ChatSession.benchmarkInfo()`). Every question gets a conversation of its own with no system prompt, and the conversation is closed after the answer. The app sets no sampler; the file declares none, so the runtime's default applies.

`descriptor/spark-x2.6-1.7b.spec.json` is the source of the descriptor in the app's assets (`tools/hfmodels_descriptor.py`, then the entry's `descriptor`). The repo carries no `hfmodels.json` and the SDK's catalog has no entry for it, so the screen passes the descriptor as `LoadOptions.descriptorJson`.

A debug build also takes the recording entry at the end of `DocumentActivity.kt`: an intent that drives the same fields and buttons a finger does and writes one JSON per question under `files/runs/`.
