#!/usr/bin/env python3
"""Each answer card's state in every frame of a take, read from the video's pixels (pill_track.py of
qwen3_asr17_work/demo, generalised from one pill to N cards).

The app writes into its run JSON a `layout` block (provisional shape; lane D and the supervisor settle it):
    "layout": {"screen_px": [1080, 2340],
               "palette": {"pending": "#5F6368", "running": "#1565C0", "done": "#2E7D32"},
               "cards": [{"qid": "team", "indicator_px": {"left": 60, "top": 1210, "width": 28, "height": 28}}, ...]}
`indicator_px` is a small solid area of the card whose colour says its state (screen pixels). This samples the middle
half of each indicator in every decoded frame with ffmpeg, classifies the mean colour as the nearest palette colour
(RGB distance <= 60, else "other"), and prints per card the state changes with the time of their frame, and whether the
card went pending -> running -> done in that order. Works on the raw take (1080x2340, variable frame rate) and on
make_media.sh's outputs: the crop version (1080x1920, --crop-y0 <y0>) and the pad version (scaled to 1920 px high and
centred, *_pad.mp4). A run JSON without `layout` is skipped (exit 0). Round 5: --map X0,Y0,SCALE,CROP_TOP places the
screen in any composite (scripts/r5_compare.py): video x = screen x * SCALE + X0, video y = (screen y - CROP_TOP) *
SCALE + Y0 (the race panels: "0,140,0.5,222" left, "540,140,0.5,222" right; the sequential pad clip "97,0,0.820513,0").

  python3 K/scripts/card_track.py <video.mp4> <run.json> [--crop-y0 210] [--map X0,Y0,SCALE,CROP_TOP] [--log <frames.log>]
"""
import argparse
import json
import re
import subprocess
import sys

MAX_DIST = 60.0
ORDER = ("pending", "running", "done")


def probe_size(video):
    out = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height",
                          "-of", "csv=p=0", video], check=True, capture_output=True, text=True).stdout
    w, h = (int(v) for v in out.strip().split(",")[:2])
    return w, h


def hex_rgb(s):
    s = s.lstrip("#")
    return tuple(int(s[i:i + 2], 16) for i in (0, 2, 4))


def to_video(box, screen, video, crop_y0, padded, mapping=None):
    """Screen px box -> video px box (even-aligned: ffmpeg crops 4:2:0 frames on even values)."""
    (sw, sh), (vw, vh) = screen, video
    x0, y0 = box["left"] + box["width"] * 0.25, box["top"] + box["height"] * 0.25
    x1, y1 = box["left"] + box["width"] * 0.75, box["top"] + box["height"] * 0.75
    if mapping is not None:                                 # round 5: a composite (X0, Y0, SCALE, CROP_TOP)
        mx, my, ms, mt = mapping
        x0, x1 = x0 * ms + mx, x1 * ms + mx
        y0, y1 = (y0 - mt) * ms + my, (y1 - mt) * ms + my
    elif (vw, vh) == (sw, sh):
        pass
    elif not padded and vw == sw and crop_y0 is not None:   # make_media.sh crop mode: same scale, band from y0
        y0, y1 = y0 - crop_y0, y1 - crop_y0
    else:                                                   # pad mode: scaled to the video height, centred
        s = vh / sh
        cw = 2 * round(sw * s / 2)
        ox = (vw - cw) // 2
        x0, x1 = x0 * cw / sw + ox, x1 * cw / sw + ox
        y0, y1 = y0 * s, y1 * s
    if y0 < 0 or y1 > vh or x0 < 0 or x1 > vw:
        return None
    x, y = 2 * int(round(x0 / 2)), 2 * int(round(y0 / 2))
    return x, y, max(2, 2 * int(round((x1 - x) / 2))), max(2, 2 * int(round((y1 - y) / 2)))


def sample(video, box):
    x, y, w, h = box
    cmd = ["ffmpeg", "-hide_banner", "-nostats", "-loglevel", "info", "-i", video, "-map", "0:v:0",
           "-vf", f"crop={w}:{h}:{x}:{y},showinfo", "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", "rgb24", "pipe:1"]
    res = subprocess.run(cmd, capture_output=True, check=True)
    times = [float(m.group(1)) for m in re.finditer(rb"\] n:\s*\d+ pts:\s*-?\d+\s+pts_time:(-?[\d.]+)", res.stderr)]
    size = w * h * 3
    n = len(res.stdout) // size
    if n != len(times):
        raise SystemExit(f"{video}: {n} frames of pixels but {len(times)} frame times")
    out = []
    for i in range(n):
        px = res.stdout[i * size:(i + 1) * size]
        out.append((times[i], tuple(round(sum(px[c::3]) / (w * h), 1) for c in range(3))))
    return out


def classify(rgb, palette):
    best, dist = "other", MAX_DIST
    for name, ref in palette.items():
        d = sum((a - b) ** 2 for a, b in zip(rgb, ref)) ** 0.5
        if d < dist:
            best, dist = name, d
    return best


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("video")
    ap.add_argument("run_json")
    ap.add_argument("--crop-y0", type=int, default=210, help="make_media.sh crop mode band start (ignored otherwise)")
    ap.add_argument("--map", help="X0,Y0,SCALE,CROP_TOP: the screen inside a composite video (round 5)")
    ap.add_argument("--log")
    a = ap.parse_args()
    mapping = tuple(float(v) for v in a.map.split(",")) if a.map else None
    layout = json.load(open(a.run_json)).get("layout")
    if not layout or not layout.get("cards"):
        print(json.dumps({"video": a.video, "skipped": "the run JSON has no layout.cards"}))
        return 0
    palette = {k: hex_rgb(v) for k, v in layout["palette"].items()}
    screen = tuple(layout["screen_px"])
    size = probe_size(a.video)
    padded = a.video.endswith("_pad.mp4")
    cards, logs = [], []
    for c in layout["cards"]:
        box = to_video(c["indicator_px"], screen, size, a.crop_y0, padded, mapping)
        if box is None:
            cards.append({"qid": c["qid"], "error": "indicator outside this video (cropped away)"})
            continue
        frames = sample(a.video, box)
        changes = []
        for t, rgb in frames:
            st = classify(rgb, palette)
            if not changes or changes[-1]["state"] != st:
                changes.append({"t": round(t, 6), "state": st, "rgb": list(rgb)})
        seq = [ch["state"] for ch in changes if ch["state"] in ORDER]
        onset = {s: next((ch["t"] for ch in changes if ch["state"] == s), None) for s in ORDER}
        in_order = (all(onset[s] is not None for s in ORDER) and onset["pending"] <= onset["running"] <= onset["done"]
                    and seq[-1] == "done")
        cards.append({"qid": c["qid"], "box": list(box), "frames": len(frames), "changes": changes,
                      "onset": onset, "pending_running_done": in_order,
                      "running_s": None if not in_order else round(onset["done"] - onset["running"], 3)})
        logs.append((c["qid"], box, frames))
    if a.log:
        with open(a.log, "w") as f:
            for qid, box, frames in logs:
                f.write(f"# {a.video} card {qid} box x={box[0]} y={box[1]} w={box[2]} h={box[3]}\n")
                for t, rgb in frames:
                    f.write(f"{t:10.6f}  {rgb[0]:5.1f} {rgb[1]:5.1f} {rgb[2]:5.1f}  {classify(rgb, palette)}\n")
    ok = all(c.get("pending_running_done") for c in cards)
    json.dump({"video": a.video, "size": list(size), "cards": cards, "all_cards_pending_running_done": ok}, sys.stdout, indent=1)
    print()
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
