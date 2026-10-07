#!/usr/bin/env python3
"""The spoken queries: 20 sentences, one per album subject (the other album photos are distractors), each said by two
macOS voices (A = Samantha, en_US; B = Daniel, en_GB), plus 3 decoys (voice A only) that name nothing in the album.

  K/venv/bin/python -I K/scripts/make_queries.py            writes fixtures/queries/q<NN>_a.wav, q<NN>_b.wav,
                                                           d<NN>_a.wav and fixtures/queries.json
  K/venv/bin/python -I K/scripts/make_queries.py --rev 2   the one allowed rewording (launch D): REWORDED sentences
                                                           as q<NN>_a2.wav (voice A only), the first files kept

Per clip: `say -v <voice> -r 170 -o <tmp>.aiff "<text>"` -> `ffmpeg -ar 16000 -ac 1 -c:a pcm_s16le` -> ONE linear
gain that brings the active RMS (the loudest 30 % of 20 ms windows) to -20 dBFS, limited so the peak stays <= -1 dBFS
(the rule of litertlm-convert/qwen3_asr17_work/demo/make_fixtures.py), rounded to 16 bit -> 0.3 s of silence before
and after. 16 kHz mono PCM16 is what the model card asks for ("Audio should be supplied as mono at 16 kHz") and what
the app's AudioRecord produces.

The 20 subjects and their sentences were fixed after the album was chosen and before any embedding was computed
(fixtures/queries.json records `chosen_before_scoring`). Sentences: 4-9 words, said the way a person asks for a photo,
naming the photo's subject in words; no names, brands or people.
"""
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import wave

import numpy as np

K = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(K, "fixtures", "queries")
ALBUM_JSON = os.path.join(K, "fixtures", "album.json")
QUERIES_JSON = os.path.join(K, "fixtures", "queries.json")
RATE = 16000
TARGET_ACTIVE_DBFS = -20.0
PEAK_CEIL_DBFS = -1.0
PAD_S = 0.3
VOICES = {"a": ("Samantha", "en_US"), "b": ("Daniel", "en_GB")}
SAY_RATE = 170

# (query id, album slug, sentence). The slug is resolved to the album id through album.json.
QUERIES = [
    ("q01", "red_bicycle", "a red bicycle leaning on a tree"),
    ("q02", "lighthouse", "a lighthouse on the coast"),
    ("q03", "snow_mountain", "a mountain with snow on top"),
    ("q04", "sunflower_field", "a field full of sunflowers"),
    ("q05", "waterfall", "a waterfall in the forest"),
    ("q06", "coffee_cup", "a cup of coffee on the table"),
    ("q07", "pizza", "a slice of pizza"),
    ("q08", "sleeping_cat", "the cat taking a nap"),
    ("q09", "dog_beach", "a dog on the beach"),
    ("q10", "city_night", "the city lights at night"),
    ("q11", "violin", "an old wooden violin"),
    ("q12", "tulip_field", "a field of tulips"),
    ("q13", "snowman", "a snowman in the snow"),
    ("q14", "campfire", "a campfire burning at night"),
    ("q15", "rainbow", "a rainbow over the field"),
    ("q16", "windmill", "an old windmill in a field"),
    ("q17", "castle_hill", "the old castle by the lake"),
    ("q18", "chess_board", "a chess board with wooden pieces"),
    ("q19", "umbrella_rain", "an umbrella in the rain"),
    ("q20", "autumn_leaves", "red autumn leaves on a tree"),
]
# The launch's one allowed rewording, used only after the first sentences missed the line: query id -> sentence.
REWORDED = {
}
DECOYS = [
    ("d01", "what time is it now"),
    ("d02", "turn the volume down"),
    ("d03", "remind me tomorrow morning"),
]


def db(v):
    return 20 * np.log10(max(v, 1e-9) / 32768.0)


