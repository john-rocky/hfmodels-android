#!/usr/bin/env bash
# Makes the device check's fixtures (TranslateDeviceCheck, `-e fixtures <dir>`) for both pairs on a Mac: every WAV
# 16 kHz mono 16-bit PCM, beside the two tsv files of its pair.
#   <out>/es: sentences.tsv, pause_resume.tsv, wav/e01..e05 (es-en rows, "Flo (Spanish (Spain))"), wav/n01..n05 (en-es
#             rows, "Eddy (English (US))"), silence_5s, noise_5s, long_35s, fr_input ("Flo (French (France))"),
#             pause_resume ("Mónica")
#   <out>/fr: sentences.tsv, pause_resume.tsv, wav/f01..f05 (fr-en rows, "Amélie"), wav/m01..m05 (en-fr rows,
#             "Samantha"), silence_5s, noise_5s, long_35s, es_input ("Mónica"), pause_resume ("Amélie")
# A sentence: say -> AIFF at the voice's own rate -> ffmpeg 16 kHz mono s16, then 0.3 s of silence before it and 1.2 s
# after. silence_5s: zeros. noise_5s: Gaussian, RMS 0.003, seed 20261019. long_35s: the pair's five sentences into English,
# in order and again, 0.3 s apart, cut at 35.00 s (the app's endpointer cuts it at its 15 s window: three utterances).
# <other>_input: one sentence in the other pair's language. pause_resume: the row of pause_resume.tsv read in two halves
# (split at " | "), each half's near-silent edges trimmed (|sample| < 33), with 0.5 s of silence between them, 0.3 s
# before and 1.2 s after (one utterance, one pause the app's early cut sees).
# say falls back to the default voice without an error when it does not know a name: every voice must be listed by
# `say -v '?'`. Each voice says one line first (its first synthesis can differ from the next ones), and every file is
# made twice and must come out the same both times.
# Then the WAVs are compared with SHA256SUMS: the files the device checks of this sample ran with (2026-10-19/20, macOS
# 27.0, ffmpeg 9.0.1).
#   bash make_fixtures.sh <out dir>
# Needs macOS `say` with the six voices above (System Settings > Accessibility > Spoken Content), ffmpeg and python3.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${1:?usage: make_fixtures.sh <out dir>}"
T="$(mktemp -d "${TMPDIR:-/tmp}/translate-fixtures.XXXXXX")"
trap 'rm -rf "${T:?}"' EXIT
mkdir -p "$T/p1" "$T/p2"

say -v '?' > "$T/voices.txt"
voice() {  # voice <name> <a line to say first>
  grep -qF -- "$1" "$T/voices.txt" || { echo "macOS has no voice '$1' (say -v '?')" >&2; exit 1; }
  say -v "$1" -o "$T/first.aiff" "$2" < /dev/null
}

raw() {  # raw <pass> <name> <voice> <text>: say -> 16 kHz mono s16 in $T/<pass>/<name>.raw
  printf '%s' "$4" > "$T/$2.txt"
  say -v "$3" -f "$T/$2.txt" -o "$T/$1/$2.aiff" < /dev/null
  ffmpeg -nostdin -v error -y -i "$T/$1/$2.aiff" -ac 1 -ar 16000 -f s16le -c:a pcm_s16le "$T/$1/$2.raw"
}

twice() {  # twice <name> <voice> <text>: made twice, the same samples both times (-> $T/p2/<name>.raw)
  raw p1 "$1" "$2" "$3"
  raw p2 "$1" "$2" "$3"
  cmp -s "$T/p1/$1.raw" "$T/p2/$1.raw" || { echo "say gave two different results for $1 ($2)" >&2; exit 1; }
}

padded() {  # padded <name> <wav>: 0.3 s of silence before (4,800 samples), 1.2 s after (19,200)
  ffmpeg -nostdin -v error -y -f s16le -ar 16000 -ac 1 -i "$T/p2/$1.raw" -af "adelay=300:all=1,apad=pad_dur=1.2" \
    -ac 1 -ar 16000 -c:a pcm_s16le -map_metadata -1 -fflags +bitexact -flags:a +bitexact "$2"
}

