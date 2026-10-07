#!/bin/zsh
# Round 4 (lane close): delete, by name, the intermediates that ROUND1, ROUND2, ROUND3 and ROUND3b marked as
# deletable in their "残した file" sections. Clips, run JSON, ROUND files, scripts and fixture manifests are not in the
# list. Every path must exist, lie inside K and start with out/ or app/app/build.
#   K/scripts/r4_clean_k.zsh           list what would go, with sizes (nothing changes)
#   K/scripts/r4_clean_k.zsh --delete  delete; the list and the sizes before go to K/out/r4_clean_k.log
set -u
K=${0:A:h:h}
cd $K || exit 1
setopt nullglob
P=(
  # ROUND1
  out/mac/run1 app/app/build out/aar out/round1_tables.md out/ov_probe.json out/ov_headers.txt
  out/dryrun_gate out/dryrun_take_file out/dryrun_take_mic
  out/zoom_*.jpg out/fetch_album_*.log
  # ROUND1: the 640 px candidate photos, one dir per subject (candidates.json and the contact sheets stay)
  ${(f)"$(find out/album_candidates -mindepth 1 -maxdepth 1 -type d | sort)"}
  # ROUND2
  out/device/hold_sleeper.pid out/dryrun_gate_r2 out/dryrun_gate_r2_* out/dryrun_run_r2 out/dryrun_run_r2_*
  out/dryrun_take_file_r2 out/dryrun_take_mic_r2 out/dryrun_check
  # ROUND3
  out/device_r3/hold_sleeper.pid out/device_r3/go_takes out/device_r3/released
  out/dryrun_r3_go out/dryrun_r3_nogo out/dryrun_r3_missing out/dryrun_r3_watchdog out/dryrun_media_r3
  # ROUND3b
  out/device_r3b/hold_sleeper.pid out/device_r3b/go_takes out/device_r3b/released
  out/dryrun_r3b_go out/dryrun_r3b_nogo out/dryrun_r3b_regress_r3 out/dryrun_media_r3b_pos out/dryrun_media_r3b_neg
  out/dryrun_cut_r3b
)
bad=0
for p in $P; do
  if [[ $p == /* || $p == *..* || ! -e $p ]]; then print -r -- "REFUSE (absolute, .. or missing): $p"; bad=1; continue; fi
  if [[ ${p:A} != $K/* ]]; then print -r -- "REFUSE (outside K): $p"; bad=1; continue; fi
  if [[ $p != out/* && $p != app/app/build ]]; then print -r -- "REFUSE (not out/ or app/app/build): $p"; bad=1; continue; fi
done
(( bad == 0 )) || exit 2
LIST=$(for p in $P; do print -r -- "$(du -sk $p | cut -f1) kB  $p"; done)
TOTAL=$(print -r -- $LIST | awk '{s += $1} END {print s}')
if [[ ${1:-} != --delete ]]; then
  print -r -- $LIST
  print -r -- "${#P} paths, $TOTAL kB (list only; --delete removes them)"
  exit 0
fi
{
  print -r -- "## $(date '+%F %T') r4_clean_k.zsh --delete in $K"
  print -r -- $LIST
  print -r -- "## ${#P} paths, $TOTAL kB"
} > out/r4_clean_k.log
for p in $P; do rm -rf -- "${p:?}"; done
left=0
for p in $P; do [[ -e $p ]] && { print -r -- "STILL THERE: $p" | tee -a out/r4_clean_k.log; left=1; }; done
print -r -- "## $(date '+%T') removed, still there: $left" | tee -a out/r4_clean_k.log
exit $left
