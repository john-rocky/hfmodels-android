#!/bin/bash
# A3 catalog gate on ONE named device: every bundled entry (or the ones given), one at a time,
# through inspect -> download (Hub) -> prepare per profile -> generate, evicting between entries.
#   export ANDROID_SERIAL=<serial>
#   tools/gate.sh [model-id ...]           # default: every catalog entry, smallest default-variant file first
#   tools/gate_to_verification.py litertlm/results/<log> --date <date> > verification/<date>-<device>.json
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; ROOT="$(cd "$HERE/.." && pwd)"
: "${ANDROID_SERIAL:?export ANDROID_SERIAL=<serial> first}"
export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}"
ENTRIES=("$@")
[ ${#ENTRIES[@]} -eq 0 ] && ENTRIES=($(python3 "$HERE/catalog_order.py" "$ROOT/core/src/main/assets/hfmodels/catalog.json"))
OUT="$ROOT/litertlm/results"; mkdir -p "$OUT"
DEV=$(adb shell getprop ro.product.model | tr -d '\r' | tr ' ' '_')
LOG="$OUT/$(date +%Y-%m-%d)-$ANDROID_SERIAL-$(grep '^litertlmVersion=' "$ROOT/gradle.properties" | cut -d= -f2)-a3-gate.log"
{
  echo "# hfmodels A3 catalog gate  date=$(date -u +%FT%TZ) serial=$ANDROID_SERIAL device=$DEV build=$(adb shell getprop ro.build.display.id | tr -d '\r') android=$(adb shell getprop ro.build.version.release | tr -d '\r')"
  echo "# git=$(git -C "$ROOT" rev-parse --short HEAD) catalog=$(shasum -a 256 "$ROOT/core/src/main/assets/hfmodels/catalog.json" | cut -c1-12) free_before=$(adb shell df /data | tail -1 | awk '{print $4}')K thermal=$(adb shell dumpsys thermalservice | grep -m1 'Thermal Status' | tr -d '\r')"
} | tee "$LOG"
cd "$ROOT"
for e in "${ENTRIES[@]}"; do
  echo "# entry=$e start=$(date -u +%FT%TZ) free=$(adb shell df /data | tail -1 | awk '{print $4}')K thermal=$(adb shell dumpsys thermalservice | grep -m1 'Thermal Status' | tr -d '\r')" | tee -a "$LOG"
  adb logcat -c
  ./gradlew --no-daemon -q :litertlm:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
    -Pandroid.testInstrumentationRunnerArguments.class=io.github.johnrocky.hfmodels.litertlm.CatalogGateTest \
    -Pandroid.testInstrumentationRunnerArguments.entries="$e" > "$OUT/_gate_gradle.out" 2>&1
  echo "# gradle rc=$? end=$(date -u +%FT%TZ)" | tee -a "$LOG"
  # never list a tag twice in -s (the last priority wins): hfmodels-a3:I already includes E
  adb logcat -d -b main,system,crash -s hfmodels-a3:I hfmodels:I AndroidRuntime:E DEBUG:F >> "$LOG"
  grep -E 'RESULT|EVICT|PLAN' "$LOG" | grep -F "model=$e" | cut -c34-300 | tail -8
done
echo "log: $LOG"
