#!/usr/bin/env python3
"""A fake round-3 take on the Mac, to run make_media.sh, place_audio.py, take_lines.py and numbers.py before the phone.

  K/venv/bin/python -I K/scripts/fake_take.py <out dir>

Writes <out>/eg2_demo_s26_fake_1791400000.mp4 (1080x2340, variable frame rate like screenrecord: one frame per screen
change), take_fake.log (REC_START / REC_SHELL_EPOCH lines), take_fake_demo.log (the app's PILL / PLAY_START / RESULT
lines) and take_fake_eg2-demo-1791400000000.json (layout, result_layout, rows) for a two-clip take: READY, q01_a played
(red pill), EMBEDDING (blue), DONE with a01, q13_a played, EMBEDDING, DONE with a20. Each pill change reaches the video
25 ms after the app logged it (DONE 41 ms: a heavier frame), so the clock fit and line (e) have something to absorb.
The screens are drawn with PIL in the app's colours and sizes (not pixel-exact copies of the app). The third line is
round 3b's (env FAKE_LINE3 replaces it: the negative test of line (g)).
"""
import glob
import json
import os
import subprocess
import sys

from PIL import Image, ImageDraw, ImageFont

K = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.abspath(sys.argv[1])
os.makedirs(OUT, exist_ok=True)
W, H = 1080, 2340
BG, TXT, SUB, ACC, BTN = (14, 17, 22), (230, 232, 235), (138, 145, 156), (138, 180, 248), (26, 31, 39)
PILL = {"idle": (0x5F, 0x63, 0x68), "live": (0xE5, 0x39, 0x35), "work": (0x15, 0x65, 0xC0), "done": (0x2E, 0x7D, 0x32)}
RGB = {"idle": "#5F6368", "live": "#E53935", "work": "#1565C0", "done": "#2E7D32"}
E0 = 1791400000000      # the phone clock (epoch ms) at video t = 0
FRAME = {"left": 0, "top": 132, "width": 1080, "height": 1920}
PILL_PX = {"left": 60, "top": 386, "width": 230, "height": 84, "pad_left": 36}   # round 3b: the app's top (round 3 LAYOUT); 330 hid the third line
PHOTO = {"left": 60, "top": 500, "width": 960, "height": 579}      # round 3 RESULT_LAYOUT
LEVEL = {"left": 60, "top": 1606, "width": 960, "height": 18}
MIC = {"left": 60, "top": 1716, "width": 960, "height": 192}
album = {os.path.basename(p).split("_")[0]: p for p in sorted(glob.glob(os.path.join(K, "fixtures", "album", "*.jpg")))}


def font(size, bold=False):
    for f in (["/System/Library/Fonts/SFNS.ttf"] if not bold else ["/System/Library/Fonts/SFNS.ttf"]):
        try:
            fnt = ImageFont.truetype(f, size)
            if bold:
                try:
                    fnt.set_variation_by_name("Bold")
                except Exception:
                    pass
            return fnt
        except OSError:
            continue
    return ImageFont.load_default()


def cover(img, w, h):
    iw, ih = img.size
    s = max(w / iw, h / ih)
    img = img.resize((int(iw * s) + 1, int(ih * s) + 1))
    x, y = (img.size[0] - w) // 2, (img.size[1] - h) // 2
    return img.crop((x, y, x + w, y + h))


def screen(pill, text, result=None, level=0.0):
    im = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(im)
    d.text((30, 40), "3:42", font=font(40), fill=TXT)
    d.text((60, 156), "EmbeddingGemma 2 740M", font=font(72, True), fill=TXT)
    d.text((60, 252), "Galaxy S26 · LiteRT-LM 0.18.0", font=font(45), fill=SUB)
    d.text((60, 308), os.environ.get("FAKE_LINE3", "audio encoder on CPU · backbone and photos on GPU"), font=font(39), fill=SUB)
    p = PILL_PX
    d.rounded_rectangle((p["left"], p["top"], p["left"] + p["width"], p["top"] + p["height"]), radius=42, fill=PILL[pill])
    d.text((p["left"] + p["pad_left"], p["top"] + 18), text, font=font(39, True), fill=TXT)
    if result is None:
        cell, gap = 152, 9
        for k, aid in enumerate(sorted(album)[:36]):
            r, c = divmod(k, 6)
            th = cover(Image.open(album[aid]).convert("RGB"), cell, cell)
            im.paste(th, (60 + c * (cell + gap), PHOTO["top"] + r * (cell + gap)))
    else:
        aid, ms, cos = result
        ph = cover(Image.open(album[aid]).convert("RGB"), PHOTO["width"], PHOTO["height"])
        im.paste(ph, (PHOTO["left"], PHOTO["top"]))
        d.text((60, 1090), f"audio file · {'q01_a' if aid == 'a01' else 'q13_a'} · 2.6 s", font=font(42), fill=SUB)
        d.rounded_rectangle((60, 1160, 660, 1260), radius=30, fill=BTN)
        d.text((84, 1180), f"audio → vector {ms} ms", font=font(54, True), fill=TXT)
        d.rounded_rectangle((690, 1160, 950, 1260), radius=30, fill=BTN)
        d.text((714, 1180), f"cos {cos}", font=font(54, True), fill=ACC)
        d.text((60, 1290), "next matches", font=font(36), fill=SUB)
    l = LEVEL
    d.rounded_rectangle((l["left"], l["top"], l["left"] + l["width"], l["top"] + l["height"]), radius=9, fill=BTN)
    if level > 0:
        d.rounded_rectangle((l["left"], l["top"], l["left"] + int(l["width"] * level), l["top"] + l["height"]), radius=9,
                            fill=PILL["live"])
    d.text((150, 1650), "Hold the button and say what is in the photo", font=font(42), fill=SUB)
    m = MIC
    d.rounded_rectangle((m["left"], m["top"], m["left"] + m["width"], m["top"] + m["height"]), radius=96, fill=BTN,
                        outline=PILL["live"], width=6)
    d.text((400, 1780), "Hold to talk", font=font(60, True), fill=TXT)
    d.text((140, 1950), "album 36 photos · indexed in 9.1 s on GPU", font=font(36), fill=SUB)
    d.text((390, 1994), "no network needed", font=font(36), fill=SUB)
    return im


