#!/bin/zsh
# Frames to look at from one video (a raw take or a make_media.sh output): the opening, middle and closing frames as PNG
# plus a contact sheet of one frame per second, into K/out/frames_<tag>/ (or FRAMES_DIR). Copied from
# litertlm-convert/kev_work/demo/frames.sh; only the output path changed.
#   frames.sh <video.mp4> <tag> [columns 6] [thumb width 270]
# Frames are picked by index from the decoded stream (n = 0, n = N/2, n = N-1), never with -sseof: screenrecord's raw
# file is variable frame rate and -sseof returns the frame before the last change (memory
# keyguard-background-cpuset-demo-trap). The contact sheet takes the frame shown at each whole second (fps=1) and tiles
# them COLS wide, row by row: thumbnail k (from 0, left to right, top to bottom) is second k (this ffmpeg has no
# drawtext, so the times are in frames.txt, not on the picture).
set -eu
HERE=${0:A:h}
V=$1
TAG=$2
COLS=${3:-6}
TW=${4:-270}
DIR=${FRAMES_DIR:-$HERE/../out/frames_$TAG}
mkdir -p $DIR
N=$(ffprobe -v error -select_streams v:0 -count_frames -show_entries stream=nb_read_frames -of csv=p=0 $V)
DUR=$(ffprobe -v error -show_entries format=duration -of csv=p=0 $V)
MID=$(( N / 2 ))
LASTI=$(( N - 1 ))
for pair in "start 0" "mid $MID" "end $LASTI"; do
  name=${pair% *}
  idx=${pair#* }
  ffmpeg -v error -y -i $V -vf "select=eq(n\,$idx)" -fps_mode passthrough -frames:v 1 $DIR/${name}.png
done
SECS=$(python3 -c "import math; print(max(1, math.ceil($DUR)))")
ROWS=$(( (SECS + COLS - 1) / COLS ))
# round=up: fps keeps the last input frame of each output slot, so with round=down thumbnail k was the frame just before
# second k + 1 (round 2: the 1 s thumbnail showed a card done at 1.97 s); with round=up it is the frame on screen at k.
ffmpeg -v error -y -i $V -vf "fps=1:round=up,scale=$TW:-2,tile=${COLS}x${ROWS}:padding=4:color=0x202020" \
  -frames:v 1 $DIR/contact_1s.png
{
  echo "# $V: $N frames, $DUR s; start = frame 0, mid = frame $MID, end = frame $LASTI; contact sheet ${COLS}x${ROWS}, 1 frame/s: thumbnail k = second k, row by row"
  for f in $DIR/start.png $DIR/mid.png $DIR/end.png $DIR/contact_1s.png; do
    echo "$f $(ffprobe -v error -show_entries stream=width,height -of csv=p=0 $f) sha256 $(shasum -a 256 $f | cut -d' ' -f1)"
  done
} | tee $DIR/frames.txt
