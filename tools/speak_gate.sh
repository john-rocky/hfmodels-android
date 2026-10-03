#!/bin/bash
# Speak device gate on ONE named device: the voice module's SpeakDeviceTest (KittenTTS nano 0.8 through Speak: load, the
# publisher's three bench sentences for G2P ids and parity, the ten fixed replies twice, peak RSS, release) for one variant
# on the CPU, with the device state in the log header and the RESULT lines from logcat. Copied from tools/transcribe_gate.sh.
#   export ANDROID_SERIAL=<serial>
#   tools/speak_gate.sh <fp32|fp16> [extra -Pandroid.testInstrumentationRunnerArguments.* ...]
# The kitten files, laid out like the repo (the graphs, voices.npz, bench_inputs.npz, g2p/), must be under
# /data/local/tmp/hfmodels/kitten and replies.tsv under /data/local/tmp/hfmodels-voice/replies (tools/voice_fixtures.sh).
# The WAVs: adb pull /sdcard/Android/data/io.github.johnrocky.hfmodels.voice.test/files/tts/<variant>/
# The synthesizer alone on the publisher's bench ids (litert's KittenSynthDeviceTest, internal API):
#   GATE_MODULE=litert GATE_TEST=KittenSynthDeviceTest GATE_TAG=kitten-synth tools/speak_gate.sh <fp32|fp16>
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; ROOT="$(cd "$HERE/.." && pwd)"
: "${ANDROID_SERIAL:?export ANDROID_SERIAL=<serial> first}"
export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}"
VARIANT="${1:?variant}"; shift 1
GATE_MODULE="${GATE_MODULE:-voice}"; GATE_TEST="${GATE_TEST:-SpeakDeviceTest}"; GATE_TAG="${GATE_TAG:-speak-kitten}"
OUT="$ROOT/litert/results"; mkdir -p "$OUT"
DEV=$(adb shell getprop ro.product.model | tr -d '\r')
LOG="$OUT/$(date +%Y-%m-%d-%H%M)-$ANDROID_SERIAL-litert$(grep '^litertVersion=' "$ROOT/gradle.properties" | cut -d= -f2)-$GATE_TAG-$VARIANT-cpu.log"
{
  echo "# hfmodels speak device gate  date=$(date -u +%FT%TZ) serial=$ANDROID_SERIAL device=$DEV build=$(adb shell getprop ro.build.display.id | tr -d '\r') android=$(adb shell getprop ro.build.version.release | tr -d '\r')"
  echo "# git=$(git -C "$ROOT" rev-parse --short HEAD) test=$GATE_TEST variant=$VARIANT backend=cpu thermal=$(adb shell dumpsys thermalservice | grep -m1 'Thermal Status' | tr -d '\r') battery_level=$(adb shell dumpsys battery | grep -m1 'level' | tr -d '\r' | tr -s ' ') battery_temp_x10C=$(adb shell dumpsys battery | grep -m1 'temperature' | tr -d '\r' | tr -s ' ') screen=$(adb shell dumpsys power | grep -m1 'mWakefulness=' | tr -d '\r' | tr -s ' ')"
} | tee "$LOG"
cd "$ROOT"
# Read the run's lines by time instead of clearing the phone's log (other lanes may be reading it).
T0=$(adb shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')
./gradlew --no-daemon -q :$GATE_MODULE:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.johnrocky.hfmodels.$GATE_MODULE.$GATE_TEST \
  -Pandroid.testInstrumentationRunnerArguments.variant="$VARIANT" "$@" > "$OUT/_speak_gradle.out" 2>&1
RC=$?
echo "# gradle rc=$RC end=$(date -u +%FT%TZ) thermal_end=$(adb shell dumpsys thermalservice | grep -m1 'Thermal Status' | tr -d '\r') battery_temp_end_x10C=$(adb shell dumpsys battery | grep -m1 'temperature' | tr -d '\r' | tr -s ' ')" | tee -a "$LOG"
adb logcat -d -T "$T0" -b main,crash -s hfmodels-speak:I hfmodels-kitten:I hfmodels:I AndroidRuntime:E DEBUG:F >> "$LOG"
grep -E "RESULT" "$LOG" | cut -c34-1600
echo "log: $LOG"
