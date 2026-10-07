#!/usr/bin/env python3
"""Mac scoring of the demo's line: spoken query -> album photo with EmbeddingGemma 2 740M (LiteRT-LM 0.18.0 Python).

  ~/code/standup/tools/quiet/quiet_wait.py -- K/venv/bin/python -I K/scripts/embed_mac.py [--backend gpu|cpu]
      [--also-cpu] [--tag <name>]

One engine on the base bundle (models/PATHS.json): backend GPU when it initializes, else CPU; the vision encoder on the
same backend; the audio encoder on the CPU (as the app and the Gallery's Instant Media Search do). One cache dir,
out/mac/cache/ (deleted at the end of the round). Every embedding is asked for with normalize=True and output_size=768;
images and audio get no prefix (model card: "Prefixes apply to text only").
  1. album: every fixtures/album/*.jpg as Content.ImageFile -> out/mac/album_emb<tag>.npy (+ ids), L2 norms min / max;
  2. queries: every wav in fixtures/queries.json (voice A, voice B, rewording A2 when present, decoys) as
     Content.AudioFile;
  3. the 20 sentences as text, three ways: the model card's query prefix `task: search result | query: `, the DevSite
     prefix `task: search query | text: `, and no prefix;
  4. cosine (dot of unit vectors) against the album -> out/mac/scores<tag>.json (per query: top-3 ids and cos, gold
     rank, hit1 / hit3) and out/mac/summary<tag>.json (top-1 / top-3 counts per voice and per text prefix, the decoys'
     best cos, the lowest cos among hits and, for misses, how far the gold photo was below the top-1).
Also: the norm of one image, one clip and one text embedded with normalize=None (the C++ default), the wall ms of
every call (a busy Mac: reference only, never a card or post number), and with --also-cpu the same inputs on a CPU
engine with cos(GPU, CPU) and max|delta| per input (the model card warns that float16 activations degrade the model).

The pre-registered line (ROUND1.md, written before this ran): audio -> photo, voice A, top-1 >= 16/20 and
top-3 >= 19/20 -> candidate A goes on (round 2). Voice B is a reference table.
"""
import argparse
import json
import os
import platform
import shutil
import sys
import time

import numpy as np

import litert_lm
from litert_lm import Backend, Content, EmbeddingEngine, EmbeddingOptions

K = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(K, "out", "mac")
CACHE = os.path.join(OUT, "cache")
ALBUM_DIR = os.path.join(K, "fixtures", "album")
QDIR = os.path.join(K, "fixtures", "queries")
DIM = 768
PREFIXES = {
    "card": "task: search result | query: ",
    "devsite": "task: search query | text: ",
    "none": "",
}
OPTS = EmbeddingOptions(normalize=True, output_size=DIM)
LINE = {"voice": "a", "top1_min": 16, "top3_min": 19, "n": 20}


def bundle_path():
    rows = json.load(open(os.path.join(K, "models", "PATHS.json")))["files"]
    row = next(r for r in rows if r["file"] == "embeddinggemma-2-740m.litertlm")
    return row["path"], row["sha256"]


def make_engine(kind, model):
    main = Backend.GPU() if kind == "gpu" else Backend.CPU()
    vision = Backend.GPU() if kind == "gpu" else Backend.CPU()
    t0 = time.perf_counter()
    eng = EmbeddingEngine(model, backend=main, vision_backend=vision, audio_backend=Backend.CPU(), cache_dir=CACHE)
    return eng, (time.perf_counter() - t0) * 1000


def embed(eng, content, opts=OPTS):
    t0 = time.perf_counter()
    v = np.asarray(eng.compute_embedding(content, opts).embedding, dtype=np.float64)
    return v, (time.perf_counter() - t0) * 1000


def rank(qv, album_m, ids):
    cos = album_m @ qv
    order = np.argsort(-cos)
    return cos, order