def synth(text, voice, dst):
    with tempfile.TemporaryDirectory() as tmp:
        aiff = os.path.join(tmp, "say.aiff")
        raw = os.path.join(tmp, "raw.wav")
        subprocess.run(["say", "-v", voice, "-r", str(SAY_RATE), "-o", aiff, text], check=True)
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", aiff, "-ar", str(RATE), "-ac", "1", "-c:a", "pcm_s16le",
                        raw], check=True)
        with wave.open(raw) as w:
            assert (w.getframerate(), w.getnchannels(), w.getsampwidth()) == (RATE, 1, 2), raw
            x = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float64)
    n = len(x)
    rms = np.sqrt(np.mean(x[:n // 320 * 320].reshape(-1, 320) ** 2, axis=1))
    active = rms[rms > np.percentile(rms, 70)]
    active_db, peak_db = db(np.sqrt(np.mean(active ** 2))), db(np.abs(x).max())
    gain_db = min(TARGET_ACTIVE_DBFS - active_db, PEAK_CEIL_DBFS - peak_db)
    y = np.round(x * 10 ** (gain_db / 20))
    assert np.abs(y).max() <= 32767, dst
    pad = np.zeros(int(PAD_S * RATE))
    y = np.concatenate([pad, y, pad])
    with wave.open(dst, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(y.astype("<i2").tobytes())
    data = open(dst, "rb").read()
    return {"seconds": round(len(y) / RATE, 3), "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data),
            "gain_db": round(gain_db, 2), "source_active_rms_dbfs": round(active_db, 2),
            "peak_dbfs": round(db(np.abs(y).max()), 2)}


def main():
    rev = 2 if "--rev" in sys.argv and sys.argv[sys.argv.index("--rev") + 1] == "2" else 1
    album = {r["slug"]: r for r in json.load(open(ALBUM_JSON))["album"]}
    os.makedirs(OUT, exist_ok=True)
    doc = json.load(open(QUERIES_JSON)) if rev == 2 else {
        "chosen_before_scoring": True,
        "rule": "16 kHz mono PCM16 WAV; say -r %d; one linear gain to %.0f dBFS active RMS (peak <= %.0f dBFS); "
                "%.1f s silence before and after" % (SAY_RATE, TARGET_ACTIVE_DBFS, PEAK_CEIL_DBFS, PAD_S),
        "voices": {k: {"name": v[0], "locale": v[1]} for k, v in VOICES.items()}, "queries": [], "decoys": []}
    if rev == 1:
        for qid, slug, text in QUERIES:
            assert 4 <= len(text.split()) <= 9, (qid, text)
            gold = album[slug]["id"]
            for v, (voice, _) in VOICES.items():
                name = f"{qid}_{v}.wav"
                row = {"id": f"{qid}_{v}", "query": qid, "text": text, "gold": gold, "gold_slug": slug, "voice": v,
                       "voice_name": voice, "file": name, "wording": 1}
                row.update(synth(text, voice, os.path.join(OUT, name)))
                doc["queries"].append(row)
                print(f"{name}: {row['seconds']:.2f} s gain {row['gain_db']:+.1f} dB  gold {gold} ({slug})  {text!r}")
        for did, text in DECOYS:
            name = f"{did}_a.wav"
            row = {"id": f"{did}_a", "query": did, "text": text, "gold": None, "voice": "a",
                   "voice_name": VOICES["a"][0], "file": name, "wording": 1}
            row.update(synth(text, VOICES["a"][0], os.path.join(OUT, name)))
            doc["decoys"].append(row)
            print(f"{name}: {row['seconds']:.2f} s  decoy {text!r}")
    else:
        golds = {q["query"]: (q["gold"], q["gold_slug"]) for q in doc["queries"]}
        doc["queries"] = [q for q in doc["queries"] if q["wording"] == 1]
        for qid, text in REWORDED.items():
            assert 4 <= len(text.split()) <= 9, (qid, text)
            gold, slug = golds[qid]
            name = f"{qid}_a2.wav"
            row = {"id": f"{qid}_a2", "query": qid, "text": text, "gold": gold, "gold_slug": slug, "voice": "a",
                   "voice_name": VOICES["a"][0], "file": name, "wording": 2}
            row.update(synth(text, VOICES["a"][0], os.path.join(OUT, name)))
            doc["queries"].append(row)
            print(f"{name}: {row['seconds']:.2f} s  gold {gold} ({slug})  reworded {text!r}")
        doc["reworded"] = True
    json.dump(doc, open(QUERIES_JSON, "w"), indent=1, ensure_ascii=False)
    print("->", QUERIES_JSON)


if __name__ == "__main__":
    main()
