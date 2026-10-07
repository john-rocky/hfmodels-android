#!/bin/zsh
# Dry run of run_device_r4_cleanup.zsh without a phone: a fresh K/out/dryrun_r4_* dir, a fake hold file (free, so
# queue_cli takes it at once), a fake phone tree shaped like the S26 after round 3b (the two bundles, three index
# caches, album/ with K's 36 names, queries/ with K's 43 wav and queries.json, mic/ with 4 recordings, Documents/
# with 8 run records, take.sh's mp4) and the r4 adb shim. DRY_UNKNOWN=1 adds a file of no known kind; DRY_GONE,
# DRY_SLOW and DEADLINE_S pass through.
#   K/scripts/dryrun_r4.zsh <K/out/dryrun_r4_name>
# Prints the wrapper's log, the fake phone's files before and after, the hold and queue at the end, the adb calls the
# shim saw, and the wrapper's exit status.
set -u
HERE=${0:A:h}
K=${HERE:h}
D=${1:A}
[[ $D == $K/out/dryrun_r4* ]] || { print -r -- "the dry run dir must be K/out/dryrun_r4*: $D"; exit 1; }
rm -rf "${D:?}"
F=$D/phone/sdcard/Android/data/com.mlboydaisuke.eg2demo/files
mkdir -p $F/album $F/queries $F/mic $F/Documents $D/device
: > $D/phone/installed
for n in embeddinggemma-2-740m.litertlm embeddinggemma-2-740m_Qualcomm_SM8850.litertlm \
         index_embeddinggemma-2-740m.litertlm_gpu.json index_embeddinggemma-2-740m.litertlm_cpu.json \
         index_embeddinggemma-2-740m_Qualcomm_SM8850.litertlm_npu.json; do print -r -- dry > $F/$n; done
for f in $K/fixtures/album/*.jpg; do print -r -- dry > $F/album/${f:t}; done
for f in $K/fixtures/queries/*.wav $K/fixtures/queries.json; do print -r -- dry > $F/queries/${f:t}; done
for n in 1791398543548 1791398547406 1791402591612 1791402595398; do print -r -- dry > $F/mic/$n.wav; done
for n in {1..8}; do print -r -- dry > $F/Documents/eg2-demo-179140000000$n.json; done
print -r -- dry > $D/phone/sdcard/eg2_demo_take.mp4
[[ ${DRY_UNKNOWN:-0} == 1 ]] && print -r -- dry > $F/other_lane.bin
# DRY_HELD=1: another lane holds the fake hold (this wrapper's live pid), so the wait must time out (exit 3).
[[ ${DRY_HELD:-0} == 1 ]] && print -r -- "{\"pid\": $$, \"script\": \"other-lane-dry\", \"device\": \"S26\", \"started\": \"$(date '+%F %T')\"}" > $D/fake_hold.json
before=$(find $D/phone -type f | wc -l | tr -d ' ')
DRY_ROOT=$D/phone ADB_SHIM=$HERE/dryrun_adb_shim_r4.zsh OUT_DIR=$D/device HOLD_FILE=$D/fake_hold.json \
  HOLD_NAME=eg2demo-cleanup-r4-dry WAIT_S=${WAIT_S:-30} zsh $HERE/run_device_r4_cleanup.zsh > $D/run.log 2>&1
RC=$?
after=$(find $D/phone -type f | wc -l | tr -d ' ')
cat $D/run.log
print -r -- "## fake phone files: before $before, after $after ($(cd $D/phone && find . -type f | sort | tr '\n' ' '))"
print -r -- "## fake hold file at the end: $([[ -s $D/fake_hold.json ]] && cat $D/fake_hold.json || print -r -- 'empty or absent'); queue: $(cat $D/fake_hold.json.queue 2>/dev/null || print -r -- absent)"
print -r -- "## sleeper $(cat $D/device/hold_sleeper.pid 2>/dev/null): $(ps -p $(cat $D/device/hold_sleeper.pid 2>/dev/null || print -r -- 0) >/dev/null 2>&1 && print -r -- ALIVE || print -r -- gone)"
print -r -- "## adb calls: $(wc -l < $D/device/adb_calls.log | tr -d ' ') ($(cut -d' ' -f4 $D/device/adb_calls.log | sort | uniq -c | tr -s ' ' | tr '\n' ';'))"
print -r -- "dry run exit $RC ($D)"
exit $RC
