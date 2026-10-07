#!/usr/bin/env python3
"""The phone's runs against the Mac's: app run JSONs (gate.sh legs) held against out/mac/scores.json (GPU) and
scores_cpu.json (CPU).

  K/venv/bin/python -I K/scripts/check_run.py --device-dir K/out/device [--legs gpu,npu,...] [--json <out.json>]
  K/venv/bin/python -I K/scripts/check_run.py <app run.json> [--logcat <leg>_app_logcat.log] [--json <out.json>]
  common: [--mac K/out/mac/scores.json] [--mac-cpu K/out/mac/scores_cpu.json]

--device-dir reads, per leg, the newest <leg>_eg2-demo-*.json and <leg>_app_logcat.log that gate.sh pulled (a leg with no
run JSON is listed with its ERROR line from <leg>_error.txt). Per leg: the runtime the app asked for, init ms, the album
index (1 photo's ms median), hit1 / hit3 per group (voice A, voice B, decoys, text per prefix), the phone's top-1 against
the Mac's and |delta| of the top-1 cosine where both picked the same photo (vs the Mac's GPU run and its CPU run), the
embed_ms of audio and text (median / p90), and from the app's logcat the runtime's backend lines (Replacing / delegate /
OpenCL / GPU / NPU / QNN / HTP / dispatch / XNNPACK / accelerator / Qualcomm: count and first line each; the app's own
Eg2Demo lines are left out, they only repeat what it asked for). The pre-registered line is judged on the gpu leg: voice
A top-1 >= 16/20 and top-3 >= 19/20. Markdown on stdout; --json writes the same numbers. The Mac's milliseconds are not
compared (a different machine, a busy Mac).
"""
import argparse
import glob
import json
import os
import re
import statistics

K = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PATTERNS = ["Replacing", "delegate", "opencl", "gpu", "npu", "qnn", "htp", "dispatch", "xnnpack", "accelerat", "qualcomm"]
LEGS = ["gpu", "npu", "npu_audio_npu", "gpu_audio_gpu", "cpu"]
RULE = "audio -> photo, voice A, top-1 >= 16/20 and top-3 >= 19/20"


def pct(v, q):
    v = sorted(v)
    return v[min(len(v) - 1, int(round(q * (len(v) - 1))))] if v else None


def group_of(row):
    i = row["id"]
    if row.get("kind") == "text" or "_t_" in i:
        return "text_" + i.split("_t_")[-1]
    if i.startswith("mic-"):
        return "mic"
    if i.startswith("d"):
        return "decoys"
    if i.endswith("_a2"):
        return "audio_voice_a_reworded"
    return "audio_voice_" + i.rsplit("_", 1)[-1]


def ms_stats(v):
    if not v:
        return None
    return {"n": len(v), "median": round(statistics.median(v), 1), "p90": round(pct(v, 0.9), 1),
            "min": round(min(v), 1), "max": round(max(v), 1)}


def mac_rows(path):
    if not path or not os.path.exists(path):
        return {}
    mac = json.load(open(path))
    return {r["id"]: r for key in ("queries", "decoys", "texts") for r in mac.get(key, [])}


def against(rows, ref):
    """Rows whose top-1 equals the reference's, and |delta| of the top-1 cosine on those."""
    same, deltas, n = 0, [], 0
    for r in rows:
        m = ref.get(r["id"])
        if m is None or r.get("top1") in (None, "-"):
            continue
        n += 1
        if m["top1"] == r["top1"]:
            same += 1
            deltas.append(abs(float(r["top1_cos"]) - float(m["top1_cos"])))
    return {"top1_same": same, "n": n, "max_abs_delta": round(max(deltas), 4) if deltas else None,
            "mean_abs_delta": round(statistics.mean(deltas), 4) if deltas else None}


def backend_lines(path):
    if not path or not os.path.exists(path):
        return None
    # logcat pads a tag shorter than 8 characters: the app's lines read "Eg2Demo : ..." on the phone.
    lines = [l for l in open(path, errors="replace").read().splitlines() if not re.search(r"\sEg2Demo\s*:", l)]
    out = []
    for p in PATTERNS:
        hit = [l for l in lines if re.search(p, l, re.I)]
        out.append({"pattern": p, "count": len(hit), "first": hit[0][:240] if hit else ""})
    return out