def score_rows(qrows, qvecs, album_m, ids):
    out = []
    for q, v in zip(qrows, qvecs):
        cos, order = rank(v, album_m, ids)
        top3 = [{"id": ids[i], "cos": round(float(cos[i]), 4)} for i in order[:3]]
        row = {"id": q["id"], "text": q["text"], "gold": q.get("gold"), "top3": top3,
               "top1": ids[order[0]], "top1_cos": round(float(cos[order[0]]), 4)}
        if q.get("gold"):
            g = ids.index(q["gold"])
            grank = int(np.where(order == g)[0][0]) + 1
            row.update({"gold_rank": grank, "gold_cos": round(float(cos[g]), 4), "hit1": int(grank == 1),
                        "hit3": int(grank <= 3)})
        for k in ("voice", "wording", "prefix", "seconds", "embed_ms_by_backend"):
            if k in q:
                row[k] = q[k]
        out.append(row)
    return out


def tally(rows):
    gold = [r for r in rows if r.get("gold")]
    hits = [r for r in gold if r["hit1"]]
    misses = [r for r in gold if not r["hit1"]]
    return {"n": len(gold), "top1": sum(r["hit1"] for r in gold), "top3": sum(r["hit3"] for r in gold),
            "hit_cos_min": min((r["top1_cos"] for r in hits), default=None),
            "misses": [{"id": r["id"], "gold": r["gold"], "gold_rank": r["gold_rank"], "top1": r["top1"],
                        "top1_cos": r["top1_cos"], "gold_cos": r["gold_cos"],
                        "gap": round(r["top1_cos"] - r["gold_cos"], 4)} for r in misses]}


