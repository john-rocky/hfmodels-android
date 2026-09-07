# AGENTS.md

Read this first if you are a coding agent asked to add an on-device LLM to an Android app with this SDK.

1. `README.md` is the integration guide: the Gradle lines, the five-line call, the ids that work today, and what the SDK does that hand-rolled code usually does not. Quote versions from `gradle.properties` and `tested-runtime-matrix.json`, never from memory.
2. `skills/hfmodels-android/SKILL.md` is the step-by-step procedure with the three finish conditions. Follow it in order; do not skip the device check.
3. `docs/errors.md` maps every `ModelException.code` to the fix. When a load fails, read the code, not the stack trace.
4. `catalog/entries/*.json` are the model ids the bundled catalog resolves without any change to the model repo; `core/src/main/assets/hfmodels/catalog.json` is what the AAR ships. A model with `hfmodels.json` in its own repo needs no catalog entry.
5. `tested-runtime-matrix.json` says which runtime versions were verified on which device, with the log paths. Do not present a version that is not in it as verified.
6. Model files are never in this repository and never in an APK. The SDK downloads them into the app's private files dir and verifies the sha256 before loading.
7. A cancelled `ChatSession` is INVALID; the next turn needs `createConversation()` again. Cancelling the collecting coroutine stops the model. `closeAndJoin()` before the process goes away.
8. Numbers here are measurements on named devices with dates; do not extrapolate them to other devices.
9. Maintainer: john-rocky (GitHub), mlboydaisuke (Hugging Face).
