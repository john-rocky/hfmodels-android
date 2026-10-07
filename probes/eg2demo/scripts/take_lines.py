#!/usr/bin/env python3
"""Lines (a) to (g) of round 3b for one take, from the files only (registered in ROUND3.md and ROUND3b.md before the
takes; (g) came with round 3b).

  K/venv/bin/python -I K/scripts/take_lines.py --raw <raw.mp4> --run <take run.json> --wavs q01_a.wav[,q13_a.wav]
      [--out <dir for the frames it reads>]                                      -> markdown on stdout, JSON with --json

Needs make_media.sh's outputs next to the raw file (<stem>_trim.mp4, <stem>_x1080x1920.mp4, <stem>_audio.json,
<stem>_pill.log). Per line:
  (a) the x1080x1920 clip (and the master) last <= 15.0 s (ffprobe);
  (b) the top-1 photo on screen is the gold photo (q01 -> a01, q13 -> a20): the photo area of each result frame (the
      first frame with the pill green after the clip's red run, RESULT_LAYOUT.photo_px) is matched against the 36 album
      photos (32x32 grey, centre-cropped to the area's aspect; the smallest mean absolute difference wins), and the run
      JSON's top1 must agree;
  (c) the ms on screen = the run JSON's embed_ms: the result frame read with scripts/ocr.swift (macOS Vision), the
      number in "audio → vector N ms" equal to round(embed_ms), the "cos" value equal to round(top1_cos, 2);
  (d) the last frame of the x1080x1920 clip is the result screen of the last clip: pill green, its photo the gold
      photo, its ms the last clip's;
  (e) |placement of the clip - first red-pill frame| <= 200 ms for every clip (<stem>_audio.json, place_audio.py);
  (f) each caption's sentence is the clip's text in K/fixtures/queries.json;
  (g) the third line on screen is LINE3 (and the run JSON's backends give the same line): each clip's result frame, the
      master's last frame and the X clip's last frame read with scripts/ocr.swift; between the subtitle ("Galaxy S26 …")
      and the pill's top there is exactly one row (observations whose tops lie within 12 px form one row), and that row
      with its separators (• ∙ ⋅) read as "·" and its spaces collapsed is LINE3.
"""
import argparse
import glob
import json
import os
import re
import subprocess
import sys

import numpy as np
from PIL import Image

K = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GOLD = {"q01_a": "a01", "q13_a": "a20"}
GREEN = (0x2E, 0x7D, 0x32)
LINE3 = "audio encoder on CPU · backbone and photos on GPU"   # round 3b's third line (the launch's wording)
SEPS = str.maketrans({"•": "·", "∙": "·", "⋅": "·"})          # macOS Vision reads the app's "·" as "•"


def dur(p):
    return float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", p],
                                capture_output=True, text=True, check=True).stdout.strip())


def frame_at_index(video, idx, out):
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", video, "-vf", f"select=eq(n\\,{idx})", "-fps_mode", "passthrough",
                    "-frames:v", "1", out], check=True)
    return out


def last_frame(video, out):
    n = int(subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-count_frames", "-show_entries",
                            "stream=nb_read_frames", "-of", "csv=p=0", video], capture_output=True, text=True,
                           check=True).stdout.strip())
    return frame_at_index(video, n - 1, out), n


def grey32(img):
    return np.asarray(img.convert("L").resize((32, 32), Image.BILINEAR), dtype=np.float64)


