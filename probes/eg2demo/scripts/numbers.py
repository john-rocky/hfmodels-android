#!/usr/bin/env python3
"""The numbers on the screen of one take, as a markdown table for the post (the post quotes only these).

  K/venv/bin/python -I K/scripts/numbers.py <take run.json> [--id <query id, default: every played clip>] [--clip <mp4>]

Round 3b's screen (MainActivity.kt) shows: the subtitle "<device> · LiteRT-LM <version>", the line "audio encoder on
<audio backend> · backbone and photos on <backend>" (parts on one backend share a phrase: backendLineText, round 3b; round
3 said "photos on <vision> · voice on <audio>"), for each result the chips "audio → vector <embed_ms to the ms> ms" and "cos <top-1
cosine to two places>", the next two photos with their cosines, and the footer "album <n> photos · indexed in <index
total_ms / 1000 to one place> s on <vision backend> · no network needed" (only when this launch indexed the album). This
prints those values from the run JSON in the same rounding, with the JSON key each one comes from, plus the take's
context that is not on screen (and the clip length with --clip). Nothing here is typed by hand.
"""
import argparse
import json
import subprocess


def backend_line(rt):
    """MainActivity.backendLineText from the run JSON's runtime: parts on one backend share a phrase."""
    groups = {}
    for name, key in (("audio encoder", "audio_backend"), ("backbone", "backend"), ("photos", "vision_backend")):
        groups.setdefault((rt.get(key) or "?").upper(), []).append(name)
    if len(groups) == 1:
        return "all on " + next(iter(groups))
    return " · ".join(f"{' and '.join(n)} on {b}" for b, n in groups.items())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run_json")
    ap.add_argument("--id")
    ap.add_argument("--clip")
    a = ap.parse_args()
    run = json.load(open(a.run_json))
    rows = [r for r in run.get("rows", []) if r.get("kind") == "audio" and "error" not in r]
    if a.id:
        rows = [r for r in rows if r["id"] == a.id]
    if not rows:
        raise SystemExit("no audio result row in " + a.run_json)
    dev, rt, idx = run.get("device") or {}, run.get("runtime") or {}, run.get("index") or {}
    vision = (rt.get("vision_backend") or "").upper()
    print(f"# on screen, {a.run_json}\n")
    print("| on screen | value | from |\n|---|---|---|")
    print(f"| subtitle | {dev.get('shown_as')} · LiteRT-LM {rt.get('litertlm_android')} | `device.shown_as`, `runtime.litertlm_android` |")
    print(f"| backends line | {backend_line(rt)} | `runtime.audio_backend`, `runtime.backend`, `runtime.vision_backend` |")
    indexed = not idx.get("cached") and idx.get("n") and idx.get("n") == idx.get("files")
    if indexed:
        print(f"| footer | album {idx.get('n')} photos · indexed in {float(idx['total_ms']) / 1000:.1f} s on {vision} · "
              f"no network needed | `index.total_ms = {idx['total_ms']}`, `index.n`, `runtime.vision_backend` |")
    else:
        print(f"| footer | album {idx.get('n')} photos · no network needed | `index.n` (index from the cache) |")
    for r in rows:
        top3 = r.get("top3") or []
        print(f"| {r['id']}: audio → vector | {float(r['embed_ms']):.0f} ms | `rows[id={r['id']}].embed_ms = {r['embed_ms']}` |")
        print(f"| {r['id']}: top-1 photo | {r.get('top1')} (gold {r.get('gold')}) | `rows[].top1` |")
        print(f"| {r['id']}: cos | {float(r['top1_cos']):.2f} | `rows[].top1_cos = {r['top1_cos']}` |")
        for k, t in enumerate(top3[1:3], start=2):
            print(f"| {r['id']}: next match {k} | {t['id']} cos {float(t['cos']):.2f} | `rows[].top3[{k - 1}]` |")
    eng, b, wu = run.get("engine") or {}, run.get("bundle") or {}, run.get("warmup") or {}
    print("\n| context (not on screen) | value |\n|---|---|")
    print(f"| bundle | {b.get('name')} sha256 {b.get('sha256_12')}… |")
    print(f"| engine init | {eng.get('init_ms')} ms |")
    print(f"| album index | {idx.get('n')} photos, total {idx.get('total_ms')} ms, per photo median {idx.get('per_image_ms_median')} ms, cached {idx.get('cached')}, reindex {idx.get('reindex')} |")
    print(f"| warm-up (1.0 s of silence, dropped) | {wu.get('ms')} ms{(' error ' + wu['error']) if wu.get('error') else ''} |")
    for r in rows:
        print(f"| {r['id']}: clip length / rank / thermal before, after | {r.get('seconds')} s / {r.get('rank_ms')} ms / "
              f"{r.get('thermal_before')}, {r.get('thermal_after')} |")
    print(f"| model | {dev.get('model')}, Android {dev.get('android')}, build {dev.get('build')} |")
    print(f"| airplane mode at start | {run.get('airplane_mode_at_start')} |")
    if a.clip:
        d = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", a.clip],
                           capture_output=True, text=True, check=True).stdout.strip()
        print(f"| clip length ({a.clip.rsplit('/', 1)[-1]}) | {float(d):.3f} s |")


if __name__ == "__main__":
    main()
