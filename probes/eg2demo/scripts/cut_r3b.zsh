#!/bin/zsh
# Round 3b's cut on the Mac after the hold went back (round 3's steps in one script), per take (default t1 t2) in TOUT
# (K/out/takes_r3b): make_media.sh with END_AFTER_RESULT=3.0 (the master *_trim.mp4 and the X clip *_x1080x1920.mp4),
# frames.sh on the X clip (frames_<tag>/), take_lines.py (lines (a)-(g): <tag>_lines.md / .json), check_run.py against
# the Mac's scores (<tag>_check_run.md), numbers.py (<tag>_numbers.md). The query wavs come from the take log's first
# line, the run JSON and the demo log from take.sh's pulls.
#   K/scripts/cut_r3b.zsh [tags...]     env: TOUT
set -u
K=${0:A:h:h}
PY=$K/venv/bin/python
TOUT=${TOUT:-$K/out/takes_r3b}
RC=0
for tag in ${@:-t1 t2}; do
  raws=($TOUT/eg2_demo_s26_${tag}_<->.mp4(N))
  rjs=($TOUT/take_${tag}_eg2-demo-*.json(N))
  (( ${#raws} == 1 && ${#rjs} == 1 )) || { echo "$tag: want one raw video and one run JSON in $TOUT (${#raws}, ${#rjs})"; RC=1; continue; }
  RAW=$raws[1] RJ=$rjs[1]
  Q=$(sed -nE '1s/.* query=([^ ]+) .*/\1/p' $TOUT/take_$tag.log)
  [[ -n $Q ]] || { echo "$tag: no query= in $TOUT/take_$tag.log"; RC=1; continue; }
  END_AFTER_RESULT=3.0 zsh $K/scripts/make_media.sh $RAW $TOUT/take_$tag.log $RJ $TOUT/take_${tag}_demo.log $Q \
    > $TOUT/make_media_$tag.log 2>&1
  r=$?
  echo "== $tag ($Q): make_media rc=$r"
  tail -4 $TOUT/make_media_$tag.log
  (( r == 0 )) || { RC=1; continue; }
  FRAMES_DIR=$TOUT/frames_$tag zsh $K/scripts/frames.sh ${RAW:r}_x1080x1920.mp4 $tag > /dev/null || RC=1
  $PY -I $K/scripts/take_lines.py --raw $RAW --run $RJ --wavs $Q --json $TOUT/${tag}_lines.json > $TOUT/${tag}_lines.md 2>&1 || RC=1
  grep -E "^\| \(|^all lines" $TOUT/${tag}_lines.md
  $PY -I $K/scripts/check_run.py $RJ --logcat $TOUT/take_${tag}_app_logcat.log > $TOUT/${tag}_check_run.md 2>&1 || RC=1
  $PY -I $K/scripts/numbers.py $RJ --clip ${RAW:r}_x1080x1920.mp4 > $TOUT/${tag}_numbers.md 2>&1 || RC=1
  echo "== $tag: frames_$tag/, ${tag}_lines.md, ${tag}_check_run.md, ${tag}_numbers.md"
done
exit $RC