def centre_crop(img, aspect):
    w, h = img.size
    if w / h > aspect:
        nw = int(h * aspect)
        return img.crop(((w - nw) // 2, 0, (w - nw) // 2 + nw, h))
    nh = int(w / aspect)
    return img.crop((0, (h - nh) // 2, w, (h - nh) // 2 + nh))


def match_photo(frame_png, box, album):
    """The album id whose centre crop looks most like the frame's photo area, and the two best distances."""
    x, y, w, h = box["left"], box["top"], box["width"], box["height"]
    inset = int(min(w, h) * 0.06)        # clear of the rounded corners
    crop = Image.open(frame_png).convert("RGB").crop((x + inset, y + inset, x + w - inset, y + h - inset))
    a = grey32(crop)
    aspect = (w - 2 * inset) / (h - 2 * inset)
    scores = sorted((float(np.abs(a - grey32(centre_crop(Image.open(p).convert("RGB"), aspect))).mean()), aid)
                    for aid, p in album.items())
    return scores[0][1], scores[0][0], scores[1]


def pill_is_green(frame_png, pill):
    pad = pill.get("pad_left") or 30
    x0, x1 = int(pill["left"] + pad * 0.25), int(pill["left"] + pad * 0.75)
    yc = int(pill["top"] + pill["height"] / 2)
    px = np.asarray(Image.open(frame_png).convert("RGB"), dtype=np.float64)[yc - 6:yc + 6, x0:x1].reshape(-1, 3).mean(axis=0)
    return float(np.linalg.norm(px - np.array(GREEN))) < 60, [round(v) for v in px]


def ocr(pngs):
    """Per image: the recognised strings top to bottom, and the same as (top px, left px, string)."""
    res = subprocess.run(["swift", os.path.join(K, "scripts", "ocr.swift"), *pngs], capture_output=True, text=True)
    out, pos, cur = {}, {}, None
    for line in res.stdout.splitlines():
        if line.startswith("# "):
            cur = line[2:]
            out[cur], pos[cur] = [], []
        elif cur and not line.startswith("!"):
            out[cur].append(line.split(" ", 2)[2] if line.count(" ") >= 2 else line)
            p = line.split(" ", 2)
            if len(p) == 3 and p[0].isdigit() and p[1].isdigit():
                pos[cur].append((int(p[0]), int(p[1]), p[2]))
    return out, pos, res.stderr


def norm(s):
    return " ".join(s.translate(SEPS).split())


def backend_line(rt):
    """MainActivity.backendLineText from the run JSON's runtime: parts on one backend share a phrase."""
    groups = {}
    for name, key in (("audio encoder", "audio_backend"), ("backbone", "backend"), ("photos", "vision_backend")):
        groups.setdefault((rt.get(key) or "?").upper(), []).append(name)
    if len(groups) == 1:
        return "all on " + next(iter(groups))
    return " · ".join(f"{' and '.join(n)} on {b}" for b, n in groups.items())


def third_line(rows, pill_top):
    """The rows between the subtitle and the pill's top, each normalised; (None, why) without a subtitle."""
    sub = [t for t, _, s in rows if "Galaxy" in s or "LiteRT" in s]
    if not sub:
        return None, "no subtitle row"
    lines = []
    for t, left, s in sorted(r for r in rows if sub[0] + 12 < r[0] < pill_top):
        if lines and t - lines[-1][0] <= 12:
            lines[-1][1].append((left, s))
        else:
            lines.append([t, [(left, s)]])
    return [norm(" ".join(s for _, s in sorted(parts))) for _, parts in lines], None


def screen_numbers(lines):
    text = " | ".join(lines)
    ms = re.search(r"vector\s*(\d+)\s*ms", text)
    cos = re.search(r"cos\s*(0[.,]\d\d)", text)
    return (int(ms.group(1)) if ms else None), (cos.group(1).replace(",", ".") if cos else None), text


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--raw", required=True)
    ap.add_argument("--run", required=True)
    ap.add_argument("--wavs", required=True)
    ap.add_argument("--out")
    ap.add_argument("--json")
    a = ap.parse_args()
    stem = os.path.splitext(a.raw)[0]
    outdir = a.out or stem + "_lines"
    os.makedirs(outdir, exist_ok=True)
    run = json.load(open(a.run))
    layout, rlay = run.get("layout") or {}, run.get("result_layout") or {}
    audio = json.load(open(stem + "_audio.json"))
    queries = json.load(open(os.path.join(K, "fixtures", "queries.json")))
    texts = {q["file"]: q["text"] for key in ("queries", "decoys") for q in queries.get(key, [])}
    album = {os.path.basename(p).split("_")[0]: p for p in sorted(glob.glob(os.path.join(K, "fixtures", "album", "*.jpg")))}
    names = [w.strip() for w in a.wavs.split(",") if w.strip()]
    ids = [n[:-4] for n in names]
    rows = {r["id"]: r for r in run.get("rows", []) if r.get("kind") == "audio" and "error" not in r}
    res = {"raw": a.raw, "run": a.run, "clips": ids}

    # (a)
    dx, dm = dur(stem + "_x1080x1920.mp4"), dur(stem + "_trim.mp4")
    res["a"] = {"x1080x1920_s": dx, "master_s": dm, "pass": dx <= 15.0 and dm <= 15.0}

    # Result frames: the first green-pill frame after each clip's red run (raw frame index, from <stem>_pill.log).
    frames = []
    for line in open(stem + "_pill.log"):
        if line.startswith("#"):
            continue
        p = line.split()
        frames.append((int(p[0]), float(p[1]), p[5]))
    result_frames = []
    for c in audio["clips"]:
        red = c.get("red_frame_t")
        start = red if red is not None else c["placed_raw_t"]
        hit = next((f for f in frames if f[1] > start and f[2] == "done"), None)
        result_frames.append(hit)
    pngs = []
    for k, (cid, hit) in enumerate(zip(ids, result_frames)):
        if hit is None:
            pngs.append(None)
            continue
        pngs.append(frame_at_index(a.raw, hit[0], os.path.join(outdir, f"result_{k + 1}_{cid}_raw{hit[1]:.3f}.png")))
    endpng, nend = last_frame(stem + "_x1080x1920.mp4", os.path.join(outdir, "end_x1080x1920.png"))
    endmaster, _ = last_frame(stem + "_trim.mp4", os.path.join(outdir, "end_master.png"))
    texts_ocr, pos_ocr, ocr_err = ocr([p for p in pngs if p] + [endmaster, endpng])
    photo = rlay.get("photo_px")

    # (b) and (c)
    b, c = [], []
    for cid, png in zip(ids, pngs):
        r = rows.get(cid) or {}
        gold = GOLD.get(cid, r.get("gold"))
        if png is None or not photo:
            b.append({"id": cid, "pass": False, "why": "no result frame" if png is None else "no RESULT_LAYOUT.photo_px"})
            c.append({"id": cid, "pass": False, "why": "no result frame"})
            continue
        got, dist, second = match_photo(png, photo, album)
        b.append({"id": cid, "gold": gold, "json_top1": r.get("top1"), "screen_photo": got, "distance": round(dist, 2),
                  "second": [second[1], round(second[0], 2)], "frame": png,
                  "pass": got == gold and r.get("top1") == gold})
        ms, cos, line = screen_numbers(texts_ocr.get(png, []))
        want_ms = round(float(r["embed_ms"])) if r.get("embed_ms") is not None else None
        want_cos = f"{float(r['top1_cos']):.2f}" if r.get("top1_cos") is not None else None
        c.append({"id": cid, "screen_ms": ms, "json_embed_ms": r.get("embed_ms"), "rounded": want_ms, "screen_cos": cos,
                  "json_top1_cos": r.get("top1_cos"), "ocr": line[:200], "pass": ms is not None and ms == want_ms and cos == want_cos})
    res["b"] = {"per_clip": b, "pass": all(x["pass"] for x in b)}
    res["c"] = {"per_clip": c, "pass": all(x["pass"] for x in c)}

    # (d) the last frame of the X clip (and of the master) is the last clip's result screen.
    last_id = ids[-1]
    r = rows.get(last_id) or {}
    gx = pill_is_green(endmaster, layout["pill_px"]) if layout.get("pill_px") else (False, None)
    got = match_photo(endmaster, photo, album)[0] if photo else None
    ms, cos, line = screen_numbers(texts_ocr.get(endmaster, []))
    # The X clip's last frame must show the same screen as the master's (same frame, cropped).
    xm = np.asarray(Image.open(endpng).convert("L"), dtype=np.float64)
    y0 = layout.get("frame_px", {}).get("top", 0)
    y0 -= y0 % 2                         # make_media.sh crops at the even row (ffmpeg's 4:2:0 crop does the same)
    mm = np.asarray(Image.open(endmaster).convert("L"), dtype=np.float64)[y0:y0 + 1920]
    same = float(np.abs(xm - mm).mean()) if xm.shape == mm.shape else None
    res["d"] = {"end_master": endmaster, "end_x": endpng, "x_frames": nend, "pill_green": gx[0], "pill_rgb": gx[1],
                "screen_photo": got, "gold": GOLD.get(last_id), "screen_ms": ms,
                "json_ms_rounded": round(float(r["embed_ms"])) if r.get("embed_ms") is not None else None,
                "x_vs_master_crop_mean_abs": same,
                "pass": bool(gx[0]) and got == GOLD.get(last_id) and ms is not None
                and ms == round(float(r.get("embed_ms", -1))) and same is not None and same < 4.0}

    # (e)
    e = [{"id": x["id"], "line_e_ms": x.get("line_e_ms"), "used": x.get("used")} for x in audio["clips"]]
    res["e"] = {"per_clip": e, "offset_s": audio.get("offset_s"), "pairing": audio.get("pairing"),
                "pass": all(x.get("line_e_pass") for x in audio["clips"])}

    # (f)
    f = [{"id": x["id"], "caption_sentence": x.get("sentence"), "queries_json_text": texts.get(x["id"] + ".wav"),
          "pass": x.get("sentence") is not None and x.get("sentence") == texts.get(x["id"] + ".wav")} for x in audio["clips"]]
    res["f"] = {"per_clip": f, "pass": all(x["pass"] for x in f)}

    # (g) the third line, read from each clip's result frame (raw), the master's and the X clip's last frame.
    line3_json = backend_line(run.get("runtime") or {})
    pill_top = (layout.get("pill_px") or {}).get("top")
    frames_g = [(f"result {cid}", png, 0) for cid, png in zip(ids, pngs) if png]
    frames_g += [("end master", endmaster, 0), ("end x1080x1920", endpng, y0)]
    g = []
    for name, png, dy in frames_g:
        got3, why = third_line(pos_ocr.get(png, []), pill_top - dy) if pill_top is not None else (None, "no pill_px")
        g.append({"frame": name, "png": png, "rows": got3, "why": why,
                  "pass": got3 is not None and len(got3) == 1 and got3[0] == LINE3})
    res["g"] = {"want": LINE3, "run_json_line": line3_json, "per_frame": g,
                "pass": bool(g) and all(x["pass"] for x in g) and line3_json == LINE3}
    res["all_pass"] = all(res[k]["pass"] for k in "abcdefg")
    if ocr_err.strip():
        res["ocr_stderr"] = ocr_err.strip()[-300:]
    if a.json:
        json.dump(res, open(a.json, "w"), indent=1, ensure_ascii=False)
    print(f"# lines (a)-(g): {a.raw}\n")
    print("| line | verdict | numbers |\n|---|---|---|")
    print(f"| (a) clip <= 15.0 s | {'PASS' if res['a']['pass'] else 'FAIL'} | x1080x1920 {dx:.3f} s, master {dm:.3f} s |")
    print(f"| (b) top-1 on screen = gold | {'PASS' if res['b']['pass'] else 'FAIL'} | " + "; ".join(
        f"{x['id']}: screen {x.get('screen_photo')} (d {x.get('distance')}, 2nd {x.get('second')}), json {x.get('json_top1')}, gold {x.get('gold')}" for x in b) + " |")
    print(f"| (c) ms on screen = embed_ms | {'PASS' if res['c']['pass'] else 'FAIL'} | " + "; ".join(
        f"{x['id']}: screen {x.get('screen_ms')} ms / json {x.get('json_embed_ms')} -> {x.get('rounded')}; cos {x.get('screen_cos')} / {x.get('json_top1_cos')}" for x in c) + " |")
    print(f"| (d) last frame = result | {'PASS' if res['d']['pass'] else 'FAIL'} | pill green {res['d']['pill_green']} {res['d']['pill_rgb']}, photo {got} (gold {GOLD.get(last_id)}), ms {ms} (json {res['d']['json_ms_rounded']}), x vs master crop {same} |")
    print(f"| (e) audio vs red pill <= 200 ms | {'PASS' if res['e']['pass'] else 'FAIL'} | " + "; ".join(
        f"{x['id']}: {x['line_e_ms']} ms" for x in e) + f" (offset {audio.get('offset_s')}, {audio.get('pairing')}) |")
    print(f"| (f) caption = queries.json | {'PASS' if res['f']['pass'] else 'FAIL'} | " + "; ".join(
        f"{x['id']}: \"{x['caption_sentence']}\"" for x in f) + " |")
    print(f"| (g) third line = `{LINE3}` | {'PASS' if res['g']['pass'] else 'FAIL'} | " + "; ".join(
        f"{x['frame']}: {x['rows'] if x['rows'] is not None else x['why']}" for x in g) + f"; run JSON gives \"{line3_json}\" |")
    print(f"\nall lines: {'PASS' if res['all_pass'] else 'FAIL'}")


if __name__ == "__main__":
    main()
