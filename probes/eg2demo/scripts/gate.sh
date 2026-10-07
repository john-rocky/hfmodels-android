#!/bin/zsh
# Round 2 device gate of the EmbeddingGemma 2 740M demo app on the Galaxy S26: the Mac's line scored again on the phone
# per backend, the app's failure paths and the three screens for round 3's sieve. Written in round 1, legs, fail and
# shots added in round 2; run only while the phone's hold is ours.
# Steps (arguments, run in the order given; default "gpu npu"):
#   gpu            backend gpu, vision gpu, audio cpu, base bundle
#   npu            backend npu, vision npu, audio cpu, SM8850 bundle
#   npu_audio_npu  backend npu, vision npu, audio npu, SM8850 bundle
#   gpu_audio_gpu  backend gpu, vision gpu, audio gpu, base bundle
#   cpu            backend cpu, vision cpu, audio cpu, base bundle
#   npu2           round 3: the npu leg again (the APK now lists libcdsprpc.so), files npu2_*; then scripts/npu_pick.py
#                  judges it (npu2_summary.txt) and writes the photos' backend for shots and takes (photos_backend.txt)
#   fail           the failure paths, one evidence file each, fail_<name>.log: bundle (a missing bundle: the ERROR line,
#                  the process alive, the pill), permission (RECORD_AUDIO revoked: MIC_PERMISSION, granted back), mic
#                  (a 1.5 s press: MIC_RECORDED and RESULT; a 120 ms press, then a tap if that one recorded), busy (two
#                  intents within 200 ms: one IGNORED), back (BACK: the activity ends, no FATAL / IllegalStateException)
#   shots          READY, q01_a's and q13_a's result screens: shot1_ready.png, shot2_q01.png, shot3_q13.png; round 3:
#                  launched with --ez reindex true on the photos' backend (SHOTS_BACKEND, else photos_backend.txt, else
#                  gpu), the LAYOUT and RESULT_LAYOUT lines copied into shots.log; round 3b: SHOTS_READY_ONLY=1 takes
#                  shot1_ready.png only (no query is run)
#   mic           round 3: the mic floors on the phone with nobody speaking (mic_check.log): a 300 ms press must give
#                  MIC_REJECTED reason=short, a 1500 ms press reason=quiet; the clips and round 2's silent 1.35 s clip
#                  are pulled for their RMS
# Every leg launches with --ez reindex true (round 3): the album is embedded again, so INDEX_DONE is this launch's time.
# PUSH_MODE=missing (round 3): files already on the phone are listed by name, only the missing ones are pushed.
# Order:
#   1. the hold is ours (HOLD_FILE's pid = $OUT/hold_sleeper.pid, alive); its start time sets the frame: no step starts
#      25 min after it (the rest is written as CARRY_OVER), a running step is stopped at 28 min;
#   2. install, grant RECORD_AUDIO, one start so the app makes files/{album,queries,mic} (a dir made by adb shell under
#      files/ is shell-owned 2770 and the app cannot enter it: memory sdcard-app-files-push);
#   3. push every file INTO those dirs (never a dir push), each file's sha256 on the phone checked against the Mac's;
#   4. per leg: wait up to 10 min for thermal status 0, every CPU policy uncapped and the GPU uncapped (state logged),
#      force-stop, launch with the leg's extras plus `--es run_queries all --es run_texts all --es text_prefix all
#      --ei gap_ms $GAP_MS`, wait up to 15 min for `DONE json=` or `ERROR (init|bundle)` (MemAvailable polled; no new
#      app line for 4 min = stalled), then keep the run JSON (its RUN_JSON path, written on ERROR too), the app's Eg2Demo
#      lines and whole logcat by pid from the launch's device time (never `logcat -c`: a shared phone), the crash buffer,
#      lmkd kill lines, the first error lines, the first 30 backend lines and one screencap; an ERROR leg is data and the
#      next leg runs;
#   5. fail and shots as above, each sub-step within a minute;
#   6. clean up by name unless KEEP_FILES=1 (the pushed files and the app's index caches; the app stays installed).
# Exit status: 0 every step ran (an ERROR inside a leg is data), 1 setup, 3 the 25 min frame (CARRY_OVER), 4 the phone
#   left adb, 5 MemAvailable < 2,000,000 kB or an lmkd kill line, 6 the app crashed (no ERROR line) in two legs running,
#   7 the phone not ready for 10 min, 8 a step stopped at 28 min.
#   usage: K/scripts/gate.sh <steps...>    env: GAP_MS (300), DONE_LIMIT_S (900), READY_LIMIT_S (600), STALL_S (240),
#                                               KEEP_FILES (0), SOFT_S (1500), HARD_S (1680), PUSH_MODE (all|missing),
#                                               SHOTS_BACKEND (gpu|npu), SHOTS_READY_ONLY (0)
# Dry run without a phone: ADB_SHIM=K/scripts/dryrun_adb_shim.zsh OUT_DIR=<dir> HOLD_FILE=<fake hold> MODELS_DIR=<dir>
#   DRY_FAST=1 gate.sh <steps...>
# Every adb call goes through dev() (grep the file for a bare adb before a run: memory adb-run-script-traps).
set -u
HERE=${0:A:h}
K=${HERE:h}
S=RFGL80R6A6H
P=com.mlboydaisuke.eg2demo
ACT=.MainActivity
TAG=Eg2Demo
FILES=/sdcard/Android/data/$P/files
BASE=embeddinggemma-2-740m.litertlm
NPU_BUNDLE=embeddinggemma-2-740m_Qualcomm_SM8850.litertlm
APK=$K/app/app/build/outputs/apk/debug/app-debug.apk
MODELS=${MODELS_DIR:-$HOME/.cache/eg2demo}
OUT=${OUT_DIR:-$K/out/device}
LOG=${GATE_LOG:-$OUT/gate.log}
HOLD=${HOLD_FILE:-$HOME/code/litertlm-convert/community_accel_work/s2_npu_sweep/.device_hold}
GPU_MAX_MHZ=1300
MEM_FLOOR_KB=2000000
GAP_MS=${GAP_MS:-300}
READY_LIMIT_S=${READY_LIMIT_S:-600}
DONE_LIMIT_S=${DONE_LIMIT_S:-900}
STALL_S=${STALL_S:-240}
SOFT_S=${SOFT_S:-1500}
HARD_S=${HARD_S:-1680}
ARGS=(${=${*:-gpu npu}})
[[ -n ${ADB_SHIM:-} ]] && source $ADB_SHIM
mkdir -p $OUT

