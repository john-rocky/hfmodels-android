# probes/scoring: one prefill, N decisions on a language-model bundle

Not part of the SDK. It measures the primitive the typed-decisions API would use on a `.litertlm` bundle: prefill a rendered prompt once, save a checkpoint, score each candidate continuation, rewind between candidates. The published LiteRT-LM Kotlin API has no scoring or checkpoint call, so the probe uses the Kotlin sources and a JNI library built from the branch that adds them ([john-rocky/LiteRT-LM `kotlin-text-scoring`](https://github.com/john-rocky/LiteRT-LM/tree/kotlin-text-scoring): `Session.runTextScoring`, `saveCheckpoint`, `rewindToCheckpoint`, `rewindToStep`, `currentStep`, `SessionConfig.applyPromptTemplate`).

`sync.sh` copies the branch's sources and libraries in (they are never committed); the test compares the phone's letter scores with a published oracle (the SemIf `authored144` rows rendered for Qwen3-0.6B, fp32 last-position logits over the letter slots; `src/androidTest/assets`) and times the shared arm against a fresh prefill per candidate:

```sh
probes/scoring/sync.sh ~/code/litert-lm-scoring-wt <dir with libLiteRt.so from litert-2.2.0.aar>
adb push qwen3_0_6b_mixed_int4.litertlm /data/local/tmp/hfmodels/
export ANDROID_SERIAL=<serial>
./gradlew :probes:scoring:connectedDebugAndroidTest -PscoringJniLibs=probes/scoring/jniLibs \
  -Pandroid.testInstrumentationRunnerArguments.model=/data/local/tmp/hfmodels/qwen3_0_6b_mixed_int4.litertlm \
  -Pandroid.testInstrumentationRunnerArguments.backend=gpu
adb logcat -d -s hfmodels-scoring | grep RESULT
```

`results/` holds the runs. What they showed on 2026-09-21: the binding works end to end; with the runtime as on `main` the score of the same letter changed after a rewind (up to 1.58 nats) and the top letter read far below the oracle, which is the double rewind reported in google-ai-edge/LiteRT-LM#3561; with the pending fix (#3562) applied the drift is 0.00000 and the top letter's log probability tracks the fp32 oracle within the quantized bundle's error. The runtime returns the log probability of the target (negative), not the negative log probability its header comment describes; the session reports one token more than the rendered prompt (a start token the runtime prepends).
