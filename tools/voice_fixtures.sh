#!/bin/bash
# The ten fixed voice commands the transcribe gate reads: c01..c10.wav (16 kHz mono s16) and commands.tsv
# (id<TAB>expected text). Synthesized with macOS `say` (voice Samantha, 175 words per minute) and resampled by
# ffmpeg; this is a synthetic voice, not a person. The WAVs are never committed; this file is the recipe.
# Checked 2026-10-03 (macOS say, ffmpeg 9.0.1): all ten WAVs and commands.tsv came out byte-identical to the copies the first gate used.
# Also the ten fixed replies the speak gate synthesizes (replies.tsv, id<TAB>text: sentences an assistant would say; no WAVs).
#   tools/voice_fixtures.sh [out_dir] [replies_dir]   # defaults ~/.cache/hfmodels-voice/fixtures/commands and .../replies
#   adb push <out_dir>/. /data/local/tmp/hfmodels-voice/commands/
#   adb push <replies_dir>/replies.tsv /data/local/tmp/hfmodels-voice/replies/
set -euo pipefail
OUT="${1:-$HOME/.cache/hfmodels-voice/fixtures/commands}"
REPLIES="${2:-$HOME/.cache/hfmodels-voice/fixtures/replies}"
mkdir -p "$OUT" "$REPLIES"
cat > "$REPLIES/replies.tsv" <<'TSV'
r01	Alarm set for seven thirty tomorrow morning.
r02	Your timer for ten minutes is running.
r03	It is three fifteen in the afternoon.
r04	You have two events tomorrow: a team standup at nine and the dentist at five.
r05	Done. I added the meeting to your calendar.
r06	Sorry, I could not find a calendar on this phone.
r07	The alarm is set for half past nine tonight.
r08	Timer started: forty five minutes.
r09	Good morning! Everything is running on this phone, offline.
r10	I set an alarm for eight and a timer for twenty minutes.
TSV
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
ls -l "$OUT" "$REPLIES"
