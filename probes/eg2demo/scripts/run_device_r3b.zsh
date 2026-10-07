#!/bin/zsh
# Round 3b on the phone in one process, so the hold always goes back: run_device_r3.zsh without the NPU leg and the mic
# check (the app changed only in its third line). A sleeper whose pid goes to $OUT/hold_sleeper.pid, queue_cli enqueue
# and wait in one command, edge_enable 0 (the value read first is put back at the end), then
#   PHASE=A  gate.sh shots (PUSH_MODE=missing, KEEP_FILES=1, SHOTS_BACKEND=gpu, SHOTS_READY_ONLY=1): install -r, the
#            files on the phone listed by name (only missing ones pushed), one GPU launch with --ez reindex true and the
#            READY screenshot shot1_ready.png; then the line "A_DONE" with the file counts and the LAYOUT px in this
#            script's output; then wait up to GO_WAIT_S (600) from A_DONE for $OUT/go_takes (written by the worker after
#            the supervisor's go) or $OUT/stop_now; the takes after a go; no go in time: release, exit 10 (the takes go
#            to a later PHASE=B hold);
#   PHASE=B  the takes only.
# Takes (TAKES, default "t1:q01_a.wav t2:q01_a.wav,q13_a.wav"): MODE=file take.sh with the base bundle on the GPU (the
# audio encoder stays on the app's default, the CPU), OUT_DIR $K/out/takes_r3b, NO_AIRPLANE=1 (the radio of a shared
# phone is not touched; the APK has no INTERNET permission). No take starts 25 min after the hold was taken
# (CARRY_OVER); each take's COOL_LIMIT_S is cut to what is left before that line. A watchdog 29 min after the hold
# stops the running child, screenrecord and the app; then the hold is released.
#   K/scripts/run_device_r3b.zsh    env: PHASE (A|B), TAKES, GO_WAIT_S (600), WAIT_S (3600), HOLD_FILE, OUT_DIR,
#                                        TAKES_OUT_DIR, ADB_SHIM, DRY_FAST (dry run: no sleeps in gate.sh), HOLD_NAME
# Exit: 0 done, 3 no hold within WAIT_S (dequeued, the phone untouched), 10 no go within GO_WAIT_S, 11 stop_now,
#   12 the 29 min watchdog, 13 a take failed or was carried over, 14 no READY screenshot; gate.sh's own status if its
#   PHASE=A call failed.
set -u
K=${0:A:h:h}
HERE=${0:A:h}
CA=$HOME/code/litertlm-convert/community_accel_work
HOLD=${HOLD_FILE:-$CA/s2_npu_sweep/.device_hold}
NAME=${HOLD_NAME:-eg2demo-take-r3b}
OUT=${OUT_DIR:-$K/out/device_r3b}
TOUT=${TAKES_OUT_DIR:-$K/out/takes_r3b}
PHASE=${PHASE:-A}
TAKES=(${=${TAKES:-t1:q01_a.wav t2:q01_a.wav,q13_a.wav}})
GO_WAIT_S=${GO_WAIT_S:-600}
S=RFGL80R6A6H
P=com.mlboydaisuke.eg2demo
[[ $PHASE == A || $PHASE == B ]] || { echo "PHASE must be A or B"; exit 1; }
[[ -n ${ADB_SHIM:-} ]] && source $ADB_SHIM
dev() { adb -s $S "$@"; }
mkdir -p $OUT $TOUT
rm -f $OUT/go_takes $OUT/stop_now $OUT/deadline_hit $OUT/child.pid $OUT/released
SL=$(sleep 36000 </dev/null >/dev/null 2>&1 & echo $!)
print -r -- $SL > $OUT/hold_sleeper.pid
log() { echo "## $(date '+%T') $*"; }
EDGE0=""
release() {
  if [[ -n $EDGE0 ]]; then
    dev shell settings put secure edge_enable $EDGE0
    log "edge_enable put back to $EDGE0 (now $(dev shell settings get secure edge_enable | tr -d '\r'))"
    EDGE0=""
  fi
  python3 $CA/hold_cli.py release $HOLD $SL
  kill $SL 2>/dev/null
  log "released (sleeper $SL killed), held $(held) s"
  python3 $CA/queue_cli.py list $HOLD
}
log "phase $PHASE, keeper sleeper $SL, enqueue"
python3 $CA/queue_cli.py enqueue $HOLD $NAME hfmodels-android-b4 30
if ! python3 $CA/queue_cli.py wait $HOLD $NAME $SL --timeout ${WAIT_S:-3600}; then
  python3 $CA/queue_cli.py dequeue $HOLD $NAME
  kill $SL 2>/dev/null
  log "no hold after ${WAIT_S:-3600} s: dequeued, the phone untouched"
  exit 3
