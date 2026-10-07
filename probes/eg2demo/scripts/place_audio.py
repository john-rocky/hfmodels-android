#!/usr/bin/env python3
"""Where each played clip goes in a take's sound track, from the app's own clock (round 3).

  K/venv/bin/python -I K/scripts/place_audio.py --raw <raw.mp4> --demo <take_<tag>_demo.log> --run <run.json>
      --takelog <take_<tag>.log> --cut <raw s> --wavs q01_a.wav[,q13_a.wav] [--log <frames.log>]   -> JSON on stdout

The app logs `PILL rgb=<#RRGGBB> epoch_ms=<ms>` each time the pill changes colour and `PLAY_START epoch_ms=<ms>` in the
UI pass that turns the pill red and calls AudioTrack.play(). This reads the pill's colour in every frame of the raw
video (a box in the pill's left padding, layout.pill_px of the run JSON), lists the colour changes, pairs them in order
with the PILL lines logged after the recording started, and fits the phone clock to the video's time on the changes
that are NOT red (blue EMBEDDING, green DONE: offset = median of frame time - epoch). Each clip is then placed at
PLAY_START + offset; the red changes, left out of the fit, are the check: line (e) = |placement - first red frame|
<= 0.2 s. The REC_SHELL_EPOCH line of take.log (the device clock in the shell right before screenrecord started) gives
a third, rougher placement for the record. The sentence of each clip is its text in K/fixtures/queries.json.
"""
import argparse
import json
import os
import re
import statistics
import subprocess
import sys
import wave

import numpy as np

K = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PALETTE = {"idle": (0x5F, 0x63, 0x68), "live": (0xE5, 0x39, 0x35), "work": (0x15, 0x65, 0xC0), "done": (0x2E, 0x7D, 0x32)}
RGB_NAME = {"#5F6368": "idle", "#E53935": "live", "#1565C0": "work", "#2E7D32": "done"}
MAX_DIST = 60.0
LINE_E_S = 0.2


def classify(rgb):
    best, d = None, 1e9
    for name, ref in PALETTE.items():
        dd = sum((a - b) ** 2 for a, b in zip(rgb, ref)) ** 0.5
        if dd < d:
            best, d = name, dd
    return best if d <= MAX_DIST else "other"


