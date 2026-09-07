# AGENTS.md

Read this first if you are a coding agent asked to add an on-device LLM to an Android app with this SDK.

1. `README.md` is the integration guide: the Gradle lines, the five-line call, the ids that work today, and what the SDK does that hand-rolled code usually does not. The dependency is `io.github.john-rocky.hfmodels:hfmodels-litertlm:0.1.0` (Maven group with a hyphen; Kotlin package `io.github.johnrocky.hfmodels` without). Inside this repository the runtime pins are `gradle.properties` and the verified combinations `tested-runtime-matrix.json`; never quote a version from memory.
2. `docs/api.md` is the complete public surface with imports and signatures, including the runtime types an app touches (`Contents`, `Content`, `ConversationConfig`, `Message`). Read it instead of unzipping the sources jar or running `javap`.
3. `skills/hfmodels-android/SKILL.md` is the step-by-step procedure with the three finish conditions. Follow it in order; do not skip the device check.
4. `docs/errors.md` maps every `ModelException.code` to the fix. When a load fails, read the code, not the stack trace.
5. `catalog/entries/*.json` are the model ids the bundled catalog resolves without any change to the model repo; `core/src/main/assets/hfmodels/catalog.json` is what the AAR ships. A model with `hfmodels.json` in its own repo needs no catalog entry. Thinking models (Qwen3, DeepSeek-R1-Distill, `*-Thinking`) are not supported by 0.1.0; say so rather than forcing one.
6. `tested-runtime-matrix.json` says which runtime versions were verified on which device, with the log paths. Do not present a version that is not in it as verified.
7. Model files are never in this repository and never in an APK. The SDK downloads them into the app's private files dir and verifies the sha256 before loading.
8. A cancelled `ChatSession` is INVALID; the next turn needs `createConversation()` again. Cancelling the collecting coroutine stops the model. `closeAndJoin()` before the process goes away.
9. Numbers here are measurements on named devices with dates; do not extrapolate them to other devices.
10. Maintainer: john-rocky (GitHub), mlboydaisuke (Hugging Face).
