#!/bin/zsh
# One take of the EmbeddingGemma 2 740M demo on the Galaxy S26 (shape of litertlm-convert/kev_work/demo/take.sh).
# Written in round 1, changed in round 3: no SLEEP / WAKEUP / POWER key (a shared phone; the app lights the screen
# itself with setTurnScreenOn and keeps it on), the album indexed again at each launch (--ez reindex true: the footer's
# "indexed in" is this launch's time), one or more played clips per take, the clock lines make_media.sh needs.
# Order:
#   1. the hold is ours, no litert process, airplane mode on (NO_AIRPLANE=1 skips that check); state before;
#   2. cool with the app stopped: thermal status 0, every CPU policy uncapped, the GPU uncapped (kgsl max_clock_mhz ==
#      1300 and thermal_pwrlevel == 0), up to COOL_LIMIT_S; the screen is left as it is (it times out by itself);
#      state at launch (its kgsl temp = the base of step 4);
#   3. launch (LAUNCH_EXTRAS, default `--es backend gpu --es bundle embeddinggemma-2-740m.litertlm`, plus
#      `--ez reindex true`): wait for READY (after ENGINE_READY, INDEX_DONE and WARMUP) or ERROR, then LAYOUT;
#   4. recovery with the app in front: the step-2 condition plus the kgsl temp <= the launch reading + 5,000 m°C, up to
#      5 min;
#   5. `mWakefulness=Awake` read from dumpsys power (polled up to 15 s, never a key: screenrecord refuses a display that
#      is off), the device clock read just before and just after screenrecord starts (REC_START lines, epoch ms) and in
#      the device shell right before it execs (REC_SHELL_EPOCH), screenrecord;
#   6. MODE=file (default): 2 s into the recording, `--ez autoplay true --es query <wav[,wav...]> --ei delay_ms DELAY_MS
#      --ei gap_ms GAP_MS` to the running activity (each clip played through the speaker with the pill "● audio file",
#      PLAY_START logged in that UI pass, then embedded); MODE=mic: no intent, the user holds the button and speaks;
#   7. 3 s after the last RESULT line (one per clip; MODE=mic: one), screenrecord stops; the raw video, the run JSON,
#      the mic wav (MODE=mic), the app's Eg2Demo lines and its whole logcat (by pid, from the launch's device time;
#      never `logcat -c`) land in OUT.
#   usage: K/scripts/take.sh <tag> [query wav(s), comma separated, default q01_a.wav]
#     env: MODE=file|mic, LAUNCH_EXTRAS, OUT_DIR, DELAY_MS (500), GAP_MS (2500), COOL_LIMIT_S (600), HOLD_PID_FILE
# Exit status 1 when the app logged ERROR, a RESULT is missing, or a step's limit ran out (the files are still collected).
# Dry run without a phone: ADB_SHIM=K/scripts/dryrun_adb_shim.zsh OUT_DIR=<dir> HOLD_FILE=<fake hold> take.sh …
# Every adb call goes through dev() (grep the file for a bare adb before a run: memory adb-run-script-traps).
set -u
HERE=${0:A:h}
K=${HERE:h}
S=RFGL80R6A6H
P=com.mlboydaisuke.eg2demo
ACT=.MainActivity
TAG=Eg2Demo
FILES=/sdcard/Android/data/$P/files
GPU_MAX_MHZ=1300
GPU_TEMP_MARGIN=5000
COOL_LIMIT_S=${COOL_LIMIT_S:-600}
READY_LIMIT_S=${READY_LIMIT_S:-180}
RECOVER_LIMIT_S=${RECOVER_LIMIT_S:-300}
RESULT_LIMIT_S=${RESULT_LIMIT_S:-60}
MIC_LIMIT_S=${MIC_LIMIT_S:-180}
DELAY_MS=${DELAY_MS:-500}
GAP_MS=${GAP_MS:-2500}
TAKE=$1
QUERY=${2:-q01_a.wav}
QL=("${(@s:,:)QUERY}")   # an array: ${#${(s:,:)x}} of one name is the name's length (9 for q01_a.wav)
NQ=${#QL}
TAKE_MODE=${MODE:-file}
[[ $TAKE_MODE == file || $TAKE_MODE == mic ]] || { echo "MODE must be file or mic, got $TAKE_MODE"; exit 1; }
[[ $TAKE_MODE == mic ]] && NQ=1
LAUNCH_EXTRAS=${LAUNCH_EXTRAS:---es backend gpu --es bundle embeddinggemma-2-740m.litertlm}
OUT=${OUT_DIR:-$K/out/takes}
LOG=$OUT/take_$TAKE.log
REMOTE=/sdcard/eg2_demo_take.mp4
HOLD=${HOLD_FILE:-$HOME/code/litertlm-convert/community_accel_work/s2_npu_sweep/.device_hold}
[[ -n ${ADB_SHIM:-} ]] && source $ADB_SHIM
mkdir -p $OUT
[[ ! -e $LOG || ${OVERWRITE:-0} == 1 ]] || { echo "refusing to overwrite $LOG: use a new tag"; exit 1; }

dev() { adb -s $S "$@"; }

mine=$(cat ${HOLD_PID_FILE:-$K/out/device/hold_sleeper.pid} 2>/dev/null)
holder=$(python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print(d.get('pid'))" $HOLD 2>/dev/null)
[[ -n $mine && $holder == $mine ]] || { echo "hold is not ours (holder=$holder mine=$mine)"; exit 1; }
kill -0 $mine 2>/dev/null || { echo "hold sleeper $mine is not alive"; exit 1; }

state() {
  dev shell 'echo uptime $(cut -d" " -f1 /proc/uptime);
    dumpsys thermalservice | grep -E "Thermal Status|mName=SKIN" | head -2;
    for p in /sys/devices/system/cpu/cpufreq/policy*; do echo $p $(cat $p/scaling_max_freq) $(cat $p/cpuinfo_max_freq); done;
    g=/sys/class/kgsl/kgsl-3d0;
    echo kgsl: max_clock_mhz=$(cat $g/max_clock_mhz 2>/dev/null || echo missing) thermal_pwrlevel=$(cat $g/thermal_pwrlevel 2>/dev/null || echo missing) temp=$(cat $g/temp 2>/dev/null || echo missing);
    echo mem: $(grep MemAvailable /proc/meminfo);
    echo litert_procs: $(ps -A | grep -i litert | wc -l);
    echo power: $(dumpsys power | grep -m1 mWakefulness=);
    echo airplane_mode_on: $(settings get global airplane_mode_on)'
}
ready() {  # empty = thermal status 0, every CPU policy at its maximum, the GPU at its full clock range
  dev shell "for p in /sys/devices/system/cpu/cpufreq/policy*; do [ \$(cat \$p/scaling_max_freq) = \$(cat \$p/cpuinfo_max_freq) ] || echo capped \$p \$(cat \$p/scaling_max_freq); done;
    dumpsys thermalservice | grep -m1 'Thermal Status' | grep -v 'Status: 0';
    g=/sys/class/kgsl/kgsl-3d0;
    if [ -r \$g/max_clock_mhz ]; then [ \$(cat \$g/max_clock_mhz) = $GPU_MAX_MHZ ] || echo gpu_capped max_clock_mhz=\$(cat \$g/max_clock_mhz); fi;
    if [ -r \$g/thermal_pwrlevel ]; then [ \$(cat \$g/thermal_pwrlevel) = 0 ] || echo gpu_capped thermal_pwrlevel=\$(cat \$g/thermal_pwrlevel); fi"
}
kgsl_temp() { dev shell 'cat /sys/class/kgsl/kgsl-3d0/temp 2>/dev/null' | tr -dc 0-9; }
epoch_ms() {  # the phone's wall clock in ms (the app's PLAY_START epoch_ms is System.currentTimeMillis())
  local t
  t=$(dev shell 'date +%s%3N' | tr -d '\r')
  [[ $t == <-> && ${#t} -ge 13 ]] && { print -r -- $t; return; }
  t=$(dev shell 'date +%s' | tr -d '\r')
  print -r -- $(( t * 1000 ))
}
wakefulness() { dev shell "dumpsys power | grep -m1 mWakefulness=" | tr -d '\r '; }
demo_lines() { dev logcat -d -T "$SINCE" --pid=$APID -s ${TAG}:V; }

{
  echo "# $(date '+%F %T') take $TAKE mode=$TAKE_MODE query=$QUERY ($NQ) launch extras: $LAUNCH_EXTRAS --ez reindex true; delay_ms $DELAY_MS gap_ms $GAP_MS; hold pid $mine"
  echo "## state before"; state
} > $LOG 2>&1
grep -q "litert_procs: 0" $LOG || { echo "a litert process is running"; exit 1; }
if [[ ${NO_AIRPLANE:-0} != 1 ]] && ! grep -q "airplane_mode_on: 1" $LOG; then echo "airplane mode is off"; exit 1; fi

# 2. Cool with the app stopped (the screen is not touched: no SLEEP / POWER key on a shared phone).
dev shell am force-stop $P
W0=$(date +%s)
while true; do
  R=$(ready)
  [[ -z $R ]] && break
  echo "## $(date '+%T') not ready: $(print -r -- $R | tr '\n' ';')" >> $LOG
  (( $(date +%s) - W0 >= COOL_LIMIT_S )) && { echo "phone not ready after $COOL_LIMIT_S s: $R"; exit 1; }
  sleep 10
done
echo "## $(date '+%T') cool after $(( $(date +%s) - W0 )) s" >> $LOG
{ echo "## state at launch"; state; } >> $LOG 2>&1
BASE_TEMP=$(kgsl_temp)
echo "## launch kgsl temp base ${BASE_TEMP:-missing}" >> $LOG

# 3. Launch (the app turns the screen on) and wait for READY, then LAYOUT.
dev shell rm -f $REMOTE
SINCE=$(dev shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')
echo "## $(date '+%T') launch, device time $SINCE" >> $LOG
dev shell am start -n $P/$ACT ${=LAUNCH_EXTRAS} --ez reindex true >> $LOG 2>&1
sleep 1
APID=$(dev shell pidof $P | tr -d '\r')
[[ -n $APID ]] || { echo "the app is not running after the launch"; exit 1; }
RD=""
T_L=$(date +%s)
while (( $(date +%s) - T_L < READY_LIMIT_S )); do
  RD=$(demo_lines | grep -E " READY |ERROR " | head -1)
  [[ -n $RD ]] && break
  sleep 1
done
[[ $RD == *" READY "* ]] || { demo_lines | sed 's/^/## /' >> $LOG; echo "the app did not reach READY within $READY_LIMIT_S s: $RD"; exit 1; }
for k in {1..10}; do demo_lines | grep -q "LAYOUT " && break; sleep 1; done
demo_lines | grep -E "RUN_JSON|BUNDLE|ENGINE_READY|INDEX_DONE|WARMUP|READY |LAYOUT" | sed 's/^/## startup /' >> $LOG

# 4. Recovery with the app in front. The index heats the GPU; the query itself runs on the audio encoder's backend (the
#    CPU), so a GPU that is only warm or capped (kgsl) is waited for GPU_GRACE_S at most, a CPU cap or a thermal status
#    for RECOVER_LIMIT_S.
GPU_GRACE_S=${GPU_GRACE_S:-60}
W1=$(date +%s)
while true; do
  R=$(ready)
  T=$(kgsl_temp)
  [[ -n $BASE_TEMP && -n $T ]] && (( T > BASE_TEMP + GPU_TEMP_MARGIN )) && R="$R gpu_warm temp=$T base=$BASE_TEMP"
  [[ -z ${R// /} ]] && break
  echo "## $(date '+%T') recovering: $(print -r -- $R | tr '\n' ';')" >> $LOG
  if ! print -r -- "$R" | /usr/bin/grep -q -E 'capped /sys|Thermal Status' && (( $(date +%s) - W1 >= GPU_GRACE_S )); then
    echo "## $(date '+%T') only the GPU is still warm or capped after $GPU_GRACE_S s; the query runs on the CPU: going on" >> $LOG
    break
  fi
  (( $(date +%s) - W1 >= RECOVER_LIMIT_S )) && { echo "no recovery within $RECOVER_LIMIT_S s: $R"; exit 1; }
  sleep 2
done
{ echo "## state at recovery"; state; echo "## cgroup $(dev shell cat /proc/$APID/cgroup 2>/dev/null | grep cpuset)"; } >> $LOG 2>&1

# 5. The display is awake (the app's setTurnScreenOn; polled, never a key), then the recording.
WAKE=""
for k in {1..15}; do
  WAKE=$(wakefulness)
  [[ $WAKE == *Awake* ]] && break
  sleep 1
done
echo "## $(date '+%T') $WAKE" >> $LOG
[[ $WAKE == *Awake* ]] || { echo "the display is not awake: $WAKE"; exit 1; }
N0=$(demo_lines | grep -c "RESULT kind=audio")
E0=$(demo_lines | grep -c "ERROR ")
echo "## REC_START before_epoch_ms=$(epoch_ms)" >> $LOG
dev shell "echo REC_SHELL_EPOCH \$(date +%s%3N); exec screenrecord --bit-rate 8000000 --time-limit 120 $REMOTE" > $OUT/take_${TAKE}_screenrecord.out 2>&1 &
REC=$!
sleep 2
RPID=$(dev shell pidof screenrecord | tr -d '\r')
echo "## REC_START after_epoch_ms=$(epoch_ms) remote_pid=${RPID:-none}" >> $LOG
[[ -n $RPID ]] || { echo "screenrecord did not start"; exit 1; }

# 6. The query: played files (MODE=file) or the user's voice (MODE=mic).
if [[ $TAKE_MODE == file ]]; then
  echo "## $(date '+%T') autoplay intent query=$QUERY" >> $LOG
  dev shell am start -n $P/$ACT --ez autoplay true --es query $QUERY --ei delay_ms $DELAY_MS --ei gap_ms $GAP_MS >> $LOG 2>&1
  LIMIT=$(( RESULT_LIMIT_S * NQ ))
else
  echo "## $(date '+%T') waiting for the user's spoken query (MODE=mic, up to $MIC_LIMIT_S s)" >> $LOG
  LIMIT=$MIC_LIMIT_S
fi
NR=0 ERR=""
T_Q=$(date +%s)
while (( $(date +%s) - T_Q < LIMIT )); do
  DL=$(demo_lines)
  NR=$(( $(print -r -- $DL | grep -c "RESULT kind=audio") - N0 ))
  ERR=$(print -r -- $DL | grep -E "ERROR " | tail -n +$(( E0 + 1 )) | tail -1)
  (( NR >= NQ )) && break
  [[ -n $ERR ]] && break
  sleep 1
done
sleep 3
dev shell kill -2 $RPID
wait $REC
echo "## $(date '+%T') screenrecord stopped ($NR of $NQ RESULT lines${ERR:+; $ERR})" >> $LOG

# 7. Collect.
demo_lines > $OUT/take_${TAKE}_demo.log
grep -E "QUERY_START|PLAY_START|PLAY_HEAD|PILL|MIC_RECORDED|MIC_REJECTED|RESULT|DONE|ERROR" $OUT/take_${TAKE}_demo.log | sed 's/^/## /' >> $LOG
grep -h REC_SHELL_EPOCH $OUT/take_${TAKE}_screenrecord.out | tr -d '\r' | sed 's/^/## /' >> $LOG
dev logcat -d -T "$SINCE" --pid=$APID > $OUT/take_${TAKE}_app_logcat.log
EPOCH=$(date +%s)
MP4=$OUT/eg2_demo_s26_${TAKE}_$EPOCH.mp4
dev pull $REMOTE $MP4 >> $LOG 2>&1
dev shell rm -f $REMOTE
JR=$(grep -m1 "RUN_JSON path=" $OUT/take_${TAKE}_demo.log | sed -nE 's/.*RUN_JSON path=([^ ]+).*/\1/p' | tr -d '\r')
[[ -n $JR ]] && dev pull $JR $OUT/take_${TAKE}_${JR:t} >> $LOG 2>&1
MW=$(grep -m1 "MIC_RECORDED" $OUT/take_${TAKE}_demo.log | sed -nE 's/.* wav=([^ ]+).*/\1/p' | tr -d '\r')
[[ -n $MW ]] && dev pull $MW $OUT/take_${TAKE}_${MW:t} >> $LOG 2>&1
{
  echo "## video $MP4"
  ffprobe -v error -select_streams v:0 -show_entries stream=width,height,avg_frame_rate,nb_frames:format=duration,size \
    -of default=noprint_wrappers=1 $MP4 2>&1
  echo "## run json ${JR:+$OUT/take_${TAKE}_${JR:t}}"
  echo "## mic wav ${MW:+$OUT/take_${TAKE}_${MW:t}}"
  echo "## cgroup $(dev shell cat /proc/$APID/cgroup 2>/dev/null | grep cpuset)"
  echo "## backend lines"; grep -i -E "Replacing|delegate|accelerat|opencl|npu|qnn" $OUT/take_${TAKE}_app_logcat.log | head -12
  echo "## state after"; state
} >> $LOG 2>&1
dev shell am force-stop $P
tail -20 $LOG
(( NR >= NQ )) && [[ -z $ERR ]]
