#!/usr/bin/env python3
"""Where the edited video starts: the first settled frame of the app in a raw take.

The take starts on the lock screen (bright). The app's first frame is the first frame after 1 s whose mean luma is
below LUMA_CUT. The next ~0.9 s are the launch over the lock screen settling — a black splash, a fade-in, the system
navigation bar drawn over the footer, an empty status bar (memory keyguard-background-cpuset-demo-trap; the rule is
bonsai_image_work/demo/check_media.py `find_settled`, same phone, same screen size). The cut is the first frame from
the app's first frame on with the status bar icons drawn (the airplane-mode icon is part of the evidence) and no
navigation bar in it or in any later frame of the first WINDOW_S seconds. Frames are read at 1/SCALE size.

A take that starts recording with the app already on screen (take.sh since t2) passes --min-t 0: the app's first
frame is then the recording's first frame.

  python3 K/scripts/settle.py <raw.mp4> [--min-t 1.0] [--log <frames.log>]   -> JSON on stdout

(Copied unchanged from litertlm-convert/kev_work/demo/settle.py, itself from qwen3_asr17_work/demo/settle.py, except
this path: same phone, same screen size; the EmbeddingGemma 2 demo app is dark too, background 0x0E1116.)
"""
import argparse
import json
import re
import subprocess
import sys

import numpy as np

SCALE = 4
SCREEN = (1080, 2340)
LUMA_CUT = 60.0
STATUS_BAR_H = 96                       # screen px; the app's title row starts below it
NAV_BACK_BOX = (820, 2285, 870, 2340)   # screen px of the system back arrow when the navigation bar shows
NAV_ON = 60                             # brightest pixel of that box above which the navigation bar is on screen
STATUS_ON = 100                         # brightest pixel of the status bar above which its icons are drawn
WINDOW_S = 5.0


def read(video):
    w, h = SCREEN[0] // SCALE, SCREEN[1] // SCALE
    cmd = ["ffmpeg", "-hide_banner", "-nostats", "-loglevel", "info", "-i", video, "-map", "0:v:0",
           "-vf", f"showinfo,scale={w}:{h}:flags=area", "-fps_mode", "passthrough",
           "-f", "rawvideo", "-pix_fmt", "rgb24", "pipe:1"]
    res = subprocess.run(cmd, capture_output=True, check=True)
    times = [float(m.group(1)) for m in re.finditer(rb"\] n:\s*\d+ pts:\s*-?\d+\s+pts_time:(-?[\d.]+)", res.stderr)]
    n = len(res.stdout) // (w * h * 3)
    if n != len(times):
        raise SystemExit(f"{video}: {n} frames of pixels but {len(times)} frame times")
    fr = np.frombuffer(res.stdout[:n * w * h * 3], dtype=np.uint8).reshape(n, h, w, 3).astype(np.float32)
    luma = (fr @ np.array([0.2126, 0.7152, 0.0722], dtype=np.float32)).mean(axis=(1, 2))
    x0, y0, x1, y1 = (v // SCALE for v in NAV_BACK_BOX)
    nav = fr[:, y0:y1, x0:x1].max(axis=(1, 2, 3))
    status = fr[:, :STATUS_BAR_H // SCALE].max(axis=(1, 2, 3))
    return [{"t": t, "luma": float(a), "nav_max": float(b), "status_max": float(c)}
            for t, a, b, c in zip(times, luma, nav, status)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("video")
    ap.add_argument("--min-t", type=float, default=1.0)
    ap.add_argument("--log")
    a = ap.parse_args()
    rows = read(a.video)
    first = next((i for i, r in enumerate(rows) if r["t"] >= a.min_t and r["luma"] < LUMA_CUT), None)
    if first is None:
        raise SystemExit(f"no frame from {a.min_t} s on with mean luma below {LUMA_CUT}")
    t_end = rows[first]["t"] + WINDOW_S
    nav = [i for i in range(first, len(rows)) if rows[i]["t"] <= t_end and rows[i]["nav_max"] > NAV_ON]
    start = (nav[-1] + 1) if nav else first
    cut = next((i for i in range(start, len(rows)) if rows[i]["status_max"] > STATUS_ON), None)
    if cut is None:
        raise SystemExit("no frame with the status bar icons after the navigation bar")
    if a.log:
        with open(a.log, "w") as f:
            f.write(f"# {a.video}: frame t luma nav_max status_max (1/{SCALE} size)\n")
            for i, r in enumerate(rows):
                f.write(f"{i:5d} {r['t']:10.6f} {r['luma']:6.1f} {r['nav_max']:6.1f} {r['status_max']:6.1f}\n")
    json.dump({"video": a.video, "frames": len(rows),
               "min_t": a.min_t, "app_first_frame": {"index": first, "t": rows[first]["t"], "luma": rows[first]["luma"],
                                   "luma_before": rows[first - 1]["luma"] if first else None},
               "navigation_bar_frames": [nav[0], nav[-1]] if nav else None,
               "cut": {"index": cut, "t": rows[cut]["t"],
                       "reason": "first frame from the app's first frame on with the status bar icons drawn and no "
                                 f"navigation bar in it or later within {WINDOW_S:.0f} s"},
               "last_frame_t": rows[-1]["t"]}, sys.stdout, indent=1)
    print()


if __name__ == "__main__":
    main()
