#!/bin/bash
# Voice loop device gate on ONE named device: the voice module's VoiceLoopDeviceTest (the transcriber, the speaker and one
# chat model loaded side by side; the ten fixed commands through VoiceLoop with recording tools, from their WAVs through
# the endpointer or from their text; with play=true the first command once more out of the loudspeaker; listen over the
# first WAV; release) for one chat model, variant, backend, tool-call format and input, with the device state in the log
# header and the RESULT lines from logcat. Copied from tools/tools_gate.sh. Run it on committed code: the log's git= is
# the commit the test ran.
#   export ANDROID_SERIAL=<serial>
#   tools/voiceloop_gate.sh <repo id> <variant> <gpu|cpu> <runtime|qwenxml|lfm> <wav|text> [extra -Pandroid.testInstrumentationRunnerArguments.* ...]
#   e.g. tools/voiceloop_gate.sh litert-community/gemma-4-E2B-it-litert-lm default gpu runtime wav -Pandroid.testInstrumentationRunnerArguments.dir=/data/local/tmp
#        GATE_TAG=play tools/voiceloop_gate.sh litert-community/gemma-4-E2B-it-litert-lm default gpu runtime wav \
#          -Pandroid.testInstrumentationRunnerArguments.dir=/data/local/tmp -Pandroid.testInstrumentationRunnerArguments.play=true
# The chat model file must be under /data/local/tmp/hfmodels/llm (or the dir argument) unless the store has it, the
# Zipformer and kitten files as for tools/transcribe_gate.sh and tools/speak_gate.sh, and the WAVs + commands.tsv under
# /data/local/tmp/hfmodels-voice/commands. GATE_TAG adds a word to the log name.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; ROOT="$(cd "$HERE/.." && pwd)"
: "${ANDROID_SERIAL:?export ANDROID_SERIAL=<serial> first}"
export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}"
LLM="${1:?repo id}"; VARIANT="${2:?variant}"; BACKEND="${3:?backend}"; FORMAT="${4:?format}"; INPUT="${5:?wav or text}"; shift 5
MODEL="${LLM#*/}"; TAG="voiceloop-$INPUT${GATE_TAG:+-$GATE_TAG}"
OUT="$ROOT/litertlm/results"; mkdir -p "$OUT"
DEV=$(adb shell getprop ro.product.model | tr -d '\r')
LOG="$OUT/$(date +%Y-%m-%d-%H%M)-$ANDROID_SERIAL-litertlm$(grep '^litertlmVersion=' "$ROOT/gradle.properties" | cut -d= -f2)-$TAG-$MODEL-$VARIANT-$BACKEND-$FORMAT.log"
{
  echo "# hfmodels voice loop device gate  date=$(date -u +%FT%TZ) serial=$ANDROID_SERIAL device=$DEV build=$(adb shell getprop ro.build.display.id | tr -d '\r') android=$(adb shell getprop ro.build.version.release | tr -d '\r')"
  echo "# git=$(git -C "$ROOT" rev-parse --short HEAD) dirty=$(git -C "$ROOT" status --porcelain --untracked-files=no | wc -l | tr -d ' ') test=VoiceLoopDeviceTest llm=$LLM variant=$VARIANT backend=$BACKEND format=$FORMAT input=$INPUT extra=$* thermal=$(adb shell dumpsys thermalservice | grep -m1 'Thermal Status' | tr -d '\r') battery_level=$(adb shell dumpsys battery | grep -m1 'level' | tr -d '\r' | tr -s ' ') battery_temp_x10C=$(adb shell dumpsys battery | grep -m1 'temperature' | tr -d '\r' | tr -s ' ') screen=$(adb shell dumpsys power | grep -m1 'mWakefulness=' | tr -d '\r' | tr -s ' ')"
} | tee "$LOG"
cd "$ROOT"
# Read the run's lines by time instead of clearing the phone's log (other lanes may be reading it).
T0=$(adb shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')
./gradlew --no-daemon -q :voice:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.johnrocky.hfmodels.voice.VoiceLoopDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.llm="$LLM" -Pandroid.testInstrumentationRunnerArguments.variant="$VARIANT" \
  -Pandroid.testInstrumentationRunnerArguments.backend="$BACKEND" -Pandroid.testInstrumentationRunnerArguments.format="$FORMAT" \
  -Pandroid.testInstrumentationRunnerArguments.input="$INPUT" "$@" > "$OUT/_voiceloop_gradle.out" 2>&1
RC=$?
echo "# gradle rc=$RC end=$(date -u +%FT%TZ) thermal_end=$(adb shell dumpsys thermalservice | grep -m1 'Thermal Status' | tr -d '\r') battery_temp_end_x10C=$(adb shell dumpsys battery | grep -m1 'temperature' | tr -d '\r' | tr -s ' ')" | tee -a "$LOG"
adb logcat -d -T "$T0" -b main,crash -s hfmodels-voice:I hfmodels:I AndroidRuntime:E DEBUG:F >> "$LOG"
grep -E "RESULT" "$LOG" | cut -c34-2400
echo "log: $LOG"
