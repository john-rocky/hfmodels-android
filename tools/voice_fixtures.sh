#!/bin/bash
# The ten fixed voice commands the transcribe gate reads: c01..c10.wav (16 kHz mono s16) and commands.tsv
# (id<TAB>expected text). Synthesized with macOS `say` (voice Samantha, 175 words per minute) and resampled by
# ffmpeg; this is a synthetic voice, not a person. The WAVs are never committed; this file is the recipe.
# Checked 2026-10-03 (macOS say, ffmpeg 9.0.1): all ten WAVs and commands.tsv came out byte-identical to the copies the first gate used.
#   tools/voice_fixtures.sh [out_dir]      # default ~/.cache/hfmodels-voice/fixtures/commands
#   adb push <out_dir>/. /data/local/tmp/hfmodels-voice/commands/
set -euo pipefail
OUT="${1:-$HOME/.cache/hfmodels-voice/fixtures/commands}"
mkdir -p "$OUT"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
cat > "$OUT/commands.tsv" <<'TSV'
c01	Set an alarm for seven thirty tomorrow morning.
c02	Wake me up at six fifteen.
c03	Start a timer for ten minutes.
c04	Set a timer for forty five minutes.
c05	What time is it right now?
c06	What is on my calendar tomorrow?
c07	Add a meeting with the dentist at five p.m. tomorrow.
c08	Put a team standup on my calendar at nine tomorrow morning.
c09	Set an alarm for eight and a timer for twenty minutes.
c10	Please set an alarm for half past nine tonight.
TSV
while IFS=$'\t' read -r id text; do
  say -v Samantha -r 175 -o "$TMP/$id.aiff" "$text"
  ffmpeg -y -loglevel error -i "$TMP/$id.aiff" -ar 16000 -ac 1 -c:a pcm_s16le "$OUT/$id.wav"
done < "$OUT/commands.tsv"
ls -l "$OUT"
