#!/bin/zsh
# Post files from one raw take (take.sh output), the picture re-encoded from the raw file in one pass (shape of
# litertlm-convert/kev_work/demo/make_media.sh for the cut and of qwen3_asr17_work/demo/make_media.sh for the sound;
# round 3: the clips placed from the app's clock, several clips per take, crop and caption positions from the app's
# LAYOUT, the sentences from K/fixtures/queries.json):
#   make_media.sh <raw.mp4> <take.log> <run.json> <take_<tag>_demo.log> <wav[,wav...]>
#   make_media.sh <raw.mp4> - - - -        with NO_AUDIO=1 (the smoke test and silent takes: no sound, no caption)
#     env: Y0 (crop y0; default the run JSON's layout.frame_px.top, the status bar's height), CUT_T (default 0: a round
#          3 take starts recording on the READY screen, so frame 0 is the app; SETTLE=1 uses settle.py instead),
#          END_AFTER_RESULT (seconds: end that long after the last clip's result appeared instead of 3.0 s after the
#          raw's last change; round 3 uses 3.0, since the button's alpha change 2.5 s after the result is the last change)
#   <stem>_trim.mp4         the master: the take from the cut on, 1080x2340, 30 fps, the last frame held to 3.0 s after it
#                           appeared, the played clips as the sound track (AAC 48 kHz), no caption
#   <stem>_x1080x1920.mp4   the master cut to 1080x1920 (crop=1080:1920:0:<y0>), the same sound, and for each clip one
#                           caption `You say: "<sentence>"` (captions.py) on screen while that clip plays, just above the
#                           app's level bar (layout.level_px)
#   <stem>_audio.json       where each clip was placed and why, line (e) per clip (place_audio.py); <stem>_pill.log the
#                           pill colour of every raw frame
# End: screenrecord writes a frame only when the screen changes, so the last frame is held to 3.0 s after it appeared
# (tpad, then fps=30:round=up, then trim: memory screenrecord-cut-drops-last-frame). Nothing on screen is edited.
# Sound: screenrecord records none. Each clip is the file the phone played (K/fixtures/queries/<name>), resampled to
# 48 kHz, delayed to its placement in the trimmed timeline (adelay, whole samples), mixed unscaled with the others (they
# never overlap), copied to both channels, padded with silence to the picture's exact length and muxed with it (picture
# stream copied). Encoding: libx264 -preset slow -crf 18 -profile:v high -pix_fmt yuv420p -movflags +faststart.
set -eu
HERE=${0:A:h}
K=${HERE:h}
PY=${PYTHON:-$K/venv/bin/python}
RAW=$1
TAKELOG=${2:-}
RUNJSON=${3:-}
DEMOLOG=${4:-}
WAVS=${5:-}
STEM=${RAW:r}
HOLD=3.0
read W H <<< $(ffprobe -v error -select_streams v:0 -show_entries stream=width,height -of csv=p=0 $RAW | tr ',' ' ')
[[ $W == 1080 && $H == 2340 ]] || { echo "$RAW is ${W}x${H}, expected the S26 screen 1080x2340"; exit 1; }
if [[ -z ${Y0:-} ]]; then
  [[ -f $RUNJSON ]] || { echo "no run JSON for the crop's y0: give Y0"; exit 1; }
  Y0=$($PY -I -c "
import json, sys
l = json.load(open(sys.argv[1])).get('layout') or {}
y0 = l['frame_px']['top']
t, f = l['title_top_px'], l['footer_bottom_px']
if not (y0 <= t and f <= y0 + 1920):
    sys.exit(f'title {t} .. footer {f} is not inside the crop {y0} .. {y0 + 1920}')
print(y0)" $RUNJSON)
fi
(( Y0 >= 0 && Y0 <= 420 )) || { echo "y0 must be 0..420 (2340 - 1920), got $Y0"; exit 1; }
# ffmpeg's crop rounds y down to the 4:2:0 chroma grid (an even row): use that row everywhere (round 3: 111 -> 110).
Y0=$(( Y0 - Y0 % 2 ))

if [[ ${SETTLE:-0} == 1 ]]; then
  $PY -I $HERE/settle.py $RAW --min-t 0 --log ${STEM}_settle.log > ${STEM}_settle.json
  T0=$($PY -I -c "import json,sys; print(json.load(open(sys.argv[1]))['cut']['t'])" ${STEM}_settle.json)
else
  T0=${CUT_T:-0}
  echo "{\"video\": \"$RAW\", \"cut\": {\"t\": $T0, \"reason\": \"CUT_T (default 0: recorded from the READY screen, frame 0 is the app)\"}}" > ${STEM}_settle.json
fi
LAST=$(ffprobe -v error -show_entries frame=pts_time -select_streams v:0 -of csv=p=0 $RAW | /usr/bin/grep -v '^$' | tail -1 | cut -d, -f1)
RAW_DUR=$(ffprobe -v error -show_entries format=duration -of csv=p=0 $RAW)
read START KEEP PAD <<< $($PY -I -c "
import math
t0, last, dur, hold = $T0, $LAST, $RAW_DUR, $HOLD
idx = math.ceil((last - t0) * 30 - 1e-6)
print(f'{max(t0 - 0.001, 0):.6f} {max(dur - t0, (idx + round(hold * 30) + 0.25) / 30):.6f} {hold + 1:.1f}')")
echo "raw $RAW: duration $RAW_DUR s, cut at $T0 s, last change $LAST s -> keep $KEEP s from $START s, crop y0 $Y0"
ENC=(-c:v libx264 -preset slow -crf 18 -profile:v high -pix_fmt yuv420p -movflags +faststart)
CUT="trim=start=$START,setpts=PTS-STARTPTS,tpad=stop_mode=clone:stop_duration=$PAD,fps=30:round=up,trim=duration=$KEEP"
X_FIT="crop=1080:1920:0:$Y0"

if [[ ${NO_AUDIO:-0} == 1 ]]; then
  ffmpeg -v error -y -i $RAW -vf "$CUT" -an $ENC ${STEM}_trim.mp4
  ffmpeg -v error -y -i $RAW -vf "$CUT,$X_FIT" -an $ENC ${STEM}_x1080x1920.mp4
else
  [[ -f $TAKELOG && -f $RUNJSON && -f $DEMOLOG && -n $WAVS ]] || { echo "need take.log, run.json, the demo log and the wav names (or NO_AUDIO=1)"; exit 1; }
  $PY -I $HERE/place_audio.py --raw $RAW --demo $DEMOLOG --run $RUNJSON --takelog $TAKELOG --cut $T0 --wavs $WAVS \
    --log ${STEM}_pill.log > ${STEM}_audio.json
  if [[ -n ${END_AFTER_RESULT:-} ]]; then
    # End END_AFTER_RESULT s after the last clip's result appeared (its first green-pill frame), not 3 s after the last
    # change: the app's batch ends gap_ms after that result and the button's alpha change is then the last change.
    read KEEP LASTDONE <<< $($PY -I -c "
import json, math, sys
a = json.load(open(sys.argv[1]))
done = [c['t'] for c in a['video_changes'] if c['state'] == 'done']
if not done:
    sys.exit('no green DONE frame in the take')
t0, e = $T0, $END_AFTER_RESULT
idx = math.ceil((done[-1] - t0) * 30 - 1e-6)
print(f'{(idx + round(e * 30) + 0.25) / 30:.6f} {done[-1]:.6f}')" ${STEM}_audio.json)
    CUT="trim=start=$START,setpts=PTS-STARTPTS,tpad=stop_mode=clone:stop_duration=$PAD,fps=30:round=up,trim=duration=$KEEP"
    echo "end: $END_AFTER_RESULT s after the last result's DONE frame (raw $LASTDONE s) -> keep $KEEP s"
  fi
  # One line per clip: wav path, delay in 48 kHz samples, start and end in the trimmed timeline, sentence.
  PLAN=("${(@f)$($PY -I -c "
import json, sys
a = json.load(open(sys.argv[1]))
for c in a['clips']:
    d = c['placed_trim_t']
    print(f\"{c['wav']}\t{round(d * 48000)}\t{d:.3f}\t{d + c['wav_seconds']:.3f}\t{c['sentence']}\")" ${STEM}_audio.json)}")
  (( ${#PLAN} >= 1 )) || { echo "no clip placed"; exit 1; }
  read LEVEL_TOP <<< $($PY -I -c "import json,sys; print(json.load(open(sys.argv[1]))['layout']['level_px']['top'])" $RUNJSON)
  INPUTS=(-i $RAW)
  VF="[0:v]${CUT},${X_FIT}[v0]"
  AF=""
  AMIX=""
  k=0
  for line in $PLAN; do
    k=$(( k + 1 ))
    parts=("${(@ps:\t:)line}")
    wav=${parts[1]} delay=${parts[2]} a0=${parts[3]} a1=${parts[4]} sentence=${parts[5]}
    [[ -n $sentence && $sentence != None ]] || { echo "no sentence for $wav in queries.json"; exit 1; }
    $PY -I $HERE/captions.py "$sentence" ${STEM}_caption$k.png
    CH=$($PY -I -c "from PIL import Image; import sys; print(Image.open(sys.argv[1]).size[1])" ${STEM}_caption$k.png)
    CY=$(( LEVEL_TOP - Y0 - CH - 24 ))
    INPUTS+=(-i ${STEM}_caption$k.png)
    VF="${VF};[v$(( k - 1 ))][${k}:v]overlay=x=0:y=${CY}:enable='between(t,$a0,$a1)'[v$k]"
    echo "clip $k: $wav at $a0 .. $a1 s of the trimmed video, caption y $CY in the crop: $sentence"
  done
  NV=$k
  ffmpeg -v error -y -i $RAW -vf "$CUT" -an $ENC ${STEM}_trim.video.mp4
  ffmpeg -v error -y $INPUTS -filter_complex "$VF" -map "[v$NV]" -an $ENC ${STEM}_x1080x1920.video.mp4
  for V in trim x1080x1920; do
    VDUR=$(ffprobe -v error -select_streams v:0 -show_entries stream=duration -of csv=p=0 ${STEM}_${V}.video.mp4)
    AIN=(-i ${STEM}_${V}.video.mp4)
    AF=""
    LABELS=""
    k=0
    for line in $PLAN; do
      k=$(( k + 1 ))
      parts=("${(@ps:\t:)line}")
      AIN+=(-i ${parts[1]})
      AF="${AF}[${k}:a]aresample=48000,adelay=delays=${parts[2]}S:all=1[a$k];"
      LABELS="${LABELS}[a$k]"
    done
    if (( k == 1 )); then
      AF="${AF}[a1]pan=stereo|c0=c0|c1=c0,apad=whole_dur=${VDUR},atrim=end=${VDUR}[a]"
    else
      AF="${AF}${LABELS}amix=inputs=$k:normalize=0:duration=longest,pan=stereo|c0=c0|c1=c0,apad=whole_dur=${VDUR},atrim=end=${VDUR}[a]"
    fi
    ffmpeg -v error -y $AIN -filter_complex "$AF" -map 0:v -map "[a]" -c:v copy -c:a aac -b:a 192k -ar 48000 \
      -movflags +faststart ${STEM}_${V}.mp4
    rm ${STEM}_${V}.video.mp4
  done
  $PY -I -c "
import json, sys
a = json.load(open(sys.argv[1]))
print('placement:', a['pairing'], '| offset', a['offset_s'], '| fit on', a['fit_on'])
for c in a['clips']:
    print(f\"  {c['id']}: placed {c['placed_trim_t']:.3f} s ({c['used']}), red frame {c['red_frame_t']}, line (e) {c['line_e_ms']} ms -> {c['line_e_pass']}\")" ${STEM}_audio.json
fi
for f in ${STEM}_trim.mp4 ${STEM}_x1080x1920.mp4; do
  echo "$f $(stat -f %z $f) B $(ffprobe -v error -show_entries stream=codec_type,codec_name,width,height,r_frame_rate,nb_frames:format=duration \
    -of csv=p=0 $f | tr '\n' ' ') sha256 $(shasum -a 256 $f | cut -d' ' -f1)"
done