dev() { adb -s $S "$@"; }
nap() { [[ ${DRY_FAST:-0} == 1 ]] || sleep $1; }

STEPS=()
for a in $ARGS; do
  case $a in
    gpu|npu|npu2|npu_audio_npu|gpu_audio_gpu|cpu|shots|mic) STEPS+=($a) ;;
    fail) STEPS+=(fail_bundle fail_permission fail_mic fail_busy fail_back) ;;
    *) echo "unknown step $a (gpu npu npu2 npu_audio_npu gpu_audio_gpu cpu fail shots mic)"; exit 1 ;;
  esac
done
PUSH_MODE=${PUSH_MODE:-all}
[[ $PUSH_MODE == all || $PUSH_MODE == missing ]] || { echo "PUSH_MODE must be all or missing, got $PUSH_MODE"; exit 1; }

mine=$(cat $OUT/hold_sleeper.pid 2>/dev/null)
hold_line=$(python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print(d.get('pid'), d.get('started', '').replace(' ', 'T'))" $HOLD 2>/dev/null)
holder=${hold_line%% *}
hold_started=${hold_line#* }
[[ -n $mine && $holder == $mine ]] || { echo "hold is not ours (holder=$holder mine=$mine)"; exit 1; }
kill -0 $mine 2>/dev/null || { echo "hold sleeper $mine is not alive"; exit 1; }
T_HOLD=$(date -j -f '%Y-%m-%dT%H:%M:%S' "$hold_started" +%s 2>/dev/null)
[[ $T_HOLD == <-> ]] || T_HOLD=$(date +%s)
held() { print -r -- $(( $(date +%s) - T_HOLD )); }
past_hard() { (( $(held) >= HARD_S )); }

state() {
  dev shell 'echo uptime $(cut -d" " -f1 /proc/uptime);
    dumpsys thermalservice | grep -E "Thermal Status|mName=SKIN" | head -2;
    for p in /sys/devices/system/cpu/cpufreq/policy*; do echo $p $(cat $p/scaling_max_freq) $(cat $p/cpuinfo_max_freq); done;
    g=/sys/class/kgsl/kgsl-3d0;
    echo kgsl: max_clock_mhz=$(cat $g/max_clock_mhz 2>/dev/null || echo missing) thermal_pwrlevel=$(cat $g/thermal_pwrlevel 2>/dev/null || echo missing) temp=$(cat $g/temp 2>/dev/null || echo missing);
    echo mem: $(grep MemAvailable /proc/meminfo);
    echo litert_procs: $(ps -A | grep -i litert | wc -l);
    echo power: $(dumpsys power | grep -m1 mWakefulness=);
    echo data_free: $(df -h /data | tail -1)'
}
top_head() { dev shell 'top -b -n 1 | head -16'; }
ready() {  # empty = thermal status 0, every CPU policy at its maximum, the GPU at its full clock range
  dev shell "for p in /sys/devices/system/cpu/cpufreq/policy*; do [ \$(cat \$p/scaling_max_freq) = \$(cat \$p/cpuinfo_max_freq) ] || echo capped \$p \$(cat \$p/scaling_max_freq); done;
    dumpsys thermalservice | grep -m1 'Thermal Status' | grep -v 'Status: 0';
    g=/sys/class/kgsl/kgsl-3d0;
    if [ -r \$g/max_clock_mhz ]; then [ \$(cat \$g/max_clock_mhz) = $GPU_MAX_MHZ ] || echo gpu_capped max_clock_mhz=\$(cat \$g/max_clock_mhz); fi;
    if [ -r \$g/thermal_pwrlevel ]; then [ \$(cat \$g/thermal_pwrlevel) = 0 ] || echo gpu_capped thermal_pwrlevel=\$(cat \$g/thermal_pwrlevel); fi"
}
present() { [[ $(dev get-state 2>/dev/null | tr -d '\r') == device ]]; }
dev_time() { dev shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r'; }
alive() { [[ -n $1 && -n $(dev shell "[ -d /proc/$1 ] && echo up" | tr -d '\r') ]]; }
remote_sha() { dev shell "sha256sum '$1'" | cut -d' ' -f1 | tr -d '\r'; }
push_checked() {  # $1 = local file, $2 = remote path (a file inside a dir the app made)
  local want got
  want=$(shasum -a 256 $1 | cut -d' ' -f1)
  dev push $1 $2 >> $LOG 2>&1
  got=$(remote_sha $2)
  if [[ $got == $want ]]; then echo "## pushed ${2:t} sha256 ${want:0:12} (= phone)" >> $LOG; return 0; fi
  echo "## pushed ${2:t}: phone sha256 $got, local $want" >> $LOG
  return 1
}
demo_since() { dev logcat -d -T "$1" --pid=$APID -s ${TAG}:V; }
demo_lines() { demo_since "$SINCE"; }
perm_state() { dev shell "dumpsys package $P | grep -m1 'android.permission.RECORD_AUDIO: granted'" | tr -d '\r' | sed 's/^ *//'; }
top_activity() { dev shell "dumpsys activity activities | grep -m1 -E 'topResumedActivity|ResumedActivity'" | tr -d '\r' | sed 's/^ *//'; }
shot() { dev exec-out screencap -p > $OUT/$1; }

IDX=1
COMPLETED=0
finish() {  # $1 = exit status: the steps not run as CARRY_OVER, the end state, cleanup by name unless KEEP_FILES=1
  local rc=$1 from=$IDX
  (( COMPLETED == IDX )) && from=$(( IDX + 1 ))
  [[ $rc != 0 ]] && (( from <= ${#STEPS} )) && echo "## CARRY_OVER ${STEPS[from,-1]}" >> $LOG
  if present; then
    dev shell am force-stop $P
    { echo "## state at the end"; state; echo "## top at the end"; top_head; } >> $LOG 2>&1
    if [[ ${KEEP_FILES:-0} != 1 ]]; then
      local n f
      for n in $BASE $NPU_BUNDLE index_${BASE}_gpu.json index_${BASE}_cpu.json index_${NPU_BUNDLE}_npu.json; do dev shell rm -f "$FILES/$n"; done
      for f in $K/fixtures/album/*.jpg; do dev shell rm -f "$FILES/album/${f:t}"; done
      for f in $K/fixtures/queries/*.wav $K/fixtures/queries.json; do dev shell rm -f "$FILES/queries/${f:t}"; done
      echo "## $(date '+%T') pushed files and index caches removed by name (the app stays installed)" >> $LOG
    else
      echo "## KEEP_FILES=1: pushed files and index caches left in $FILES for round 3" >> $LOG
    fi
  fi
  echo "## $(date '+%T') gate end rc=$rc, held $(held) s" >> $LOG
  grep -E "^## ([0-9:]+ )?(STEP|STOP|CARRY_OVER|KEEP_FILES|gate end)" $LOG | tail -24
  exit $rc
}
stop() {  # $1 = exit status, $2 = why
  echo "## $(date '+%T') STOP $2 (held $(held) s)" >> $LOG
  if (( $1 == 4 )); then
    echo "## $(date '+%T') the phone left adb: a reboot is a hard stop for this bundle (memory s26-reboot-at-engine-start-hard-stop); read /proc/uptime when it is back" >> $LOG
    grep -E "^## ([0-9:]+ )?(STEP|STOP)" $LOG | tail -24
    exit 4
  fi
  finish $1
}
step_line() { echo "## $(date '+%T') STEP $1 $2 (held $(held) s)" >> $LOG; COMPLETED=$IDX; }

# Wait for the first Eg2Demo line since $1 matching $2, up to $3 s. Sets FOUND (the line) and WHY (line, timeout,
# process_gone, phone_gone, hard). Called directly, never inside $( ), so the two survive.
wait_line() {
  local since=$1 re=$2 lim=$3 T0=$(date +%s)
  FOUND="" WHY=timeout
  while (( $(date +%s) - T0 < lim )); do
    FOUND=$(demo_since "$since" | grep -E -- "$re" | head -1)
    [[ -n $FOUND ]] && { WHY=line; return 0; }
    if ! alive $APID; then
      if present; then WHY=process_gone; else WHY=phone_gone; fi
      return 1
    fi
    past_hard && { WHY=hard; return 1; }
    nap 1
  done
  return 1
}
why_stop() {  # after wait_line: the phone gone or the 28 min line ends the gate
  [[ $WHY == phone_gone ]] && stop 4 "phone gone during $1"
  [[ $WHY == hard ]] && { dev shell am force-stop $P; stop 8 "28 min reached during $1"; }
  return 0
}

launch() {  # $1 = label, the rest = extras; sets SINCE and APID
  local label=$1
  shift
  dev shell am force-stop $P
  SINCE=$(dev_time)
  echo "## $(date '+%T') $label launch, device time $SINCE, extras $*" >> $LOG
  dev shell am start -n $P/$ACT "$@" >> $LOG 2>&1
  local k
  for k in 1 2 3 4 5; do
    nap 1
    APID=$(dev shell pidof $P | tr -d '\r')
    [[ -n $APID ]] && break
  done
}

READY_PID=""
MIC_X="" MIC_Y=""
mic_px() {  # the mic button's centre: LAYOUT's mic_px (round 3), else level bar top + 8 dp + 8 dp + status + 10 dp + 36 dp
  local l fs
  wait_line "$SINCE" "LAYOUT " 10
  l=${FOUND#*LAYOUT }
  fs=$(dev shell "settings get system font_scale" | tr -d '\r')
  read -r MIC_X MIC_Y <<< "$(python3 -c '
import json, sys
d = json.loads(sys.argv[1])
try:
    fs = float(sys.argv[2])
except ValueError:
    fs = 1.0
w, h = d["screen_px"]
m = d.get("mic_px")
if m:
    print(m["left"] + m["width"] // 2, m["top"] + m["height"] // 2)
    sys.exit(0)
lv = d["level_px"]
# the status line under the bar is 15 sp: 1 or 2 lines (27 dp x font scale = the middle of the two); the button is 72 dp
print(w // 2, lv["top"] + round((62 + 27 * fs) * d["density"]))
' "$l" "$fs" 2>/dev/null)"
  echo "## $(date '+%T') mic button press point x=$MIC_X y=$MIC_Y (font_scale $fs, LAYOUT $l)" >> $LOG
  [[ $MIC_X == <-> && $MIC_Y == <-> ]]
}
ensure_ready() {  # $1 = label: the app alive at READY and in front (the fail_permission launch), or a new gpu launch
  if [[ -n ${APID:-} && $READY_PID == $APID ]] && alive $APID && [[ $(top_activity) == *$P/* ]]; then return 0; fi
  launch ${1}_launch --es backend gpu --es bundle $BASE
  [[ -n $APID ]] || return 1
  wait_line "$SINCE" " READY |ERROR (init|bundle)" 90
  why_stop $1
  [[ $FOUND == *" READY "* ]] || return 1
  READY_PID=$APID
  mic_px
}

# ---- legs ---------------------------------------------------------------------------------------------------------

CRASHES=0
leg() {
  local LEG=$1 W0 R T0 POLL MEM DL N NLAST TLAST STATUS jr
  case $LEG in
    gpu) EXTRAS=(--es backend gpu --es bundle $BASE) ;;
    npu|npu2) EXTRAS=(--es backend npu --es bundle $NPU_BUNDLE) ;;
    npu_audio_npu) EXTRAS=(--es backend npu --es bundle $NPU_BUNDLE --es audio_backend npu) ;;
    gpu_audio_gpu) EXTRAS=(--es backend gpu --es bundle $BASE --es audio_backend gpu) ;;
    cpu) EXTRAS=(--es backend cpu --es bundle $BASE) ;;
  esac
  EXTRAS+=(--ez reindex true)
  W0=$(date +%s)
  while true; do
    present || stop 4 "phone gone before leg $LEG"
    R=$(ready)
    [[ -z $R ]] && break
    echo "## $(date '+%T') $LEG not ready: $(print -r -- $R | tr '\n' ';')" >> $LOG
    (( $(date +%s) - W0 >= READY_LIMIT_S )) && stop 7 "phone not ready for leg $LEG after $READY_LIMIT_S s: $(print -r -- $R | tr '\n' ';')"
    past_hard && stop 8 "28 min reached while leg $LEG waited for the phone"
    nap 10
  done
  { echo "## $(date '+%T') leg $LEG ready after $(( $(date +%s) - W0 )) s; state"; state; } >> $LOG 2>&1
  launch "leg $LEG" $EXTRAS --es run_queries all --es run_texts all --es text_prefix all --ei gap_ms $GAP_MS
  if [[ -z $APID ]]; then
    STATUS=CRASH
    echo "## $(date '+%T') leg $LEG: the app is not running 2 s after the launch" >> $LOG
    dev logcat -d -b crash -T "$SINCE" > $OUT/${LEG}_crash.log
  else
    FOUND="" WHY=limit NLAST=0 TLAST=$(date +%s) T0=$(date +%s)
    while (( $(date +%s) - T0 < DONE_LIMIT_S )); do
      DL=$(demo_lines)
      N=${#${(f)DL}}
      FOUND=$(print -r -- $DL | grep -E "DONE json=|ERROR (init|bundle)" | head -1)
      [[ -n $FOUND ]] && { WHY=line; break; }
      POLL=$(dev shell "if [ -d /proc/$APID ]; then echo up; else echo gone; fi; grep MemAvailable /proc/meminfo" | tr -d '\r')
      if [[ $POLL != *up* ]]; then
        if [[ $POLL == *gone* ]]; then WHY=process_gone; break; fi
        present || { WHY=phone_gone; break; }
      fi
      MEM=$(print -r -- $POLL | sed -nE 's/.*MemAvailable: *([0-9]+).*/\1/p' | head -1)
      [[ -n $MEM ]] && (( MEM < MEM_FLOOR_KB )) && { WHY="mem_low MemAvailable $MEM kB"; break; }
      if (( N > NLAST )); then NLAST=$N TLAST=$(date +%s); fi
      (( $(date +%s) - TLAST >= STALL_S )) && { WHY="stalled (no new $TAG line for $STALL_S s)"; break; }
      past_hard && { WHY=hard; break; }
      nap 2
    done
    [[ $WHY == phone_gone ]] && stop 4 "phone gone during leg $LEG"
    case $WHY in
      line) [[ $FOUND == *"DONE json="* ]] && STATUS=DONE || STATUS=ERROR ;;
      process_gone) STATUS=CRASH ;;
      *) STATUS="STOPPED $WHY" ;;
    esac
    demo_lines > $OUT/${LEG}_demo.log
    [[ $STATUS == CRASH ]] && grep -q -E "ERROR (init|bundle)" $OUT/${LEG}_demo.log && STATUS="ERROR (then the process ended)"
    [[ $STATUS == ERROR* ]] && grep -m1 -E "ERROR (init|bundle)" $OUT/${LEG}_demo.log > $OUT/${LEG}_error.txt
    dev logcat -d -T "$SINCE" --pid=$APID > $OUT/${LEG}_app_logcat.log
    dev logcat -d -b crash -T "$SINCE" > $OUT/${LEG}_crash.log
    { dev logcat -d -T "$SINCE" -s lowmemorykiller:V | grep -v '^-----'
      dev logcat -d -b events -T "$SINCE" | grep -E ' killinfo' ; } > $OUT/${LEG}_lmk.log
    grep -E '^[0-9-]+ [0-9:.]+ +[0-9]+ +[0-9]+ [EF] ' $OUT/${LEG}_app_logcat.log | head -20 > $OUT/${LEG}_first_errors.txt
    # The runtime's lines only: the app's own lines repeat the backend it asked for (logcat pads the tag: "Eg2Demo :").
    { grep -v -E " $TAG *:" $OUT/${LEG}_app_logcat.log | grep -i -E "qnn|htp|dispatch|npu|Replacing|delegate|accelerat|qualcomm" | head -20
      grep -v -E " $TAG *:" $OUT/${LEG}_app_logcat.log | grep -i -E "xnnpack|opencl|gpu" | grep -v -i -E "qnn|htp|dispatch|npu|Replacing|delegate|accelerat|qualcomm" | head -10
    } > $OUT/${LEG}_backend_lines.txt
    [[ $LEG == npu* ]] && dev logcat -d -T "$SINCE" | grep -i -E "qnn|htp|fastrpc|adsprpc|cdsp|litert" | head -200 > $OUT/${LEG}_npu_all_pids.txt
    alive $APID && shot ${LEG}_screen.png
    jr=$(grep -m1 "RUN_JSON path=" $OUT/${LEG}_demo.log | sed -nE 's/.*RUN_JSON path=([^ ]+).*/\1/p' | tr -d '\r')
    [[ -n $jr ]] && dev pull $jr $OUT/${LEG}_${jr:t} >> $LOG 2>&1
    echo "## leg $LEG cgroup: $(dev shell cat /proc/$APID/cgroup 2>/dev/null | grep cpuset | tr -d '\r')" >> $LOG
  fi
  dev shell am force-stop $P
  { echo "## state after $LEG"; state; } >> $LOG 2>&1
  step_line $LEG "$STATUS: $(grep -c 'RESULT ' $OUT/${LEG}_demo.log 2>/dev/null) RESULT lines${FOUND:+; $FOUND}"
  if [[ $LEG == npu2 ]]; then
    # The launch's rule (no QnnDsp <E>, 103 RESULT rows, NPU timings unlike the GPU's and CPU's) and the photos' backend.
    local -a rj; rj=(${OUT}/npu2_eg2-demo-*.json(N))
    $K/venv/bin/python -I $HERE/npu_pick.py --run "${rj[-1]:-}" --logcat $OUT/npu2_app_logcat.log \
      --gpu ${GPU_REF_JSON:-$K/out/device/gpu_eg2-demo-1791398317210.json} \
      --cpu ${CPU_REF_JSON:-$K/out/device/cpu_eg2-demo-1791398460931.json} --out $OUT/npu2_summary.txt \
      > $OUT/photos_backend.txt 2>> $LOG
    [[ $(cat $OUT/photos_backend.txt 2>/dev/null) == npu ]] || print -r -- gpu > $OUT/photos_backend.txt
    echo "## $(date '+%T') npu2 judged: $(head -1 $OUT/npu2_summary.txt 2>/dev/null); photos backend $(cat $OUT/photos_backend.txt)" >> $LOG
  fi
  if [[ $STATUS == CRASH ]]; then CRASHES=$(( CRASHES + 1 )); else CRASHES=0; fi
  # A leg cut short by memory or the 28 min line goes into CARRY_OVER; a stalled one is data like an ERROR.
  [[ $STATUS == "STOPPED mem_low"* ]] && { COMPLETED=0; stop 5 "leg $LEG: $WHY"; }
  [[ $STATUS == "STOPPED hard" ]] && { COMPLETED=0; stop 8 "28 min reached during leg $LEG"; }
  [[ -s $OUT/${LEG}_lmk.log ]] && stop 5 "lmkd kill lines during leg $LEG ($OUT/${LEG}_lmk.log)"
  (( CRASHES >= 2 )) && stop 6 "the app crashed in two legs running (the last: $LEG)"
  return 0
}

# ---- failure paths ------------------------------------------------------------------------------------------------

verdict() { echo "## verdict: $1" >> $2; step_line $3 "$1"; }

fail_bundle() {
  local f=$OUT/fail_bundle.log a
  launch fail_bundle --es backend gpu --es bundle no-such.litertlm
  echo "# fail bundle: --es bundle no-such.litertlm, pid ${APID:-none}, device time $SINCE" > $f
  [[ -n $APID ]] || { verdict "FAIL the app is not running after the launch" $f fail_bundle; return 0; }
  wait_line "$SINCE" "ERROR bundle missing" 30
  why_stop fail_bundle
  nap 1
  alive $APID && a=alive || a=gone
  shot fail_bundle_screen.png
  { echo "## line: ${FOUND:-none ($WHY)}"; echo "## process $APID after the line: $a"
    echo "## screencap: fail_bundle_screen.png (the pill reads \"bundle missing\" in #E53935)"; demo_lines; } >> $f
  dev shell am force-stop $P
  if [[ -n $FOUND && $a == alive ]]; then verdict "PASS ERROR line, process alive" $f fail_bundle
  else verdict "FAIL line=${FOUND:-none} process=$a" $f fail_bundle; fi
}

fail_permission() {
  local f=$OUT/fail_permission.log g0 g1 t1 t2 got
  dev shell am force-stop $P
  dev shell pm revoke $P android.permission.RECORD_AUDIO >> $LOG 2>&1
  g0=$(perm_state)
  launch fail_permission --es backend gpu --es bundle $BASE
  echo "# fail permission: RECORD_AUDIO revoked ($g0), gpu launch pid ${APID:-none}, device time $SINCE" > $f
  if [[ -n $APID ]]; then
    wait_line "$SINCE" " READY |ERROR (init|bundle)" 90
    why_stop fail_permission
  fi
  if [[ -z $APID || $FOUND != *" READY "* ]]; then
    dev shell pm grant $P android.permission.RECORD_AUDIO >> $LOG 2>&1
    { echo "## no READY: ${FOUND:-$WHY}"; echo "## granted back: $(perm_state)"; } >> $f
    verdict "FAIL no READY (${FOUND:-$WHY})" $f fail_permission
    return 0
  fi
  READY_PID=$APID
  mic_px || { dev shell pm grant $P android.permission.RECORD_AUDIO >> $LOG 2>&1; verdict "FAIL no LAYOUT line for the mic button" $f fail_permission; return 0; }
  nap 1
  dev shell input swipe $MIC_X $MIC_Y $MIC_X $MIC_Y 600
  wait_line "$SINCE" "MIC_PERMISSION missing" 15
  why_stop fail_permission
  got=$FOUND
  nap 1
  t1=$(top_activity)
  shot fail_permission_screen.png
  dev shell pm grant $P android.permission.RECORD_AUDIO >> $LOG 2>&1
  # BACK only while the dialog is still in front: a dialog that closed itself on the grant would hand BACK to the app.
  nap 1
  t2=$(top_activity)
  FOUND=""
  if [[ $t2 == *ermission* ]]; then
    dev shell input keyevent KEYCODE_BACK
    wait_line "$SINCE" "MIC_PERMISSION granted=" 10
    why_stop fail_permission
  else
    wait_line "$SINCE" "MIC_PERMISSION granted=" 5
  fi
  nap 1
  g1=$(perm_state)
  t2="$t2 -> $(top_activity)"
  { echo "## press x=$MIC_X y=$MIC_Y 600 ms -> ${got:-no MIC_PERMISSION line}"
    echo "## top activity after the press: $t1 (screencap fail_permission_screen.png)"
    echo "## pm grant, then BACK only if the dialog was still in front: ${FOUND:-no MIC_PERMISSION granted= line}"
    echo "## after: $g1; top activity: $t2"; demo_lines; } >> $f
  if [[ -n $got && $g1 == *granted=true* ]]; then verdict "PASS MIC_PERMISSION missing, granted back" $f fail_permission
  else verdict "FAIL line=${got:-none} after=$g1" $f fail_permission; fi
}

fail_mic() {
  local f=$OUT/fail_mic.log s m0 m1 long short tap=""
  echo "# fail mic: RECORD_AUDIO granted, presses on the mic button with nobody speaking" > $f
  ensure_ready fail_mic || { verdict "FAIL the app did not reach READY" $f fail_mic; return 0; }
  m0=$(dev shell "ls $FILES/mic/" | tr -d '\r' | tr '\n' ' ')
  nap 1
  s=$(dev_time)
  dev shell input swipe $MIC_X $MIC_Y $MIC_X $MIC_Y 1500
  wait_line "$s" "RESULT kind=audio id=mic-|MIC_REJECTED|ERROR " 30
  why_stop fail_mic
  long=$FOUND
  m1=$(dev shell "ls -l $FILES/mic/" | tr -d '\r')
  { echo "## 1500 ms press at x=$MIC_X y=$MIC_Y (device time $s) -> ${long:-none ($WHY)}"
    echo "## mic/ before: ${m0:-empty}"; echo "## mic/ after:"; print -r -- $m1; demo_since "$s"; } >> $f
  nap 1
  s=$(dev_time)
  dev shell input swipe $MIC_X $MIC_Y $MIC_X $MIC_Y 120
  wait_line "$s" "MIC_REJECTED|RESULT kind=audio id=mic-|ERROR " 15
  why_stop fail_mic
  short=$FOUND
  { echo "## 120 ms press (device time $s) -> ${short:-none ($WHY)}"; demo_since "$s"; } >> $f
  if [[ $short != *MIC_REJECTED* ]]; then
    # The app rejects only an empty clip (no length floor): a 120 ms press can record 50 ms. A tap (down and up in the
    # same moment) is the empty clip.
    nap 1
    s=$(dev_time)
    dev shell input tap $MIC_X $MIC_Y
    wait_line "$s" "MIC_REJECTED|RESULT kind=audio id=mic-|ERROR " 15
    why_stop fail_mic
    tap=$FOUND
    { echo "## tap (device time $s) -> ${tap:-none ($WHY)}"; demo_since "$s"; } >> $f
  fi
  if [[ $long == *"RESULT kind=audio"* && ( $short == *MIC_REJECTED* || $tap == *MIC_REJECTED* ) ]]; then
    verdict "PASS 1.5 s press -> RESULT; reject from the $([[ $short == *MIC_REJECTED* ]] && echo 120 ms press || echo tap)" $f fail_mic
  else
    verdict "FAIL long=${long:-none} short=${short:-none} tap=${tap:-not sent}" $f fail_mic
  fi
}

fail_busy() {
  local f=$OUT/fail_busy.log s ni nr
  echo "# fail busy: two run_queries intents for q01_a.wav sent in one device shell (within 200 ms)" > $f
  ensure_ready fail_busy || { verdict "FAIL the app did not reach READY" $f fail_busy; return 0; }
  nap 1
  s=$(dev_time)
  dev shell "am start -n $P/$ACT --es run_queries q01_a.wav & am start -n $P/$ACT --es run_queries q01_a.wav; wait" >> $f 2>&1
  wait_line "$s" "DONE json=" 30
  why_stop fail_busy
  ni=$(demo_since "$s" | grep -c "IGNORED busy source=intent")
  nr=$(demo_since "$s" | grep -c "RESULT kind=audio id=q01_a")
  { echo "## since $s: IGNORED busy source=intent x$ni, RESULT q01_a x$nr, ${FOUND:-no DONE ($WHY)}"; demo_since "$s"; } >> $f
  if (( ni == 1 && nr == 1 )); then verdict "PASS one IGNORED, one RESULT" $f fail_busy
  else verdict "FAIL IGNORED x$ni RESULT x$nr" $f fail_busy; fi
}

fail_back() {
  local f=$OUT/fail_back.log s pid ev a3 a10 bad cr t
  echo "# fail back: KEYCODE_BACK on the READY screen" > $f
  ensure_ready fail_back || { verdict "FAIL the app did not reach READY" $f fail_back; return 0; }
  pid=$APID
  nap 1
  s=$(dev_time)
  dev shell input keyevent KEYCODE_BACK
  nap 3
  ev=$(dev logcat -d -b events -T "$s" | grep -E "eg2demo" | grep -E "finish_activity|destroy_activity|on_destroy_called")
  alive $pid && a3=alive || a3=gone
  t=$(top_activity)
  nap 7
  alive $pid && a10=alive || a10=gone
  bad=$(dev logcat -d -T "$s" --pid=$pid | grep -E "FATAL|IllegalStateException|close failed")
  cr=$(dev logcat -d -b crash -T "$s" | grep -E "eg2demo")
  { echo "## BACK at device time $s, pid $pid"; echo "## events:"; print -r -- ${ev:-none}
    echo "## top activity 3 s after: $t"; echo "## process 3 s after: $a3, 10 s after: $a10"
    echo "## FATAL / IllegalStateException / close failed lines: ${bad:-none}"; echo "## crash buffer lines: ${cr:-none}"
    dev logcat -d -T "$s" --pid=$pid; } >> $f
  dev shell am force-stop $P
  READY_PID=""
  if [[ -n $ev && -z $bad && -z $cr ]]; then verdict "PASS activity ended, no FATAL / IllegalStateException (process 10 s after: $a10)" $f fail_back
  else verdict "FAIL events=${ev:+yes} bad=${bad:-none} crash=${cr:-none}" $f fail_back; fi
}

# ---- shots --------------------------------------------------------------------------------------------------------

shots() {
  local f=$OUT/shots.log s q r pb qs
  pb=${SHOTS_BACKEND:-$(cat $OUT/photos_backend.txt 2>/dev/null)}
  if [[ $pb == npu ]]; then
    launch shots --es backend npu --es bundle $NPU_BUNDLE --ez reindex true
  else
    pb=gpu
    launch shots --es backend gpu --es bundle $BASE --ez reindex true
  fi
  echo "# shots: $pb launch (reindex) pid ${APID:-none}, device time $SINCE" > $f
  [[ -n $APID ]] || { verdict "FAIL the app is not running after the launch" $f shots; return 0; }
  wait_line "$SINCE" " READY |ERROR (init|bundle)" 120
  why_stop shots
  [[ $FOUND == *" READY "* ]] || { echo "## no READY: ${FOUND:-$WHY}" >> $f; verdict "FAIL no READY" $f shots; return 0; }
  wait_line "$SINCE" "LAYOUT " 10
  why_stop shots
  nap 2
  shot shot1_ready.png
  r="shot1_ready.png"
  qs=(q01_a:shot2_q01.png q13_a:shot3_q13.png)
  [[ ${SHOTS_READY_ONLY:-0} == 1 ]] && qs=()
  for q in $qs; do
    nap 1
    s=$(dev_time)
    dev shell am start -n $P/$ACT --es run_queries ${q%%:*}.wav >> $LOG 2>&1
    wait_line "$s" "RESULT kind=audio id=${q%%:*}" 30
    why_stop shots
    echo "## ${q%%:*}: ${FOUND:-no RESULT ($WHY)}" >> $f
    [[ -n $FOUND ]] || continue
    nap 3
    shot ${q#*:}
    r="$r ${q#*:}"
    wait_line "$s" "DONE json=" 15
    why_stop shots
  done
  demo_lines > $OUT/shots_demo.log
  grep -E "ENGINE_READY|INDEX_DONE|WARMUP|READY |LAYOUT |RESULT kind" $OUT/shots_demo.log | sed 's/^/## /' >> $f
  dev logcat -d -T "$SINCE" --pid=$APID > $OUT/shots_app_logcat.log
  s=$(grep -m1 "RUN_JSON path=" $OUT/shots_demo.log | sed -nE 's/.*RUN_JSON path=([^ ]+).*/\1/p' | tr -d '\r')
  [[ -n $s ]] && dev pull $s $OUT/shots_${s:t} >> $LOG 2>&1
  dev shell am force-stop $P
  READY_PID=""
  if [[ ${SHOTS_READY_ONLY:-0} == 1 ]]; then
    if [[ -s $OUT/shot1_ready.png ]]; then verdict "PASS $r ($pb, READY only)" $f shots; else verdict "FAIL no shot1_ready.png ($pb)" $f shots; fi
  elif [[ $r == *shot3_q13.png* && $r == *shot2_q01.png* ]]; then verdict "PASS $r ($pb)" $f shots; else verdict "FAIL only $r ($pb)" $f shots; fi
}

# ---- mic floors (round 3) -----------------------------------------------------------------------------------------

mic() {
  local f=$OUT/mic_check.log s short quiet w rs rq
  echo "# mic check: MIC_MIN_S and the RMS floor on the phone, nobody speaking (presses on the mic button)" > $f
  ensure_ready mic || { verdict "FAIL the app did not reach READY" $f mic; return 0; }
  # Round 2's silent 1.35 s clip is still in files/mic/: its RMS is the floor's lower bound.
  dev pull $FILES/mic/1791398543548.wav $OUT/r2_silent_1791398543548.wav >> $LOG 2>&1
  nap 1
  s=$(dev_time)
  dev shell input swipe $MIC_X $MIC_Y $MIC_X $MIC_Y 300
  wait_line "$s" "MIC_REJECTED|RESULT kind=audio id=mic-|ERROR " 15
  why_stop mic
  short=$FOUND
  { echo "## 300 ms press at x=$MIC_X y=$MIC_Y (device time $s) -> ${short:-none ($WHY)}"; demo_since "$s"; } >> $f
  nap 2
  s=$(dev_time)
  dev shell input swipe $MIC_X $MIC_Y $MIC_X $MIC_Y 1500
  wait_line "$s" "MIC_REJECTED|RESULT kind=audio id=mic-|ERROR " 20
  why_stop mic
  quiet=$FOUND
  { echo "## 1500 ms press (device time $s) -> ${quiet:-none ($WHY)}"; demo_since "$s"; } >> $f
  for w in ${(f)"$(print -r -- "$short"$'\n'"$quiet" | sed -nE 's/.* wav=([^ ]+).*/\1/p')"}; do
    [[ -n $w && $w != - ]] && dev pull $w $OUT/mic_check_${w:t} >> $LOG 2>&1
  done
  nap 1
  dev shell am force-stop $P
  READY_PID=""
  [[ $short == *"reason=short"* ]] && rs=ok || rs=no
  [[ $quiet == *"reason=quiet"* ]] && rq=ok || rq=no
  if [[ $rs == ok && $rq == ok ]]; then verdict "PASS 300 ms -> reason=short, 1500 ms silent -> reason=quiet" $f mic
  else verdict "FAIL short=${short:-none} quiet=${quiet:-none}" $f mic; fi
}

# ---- run ----------------------------------------------------------------------------------------------------------

{
  echo "# $(date '+%F %T') gate steps=${STEPS[*]} gap_ms=$GAP_MS hold pid $mine since $hold_started (held $(held) s)"
  echo "## apk $APK sha256 $(shasum -a 256 $APK | cut -d' ' -f1)"
  echo "## state before"; state
  echo "## top before"; top_head
} > $LOG 2>&1
present || stop 4 "phone not in adb at the start"
grep -q "litert_procs: 0" $LOG || { echo "a litert process is running"; tail -20 $LOG; exit 1; }

if [[ ${SKIP_SETUP:-0} == 1 ]]; then
  echo "## SKIP_SETUP=1: no install, no push (an earlier gate.sh call in this hold did both)" >> $LOG
else
# Install (2 min at most: a prompt on the phone would hold it), grant, one start to make the dirs.
dev install -r $APK >> $LOG 2>&1 &
IPID=$!
for k in {1..120}; do kill -0 $IPID 2>/dev/null || break; nap 1; done
kill -0 $IPID 2>/dev/null && { kill $IPID; echo "install did not return within 120 s"; tail -5 $LOG; exit 1; }
wait $IPID || { echo "install failed"; tail -5 $LOG; exit 1; }
dev shell pm grant $P android.permission.RECORD_AUDIO >> $LOG 2>&1
dev shell am force-stop $P
dev shell am start -n $P/$ACT >> $LOG 2>&1
nap 4
dev shell am force-stop $P
for d in album queries mic; do
  [[ -n $(dev shell "[ -d $FILES/$d ] && echo yes") ]] || { echo "$FILES/$d was not made by the app"; exit 1; }
done
echo "## app made $FILES/{album,queries,mic}" >> $LOG

# Files into the app's dirs, one by one, sha256 checked. PUSH_MODE=missing: what is already there (listed by name) stays.
FAIL=0
HAVE_TOP="" HAVE_ALBUM="" HAVE_QUERIES=""
if [[ $PUSH_MODE == missing ]]; then
  HAVE_TOP=$(dev shell "ls $FILES" | tr -d '\r')
  HAVE_ALBUM=$(dev shell "ls $FILES/album" | tr -d '\r')
  HAVE_QUERIES=$(dev shell "ls $FILES/queries" | tr -d '\r')
fi
NPUSHED=0 NHAD=0
push_if() {  # $1 = local file, $2 = remote path, $3 = the remote dir's listing (empty in PUSH_MODE=all)
  if [[ $PUSH_MODE == missing ]] && print -r -- "$3" | grep -qx -F -- "${2:t}"; then NHAD=$(( NHAD + 1 )); return 0; fi
  NPUSHED=$(( NPUSHED + 1 ))
  push_checked $1 $2
}
push_if $MODELS/$BASE $FILES/$BASE "$HAVE_TOP" || FAIL=1
(( ${STEPS[(I)npu*]} )) && { push_if $MODELS/$NPU_BUNDLE $FILES/$NPU_BUNDLE "$HAVE_TOP" || FAIL=1; }
for f in $K/fixtures/album/*.jpg; do push_if $f $FILES/album/${f:t} "$HAVE_ALBUM" || FAIL=1; done
for f in $K/fixtures/queries/*.wav $K/fixtures/queries.json; do push_if $f $FILES/queries/${f:t} "$HAVE_QUERIES" || FAIL=1; done
{ echo "## files: push mode $PUSH_MODE, already on the phone $NHAD, pushed $NPUSHED"
  echo "## on the phone: bundles $(print -r -- $HAVE_TOP | grep -c -E '\.litertlm$'), album $(print -r -- $HAVE_ALBUM | grep -c -E '\.jpg$'), queries $(print -r -- $HAVE_QUERIES | grep -c -E '\.(wav|json)$') (listing before the push)"
} >> $LOG
(( FAIL == 0 )) || { echo "a push did not arrive byte for byte (see $LOG)"; exit 1; }
fi
echo "## $(date '+%T') setup done (held $(held) s)" >> $LOG

for (( IDX = 1; IDX <= ${#STEPS}; IDX++ )); do
  STEP=${STEPS[IDX]}
  (( $(held) >= SOFT_S )) && stop 3 "25 min frame reached before $STEP"
  case $STEP in
    gpu|npu|npu2|npu_audio_npu|gpu_audio_gpu|cpu) leg $STEP ;;
    *) $STEP ;;
  esac
done
finish 0
