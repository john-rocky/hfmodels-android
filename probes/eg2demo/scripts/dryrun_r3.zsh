#!/bin/zsh
# Dry run of run_device_r3.zsh without a phone: a fresh dir, a fake hold file (free, so queue_cli takes it at once), two
# tiny stand-in bundles, the adb shim. The supervisor's go is a file written GO seconds after the wrapper prints A_DONE
# ("none": never, the 10 min path with a short GO_WAIT_S). DRY_*, PHASE, GO_WAIT_S and FAKE_HELD_S pass through.
#   K/scripts/dryrun_r3.zsh <out dir> [GO seconds | none]
# Prints the wrapper's log tail and its exit status; the shim's calls are in <out dir>/device/adb_calls.log and
# <out dir>/takes/adb_calls.log.
set -u
HERE=${0:A:h}
D=${1:A}
GO=${2:-3}
[[ $D == */out/dryrun_* ]] || { echo "the dry run dir must be K/out/dryrun_*: $D"; exit 1; }
rm -rf "${D:?}"
mkdir -p $D/models $D/device $D/takes
print -r -- "dry base" > $D/models/embeddinggemma-2-740m.litertlm
print -r -- "dry npu" > $D/models/embeddinggemma-2-740m_Qualcomm_SM8850.litertlm
if [[ $GO != none ]]; then
  ( for k in {1..300}; do grep -q A_DONE $D/run.log 2>/dev/null && break; sleep 1; done
    sleep $GO
    print -r -- "dry go $(date '+%T')" > $D/device/go_takes ) &
  GOW=$!
fi
ADB_SHIM=$HERE/dryrun_adb_shim.zsh OUT_DIR=$D/device TAKES_OUT_DIR=$D/takes HOLD_FILE=$D/fake_hold.json \
  MODELS_DIR=$D/models DRY_FAST=1 HOLD_NAME=eg2demo-take-r3-dry zsh $HERE/run_device_r3.zsh > $D/run.log 2>&1
RC=$?
[[ -n ${GOW:-} ]] && kill $GOW 2>/dev/null
tail -25 $D/run.log
print -r -- "dry run exit $RC ($D)"
exit $RC
