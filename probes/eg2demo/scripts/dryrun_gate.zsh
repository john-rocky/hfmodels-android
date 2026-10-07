#!/bin/zsh
# Dry run of gate.sh without a phone: a fresh OUT_DIR, a live sleeper as the fake hold's owner (started now), two tiny
# stand-in bundles, the adb shim. DRY_* variables of the caller pass through to the shim (dryrun_adb_shim.zsh).
#   K/scripts/dryrun_gate.zsh <out dir> <steps...>          e.g. DRY_NPU_CRASH=1 dryrun_gate.zsh out/dry_crash gpu npu
# Prints the gate's summary lines and its exit status; the shim's calls are in <out dir>/adb_calls.log.
set -u
HERE=${0:A:h}
D=${1:A}
shift
rm -rf $D
mkdir -p $D/models
print -r -- "dry base" > $D/models/embeddinggemma-2-740m.litertlm
print -r -- "dry npu" > $D/models/embeddinggemma-2-740m_Qualcomm_SM8850.litertlm
SL=$(sleep 900 </dev/null >/dev/null 2>&1 & echo $!)
print -r -- $SL > $D/hold_sleeper.pid
printf '{"pid": %s, "script": "eg2demo-gate-dry", "device": "S26", "started": "%s"}' $SL "$(date '+%Y-%m-%d %H:%M:%S')" > $D/fake_hold.json
ADB_SHIM=$HERE/dryrun_adb_shim.zsh OUT_DIR=$D HOLD_FILE=$D/fake_hold.json MODELS_DIR=$D/models DRY_FAST=1 KEEP_FILES=${KEEP_FILES:-1} \
  zsh $HERE/gate.sh "$@"
RC=$?
kill $SL 2>/dev/null
print -r -- "dry run exit $RC ($D)"
exit $RC
