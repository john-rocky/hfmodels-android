#!/bin/bash
# Tools device gate on ONE named device: the voice module's ToolsDeviceTest (the transcriber, the speaker and one chat
# model loaded side by side; the fixed commands through ToolRunner with recording tools; the first sentence of one reply
# through the speaker; release) for one chat model, variant, backend and tool-call format, with the device state in the
# log header and the RESULT lines from logcat. Copied from tools/speak_gate.sh.
#   export ANDROID_SERIAL=<serial>
#   tools/tools_gate.sh <repo id> <variant> <gpu|cpu> <runtime|qwenxml|lfm> [extra -Pandroid.testInstrumentationRunnerArguments.* ...]
#   e.g. tools/tools_gate.sh litert-community/Qwen3-1.7B int4 gpu runtime
#        tools/tools_gate.sh litert-community/functiongemma-270m-ft-mobile-actions q8_ekv1024 cpu runtime \
#          -Pandroid.testInstrumentationRunnerArguments.descriptor=litert-community__functiongemma-270m-ft-mobile-actions.hfmodels.json
# The chat model file must be under /data/local/tmp/hfmodels/llm (or the dir argument), the Zipformer and kitten files as
# for tools/transcribe_gate.sh and tools/speak_gate.sh, and the WAVs + commands.tsv under /data/local/tmp/hfmodels-voice/commands.
# GATE_TAG adds a word to the log name (e.g. GATE_TAG=c01only).
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; ROOT="$(cd "$HERE/.." && pwd)"
: "${ANDROID_SERIAL:?export ANDROID_SERIAL=<serial> first}"
export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}"
LLM="${1:?repo id}"; VARIANT="${2:?variant}"; BACKEND="${3:?backend}"; FORMAT="${4:?format}"; shift 4
MODEL="${LLM#*/}"; TAG="tools${GATE_TAG:+-$GATE_TAG}"
OUT="$ROOT/litertlm/results"; mkdir -p "$OUT"
DEV=$(adb shell getprop ro.product.model | tr -d '\r')
LOG="$OUT/$(date +%Y-%m-%d-%H%M)-$ANDROID_SERIAL-litertlm$(grep '^litertlmVersion=' "$ROOT/gradle.properties" | cut -d= -f2)-$TAG-$MODEL-$VARIANT-$BACKEND-$FORMAT.log"
{
  echo "# hfmodels tools device gate  date=$(date -u +%FT%TZ) serial=$ANDROID_SERIAL device=$DEV build=$(adb shell getprop ro.build.display.id | tr -d '\r') android=$(adb shell getprop ro.build.version.release | tr -d '\r')"
  echo "# git=$(git -C "$ROOT" rev-parse --short HEAD) test=ToolsDeviceTest llm=$LLM variant=$VARIANT backend=$BACKEND format=$FORMAT extra=$* thermal=$(adb shell dumpsys thermalservice | grep -m1 'Thermal Status' | tr -d '\r') battery_level=$(adb shell dumpsys battery | grep -m1 'level' | tr -d '\r' | tr -s ' ') battery_temp_x10C=$(adb shell dumpsys battery | grep -m1 'temperature' | tr -d '\r' | tr -s ' ') screen=$(adb shell dumpsys power | grep -m1 'mWakefulness=' | tr -d '\r' | tr -s ' ')"
} | tee "$LOG"
cd "$ROOT"
# Read the run's lines by time instead of clearing the phone's log (other lanes may be reading it).
T0=$(adb shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')
./gradlew --no-daemon -q :voice:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.johnrocky.hfmodels.voice.ToolsDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.llm="$LLM" -Pandroid.testInstrumentationRunnerArguments.variant="$VARIANT" \
  -Pandroid.testInstrumentationRunnerArguments.backend="$BACKEND" -Pandroid.testInstrumentationRunnerArguments.format="$FORMAT" "$@" > "$OUT/_tools_gradle.out" 2>&1
RC=$?
echo "# gradle rc=$RC end=$(date -u +%FT%TZ) thermal_end=$(adb shell dumpsys thermalservice | grep -m1 'Thermal Status' | tr -d '\r') battery_temp_end_x10C=$(adb shell dumpsys battery | grep -m1 'temperature' | tr -d '\r' | tr -s ' ')" | tee -a "$LOG"
adb logcat -d -T "$T0" -b main,crash -s hfmodels-tools:I hfmodels:I AndroidRuntime:E DEBUG:F >> "$LOG"
grep -E "RESULT" "$LOG" | cut -c34-2400
echo "log: $LOG"
