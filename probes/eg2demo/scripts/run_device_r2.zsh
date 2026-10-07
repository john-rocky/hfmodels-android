#!/bin/zsh
# Round 2 on the phone in one process, so the hold always goes back: the keeper (a sleeper whose pid goes to
# $OUT/hold_sleeper.pid; queue_cli enqueue and wait in the same command, so no ticket waits without a keeper), gate.sh
# with the round's steps (KEEP_FILES=1: round 3 uses the pushed files), then the hold released and the sleeper killed.
# A watchdog stops the gate 29 min after it started (gate.sh stops itself at 28 min; this is the backstop).
#   K/scripts/run_device_r2.zsh     env: STEPS ("gpu npu npu_audio_npu gpu_audio_gpu cpu fail shots"), WAIT_S (3600),
#                                        HOLD_FILE and OUT_DIR (a fake hold and dir for a dry run, with gate.sh's ADB_SHIM)
# The sleeper is started with its output redirected: `S=$(sleep 36000 & echo $!)` keeps the substitution's pipe open
# and returns after 10 h (zsh, measured with sleep 3: 3.0 s).
# Exit: the gate's status; 3 with "no hold" when the queue wait ran out (the entry dequeued, the phone untouched).
set -u
K=${0:A:h:h}
CA=$HOME/code/litertlm-convert/community_accel_work
HOLD=${HOLD_FILE:-$CA/s2_npu_sweep/.device_hold}
NAME=${HOLD_NAME:-eg2demo-gate-r2}
OUT=${OUT_DIR:-$K/out/device}
RUN_STEPS=${STEPS:-gpu npu npu_audio_npu gpu_audio_gpu cpu fail shots}
mkdir -p $OUT
SL=$(sleep 36000 </dev/null >/dev/null 2>&1 & echo $!)
print -r -- $SL > $OUT/hold_sleeper.pid
release() {
  python3 $CA/hold_cli.py release $HOLD $SL
  kill $SL 2>/dev/null
  echo "released $(date '+%F %T') (sleeper $SL killed)"
  python3 $CA/queue_cli.py list $HOLD
}
echo "keeper sleeper $SL, enqueue at $(date '+%F %T')"
python3 $CA/queue_cli.py enqueue $HOLD $NAME hfmodels-android-b4 30
if ! python3 $CA/queue_cli.py wait $HOLD $NAME $SL --timeout ${WAIT_S:-3600}; then
  python3 $CA/queue_cli.py dequeue $HOLD $NAME
  kill $SL 2>/dev/null
  echo "no hold after ${WAIT_S:-3600} s ($(date '+%F %T')): dequeued, the phone untouched"
  exit 3
fi
trap 'release; exit 130' INT TERM
KEEP_FILES=1 zsh $K/scripts/gate.sh ${=RUN_STEPS} > $OUT/gate_stdout.log 2>&1 &
G=$!
( t0=$(date +%s)
  while kill -0 $G 2>/dev/null; do
    (( $(date +%s) - t0 >= 1740 )) && { kill -TERM $G; echo "watchdog: gate stopped at 29 min" >> $OUT/gate_stdout.log; break; }
    sleep 5
  done ) &
W=$!
wait $G
RC=$?
wait $W 2>/dev/null
trap - INT TERM
release
tail -30 $OUT/gate_stdout.log
echo "gate rc=$RC"
exit $RC