def leg_stats(run, logcat, mac, mac_cpu):
    all_rows = run.get("rows", [])
    rows = [r for r in all_rows if "error" not in r and r.get("top1") not in (None, "-")]
    errors = [{"id": r.get("id"), "error": r.get("error")} for r in all_rows if "error" in r]
    groups = {}
    for r in rows:
        g = groups.setdefault(group_of(r), {"n": 0, "hit1": 0, "hit3": 0, "top1_cos_max": None})
        g["n"] += 1
        if r.get("hit1") is not None:
            g["hit1"] += int(r["hit1"])
            g["hit3"] += int(r["hit3"])
        c = float(r["top1_cos"])
        g["top1_cos_max"] = c if g["top1_cos_max"] is None else max(g["top1_cos_max"], c)
    misses = [{"id": r["id"], "gold": r.get("gold"), "gold_rank": r.get("gold_rank"), "top1": r["top1"],
               "top1_cos": round(float(r["top1_cos"]), 4)}
              for r in rows if r.get("hit1") == 0 and group_of(r).startswith("audio_voice")]
    ms = {k: ms_stats([float(r["embed_ms"]) for r in rows if r.get("kind") == k]) for k in ("audio", "text")}
    va = groups.get("audio_voice_a", {"n": 0, "hit1": 0, "hit3": 0})
    line = {"rule": RULE, "n": va["n"], "top1": va["hit1"], "top3": va["hit3"],
            "pass": va["n"] == 20 and va["hit1"] >= 16 and va["hit3"] >= 19}
    idx = run.get("index") or {}
    per_image = [float(x) for x in idx.get("per_image_ms") or []]
    mi = run.get("model_info") or {}
    return {
        "device": run.get("device"), "runtime": run.get("runtime"), "bundle": run.get("bundle"),
        "run_error": run.get("error"), "engine": run.get("engine"),
        "model_info": {k: v for k, v in mi.items() if k.startswith(("backends_", "soc_", "type", "error"))},
        "index": {"n": idx.get("n"), "cached": idx.get("cached"), "total_ms": idx.get("total_ms"),
                  "per_image_ms": ms_stats(per_image)},
        "rows": len(rows), "errors": errors, "groups": groups, "audio_misses": misses,
        "vs_mac_gpu": against(rows, mac), "vs_mac_cpu": against(rows, mac_cpu),
        "embed_ms": ms, "line": line, "backend_lines": backend_lines(logcat),
        "thermal": {"start": run.get("thermal_at_start"), "done": run.get("thermal_at_done")},
        "airplane_mode_at_start": run.get("airplane_mode_at_start"),
    }


def fmt(v, nd=1):
    return "-" if v is None else (f"{v:.{nd}f}" if isinstance(v, float) else str(v))


def hits(g, key):
    x = g.get(key)
    return f"{x['hit1']}/{x['n']} · {x['hit3']}/{x['n']}" if x else "-"


def summary_table(legs):
    print("| leg | asked backend (vision / audio) | init ms | 1 photo ms median (n) | audio embed_ms median / p90 (n) "
          "| text embed_ms median (n) | voice A hit1 · hit3 | voice B hit1 · hit3 | text card · devsite · none hit1 "
          "| top-1 = Mac GPU | max abs Δcos vs Mac GPU · CPU | decoy max cos | QNN · HTP · dispatch lines | error |")
    print("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|")
    for name, s in legs:
        if s is None:
            continue
        rt = s.get("runtime") or {}
        eng = s.get("engine") or {}
        idx = s.get("index") or {}
        pim = idx.get("per_image_ms") or {}
        a = (s.get("embed_ms") or {}).get("audio") or {}
        t = (s.get("embed_ms") or {}).get("text") or {}
        g = s.get("groups") or {}
        text = " · ".join(f"{g[k]['hit1']}/{g[k]['n']}" if k in g else "-"
                          for k in ("text_card", "text_devsite", "text_none"))
        vg, vc = s.get("vs_mac_gpu") or {}, s.get("vs_mac_cpu") or {}
        bl = {b["pattern"]: b["count"] for b in (s.get("backend_lines") or [])}
        err = s.get("run_error") or s.get("error_line") or ""
        if s.get("errors"):
            err = (err + f" {len(s['errors'])} row errors").strip()
        image = f"{fmt(pim.get('median'))} ({pim.get('n')})" if pim else ("cached" if idx.get("cached") else "-")
        print(f"| {name} | {rt.get('backend', '-')} ({rt.get('vision_backend', '-')} / {rt.get('audio_backend', '-')}) "
              f"| {fmt(eng.get('init_ms'))} | {image} "
              f"| {fmt(a.get('median'))} / {fmt(a.get('p90'))} ({a.get('n', 0)}) | {fmt(t.get('median'))} ({t.get('n', 0)}) "
              f"| {hits(g, 'audio_voice_a')} | {hits(g, 'audio_voice_b')} | {text} "
              f"| {vg.get('top1_same', 0)}/{vg.get('n', 0)} | {fmt(vg.get('max_abs_delta'), 4)} · {fmt(vc.get('max_abs_delta'), 4)} "
              f"| {fmt((g.get('decoys') or {}).get('top1_cos_max'), 4)} "
              f"| {bl.get('qnn', '-')} · {bl.get('htp', '-')} · {bl.get('dispatch', '-')} | {err[:160]} |")


