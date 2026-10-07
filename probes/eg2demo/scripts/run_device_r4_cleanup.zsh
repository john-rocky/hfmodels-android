#!/bin/zsh
# Round 4 (lane close) on the phone in one process, so the hold always goes back: delete by name what rounds 2 to 3b
# left in the app's external files dir, uninstall com.mlboydaisuke.eg2demo (its GPU cache in the app's cache dir and its
# mic permission go with it) and release. A sleeper whose pid goes to $OUT/hold_sleeper.pid, queue_cli enqueue and
# wait in one process (script name eg2demo-cleanup-r4), then, in a child the watchdog can stop:
#   1. the phone on adb (`get-state`), its state (uptime, thermal status, wakefulness, /data) and the package line;
#      `ls -laR` of the files dir and `ls -la` of take.sh's /sdcard/eg2_demo_take.mp4 -> $OUT/ls_before.txt;
#   2. every name sorted into a known kind: the two bundles, index_*.json, album/<the names in K/fixtures/album>,
#      queries/<the wav names in K/fixtures/queries and queries.json>, mic/<digits>.wav,
#      Documents/eg2-demo-<digits>.json, and the four directories; a name of no known kind stops the run before
#      anything changes (exit 5);
#   3. am force-stop, `rm -f` of the known names (one adb call per directory), rmdir of the four directories, the
#      take mp4 by name when it is there;
#   4. `ls -la` of the files dir after -> $OUT/ls_after.txt (names still there are logged), `adb uninstall`, then the
#      package line and the app's dir read again -> $OUT/ls_after_uninstall.txt.
# Then release (hold_cli) and the sleeper killed by its pid. A watchdog DEADLINE_S (540) after the hold was taken
# stops the child and releases (the launch's frame is 10 minutes from taking the hold to giving it back).
#   K/scripts/run_device_r4_cleanup.zsh    env: OUT_DIR (K/out/device_r4), HOLD_FILE, HOLD_NAME, WAIT_S (3600),
#                                               DEADLINE_S (540), ADB_SHIM (dry run), DRY_* (the shim's switches)
# Exit: 0 done, 3 no hold within WAIT_S (dequeued, the phone untouched), 4 the phone is not on adb, 5 a name of no
#   known kind (nothing deleted, the app stays), 6 the app or its dir is still there after the uninstall,
#   12 the watchdog.
set -u
K=${0:A:h:h}
CA=$HOME/code/litertlm-convert/community_accel_work
HOLD=${HOLD_FILE:-$CA/s2_npu_sweep/.device_hold}
NAME=${HOLD_NAME:-eg2demo-cleanup-r4}
OUT=${OUT_DIR:-$K/out/device_r4}
DEADLINE_S=${DEADLINE_S:-540}
S=RFGL80R6A6H
P=com.mlboydaisuke.eg2demo
APPDIR=/sdcard/Android/data/$P
FILES=$APPDIR/files
TAKE_MP4=/sdcard/eg2_demo_take.mp4
BASE=embeddinggemma-2-740m.litertlm
NPU=embeddinggemma-2-740m_Qualcomm_SM8850.litertlm
[[ -n ${ADB_SHIM:-} ]] && source $ADB_SHIM
dev() { adb -s $S "$@"; }
mkdir -p $OUT
LOG=$OUT/cleanup.log
: > $LOG
rm -f "${OUT:?}/released" "${OUT:?}/deadline_hit"
log() { print -r -- "## $(date '+%T') $*" | tee -a $LOG; }
SL=$(sleep 36000 </dev/null >/dev/null 2>&1 & echo $!)
print -r -- $SL > $OUT/hold_sleeper.pid
log "keeper sleeper $SL, enqueue $NAME"
python3 $CA/queue_cli.py enqueue $HOLD $NAME hfmodels-android-b4 10
if ! python3 $CA/queue_cli.py wait $HOLD $NAME $SL --timeout ${WAIT_S:-3600}; then
  python3 $CA/queue_cli.py dequeue $HOLD $NAME
  kill $SL 2>/dev/null
  log "no hold after ${WAIT_S:-3600} s: dequeued, the phone untouched"
  exit 3
fi
T_HOLD=$(date +%s)
held() { print -r -- $(( $(date +%s) - T_HOLD )); }
log "HOLD taken ($(python3 $CA/hold_cli.py read $HOLD 2>&1 | tr '\n' ' '))"

# Names a device directory holds (one per line, CR dropped); nothing when it is missing.
names() { dev shell "ls -1 $1 2>/dev/null" | tr -d '\r'; }
SAFE='^[A-Za-z0-9][A-Za-z0-9._-]*$'