def pill_frames(raw, layout):
    p = layout["pill_px"]
    pad = p.get("pad_left") or max(8, p["height"] // 2)
    x0 = int(p["left"] + pad * 0.25)
    w = max(4, int(pad * 0.5))
    y0 = int(p["top"] + p["height"] / 2 - 6)
    h = 12
    cmd = ["ffmpeg", "-hide_banner", "-nostats", "-loglevel", "info", "-i", raw, "-map", "0:v:0",
           "-vf", f"crop={w}:{h}:{x0}:{y0},showinfo", "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", "rgb24",
           "pipe:1"]
    res = subprocess.run(cmd, capture_output=True, check=True)
    times = [float(m.group(1)) for m in re.finditer(rb"\] n:\s*\d+ pts:\s*-?\d+\s+pts_time:(-?[\d.]+)", res.stderr)]
    n = len(res.stdout) // (w * h * 3)
    if n != len(times):
        sys.exit(f"{raw}: {n} frames of pixels but {len(times)} frame times")
    fr = np.frombuffer(res.stdout[:n * w * h * 3], np.uint8).reshape(n, h, w, 3).astype(np.float64)
    means = fr.mean(axis=(1, 2))
    return [(t, tuple(float(v) for v in m), classify(m)) for t, m in zip(times, means)], (x0, y0, w, h)


def changes(frames):
    out, prev = [], None
    for t, rgb, s in frames:
        if s != prev:
            out.append({"t": t, "state": s, "rgb": [round(v) for v in rgb]})
            prev = s
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--raw", required=True)
    ap.add_argument("--demo", required=True)
    ap.add_argument("--run", required=True)
    ap.add_argument("--takelog", required=True)
    ap.add_argument("--cut", type=float, required=True)
    ap.add_argument("--wavs", required=True)
    ap.add_argument("--queries", default=os.path.join(K, "fixtures", "queries.json"))
    ap.add_argument("--wav-dir", default=os.path.join(K, "fixtures", "queries"))
    ap.add_argument("--log")
    a = ap.parse_args()
    run = json.load(open(a.run))
    layout = run.get("layout") or {}
    if not layout.get("pill_px"):
        sys.exit("the run JSON has no layout.pill_px")
    demo = open(a.demo, errors="replace").read()
    takelog = open(a.takelog, errors="replace").read()
    pills = [(int(m.group(2)), RGB_NAME.get(m.group(1).upper(), "other"))
             for m in re.finditer(r"PILL rgb=(#[0-9A-Fa-f]{6}) epoch_ms=(\d+)", demo)]
    plays = [(int(m.group(1)), m.group(2)) for m in re.finditer(r"PLAY_START epoch_ms=(\d+) id=(\S+)", demo)]
    rs = re.search(r"REC_START before_epoch_ms=(\d+)", takelog)
    shell = re.search(r"REC_SHELL_EPOCH (\d+)", takelog)
    rec_before = int(rs.group(1)) if rs else None
    rec_shell = int(shell.group(1)) if shell else None
    frames, box = pill_frames(a.raw, layout)
    if a.log:
        with open(a.log, "w") as f:
            f.write(f"# {a.raw}: pill sample box x={box[0]} y={box[1]} {box[2]}x{box[3]}; frame t mean_rgb state\n")
            for i, (t, rgb, s) in enumerate(frames):
                f.write(f"{i:5d} {t:10.6f} {rgb[0]:6.1f} {rgb[1]:6.1f} {rgb[2]:6.1f} {s}\n")
    ch = changes(frames)
    video = ch[1:]                       # the first entry is the state on screen when the recording started
    start_epoch = rec_before if rec_before is not None else (rec_shell or 0)
    logged = [(e, s) for e, s in pills if e >= start_epoch]
    names = [w.strip() for w in a.wavs.split(",") if w.strip()]
    queries = json.load(open(a.queries))
    texts = {q["file"]: q["text"] for key in ("queries", "decoys") for q in queries.get(key, [])}
    out = {"raw": a.raw, "cut_t": a.cut, "pill_box": box, "frames": len(frames), "video_changes": ch,
           "logged_changes_since_rec_start": [{"epoch_ms": e, "state": s} for e, s in logged],
           "rec_start_before_epoch_ms": rec_before, "rec_shell_epoch_ms": rec_shell}
    pairs = []
    if [s for _, s in logged] == [c["state"] for c in video[:len(logged)]] and len(video) >= len(logged) > 0:
        pairs = [{"state": s, "epoch_ms": e, "frame_t": c["t"], "d": c["t"] - e / 1000.0} for (e, s), c in zip(logged, video)]
        out["pairing"] = f"{len(pairs)} logged colour changes paired in order with the video's"
    else:
        out["pairing"] = ("FAILED: logged " + ",".join(s for _, s in logged) + " vs video " +
                          ",".join(c["state"] for c in video))
    fit = [p["d"] for p in pairs if p["state"] != "live"]
    offset = statistics.median(fit) if fit else None
    out["offset_s"] = offset
    out["fit_on"] = [p["state"] for p in pairs if p["state"] != "live"]
    if offset is not None:
        for p in pairs:
            p["residual_ms"] = round((p["d"] - offset) * 1000, 1)
    out["pairs"] = pairs
    reds = [p for p in pairs if p["state"] == "live"]
    clips = []
    for k, name in enumerate(names):
        cid = name[:-4] if name.endswith(".wav") else name
        wav = os.path.join(a.wav_dir, name)
        with wave.open(wav) as wf:
            secs = wf.getnframes() / wf.getframerate()
        # The k-th PLAY_START belongs to the k-th clip; by id if the order does not hold.
        play = plays[k][0] if k < len(plays) and plays[k][1] == cid else next((e for e, i in plays if i == cid), None)
        c = {"id": cid, "wav": wav, "wav_seconds": secs, "sentence": texts.get(name), "play_start_epoch_ms": play}
        red = reds[k]["frame_t"] if k < len(reds) else None
        c["red_frame_t"] = red
        if play is not None and offset is not None:
            c["placed_raw_t"] = play / 1000.0 + offset
            c["used"] = "PLAY_START + offset (fit on the non-red pill changes)"
        elif red is not None:
            c["placed_raw_t"] = red
            c["used"] = "the first red frame (no clock fit)"
        else:
            sys.exit(f"no placement for {cid}: no PLAY_START with a clock fit and no red frame")
        c["placed_trim_t"] = c["placed_raw_t"] - a.cut
        if c["placed_trim_t"] < 0:
            sys.exit(f"{cid} would start {c['placed_trim_t']:.3f} s before the cut")
        c["line_e_ms"] = round((c["placed_raw_t"] - red) * 1000, 1) if (red is not None and offset is not None and play is not None) else None
        c["line_e_pass"] = (abs(c["line_e_ms"]) <= LINE_E_S * 1000) if c["line_e_ms"] is not None else None
        c["clock_shell_t"] = (play - rec_shell) / 1000.0 if (play is not None and rec_shell) else None
        clips.append(c)
    out["clips"] = clips
    json.dump(out, sys.stdout, indent=1, ensure_ascii=False)
    print()


if __name__ == "__main__":
    main()