# (video t of the change, pill, pill text, result, level, extra latency of this change in s)
timeline = [
    (0.000, "idle", "READY", None, 0.0, None),
    (2.525, "live", "● audio file", None, 0.4, 0.025),
    (5.125, "work", "EMBEDDING", None, 0.0, 0.025),
    (5.225, "done", "DONE", ("a01", 104, "0.72"), 0.0, 0.041),
    (7.725, "live", "● audio file", ("a01", 104, "0.72"), 0.4, 0.025),
    (9.845, "work", "EMBEDDING", ("a01", 104, "0.72"), 0.0, 0.025),
    (9.945, "done", "DONE", ("a20", 101, "0.71"), 0.0, 0.041),
]
lst = []
for k, (t, pill, text, res, lv, lat) in enumerate(timeline):
    png = os.path.join(OUT, f"fake_screen_{k}.png")
    screen(pill, text, res, lv).save(png)
    nxt = timeline[k + 1][0] if k + 1 < len(timeline) else t + 0.05
    lst.append(f"file '{png}'\nduration {nxt - t:.3f}\n")
lst.append(f"file '{os.path.join(OUT, f'fake_screen_{len(timeline) - 1}.png')}'\n")
open(os.path.join(OUT, "fake_concat.txt"), "w").write("".join(lst))
raw = os.path.join(OUT, "eg2_demo_s26_fake_1791400000.mp4")
subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", os.path.join(OUT, "fake_concat.txt"),
                "-fps_mode", "vfr", "-enc_time_base", "1:1000", "-c:v", "libx264", "-pix_fmt", "yuv420p", raw], check=True)

demo, rows = [], []
tag = "10-08 04:30:00.000  4242  4242 I Eg2Demo :"
for t, pill, text, res, lv, lat in timeline:
    if lat is None:
        demo.append(f"{tag} PILL rgb={RGB[pill]} epoch_ms={E0 - 3000} text={text}")
        continue
    ep = E0 + round((t - lat) * 1000)
    demo.append(f"{tag} PILL rgb={RGB[pill]} epoch_ms={ep} text={text}")
    if pill == "live":
        demo.append(f"{tag} PLAY_START epoch_ms={ep + 1} id={'q01_a' if res is None else 'q13_a'}")
rows.append({"kind": "audio", "id": "q01_a", "source": "file", "gold": "a01", "seconds": 2.582, "embed_ms": 104.2,
             "rank_ms": 1.4, "top1": "a01", "top1_cos": 0.7235,
             "top3": [{"id": "a01", "cos": 0.7235}, {"id": "a35", "cos": 0.6012}, {"id": "a34", "cos": 0.5933}]})
rows.append({"kind": "audio", "id": "q13_a", "source": "file", "gold": "a20", "seconds": 2.106, "embed_ms": 100.8,
             "rank_ms": 1.4, "top1": "a20", "top1_cos": 0.7123,
             "top3": [{"id": "a20", "cos": 0.7123}, {"id": "a21", "cos": 0.6302}, {"id": "a03", "cos": 0.6111}]})
for r in rows:
    demo.append(f"{tag} RESULT kind=audio id={r['id']} seconds={r['seconds']} embed_ms={r['embed_ms']} top1={r['top1']}")
open(os.path.join(OUT, "take_fake_demo.log"), "w").write("\n".join(demo) + "\n")
open(os.path.join(OUT, "take_fake.log"), "w").write(
    f"# fake take\n## REC_START before_epoch_ms={E0 - 400}\n## REC_SHELL_EPOCH {E0 - 150}\n## REC_START after_epoch_ms={E0 + 1700}\n")
layout = {"screen_px": [W, H], "density": 3.0, "pill_px": PILL_PX, "level_px": LEVEL, "mic_px": MIC, "frame_px": FRAME,
          "status_bar_px": 132, "title_top_px": 156, "footer_bottom_px": 2028, "title_to_footer_px": 1872}
run = {"device": {"shown_as": "Galaxy S26", "model": "SM-S942Q", "android": "16", "build": "fake"},
       "runtime": {"litertlm_android": "0.18.0", "backend": "gpu", "vision_backend": "gpu", "audio_backend": "cpu", "reindex": True},
       "index": {"n": 36, "files": 36, "total_ms": 9112.4, "per_image_ms_median": 240.1, "cached": False, "reindex": True},
       "engine": {"init_ms": 2301.0}, "bundle": {"name": "embeddinggemma-2-740m.litertlm", "sha256_12": "e7a8a2204b91"},
       "warmup": {"ms": 180.2, "seconds": 1.0}, "layout": layout,
       "result_layout": {"photo_px": PHOTO}, "rows": rows, "airplane_mode_at_start": False}
json.dump(run, open(os.path.join(OUT, "take_fake_eg2-demo-1791400000000.json"), "w"), indent=1)
print(raw)
