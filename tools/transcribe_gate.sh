#!/bin/bash
# Transcribe device gate on ONE named device: the voice module's TranscribeDeviceTest (Zipformer CTC through
# Transcribe, then the Endpointer on the same WAVs) for one variant and backend, with the device state in the
# log header and the RESULT lines from logcat. Copied from tools/decide_gate.sh; the test lives in :voice because
# the Endpointer does.
#   export ANDROID_SERIAL=<serial>
#   tools/transcribe_gate.sh <small_fp16|medium_fp16> <gpu|cpu|auto> [extra -Pandroid.testInstrumentationRunnerArguments.* ...]
# The model files and tokens.txt must be under /data/local/tmp/hfmodels/zipformer and the WAVs + commands.tsv under
# /data/local/tmp/hfmodels-voice/commands on the device (see the test's KDoc; tools/voice_fixtures.sh makes the WAVs).
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; ROOT="$(cd "$HERE/.." && pwd)"
: "${ANDROID_SERIAL:?export ANDROID_SERIAL=<serial> first}"
export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}"
VARIANT="${1:?variant}"; BACKEND="${2:?backend}"; shift 2
GATE_TEST="${GATE_TEST:-TranscribeDeviceTest}"; GATE_TAG="${GATE_TAG:-transcribe-zipformer}"
OUT="$ROOT/litert/results"; mkdir -p "$OUT"
DEV=$(adb shell getprop ro.product.model | tr -d '\r')
LOG="$OUT/$(date +%Y-%m-%d-%H%M)-$ANDROID_SERIAL-litert$(grep '^litertVersion=' "$ROOT/gradle.properties" | cut -d= -f2)-$GATE_TAG-$VARIANT-$BACKEND.log"
{
  echo "# hfmodels transcribe device gate  date=$(date -u +%FT%TZ) serial=$ANDROID_SERIAL device=$DEV build=$(adb shell getprop ro.build.display.id | tr -d '\r') android=$(adb shell getprop ro.build.version.release | tr -d '\r')"
  echo "# git=$(git -C "$ROOT" rev-parse --short HEAD) test=$GATE_TEST variant=$VARIANT backend=$BACKEND thermal=$(adb shell dumpsys thermalservice | grep -m1 'Thermal Status' | tr -d '\r') battery_level=$(adb shell dumpsys battery | grep -m1 'level' | tr -d '\r' | tr -s ' ') battery_temp_x10C=$(adb shell dumpsys battery | grep -m1 'temperature' | tr -d '\r' | tr -s ' ') screen=$(adb shell dumpsys power | grep -m1 'mWakefulness=' | tr -d '\r' | tr -s ' ')"
} | tee "$LOG"
cd "$ROOT"
# Read the run's lines by time instead of clearing the phone's log (other lanes may be reading it).
T0=$(adb shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')
./gradlew --no-daemon -q :voice:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.johnrocky.hfmodels.voice.$GATE_TEST \
  -Pandroid.testInstrumentationRunnerArguments.variant="$VARIANT" -Pandroid.testInstrumentationRunnerArguments.backend="$BACKEND" "$@" > "$OUT/_transcribe_gradle.out" 2>&1
RC=$?
echo "# gradle rc=$RC end=$(date -u +%FT%TZ) thermal_end=$(adb shell dumpsys thermalservice | grep -m1 'Thermal Status' | tr -d '\r') battery_temp_end_x10C=$(adb shell dumpsys battery | grep -m1 'temperature' | tr -d '\r' | tr -s ' ')" | tee -a "$LOG"
adb logcat -d -T "$T0" -b main,crash -s hfmodels-transcribe:I hfmodels:I AndroidRuntime:E DEBUG:F >> "$LOG"
grep -E "RESULT" "$LOG" | cut -c34-1600
echo "log: $LOG"
