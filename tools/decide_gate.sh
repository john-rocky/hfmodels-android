#!/bin/bash
# Typed-decisions device gate on ONE named device: the litert module's EncoderDecisionsDeviceTest (laya) or,
# with GATE_TEST=JuliaDecisionsDeviceTest GATE_TAG=decide-julia1, the Julia-1 test (GATE_TEST=GlinerDecideDeviceTest
# GATE_TAG=decide-gliner: GLiNER2.5-Decide), for one variant and backend, with the device state in the log header
# and the RESULT lines from logcat.
#   GATE_TEST=GliclassDeviceTest GATE_TAG=decide-gliclass: GLiClass-Edge v3.0 (variants s128_fp32 / s256_fp32)
#   GATE_TEST=DebertaDecisionDeviceTest GATE_TAG=decide-opendecision: Open-Decision DeBERTa-v3-Large (s256_wfp16 / s512_wfp16)
#   export ANDROID_SERIAL=<serial>
#   tools/decide_gate.sh <variant> <gpu|cpu|npu|auto> [extra -Pandroid.testInstrumentationRunnerArguments.* ...]
# The graphs and fixtures must be under /data/local/tmp/hfmodels/laya (or /julia1, /gliner-decide, /gliclass, /open-decision) on the device (see the test's KDoc).
# GATE_RUNNER=am runs the installed test APK with `am instrument` instead of gradle (install it first: ./gradlew :litert:assembleDebugAndroidTest,
# adb install -r litert/build/outputs/apk/androidTest/debug/litert-debug-androidTest.apk); extra arguments are then k=v pairs.
# The log ends with the test process's own LiteRT lines (partition, delegate, NPU registration and cache): an npu run counts only
# with `Replacing 1 out of 1 node(s) with delegate (DispatchDelegate)` there.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; ROOT="$(cd "$HERE/.." && pwd)"
: "${ANDROID_SERIAL:?export ANDROID_SERIAL=<serial> first}"
export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}"
VARIANT="${1:?variant}"; BACKEND="${2:?backend}"; shift 2
GATE_TEST="${GATE_TEST:-EncoderDecisionsDeviceTest}"; GATE_TAG="${GATE_TAG:-decide}"
OUT="$ROOT/litert/results"; mkdir -p "$OUT"
DEV=$(adb shell getprop ro.product.model | tr -d '\r')
LOG="$OUT/$(date +%Y-%m-%d-%H%M)-$ANDROID_SERIAL-litert$(grep '^litertVersion=' "$ROOT/gradle.properties" | cut -d= -f2)-$GATE_TAG-$VARIANT-$BACKEND.log"
{
  echo "# hfmodels typed-decisions device gate  date=$(date -u +%FT%TZ) serial=$ANDROID_SERIAL device=$DEV build=$(adb shell getprop ro.build.display.id | tr -d '\r') android=$(adb shell getprop ro.build.version.release | tr -d '\r')"
  echo "# git=$(git -C "$ROOT" rev-parse --short HEAD) test=$GATE_TEST variant=$VARIANT backend=$BACKEND thermal=$(adb shell dumpsys thermalservice | grep -m1 'Thermal Status' | tr -d '\r') battery_level=$(adb shell dumpsys battery | grep -m1 'level' | tr -d '\r' | tr -s ' ') battery_temp_x10C=$(adb shell dumpsys battery | grep -m1 'temperature' | tr -d '\r' | tr -s ' ') screen=$(adb shell dumpsys power | grep -m1 'mWakefulness=' | tr -d '\r' | tr -s ' ')"
} | tee "$LOG"
cd "$ROOT"
# Read the run's lines by time instead of clearing the phone's log (other lanes may be reading it).
T0=$(adb shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')
if [ "${GATE_RUNNER:-gradle}" = am ]; then
  EXTRA=()
  for kv in "$@"; do kv="${kv#-Pandroid.testInstrumentationRunnerArguments.}"; EXTRA+=(-e "${kv%%=*}" "${kv#*=}"); done
  adb shell am instrument -w -e class "io.github.johnrocky.hfmodels.litert.$GATE_TEST" -e variant "$VARIANT" -e backend "$BACKEND" ${EXTRA[@]+"${EXTRA[@]}"} \
    io.github.johnrocky.hfmodels.litert.test/androidx.test.runner.AndroidJUnitRunner > "$OUT/_decide_am.out" 2>&1
  grep -q "^OK (1 test)" "$OUT/_decide_am.out"; RC=$?
else
  ./gradlew --no-daemon -q :litert:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
    -Pandroid.testInstrumentationRunnerArguments.class=io.github.johnrocky.hfmodels.litert.$GATE_TEST \
    -Pandroid.testInstrumentationRunnerArguments.variant="$VARIANT" -Pandroid.testInstrumentationRunnerArguments.backend="$BACKEND" "$@" > "$OUT/_decide_gradle.out" 2>&1
  RC=$?
fi
echo "# gradle rc=$RC end=$(date -u +%FT%TZ) thermal_end=$(adb shell dumpsys thermalservice | grep -m1 'Thermal Status' | tr -d '\r') battery_temp_end_x10C=$(adb shell dumpsys battery | grep -m1 'temperature' | tr -d '\r' | tr -s ' ')" | tee -a "$LOG"
adb logcat -d -T "$T0" -b main,crash -s hfmodels-decide:I hfmodels:I AndroidRuntime:E DEBUG:F >> "$LOG"
# The test process's own LiteRT lines (by pid: other apps on the phone log under the same tags).
PIDS=$(grep -E " I hfmodels-decide: " "$LOG" | awk '{print $3}' | sort -u | tr '\n' '|' | sed 's/|$//')
if [ -n "$PIDS" ]; then
  RES=$(adb logcat -d -T "$T0" -b main | grep -E "^[0-9-]+ [0-9:.]+ +($PIDS) " | grep -E "NPU accelerator registered|Replacing [0-9]+ out of [0-9]+ node|compiler plugins were applied|Partitioned subgraph|JIT compilation caching|cached model|HtpPerformanceMode|dlopen failed|Failed to apply|No compiler plugin|Falling back|fallback" | head -60)
  echo "## residency ($(printf '%s' "$RES" | grep -c .) lines, pid $PIDS)" >> "$LOG"
  [ -n "$RES" ] && printf '%s\n' "$RES" >> "$LOG"
fi
grep -E "RESULT" "$LOG" | cut -c34-1200
echo "log: $LOG"
