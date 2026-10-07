#!/usr/bin/env python3
"""One caption image for the 9:16 clip: `You say: "<sentence>"` while the spoken query plays (this ffmpeg has no
drawtext, so make_media.sh overlays this PNG and switches it on with enable='between(t,a,b)').

  K/venv/bin/python -I K/scripts/captions.py "<sentence>" <out.png> [--width 1080] [--size 46]

A transparent 1080 px wide PNG: the text in white, centred, on a dark rounded box (the app's background colour at 88 %
opacity), wrapped to at most two lines. The sentence is the one the clip says (the query's text in queries.json, or for
a mic take the sentence the user was asked to say); nothing on the screen recording itself is changed.
"""
import argparse
import sys

from PIL import Image, ImageDraw, ImageFont

FONTS = ["/System/Library/Fonts/SFNS.ttf", "/System/Library/Fonts/HelveticaNeue.ttc", "/System/Library/Fonts/Helvetica.ttc"]
BG = (14, 17, 22, 224)       # 0x0E1116, the app's background
FG = (230, 232, 235, 255)    # the app's text colour


def font(size):
    for f in FONTS:
        try:
            return ImageFont.truetype(f, size)
        except OSError:
            continue
    return ImageFont.load_default()


def wrap(draw, text, fnt, max_w):
    words, lines, cur = text.split(), [], ""
    for w in words:
        t = (cur + " " + w).strip()
        if draw.textlength(t, font=fnt) <= max_w or not cur:
            cur = t
        else:
            lines.append(cur)
            cur = w
    lines.append(cur)
    return lines


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("sentence")
    ap.add_argument("out")
    ap.add_argument("--width", type=int, default=1080)
    ap.add_argument("--size", type=int, default=46)
    a = ap.parse_args()
    text = f"You say: “{a.sentence.strip()}”"
    fnt = font(a.size)
    probe = ImageDraw.Draw(Image.new("RGBA", (10, 10)))
    pad_x, pad_y, margin = 36, 22, 48
    lines = wrap(probe, text, fnt, a.width - 2 * margin - 2 * pad_x)
    if len(lines) > 2:
        sys.exit(f"caption needs {len(lines)} lines at size {a.size}: shorten it or lower --size")
    asc, desc = fnt.getmetrics()
    line_h = asc + desc + 8
    box_w = int(max(probe.textlength(l, font=fnt) for l in lines)) + 2 * pad_x
    box_h = line_h * len(lines) + 2 * pad_y
    img = Image.new("RGBA", (a.width, box_h), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    x0 = (a.width - box_w) // 2
    d.rounded_rectangle((x0, 0, x0 + box_w, box_h), radius=28, fill=BG)
    for k, l in enumerate(lines):
        w = probe.textlength(l, font=fnt)
        d.text(((a.width - w) / 2, pad_y + k * line_h), l, font=fnt, fill=FG)
    img.save(a.out)
    print(f"{a.out} {img.size[0]}x{img.size[1]} lines={len(lines)} text={text!r}")


if __name__ == "__main__":
    main()
