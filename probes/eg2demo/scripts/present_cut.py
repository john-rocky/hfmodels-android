#!/usr/bin/env python3
"""(Copied from litertlm-convert/kev_work/demo/present_cut.py with card_track.py; only the paths changed. It reads the
Kev app's run JSON `layout` block, which the EmbeddingGemma 2 demo app does not write: for this app the cut is settle.py's.)
Where the edited video starts for the Kev demo app: the earliest settled frame of the presentation screen.

A take is recorded from READY, so it opens on the app's normal editing screen; `delay_ms` after the autoplay intent the
app switches to the presentation layout (title, ticket, three grey cards, footer). settle.py (qwen3_asr17_work/demo)
looks for a dark app over a bright lock screen and does not apply to this light layout. Two steps:
  1. the presentation is up: the earliest frame in which every card's state indicator (run JSON
     `layout.cards[].indicator_px`) shows the `pending` colour within NEAR (RGB distance) and still does in the next
     frame (a blended frame of a cross-fade is skipped);
  2. it has settled: the app fits the ticket by shrinking its text a step at a time, so the opening ~0.1 s of the
     presentation shows the ticket larger and cut off with an ellipsis (round 2: 9 frames over 90-98 ms), and the system
     navigation bar (home / back) is still sliding out below the footer until ~0.165 s (round 2, all three takes). The
     cut is the earliest frame from step 1 on whose title + ticket band (`layout.content_px.top` down to above the top
     card) matches the take's last frame within BAND_MAD (mean absolute grey difference: encoder noise between identical
     screens is ~2.5, a frame of the shrinking ticket ~30), whose band below the footer (`content_px.bottom` + 22 px to
     the screen's bottom) has at most BOTTOM_PIX pixels more than STRONG grey levels off the last frame (the thin
     navigation bar icons are ~1,000 such pixels but move the mean by only ~0.6), with every indicator still pending,
     and the next frame too. Without `content_px` step 2 is skipped (reported).
Nothing is edited: the frames before the cut are cut off the head.

  python3 K/scripts/present_cut.py <raw.mp4> <run.json> [--log <frames.log>]    -> JSON on stdout: {"cut": {"t": …}}
Exit 1 when no such frame exists or the run JSON has no layout."""
import argparse
import json
import os
import subprocess
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import card_track  # noqa: E402

NEAR = 12.0
BAND_MAD = 6.0
STRONG, BOTTOM_PIX = 48, 10


def dist(a, b):
    return sum((x - y) ** 2 for x, y in zip(a, b)) ** 0.5


def band_diff(video, size, top, bottom):
    """Per frame, the band [top, bottom) against the last frame's: (mean absolute grey difference, pixels off by more
    than STRONG grey levels)."""
    w, h = size[0], bottom - top
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", video, "-map", "0:v:0", "-fps_mode", "passthrough",
                          "-vf", f"crop={w}:{h}:0:{top}", "-f", "rawvideo", "-pix_fmt", "gray", "pipe:1"],
                         capture_output=True, check=True).stdout
    n = len(raw) // (w * h)
    fr = np.frombuffer(raw[: n * w * h], np.uint8).reshape(n, h, w).astype(np.int16)
    d = [np.abs(fr[i] - fr[-1]) for i in range(n)]
    return [float(x.mean()) for x in d], [int((x > STRONG).sum()) for x in d]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("video")
    ap.add_argument("run_json")
    ap.add_argument("--log")
    a = ap.parse_args()
    layout = json.load(open(a.run_json)).get("layout")
    if not layout or not layout.get("cards"):
        print("the run JSON has no layout.cards", file=sys.stderr)
        return 1
    pending = card_track.hex_rgb(layout["palette"]["pending"])
    screen = tuple(layout["screen_px"])
    size = card_track.probe_size(a.video)
    if size != screen:
        print(f"{a.video} is {size}, expected the raw take at the screen size {screen}", file=sys.stderr)
        return 1
    per_card = []
    for c in layout["cards"]:
        box = card_track.to_video(c["indicator_px"], screen, size, None, False)
        per_card.append(card_track.sample(a.video, box))
    n = min(len(f) for f in per_card)
    times = [per_card[0][i][0] for i in range(n)]
    worst = [max(dist(f[i][1], pending) for f in per_card) for i in range(n)]
    up = next((i for i in range(n) if worst[i] <= NEAR and (i + 1 >= n or worst[i + 1] <= NEAR)), None)
    if up is None:
        print(f"no frame with every card indicator within {NEAR} of the pending colour", file=sys.stderr)
        return 1
    c = layout.get("content_px")
    band, below, mad, low = None, None, None, None
    if c:
        top = 2 * (int(c["top"]) // 2)
        bottom = 2 * ((min(cd["indicator_px"]["top"] for cd in layout["cards"]) - 20) // 2)
        band, below = [top, bottom], [2 * ((int(c["bottom"]) + 22) // 2), screen[1]]
        mad = band_diff(a.video, size, *band)[0]
        low = band_diff(a.video, size, *below)[1]
        n = min(n, len(mad), len(low))
        ok = [worst[i] <= NEAR and mad[i] <= BAND_MAD and low[i] <= BOTTOM_PIX for i in range(n)]
        cut = next((i for i in range(up, n) if ok[i] and (i + 1 >= n or ok[i + 1])), None)
        if cut is None:
            print(f"the screen never settles to the last frame (title + ticket band within {BAND_MAD}, below the footer "
                  f"<= {BOTTOM_PIX} px off) with the cards pending", file=sys.stderr)
            return 1
        reason = ("earliest frame of the presentation screen (every card indicator at the pending colour) whose title + "
                  "ticket band and band below the footer match the take's last frame, and the next frame too: the "
                  "ticket text has stopped resizing and the navigation bar is gone")
    else:
        cut = up
        reason = ("earliest frame with every card indicator at the pending colour (and the next frame too); no "
                  "layout.content_px, so the settle step was skipped")
    if a.log:
        with open(a.log, "w") as f:
            f.write(f"# {a.video}: frame t, max RGB distance of the {len(per_card)} card indicators to pending {pending}"
                    f"{', mean abs grey difference of the band ' + str(band) + ' to the last frame, pixels off by > ' + str(STRONG) + ' in the band ' + str(below) if band else ''}\n")
            for i in range(n):
                f.write(f"{i:5d} {times[i]:10.6f} {worst[i]:7.1f}" + (f" {mad[i]:7.2f} {low[i]:6d}" if mad else "") + "\n")
    json.dump({"video": a.video, "frames": n,
               "cut": {"index": cut, "t": times[cut], "max_distance": round(worst[cut], 2),
                       "band_mad": None if mad is None else round(mad[cut], 2),
                       "below_footer_px": None if low is None else low[cut], "reason": reason},
               "presentation_up": {"index": up, "t": times[up], "band_mad": None if mad is None else round(mad[up], 2),
                                   "below_footer_px": None if low is None else low[up]},
               "settle_s": round(times[cut] - times[up], 6), "band_px": band, "below_footer_band_px": below,
               "frame_before": None if cut == 0 else {"t": times[cut - 1], "max_distance": round(worst[cut - 1], 2),
                                                      "band_mad": None if mad is None else round(mad[cut - 1], 2),
                                                      "below_footer_px": None if low is None else low[cut - 1]},
               "last_frame_t": times[n - 1]}, sys.stdout, indent=1)
    print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
