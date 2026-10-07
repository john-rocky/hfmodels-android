#!/usr/bin/env python3
"""Stand-in phone runs for a dry run of check_run.py: app run JSONs in the app's format, made from the Mac's scores, and
a few logcat lines per leg, written the way gate.sh names them (<leg>_eg2-demo-<epoch>.json, <leg>_app_logcat.log,
<leg>_error.txt). Not a measurement: the numbers are the Mac's, the phone's milliseconds are invented.

  K/venv/bin/python -I K/scripts/fake_device_runs.py <out dir>

gpu: Mac GPU rows (cos + 0.002); gpu_audio_gpu: the same with q08_a turned into a miss; cpu: Mac CPU rows; npu: an
init error (run JSON with "error", no rows, QNN lines in the logcat); npu_audio_npu: an init error with no run JSON.
"""
import json
import os
import sys

K = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEVICE = {"manufacturer": "samsung", "model": "SM-S942Q", "device": "dry", "soc_manufacturer": "QTI", "soc_model": "SM8850",
          "android": "16", "sdk_int": 36, "build": "dry", "shown_as": "Galaxy S26"}
T = "10-08 03:00:00.100  4242  4242 I"


def rows_from(mac, backend, bump, miss=None):
    rows = []
    for key in ("queries", "decoys", "texts"):
        for m in mac.get(key, []):
            kind = "text" if key == "texts" else "audio"
            top3 = [{"id": t["id"], "cos": round(t["cos"] + bump, 4)} for t in m["top3"]]
            r = {"kind": kind, "id": m["id"], "gold": m.get("gold"), "embed_ms": m["embed_ms_by_backend"][backend] * 3,
                 "rank_ms": 0.2, "dim": 768, "norm": 1.0, "top1": top3[0]["id"], "top1_cos": top3[0]["cos"], "top3": top3,
                 "gold_rank": m.get("gold_rank"), "hit1": m.get("hit1"), "hit3": m.get("hit3")}
            if m["id"] == miss:
                r["top1"], r["top1_cos"], r["gold_rank"], r["hit1"] = top3[1]["id"], top3[1]["cos"], 2, 0
            rows.append(r)
    return rows


def run(backend, vision, audio, bundle, rows, error=None, image_ms=None, init_ms=2100.0):
    d = {"app": "com.mlboydaisuke.eg2demo", "started_epoch_ms": 1791400000000, "device": DEVICE,
         "runtime": {"litertlm_android": "0.18.0", "backend": backend, "vision_backend": vision, "audio_backend": audio},
         "airplane_mode_at_start": False, "thermal_at_start": {"status": 0, "headroom_10s": 0.4},
         "bundle": {"name": bundle, "bytes": 1}, "rows": rows,
         "model_info": {"type": "EMBEDDING", "backends_text": ["CPU", "GPU"], "backends_vision": ["CPU", "GPU"],
                        "backends_audio": ["CPU", "GPU"]}}
    if error:
        d["error"] = error
        return d
    d["engine"] = {"init_ms": init_ms}
    d["index"] = {"n": 36, "files": 36, "total_ms": sum(image_ms or []), "cached": image_ms is None,
                  "per_image_ms_median": sorted(image_ms)[18] if image_ms else None, "per_image_ms": image_ms or []}
    d["thermal_at_done"] = {"status": 0, "headroom_10s": 0.5}
    return d


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    gpu = json.load(open(os.path.join(K, "out", "mac", "scores.json")))
    cpu = json.load(open(os.path.join(K, "out", "mac", "scores_cpu.json")))
    base, npu = "embeddinggemma-2-740m.litertlm", "embeddinggemma-2-740m_Qualcomm_SM8850.litertlm"
    legs = {
        "gpu": run("gpu", "gpu", "cpu", base, rows_from(gpu, "gpu", 0.002), image_ms=[300.0 + k for k in range(36)]),
        "gpu_audio_gpu": run("gpu", "gpu", "gpu", base, rows_from(gpu, "gpu", 0.002, miss="q08_a")),
        "cpu": run("cpu", "cpu", "cpu", base, rows_from(cpu, "cpu", 0.0), image_ms=[1500.0 + k for k in range(36)],
                   init_ms=900.0),
        "npu": run("npu", "npu", "cpu", npu, [], error="init: java.lang.IllegalStateException: dry run"),
    }
    for name, d in legs.items():
        json.dump(d, open(os.path.join(out, f"{name}_eg2-demo-1791400000000.json"), "w"), indent=1)
    lines = {
        "gpu": [f"{T} tflite  : Replacing 1234 out of 1234 node(s) with delegate (TfLiteGpuDelegateV2) node, yielding 1 partitions.",
                f"{T} Eg2Demo: ENGINE_READY init_ms=2100.0 backend=gpu vision_backend=gpu audio_backend=cpu"],
        "gpu_audio_gpu": [f"{T} tflite  : Created TensorFlow Lite delegate for GPU."],
        "cpu": [f"{T} tflite  : Created TensorFlow Lite XNNPACK delegate for CPU."],
        "npu": [f"{T} litert  : Loading dispatch library from /data/app/dry/lib/arm64",
                "10-08 03:00:00.200  4242  4242 E QnnHtp  : (dry run) context binary version mismatch",
                f"10-08 03:00:00.300  4242  4242 E Eg2Demo: ERROR init dry run"],
        "npu_audio_npu": [f"10-08 03:00:00.300  4242  4242 E Eg2Demo: ERROR init dry run (no run JSON pulled)"],
    }
    for name, ls in lines.items():
        open(os.path.join(out, f"{name}_app_logcat.log"), "w").write("\n".join(ls) + "\n")
    open(os.path.join(out, "npu_error.txt"), "w").write(lines["npu"][-1] + "\n")
    open(os.path.join(out, "npu_audio_npu_error.txt"), "w").write(lines["npu_audio_npu"][-1] + "\n")
    open(os.path.join(out, "npu_first_errors.txt"), "w").write("\n".join(lines["npu"][1:]) + "\n")
    print(f"fake phone runs in {out}: {', '.join(legs)} (+ npu_audio_npu without a run JSON)")


if __name__ == "__main__":
    main()