steps() {
  local st=$(dev get-state 2>&1 | tr -d '\r')
  [[ $st == device ]] || { log "phone not on adb: $st"; return 4; }
  log "phone: $(dev shell 'echo uptime $(cut -d" " -f1 /proc/uptime); dumpsys thermalservice | grep -m1 "Thermal Status"; dumpsys power | grep -m1 mWakefulness=; df -h /data | tail -1' | tr -d '\r' | tr '\n' ' ')"
  log "package before: $(dev shell "pm list packages $P" | tr -d '\r' | tr '\n' ' ')"
  { print -r -- "# ls -laR $FILES"; dev shell "ls -laR $FILES 2>&1"; print -r -- "# ls -la $TAKE_MP4"; dev shell "ls -la $TAKE_MP4 2>&1"; } \
    | tr -d '\r' > $OUT/ls_before.txt

  local -a top album queries mic docs unknown want_album want_queries
  local n
  want_album=(${(f)"$(cd $K/fixtures/album && print -l -- *.jpg)"})
  want_queries=(${(f)"$(cd $K/fixtures/queries && print -l -- *.wav)"} queries.json)
  for n in ${(f)"$(names $FILES)"}; do
    if [[ ! $n =~ $SAFE ]]; then unknown+=("files/$n")
    elif [[ $n == album || $n == queries || $n == mic || $n == Documents ]]; then :
    elif [[ $n == $BASE || $n == $NPU || $n =~ '^index_[A-Za-z0-9._-]+\.json$' ]]; then top+=($n)
    else unknown+=("files/$n"); fi
  done
  for n in ${(f)"$(names $FILES/album)"}; do
    [[ $n =~ $SAFE && ${want_album[(Ie)$n]} -gt 0 ]] && album+=($n) || unknown+=("files/album/$n")
  done
  for n in ${(f)"$(names $FILES/queries)"}; do
    [[ $n =~ $SAFE && ${want_queries[(Ie)$n]} -gt 0 ]] && queries+=($n) || unknown+=("files/queries/$n")
  done
  for n in ${(f)"$(names $FILES/mic)"}; do
    [[ $n =~ '^[0-9]+\.wav$' ]] && mic+=($n) || unknown+=("files/mic/$n")
  done
  for n in ${(f)"$(names $FILES/Documents)"}; do
    [[ $n =~ '^eg2-demo-[0-9]+\.json$' ]] && docs+=($n) || unknown+=("files/Documents/$n")
  done
  local take=$(dev shell "ls $TAKE_MP4 2>/dev/null" | tr -d '\r')
  log "BEFORE files/: ${#top} top-level files (${top[*]}), album ${#album} of ${#want_album}, queries ${#queries} of ${#want_queries}, mic ${#mic}, Documents ${#docs}, take mp4: ${take:-none}, unknown ${#unknown}"
  if (( ${#unknown} )); then
    log "STOP: names of no known kind, nothing deleted, the app stays: ${unknown[*]}"
    return 5
  fi

  dev shell am force-stop $P
  (( ${#top} )) && dev shell "cd $FILES && rm -f ${top[*]} 2>&1" | tr -d '\r' | tee -a $LOG
  (( ${#album} )) && dev shell "cd $FILES/album && rm -f ${album[*]} 2>&1" | tr -d '\r' | tee -a $LOG
  (( ${#queries} )) && dev shell "cd $FILES/queries && rm -f ${queries[*]} 2>&1" | tr -d '\r' | tee -a $LOG
  (( ${#mic} )) && dev shell "cd $FILES/mic && rm -f ${mic[*]} 2>&1" | tr -d '\r' | tee -a $LOG
  (( ${#docs} )) && dev shell "cd $FILES/Documents && rm -f ${docs[*]} 2>&1" | tr -d '\r' | tee -a $LOG
  dev shell "rmdir $FILES/album $FILES/queries $FILES/mic $FILES/Documents 2>&1" | tr -d '\r' | tee -a $LOG
  [[ -n $take ]] && dev shell "rm -f $TAKE_MP4 2>&1" | tr -d '\r' | tee -a $LOG
  { print -r -- "# ls -la $FILES"; dev shell "ls -la $FILES 2>&1"; print -r -- "# ls -la $TAKE_MP4"; dev shell "ls -la $TAKE_MP4 2>&1"; } \
    | tr -d '\r' > $OUT/ls_after.txt
  local left=(${(f)"$(names $FILES)"})
  log "AFTER deleting by name: files/ holds ${#left} names${left:+ (${left[*]})}; take mp4: $(dev shell "ls $TAKE_MP4 2>/dev/null" | tr -d '\r' | grep -c . ) left"

  log "uninstall: $(dev uninstall $P 2>&1 | tr -d '\r' | tr '\n' ' ')"
  local pkg=$(dev shell "pm list packages $P" | tr -d '\r')
  { print -r -- "# pm list packages $P"; print -r -- $pkg; print -r -- "# ls -la $APPDIR"; dev shell "ls -la $APPDIR 2>&1"; } \
    | tr -d '\r' > $OUT/ls_after_uninstall.txt
  local appdir=$(dev shell "ls -d $APPDIR 2>/dev/null" | tr -d '\r')
  log "AFTER uninstall: package line '${pkg}', app dir ${appdir:-gone}"
  [[ -z $pkg && -z $appdir ]] || return 6
  return 0
}

release() {
  python3 $CA/hold_cli.py release $HOLD $SL
  kill $SL 2>/dev/null
  log "released (sleeper $SL killed), held $(held) s"
  python3 $CA/queue_cli.py list $HOLD 2>&1 | tee -a $LOG
}

steps &
C=$!
( while (( $(held) < DEADLINE_S )); do
    [[ -f $OUT/released ]] && exit 0
    sleep 1
  done
  print -r -- "deadline $(date '+%T') after $DEADLINE_S s" > $OUT/deadline_hit
  kill -TERM $C 2>/dev/null ) &
WD=$!
trap 'log "interrupted"; kill -TERM $C 2>/dev/null' INT TERM
wait $C
RC=$?
print -r -- done > $OUT/released
kill $WD 2>/dev/null
[[ -f $OUT/deadline_hit ]] && { log "watchdog: $(cat $OUT/deadline_hit)"; RC=12; }
release
log "end rc=$RC"
exit $RC