fi
hold_started=$(python3 -c "import json,sys; print(json.load(open(sys.argv[1])).get('started', ''))" $HOLD 2>/dev/null)
T_HOLD=$(date -j -f '%Y-%m-%d %H:%M:%S' "$hold_started" +%s 2>/dev/null)
[[ $T_HOLD == <-> ]] || T_HOLD=$(date +%s)
T_HOLD=$(( T_HOLD - ${FAKE_HELD_S:-0} ))   # dry run only: pretend the hold is older (the watchdog test)
held() { print -r -- $(( $(date +%s) - T_HOLD )); }
log "HOLD taken (started $hold_started); phone: $(dev shell 'echo uptime $(cut -d" " -f1 /proc/uptime); dumpsys thermalservice | grep -m1 "Thermal Status"; dumpsys power | grep -m1 mWakefulness=' | tr -d '\r' | tr '\n' ' ')"
trap 'log "interrupted"; [[ -f $OUT/child.pid ]] && kill -TERM $(cat $OUT/child.pid) 2>/dev/null; release; exit 130' INT TERM
EDGE0=$(dev shell settings get secure edge_enable | tr -d '\r')
[[ $EDGE0 == <-> ]] || EDGE0=1
dev shell settings put secure edge_enable 0
log "edge_enable $EDGE0 -> $(dev shell settings get secure edge_enable | tr -d '\r')"

# The 29 min watchdog: the running child, screenrecord and the app stop; the main flow then releases.
( while (( $(held) < 1740 )); do
    [[ -f $OUT/released ]] && exit 0
    sleep 5
  done
  print -r -- "deadline $(date '+%T')" > $OUT/deadline_hit
  [[ -f $OUT/child.pid ]] && kill -TERM $(cat $OUT/child.pid) 2>/dev/null
  dev shell 'p=$(pidof screenrecord); [ -n "$p" ] && kill -2 $p'
  dev shell am force-stop $P ) &
WD=$!

child() {  # run one child in the background so the watchdog can stop it; returns its status
  "$@" &
  local c=$!
  print -r -- $c > $OUT/child.pid
  wait $c
  local rc=$?
  rm -f $OUT/child.pid
  return $rc
}
finish() {
  print -r -- done > $OUT/released
  kill $WD 2>/dev/null
  trap - INT TERM
  release
  log "end rc=$1"
  exit $1
}

if [[ $PHASE == A ]]; then
  child env PUSH_MODE=missing KEEP_FILES=1 SHOTS_BACKEND=gpu SHOTS_READY_ONLY=1 OUT_DIR=$OUT HOLD_FILE=$HOLD \
    zsh $HERE/gate.sh shots > $OUT/gate_stdout.log 2>&1
  RC=$?
  tail -8 $OUT/gate_stdout.log
  [[ -f $OUT/deadline_hit ]] && finish 12
  (( RC == 0 )) || { log "gate.sh rc=$RC"; finish $RC; }
  grep -q "verdict: PASS" $OUT/shots.log 2>/dev/null && [[ -s $OUT/shot1_ready.png ]] || { log "no READY screenshot"; finish 14; }
  # The READY screen's LAYOUT line (title top and footer bottom must lie inside the 1920 px band from frame_px.top).
  LAY=$(python3 -c "
import json, sys
for line in open(sys.argv[1]):
    if 'LAYOUT {' in line and 'RESULT_LAYOUT' not in line:
        d = json.loads(line.split('LAYOUT ', 1)[1])
        y0 = d['frame_px']['top']
        t, f = d['title_top_px'], d['footer_bottom_px']
        print(f\"title_top_px {t} footer_bottom_px {f} title_to_footer_px {d['title_to_footer_px']} frame_px.top {y0}\",
              f\"pill_px.top {d['pill_px']['top']} footer_lines {d['footer_lines']}\",
              'inside' if y0 <= t and f <= y0 + 1920 else 'OUTSIDE', f'{y0}..{y0 + 1920}')
        break
else:
    print('no LAYOUT line')" $OUT/shots.log 2>&1)
  log "A_DONE shot1_ready.png in $OUT; $(grep -m1 -h '## on the phone:' $OUT/gate.log | sed 's/^## //'); LAYOUT $LAY"
  T_A=$(date +%s)
  while [[ ! -f $OUT/go_takes ]]; do
    [[ -f $OUT/stop_now ]] && { log "stop_now"; finish 11; }
    [[ -f $OUT/deadline_hit ]] && finish 12
    (( $(date +%s) - T_A >= GO_WAIT_S )) && { log "no go within $GO_WAIT_S s of A_DONE: releasing (the takes go to PHASE=B)"; finish 10; }
    sleep 2
  done
  log "GO for the takes ($(cat $OUT/go_takes))"
fi

LX="--es backend gpu --es bundle embeddinggemma-2-740m.litertlm"
FAILED=0
for spec in $TAKES; do
  tag=${spec%%:*}
  q=${spec#*:}
  left=$(( 1500 - $(held) ))
  if (( left <= 60 )); then log "CARRY_OVER take $tag ($q): 25 min line"; FAILED=1; continue; fi
  cool=$(( left - 60 < 600 ? left - 60 : 600 ))
  log "take $tag ($q) on gpu, cool limit $cool s"
  child env MODE=file OUT_DIR=$TOUT HOLD_FILE=$HOLD HOLD_PID_FILE=$OUT/hold_sleeper.pid NO_AIRPLANE=1 COOL_LIMIT_S=$cool \
    LAUNCH_EXTRAS=$LX zsh $HERE/take.sh $tag $q > $TOUT/take_${tag}_stdout.log 2>&1
  rc=$?
  log "take $tag rc=$rc: $(grep -m1 'screenrecord stopped' $TOUT/take_${tag}.log 2>/dev/null)"
  (( rc == 0 )) || FAILED=1
  [[ -f $OUT/deadline_hit ]] && finish 12
done
(( FAILED == 0 )) && finish 0
finish 13