def run(kind, model, album_files, queries, decoys, texts, tag):
    """All inputs on one engine; returns (record, vectors)."""
    eng, init_ms = make_engine(kind, model)
    rec = {"backend": kind.upper(), "vision_backend": kind.upper(), "audio_backend": "CPU", "init_ms": round(init_ms, 1)}
    # normalize=None: what the C++ default gives (the launch could not tell from the API docs).
    dflt = EmbeddingOptions(output_size=DIM)
    rec["default_options_norms"] = {
        "image": round(float(np.linalg.norm(embed(eng, Content.ImageFile(album_files[0]), dflt)[0])), 5),
        "audio": round(float(np.linalg.norm(embed(eng, Content.AudioFile(os.path.join(QDIR, queries[0]["file"])),
                                                  dflt)[0])), 5),
        "text": round(float(np.linalg.norm(embed(eng, Content.Text(texts[0]["text"]), dflt)[0])), 5),
        "options": "EmbeddingOptions(output_size=768), normalize=None",
    }
    vec = {"album": [], "queries": [], "decoys": [], "texts": []}
    ms = {"image": [], "audio": [], "text": []}
    for f in album_files:
        v, t = embed(eng, Content.ImageFile(f))
        vec["album"].append(v)
        ms["image"].append(t)
    rec["image_ms"] = {os.path.basename(f): round(t, 1) for f, t in zip(album_files, ms["image"])}
    for group in ("queries", "decoys"):
        for q in (queries if group == "queries" else decoys):
            v, t = embed(eng, Content.AudioFile(os.path.join(QDIR, q["file"])))
            q.setdefault("embed_ms_by_backend", {})[kind] = round(t, 1)
            vec[group].append(v)
            ms["audio"].append(t)
    for tq in texts:
        v, t = embed(eng, Content.Text(tq["prefix_text"]))
        tq.setdefault("embed_ms_by_backend", {})[kind] = round(t, 1)
        vec["texts"].append(v)
        ms["text"].append(t)
    eng.close()
    rec["ms"] = {k: {"n": len(v), "median": round(float(np.median(v)), 1), "p90": round(float(np.percentile(v, 90)), 1),
                     "min": round(float(min(v)), 1), "max": round(float(max(v)), 1)} for k, v in ms.items() if v}
    rec["ms_note"] = "wall time of compute_embedding on a busy Mac (other lanes running): reference only"
    return rec, {k: np.array(v) for k, v in vec.items()}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--backend", choices=["gpu", "cpu"], default="gpu")
    ap.add_argument("--also-cpu", action="store_true")
    ap.add_argument("--tag", default="")
    a = ap.parse_args()
    tag = ("_" + a.tag) if a.tag else ""
    os.makedirs(CACHE, exist_ok=True)
    model, model_sha = bundle_path()
    album = json.load(open(os.path.join(K, "fixtures", "album.json")))["album"]
    ids = [r["id"] for r in album]
    album_files = [os.path.join(ALBUM_DIR, r["file"]) for r in album]
    qdoc = json.load(open(os.path.join(K, "fixtures", "queries.json")))
    queries = qdoc["queries"]
    decoys = qdoc["decoys"]
    # The 20 sentences (wording 1, one row per query id) as text, three ways.
    seen, texts = set(), []
    for q in queries:
        if q["wording"] != 1 or q["query"] in seen:
            continue
        seen.add(q["query"])
        for p, pre in PREFIXES.items():
            texts.append({"id": f"{q['query']}_t_{p}", "query": q["query"], "text": q["text"], "prefix": p,
                          "prefix_text": pre + q["text"].strip(), "gold": q["gold"]})

    kind = a.backend
    try:
        rec, vec = run(kind, model, album_files, queries, decoys, texts, tag)
        fallback = None
    except Exception as e:  # noqa: BLE001 - the launch: GPU when it initializes, else CPU
        if kind != "gpu":
            raise
        fallback = repr(e)
        print("GPU engine failed, using CPU:", fallback, file=sys.stderr)
        kind = "cpu"
        rec, vec = run(kind, model, album_files, queries, decoys, texts, tag)
    rec["gpu_failure"] = fallback

    norms = np.linalg.norm(vec["album"], axis=1)
    np.save(os.path.join(OUT, f"album_emb{tag}.npy"), vec["album"].astype(np.float32))
    json.dump(ids, open(os.path.join(OUT, f"album_ids{tag}.json"), "w"))
    allv = np.concatenate([vec["album"], vec["queries"], vec["decoys"], vec["texts"]])
    alln = np.linalg.norm(allv, axis=1)

    m = vec["album"]
    q_scores = score_rows(queries, vec["queries"], m, ids)
    d_scores = score_rows(decoys, vec["decoys"], m, ids)
    t_scores = score_rows(texts, vec["texts"], m, ids)

    # The spoken sentence against its own text (no prefix): does the clip land on its words among the 20 sentences?
    tn = [t for t in texts if t["prefix"] == "none"]
    tvec = np.array([vec["texts"][texts.index(t)] for t in tn])
    a_vs_t = []
    for q, v in zip(queries, vec["queries"]):
        c = tvec @ v
        own = [t["query"] for t in tn].index(q["query"])
        a_vs_t.append({"id": q["id"], "own_text_cos": round(float(c[own]), 4),
                       "own_text_rank": int(np.where(np.argsort(-c) == own)[0][0]) + 1})

    groups = {}
    for v in ("a", "b"):
        groups[f"audio_voice_{v}"] = tally([r for r in q_scores if r["voice"] == v and r.get("wording") == 1])
    if any(r.get("wording") == 2 for r in q_scores):
        groups["audio_voice_a_reworded"] = tally([r for r in q_scores if r.get("wording") == 2])
    for p in PREFIXES:
        groups[f"text_prefix_{p}"] = tally([r for r in t_scores if r["prefix"] == p])
    va = groups["audio_voice_a"]
    line_pass = va["n"] == LINE["n"] and va["top1"] >= LINE["top1_min"] and va["top3"] >= LINE["top3_min"]
    best_prefix = max(PREFIXES, key=lambda p: (groups[f"text_prefix_{p}"]["top1"], groups[f"text_prefix_{p}"]["top3"]))

    summary = {
        "runtime": {"litert_lm": "0.18.0", "python": platform.python_version(), "machine": platform.machine(),
                    "macos": platform.mac_ver()[0], "module": litert_lm.__file__},
        "bundle": {"path": model, "sha256": model_sha},
        "engine": rec,
        "options": "EmbeddingOptions(normalize=True, output_size=768); no prefix on images and audio",
        "album_n": len(ids), "queries_n": len(queries), "decoys_n": len(decoys), "texts_n": len(texts),
        "norms": {"album_min": round(float(norms.min()), 6), "album_max": round(float(norms.max()), 6),
                  "all_min": round(float(alln.min()), 6), "all_max": round(float(alln.max()), 6)},
        "groups": groups,
        "decoys": [{"id": r["id"], "text": r["text"], "top1": r["top1"], "top1_cos": r["top1_cos"]} for r in d_scores],
        "decoy_max_cos": max(r["top1_cos"] for r in d_scores),
        "audio_vs_own_text": {"own_rank1": sum(1 for x in a_vs_t if x["own_text_rank"] == 1), "n": len(a_vs_t)},
        "line": {"rule": "audio -> photo, voice A, top-1 >= 16/20 and top-3 >= 19/20", "voice_a_top1": va["top1"],
                 "voice_a_top3": va["top3"], "pass": line_pass},
        "candidate_b_line": {"rule": "text -> photo, best prefix, top-1 >= 18/20", "best_prefix": best_prefix,
                             "top1": groups[f"text_prefix_{best_prefix}"]["top1"],
                             "pass": groups[f"text_prefix_{best_prefix}"]["top1"] >= 18},
    }

    if a.also_cpu and kind == "gpu":
        crec, cvec = run("cpu", model, album_files, queries, decoys, texts, tag)
        par = {}
        for k in ("album", "queries", "decoys", "texts"):
            g, c = vec[k], cvec[k]
            cs = np.sum(g * c, axis=1) / (np.linalg.norm(g, axis=1) * np.linalg.norm(c, axis=1))
            par[k] = {"n": len(g), "cos_min": round(float(cs.min()), 6), "cos_mean": round(float(cs.mean()), 6),
                      "max_abs_delta": round(float(np.abs(g - c).max()), 6)}
        cq = score_rows(queries, cvec["queries"], cvec["album"], ids)
        ct = score_rows(texts, cvec["texts"], cvec["album"], ids)
        cgroups = {f"audio_voice_{v}": tally([r for r in cq if r["voice"] == v and r.get("wording") == 1])
                   for v in ("a", "b")}
        for p in PREFIXES:
            cgroups[f"text_prefix_{p}"] = tally([r for r in ct if r["prefix"] == p])
        top1_same = sum(1 for x, y in zip(q_scores, cq) if x["top1"] == y["top1"])
        summary["cpu_reference"] = {"engine": crec, "gpu_vs_cpu": par, "groups": cgroups,
                                    "audio_top1_same_as_gpu": f"{top1_same}/{len(cq)}"}
        json.dump({"queries": cq, "texts": ct}, open(os.path.join(OUT, f"scores_cpu{tag}.json"), "w"), indent=1)

    json.dump({"queries": q_scores, "decoys": d_scores, "texts": t_scores, "audio_vs_own_text": a_vs_t},
              open(os.path.join(OUT, f"scores{tag}.json"), "w"), indent=1)
    json.dump(summary, open(os.path.join(OUT, f"summary{tag}.json"), "w"), indent=1)
    print(json.dumps({"engine": rec["backend"], "norms": summary["norms"], "groups": {
        k: {"top1": v["top1"], "top3": v["top3"], "n": v["n"]} for k, v in groups.items()},
        "decoy_max_cos": summary["decoy_max_cos"], "line": summary["line"],
        "candidate_b_line": summary["candidate_b_line"]}, indent=1))


if __name__ == "__main__":
    main()
