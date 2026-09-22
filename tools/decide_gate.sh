#!/bin/bash
# Typed-decisions device gate on ONE named device: the litert module's EncoderDecisionsDeviceTest for one
# variant and backend, with the device state in the log header and the RESULT lines from logcat.
#   export ANDROID_SERIAL=<serial>
#   tools/decide_gate.sh <variant> <gpu|cpu|auto> [extra -Pandroid.testInstrumentationRunnerArguments.* ...]
# The graphs and fixtures must be under /data/local/tmp/hfmodels/laya on the device (see the test's KDoc).
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; ROOT="$(cd "$HERE/.." && pwd)"
: "${ANDROID_SERIAL:?export ANDROID_SERIAL=<serial> first}"
export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}"
VARIANT="${1:?variant}"; BACKEND="${2:?backend}"; shift 2
OUT="$ROOT/litert/results"; mkdir -p "$OUT"
DEV=$(adb shell getprop ro.product.model | tr -d '\r')
LOG="$OUT/$(date +%Y-%m-%d-%H%M)-$ANDROID_SERIAL-litert$(grep '^litertVersion=' "$ROOT/gradle.properties" | cut -d= -f2)-decide-$VARIANT-$BACKEND.log"
{
  echo "# hfmodels typed-decisions device gate  date=$(date -u +%FT%TZ) serial=$ANDROID_SERIAL device=$DEV build=$(adb shell getprop ro.build.display.id | tr -d '\r') android=$(adb shell getprop ro.build.version.release | tr -d '\r')"
  echo "# git=$(git -C "$ROOT" rev-parse --short HEAD) variant=$VARIANT backend=$BACKEND thermal=$(adb shell dumpsys thermalservice | grep -m1 'Thermal Status' | tr -d '\r') battery_level=$(adb shell dumpsys battery | grep -m1 'level' | tr -d '\r' | tr -s ' ') battery_temp_x10C=$(adb shell dumpsys battery | grep -m1 'temperature' | tr -d '\r' | tr -s ' ') screen=$(adb shell dumpsys power | grep -m1 'mWakefulness=' | tr -d '\r' | tr -s ' ')"
} | tee "$LOG"
cd "$ROOT"
adb logcat -c
./gradlew --no-daemon -q :litert:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.johnrocky.hfmodels.litert.EncoderDecisionsDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.variant="$VARIANT" -Pandroid.testInstrumentationRunnerArguments.backend="$BACKEND" "$@" > "$OUT/_decide_gradle.out" 2>&1
RC=$?
echo "# gradle rc=$RC end=$(date -u +%FT%TZ) thermal_end=$(adb shell dumpsys thermalservice | grep -m1 'Thermal Status' | tr -d '\r') battery_temp_end_x10C=$(adb shell dumpsys battery | grep -m1 'temperature' | tr -d '\r' | tr -s ' ')" | tee -a "$LOG"
adb logcat -d -b main,crash -s hfmodels-decide:I hfmodels:I AndroidRuntime:E DEBUG:F >> "$LOG"
grep -E "RESULT" "$LOG" | cut -c34-1200
echo "log: $LOG"
