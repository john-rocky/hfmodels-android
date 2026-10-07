#!/usr/bin/env python3
"""Round 2 numbers that check_run.py's table does not print, from the files gate.sh pulled (no hand-typed numbers):
per scored leg the rows whose top-1 differs from the Mac's GPU run, the largest |delta| of the top-1 cosine, voice A's
gap between the first and the second photo, q08 (cat / dog), and the red pixels in the pill of fail_bundle_screen.png.

  K/venv/bin/python -I K/scripts/r2_extra.py [--device-dir K/out/device] > K/out/device/r2_extra.txt
"""
import argparse
import glob
import json
import os
import statistics

from PIL import Image

K = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--device-dir", default=os.path.join(K, "out", "device"))
    a = ap.parse_args()
    mac = {r["id"]: r for k in ("queries", "decoys", "texts")
           for r in json.load(open(os.path.join(K, "out", "mac", "scores.json"))).get(k, [])}
    for leg in ("gpu", "gpu_audio_gpu", "cpu"):
        paths = sorted(glob.glob(os.path.join(a.device_dir, f"{leg}_eg2-demo-*.json")))
        if not paths:
            continue
        rows = [r for r in json.load(open(paths[-1]))["rows"] if "error" not in r]
        same = [r for r in rows if mac[r["id"]]["top1"] == r["top1"]]
        diff = [f"{r['id']} phone {r['top1']} {r['top1_cos']:.4f} / Mac {mac[r['id']]['top1']} {mac[r['id']]['top1_cos']:.4f}"
                for r in rows if mac[r["id"]]["top1"] != r["top1"]]
        top = sorted(((abs(r["top1_cos"] - mac[r["id"]]["top1_cos"]), r["id"]) for r in same), reverse=True)[:3]
        au = [abs(r["top1_cos"] - mac[r["id"]]["top1_cos"]) for r in same if r["kind"] == "audio"]
        va = [r for r in rows if r["id"].startswith("q") and r["id"].endswith("_a")]
        gaps = sorted(r["top3"][0]["cos"] - r["top3"][1]["cos"] for r in va)
        print(f"## {leg} ({os.path.basename(paths[-1])})")
        print(f"- top-1 different from the Mac GPU run: {'; '.join(diff) or 'none'}")
        print(f"- largest abs delta of the top-1 cos (same photo): "
              + ", ".join(f"{i} {d:.4f}" for d, i in top) + f"; audio median {statistics.median(au):.4f}")
        print(f"- voice A: gap 1st - 2nd min {gaps[0]:.4f}, median {statistics.median(gaps):.4f}; "
              f"lowest cos of a hit {min(r['top1_cos'] for r in va if r['hit1'] == 1):.4f}")
        for r in rows:
            if r["id"] in ("q08_a", "q08_b"):
                t = r["top3"]
                print(f"- {r['id']}: gold rank {r['gold_rank']}, {t[0]['id']} {t[0]['cos']:.4f}, {t[1]['id']} {t[1]['cos']:.4f}")
    shot = os.path.join(a.device_dir, "fail_bundle_screen.png")
    if os.path.exists(shot):
        box = Image.open(shot).convert("RGB").crop((60, 475, 60 + 207, 475 + 84))   # pill_px of the LAYOUT line
        w, h = box.size
        px = [box.getpixel((x, y)) for y in range(h) for x in range(w)]
        red = sum(1 for (r, g, b) in px if abs(r - 229) < 25 and abs(g - 57) < 25 and abs(b - 53) < 25)
        print(f"## fail_bundle_screen.png: {red}/{len(px)} px of the pill box (60, 475, 207 x 84) within 25 of #E53935 "
              f"({red / len(px):.0%})")


if __name__ == "__main__":
    main()
