#!/bin/bash
# Build the A0 probe in one keep configuration, install it on $ANDROID_SERIAL, run the checks, save the log.
#   run.sh <nokeep|keepwork|keepwork-keepjni> [litertlmVersion]
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; ROOT="$(cd "$HERE/../.." && pwd)"
CFG="${1:?nokeep|keepwork|keepwork-keepjni}"; VER="${2:-}"
: "${ANDROID_SERIAL:?export ANDROID_SERIAL=<serial> first}"
export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}"
PROPS=()
case "$CFG" in
  nokeep) ;;
  keepwork) PROPS+=(-PkeepWork=true) ;;
  keepwork-keepjni) PROPS+=(-PkeepWork=true -PkeepJni=true) ;;
  *) echo "unknown config $CFG"; exit 2 ;;
esac
[ -n "$VER" ] && PROPS+=(-PlitertlmVersion="$VER" -PskipKotlinMetadataCheck=true)
PKG=io.github.johnrocky.hfmodels.probe.coexist
OUT="$HERE/results"; mkdir -p "$OUT"
LOG="$OUT/$(date +%Y-%m-%d)-$ANDROID_SERIAL-${VER:-$(grep '^litertlmVersion=' "$ROOT/gradle.properties" | cut -d= -f2)}-$CFG.log"
cd "$ROOT"
./gradlew --no-daemon -q :probes:coexist:assembleRelease ${PROPS[@]+"${PROPS[@]}"} || { echo "build failed" | tee "$LOG"; exit 1; }
APK="$HERE/build/outputs/apk/release/coexist-release.apk"
{
  echo "# hfmodels A0 coexist probe  date=$(date -u +%FT%TZ) serial=$ANDROID_SERIAL cfg=$CFG props=${PROPS[*]:-none}"
  echo "# device=$(adb shell getprop ro.product.model | tr -d '\r') build=$(adb shell getprop ro.build.display.id | tr -d '\r') android=$(adb shell getprop ro.build.version.release | tr -d '\r') thermal=$(adb shell dumpsys thermalservice | grep -m1 'Thermal Status' | tr -d '\r')"
  echo "# apk bytes=$(stat -f %z "$APK") sha256=$(shasum -a 256 "$APK" | cut -d' ' -f1) git=$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo n/a)"
  echo "# so=$(unzip -l "$APK" | grep -oE 'lib/arm64-v8a/[^ ]+\.so' | tr '\n' ' ')"
} | tee "$LOG"
adb uninstall "$PKG" >/dev/null 2>&1
adb install -r "$APK" >/dev/null || { echo "install failed" | tee -a "$LOG"; exit 1; }
adb logcat -c
adb shell am start -W -n "$PKG/.ProbeActivity" >/dev/null
# Wait for DONE, or for the process to be gone (a Java crash lands in AndroidRuntime:E, a JNI abort
# in the crash buffer under tag DEBUG with no Java exception at all), for up to 15 minutes.
sleep 2
for i in $(seq 1 450); do
  if adb logcat -d -s hfmodels-a0:I | grep -q ' DONE '; then break; fi
  if [ -z "$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r')" ]; then sleep 2; break; fi
  sleep 2
done
# NOTE: never list the same tag twice in -s (the last priority wins, so "hfmodels-a0:I hfmodels-a0:E" drops every INFO line).
adb logcat -d -b main,system,crash -s hfmodels-a0:I AndroidRuntime:E DEBUG:F >> "$LOG"
adb shell am force-stop "$PKG" >/dev/null 2>&1
grep -E ' (START|RESULT|DONE) |FATAL|Caused by|Abort message|in call to|from com\.google' "$LOG" | cut -c34-420
echo "log: $LOG"