def detail(name, s):
    rt = s.get("runtime") or {}
    dev = s.get("device") or {}
    print(f"\n### {name}: {dev.get('shown_as')} ({dev.get('model')}, {dev.get('soc_model')}, Android {dev.get('android')}), "
          f"LiteRT-LM {rt.get('litertlm_android')}, bundle {(s.get('bundle') or {}).get('name')}")
    if s.get("run_error") or s.get("error_line"):
        print(f"\nerror: `{s.get('run_error') or s.get('error_line')}`")
    if s.get("first_errors"):
        print("\nfirst E/F lines of the app's logcat:\n")
        for l in s["first_errors"][:8]:
            print(f"    {l[:240]}")
    if s.get("model_info"):
        print(f"\nmodel info: `{json.dumps(s['model_info'], ensure_ascii=False)}`")
    g = s.get("groups") or {}
    if g:
        print("\n| group | n | hit1 | hit3 | top-1 cos max |\n|---|---:|---:|---:|---:|")
        for k in sorted(g):
            print(f"| {k} | {g[k]['n']} | {g[k]['hit1']} | {g[k]['hit3']} | {fmt(g[k]['top1_cos_max'], 4)} |")
    if s.get("audio_misses"):
        print("\naudio misses: " + "; ".join(f"{m['id']} gold {m['gold']} rank {m['gold_rank']}, top-1 {m['top1']} "
                                             f"{m['top1_cos']}" for m in s["audio_misses"]))
    vg, vc = s.get("vs_mac_gpu") or {}, s.get("vs_mac_cpu") or {}
    if vg.get("n"):
        print(f"\ntop-1 = Mac GPU {vg['top1_same']}/{vg['n']} (abs Δ top-1 cos max {vg['max_abs_delta']}, mean "
              f"{vg['mean_abs_delta']}); = Mac CPU {vc.get('top1_same')}/{vc.get('n')} (max {vc.get('max_abs_delta')}, "
              f"mean {vc.get('mean_abs_delta')}); row errors {len(s.get('errors') or [])}")
    th = s.get("thermal") or {}
    print(f"\nthermal at start {th.get('start')}, at done {th.get('done')}; airplane mode at start "
          f"{s.get('airplane_mode_at_start')}")
    if s.get("backend_lines"):
        print("\n| pattern | lines | first (the runtime's, not the app's) |\n|---|---:|---|")
        for b in s["backend_lines"]:
            print(f"| {b['pattern']} | {b['count']} | `{b['first']}` |")


def newest(pattern):
    hits = sorted(glob.glob(pattern))
    return hits[-1] if hits else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run_json", nargs="?")
    ap.add_argument("--device-dir")
    ap.add_argument("--legs", default=",".join(LEGS))
    ap.add_argument("--mac", default=os.path.join(K, "out", "mac", "scores.json"))
    ap.add_argument("--mac-cpu", default=os.path.join(K, "out", "mac", "scores_cpu.json"))
    ap.add_argument("--logcat")
    ap.add_argument("--json")
    a = ap.parse_args()
    mac, mac_cpu = mac_rows(a.mac), mac_rows(a.mac_cpu)
    legs = []
    if a.device_dir:
        for name in [x for x in a.legs.split(",") if x]:
            jp = newest(os.path.join(a.device_dir, f"{name}_eg2-demo-*.json"))
            lp = os.path.join(a.device_dir, f"{name}_app_logcat.log")
            ep = os.path.join(a.device_dir, f"{name}_error.txt")
            fp = os.path.join(a.device_dir, f"{name}_first_errors.txt")
            if jp is None and not os.path.exists(lp):
                continue
            s = leg_stats(json.load(open(jp)), lp, mac, mac_cpu) if jp else {"backend_lines": backend_lines(lp)}
            s["run_json"] = jp
            if os.path.exists(ep):
                s["error_line"] = open(ep, errors="replace").read().strip()
            if os.path.exists(fp):
                s["first_errors"] = open(fp, errors="replace").read().splitlines()
            legs.append((name, s))
    elif a.run_json:
        s = leg_stats(json.load(open(a.run_json)), a.logcat, mac, mac_cpu)
        s["run_json"] = a.run_json
        legs.append((os.path.basename(a.run_json), s))
    else:
        ap.error("a run JSON or --device-dir")
    if a.json:
        json.dump({name: s for name, s in legs}, open(a.json, "w"), indent=1, ensure_ascii=False)
    print(f"# check_run: {len(legs)} run(s) against {os.path.relpath(a.mac, K)} and {os.path.relpath(a.mac_cpu, K)}\n")
    summary_table(legs)
    for name, s in legs:
        if name == "gpu" or not a.device_dir:
            ln = s.get("line")
            if ln:
                print(f"\nline ({name}): voice A top-1 {ln['top1']}/{ln['n']}, top-3 {ln['top3']}/{ln['n']} -> "
                      f"{'PASS' if ln['pass'] else 'FAIL'} ({RULE})")
    for name, s in legs:
        detail(name, s)


if __name__ == "__main__":
    main()