# pair <tag> <into-English dir> <its voice> <from-English voice> <other input name> <its voice> <its text> <pause voice>
pair() {
  local tag=$1 into=$2 v_into=$3 v_from=$4 other=$5 v_other=$6 other_text=$7 v_pause=$8
  local W="$OUT/$tag/wav" id dir src ref sec checks v h1 h2 longs=()
  mkdir -p "$W"
  while IFS=$'\t' read -r id dir src ref sec checks; do
    if [ "$dir" = "$into" ]; then v=$v_into; longs+=("$id"); else v=$v_from; fi
    twice "$tag-$id" "$v" "$src"
    padded "$tag-$id" "$W/$id.wav"
    echo "$tag/wav/$id.wav ($v): $(( $(stat -f %z "$T/p2/$tag-$id.raw") / 2 )) samples of speech"
  done < <(tail -n +2 "$HERE/$tag/sentences.tsv")
  twice "$tag-$other" "$v_other" "$other_text"
  padded "$tag-$other" "$W/$other.wav"
  IFS=$'\t' read -r dir src < <(sed -n 2p "$HERE/$tag/pause_resume.tsv" | cut -f1,2)
  h1=${src%% | *}
  h2=${src#* | }
  twice "$tag-pause_a" "$v_pause" "$h1"
  twice "$tag-pause_b" "$v_pause" "$h2"
  python3 -I - "$W" "$T/p2" "$tag" "${longs[@]}" <<'PY'
import math, random, struct, sys, wave
w, t, tag, longs = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4:]
RATE, FRAME = 16000, 320

def read(path):
    data = open(path, "rb").read()
    return list(struct.unpack("<%dh" % (len(data) // 2), data))

def write(path, samples):
    with wave.open(path, "wb") as f:
        f.setnchannels(1); f.setsampwidth(2); f.setframerate(RATE)
        f.writeframes(struct.pack("<%dh" % len(samples), *samples))

def utterances(s):
    # hfmodels-voice's Endpointer as the app runs it, frame by frame: 20 ms frames, voiced = mean square >= 0.02^2, speech
    # after 5 voiced frames (100 ms), 300 ms of pre-roll, a cut after 40 unvoiced frames (the 800 ms hangover) or at
    # 15 s; plus the 1 s of silence the play extra adds after a file. Returns (start, end) samples per utterance.
    x = s + [0] * RATE
    ring, run, inside, start, n, silent, out = [], 0, False, 0, 0, 0, []
    for k in range(len(x) // FRAME):
        fr = x[k * FRAME:(k + 1) * FRAME]
        voiced = sum((v / 32768.0) ** 2 for v in fr) / FRAME >= 0.02 ** 2
        if not inside:
            ring.append(k)
            if len(ring) > 20:
                ring.pop(0)
            run = run + 1 if voiced else 0
            if run < 5:
                continue
            inside, silent, start, n, ring, run = True, 0, ring[0] * FRAME, len(ring) * FRAME, [], 0
        else:
            n += FRAME
            silent = 0 if voiced else silent + 1
        if n >= 15 * RATE or silent >= 40:
            out.append((start, start + n))
            inside, n, silent = False, 0, 0
    if inside:
        out.append((start, start + n))
    return out

write(f"{w}/silence_5s.wav", [0] * (5 * RATE))
rng = random.Random(20261019)
write(f"{w}/noise_5s.wav", [max(-32768, min(32767, round(rng.gauss(0.0, 0.003) * 32768))) for _ in range(5 * RATE)])
out, k = [], 0
while len(out) < 35 * RATE:
    out += read(f"{t}/{tag}-{longs[k % len(longs)]}.raw") + [0] * (RATE * 3 // 10)
    k += 1
write(f"{w}/long_35s.wav", out[: 35 * RATE])
u = utterances(out[: 35 * RATE])
print(f"{tag}/wav/long_35s.wav: {k} sentences, utterances at " + ", ".join(f"{a / RATE:.2f}-{b / RATE:.2f} s" for a, b in u))
if len(u) != 3:
    sys.exit(f"long_35s: {len(u)} utterances, the device check wants 3")

def trim(s):
    i, j = 0, len(s)
    while i < j and abs(s[i]) < 33: i += 1
    while j > i and abs(s[j - 1]) < 33: j -= 1
    return s[i:j]

a, b = trim(read(f"{t}/{tag}-pause_a.raw")), trim(read(f"{t}/{tag}-pause_b.raw"))
pr = [0] * (RATE * 3 // 10) + a + [0] * (RATE // 2) + b + [0] * (RATE * 12 // 10)
write(f"{w}/pause_resume.wav", pr)
voiced = [sum((v / 32768.0) ** 2 for v in pr[k * FRAME:(k + 1) * FRAME]) / FRAME >= 0.02 ** 2 for k in range(len(pr) // FRAME)]
first, last = voiced.index(True), len(voiced) - 1 - voiced[::-1].index(True)
runs, cur = [], 0
for v in voiced[first:last + 1]:
    if v:
        if cur: runs.append(cur)
        cur = 0
    else:
        cur += 1
inner, early, n = max(runs, default=0), sum(1 for r in runs if r >= 15), len(utterances(pr))
print(f"{tag}/wav/pause_resume.wav: {len(pr) / RATE:.2f} s, {n} utterance(s), the pause {inner * 20} ms")
# The early cut comes at 15 unvoiced frames (300 ms), the endpointer's cut at 40 (800 ms): the pause must lie between.
if n != 1 or early != 1 or not (320 <= inner * 20 <= 760):
    sys.exit(f"pause_resume: {n} utterances, {early} pauses of 300 ms or more, {inner * 20} ms (want 1, 1 and 320-760 ms)")
PY
  cp "$HERE/$tag/sentences.tsv" "$HERE/$tag/pause_resume.tsv" "$OUT/$tag/"
}

voice "Flo (Spanish (Spain))" "Buenos días, quiero un café, por favor."
voice "Eddy (English (US))" "Good morning, I would like a coffee, please."
voice "Flo (French (France))" "Bonjour, je voudrais un café, s'il vous plaît."
voice "Amélie" "Bonjour, je voudrais un café, s'il vous plaît."
voice "Samantha" "Good morning, I would like a coffee, please."
voice "Mónica" "Buenos días, quiero un café, por favor."
pair es es-en "Flo (Spanish (Spain))" "Eddy (English (US))" fr_input "Flo (French (France))" \
  "Excusez-moi, à quelle heure part le prochain bus pour l'aéroport ?" "Mónica"
pair fr fr-en "Amélie" "Samantha" es_input "Mónica" "Perdone, ¿a qué hora sale el próximo autobús al aeropuerto?" "Amélie"

# The files the device checks ran with.
cd "$OUT"
if shasum -a 256 -c "$HERE/SHA256SUMS" > "$T/sums.txt" 2>&1; then
  echo "SHA256SUMS: all $(grep -c ': OK$' "$T/sums.txt") WAVs are the ones the device checks used"
else
  echo "SHA256SUMS: $(grep -c ': OK$' "$T/sums.txt") of $(wc -l < "$HERE/SHA256SUMS" | tr -d ' ') WAVs are the ones the device checks used; different: $(grep ': FAILED' "$T/sums.txt" | cut -d: -f1 | tr '\n' ' ')"
fi
