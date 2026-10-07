#!/usr/bin/env python3
"""Round 3's NPU leg judged by the launch's rule, and the photos' backend for the shots and the takes.

  K/venv/bin/python -I K/scripts/npu_pick.py --run <npu2 run.json> --logcat <npu2_app_logcat.log> \
      --gpu <round 2 gpu run.json> --cpu <round 2 cpu run.json> --out <npu2_summary.txt>      -> prints npu or gpu

"Loaded on the NPU" needs all three (launch, 2026-10-08): no `QnnDsp <E>` line in the app's logcat, 103 result rows
without an error (43 clips + 60 texts), and the NPU's timings unlike the other backends' (a run that silently fell back
would time like the GPU or the CPU): the per-photo median of the index more than 15 % away from both round 2's GPU and
CPU medians, and the text median more than 15 % away from the GPU's. The photos go on the NPU only when it loaded AND
its index total is shorter than the GPU's (round 2's gpu leg, cache empty, same index loop); otherwise on the GPU.
The summary's first line is the verdict; the rest are the numbers and the evidence lines (DispatchDelegate, QNN, the
first failure line when it did not load).
"""
import argparse
import json
import os
import re
import statistics


def med(v):
    return statistics.median(v) if v else None


def rows_of(run, kind):
    return [r for r in run.get("rows", []) if r.get("kind") == kind and "error" not in r and r.get("top1") not in (None, "-")]


def timings(run):
    idx = run.get("index") or {}
    return {
        "photo_median_ms": idx.get("per_image_ms_median"),
        "index_total_ms": idx.get("total_ms"),
        "index_n": idx.get("n"),
        "index_cached": idx.get("cached"),
        "text_median_ms": med([float(r["embed_ms"]) for r in rows_of(run, "text")]),
        "audio_median_ms": med([float(r["embed_ms"]) for r in rows_of(run, "audio")]),
        "init_ms": (run.get("engine") or {}).get("init_ms"),
    }


def far(a, b, frac=0.15):
    return a is not None and b is not None and abs(a - b) > frac * b


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", default="")
    ap.add_argument("--logcat", required=True)
    ap.add_argument("--gpu", required=True)
    ap.add_argument("--cpu", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    run = json.load(open(a.run)) if a.run and os.path.exists(a.run) else {}
    gpu, cpu = json.load(open(a.gpu)), json.load(open(a.cpu))
    lines = open(a.logcat, errors="replace").read().splitlines() if os.path.exists(a.logcat) else []
    app = re.compile(r"\sEg2Demo\s*:")
    runtime = [l for l in lines if not app.search(l)]
    qnn_err = [l for l in runtime if "QnnDsp <E>" in l]
    dispatch = [l for l in runtime if "DispatchDelegate" in l]
    qnn = [l for l in runtime if re.search(r"qnn|htp", l, re.I)]
    errors = [l for l in lines if re.search(r"^\S+ \S+\s+\d+\s+\d+ [EF] ", l)]
    ok_rows = len(rows_of(run, "audio")) + len(rows_of(run, "text"))
    err_rows = sum(1 for r in run.get("rows", []) if "error" in r)
    t, tg, tc = timings(run), timings(gpu), timings(cpu)
    differs = far(t["photo_median_ms"], tg["photo_median_ms"]) and far(t["photo_median_ms"], tc["photo_median_ms"]) \
        and far(t["text_median_ms"], tg["text_median_ms"])
    loaded = bool(run) and not qnn_err and ok_rows == 103 and err_rows == 0 and differs
    faster = loaded and t["index_total_ms"] is not None and tg["index_total_ms"] is not None \
        and t["index_total_ms"] < tg["index_total_ms"]
    pick = "npu" if faster else "gpu"
    why = []
    if not run:
        why.append("no run JSON")
    if qnn_err:
        why.append(f"{len(qnn_err)} QnnDsp <E> lines")
    if ok_rows != 103 or err_rows:
        why.append(f"{ok_rows} result rows, {err_rows} rows with an error (103 and 0 wanted)")
    if run and not differs:
        why.append("timings not unlike the GPU's / CPU's")
    verdict = "LOADED (NPU)" if loaded else "NOT LOADED (未達): " + "; ".join(why)
    with open(a.out, "w") as f:
        f.write(f"{verdict}\n")
        f.write(f"photos backend: {pick} ({'NPU loaded and its index total is shorter than the GPU' if faster else ('NPU loaded but its index total is not shorter than the GPU' if loaded else 'the NPU did not load')})\n")
        f.write(f"run json: {a.run or '-'}\n")
        f.write("| leg | photo median ms | index total ms (n, cached) | text median ms | audio median ms | init ms |\n|---|---:|---:|---:|---:|---:|\n")
        for name, x in (("npu2 (round 3)", t), ("gpu (round 2)", tg), ("cpu (round 2)", tc)):
            f.write(f"| {name} | {x['photo_median_ms']} | {x['index_total_ms']} ({x['index_n']}, {x['index_cached']}) | "
                    f"{x['text_median_ms']} | {x['audio_median_ms']} | {x['init_ms']} |\n")
        f.write(f"result rows without error: {ok_rows}; rows with an error: {err_rows}\n")
        f.write(f"QnnDsp <E> lines: {len(qnn_err)}; DispatchDelegate lines: {len(dispatch)}; QNN / HTP lines: {len(qnn)}\n")
        for l in dispatch[:8]:
            f.write(f"  dispatch: {l[:240]}\n")
        for l in qnn[:12]:
            f.write(f"  qnn: {l[:240]}\n")
        if not loaded:
            f.write(f"first failure line: {(qnn_err or errors or ['-'])[0][:300]}\n")
            if run.get("error"):
                f.write(f"run error: {run['error'][:300]}\n")
    print(pick)


if __name__ == "__main__":
    main()
