#!/usr/bin/env python3
"""Build the Julia-1 parity fixtures for hfmodels-litert from the oracle dumps of the conversion.

    tools/julia1_fixture.py <oracle_typed.json> <oracle_parity.json> --device-out <rows.json.gz> [--unit-out <subset.json.gz>]

The oracle files are the publisher's own runtime (SupersonicLabs/Julia-1, CPU FP32, strict encoding,
`engine.logits` one request per forward) on the LocalLLaMA/typed-decisions test split (2,000 questions,
ids / markers / qtype from `julia/data.py sequence()`) and on the 100 parity cases of
SupersonicLabs/Julia-1-ONNX. Each output row carries what the device test needs: the request in the
SDK's question form (`keys` + `options`), the publisher's token ids, marker positions and qtype, which
windows the row fits without truncation, and the raw marker logits. `--unit-out` writes the small
subset the JVM test ships in the repository (`litert/src/test/resources/julia1/`).
"""
import argparse, gzip, json, random


def rows_from(typed, parity):
    out = []
    for r in typed["records"]:
        q = r["request"]
        out.append({
            "id": r["id"], "type": r["type"], "keys": r["keys"], "gold": r.get("gold"),
            "state": q["state"], "question": q["question"], "options": q["options"],
            "ids": r["ids"], "markers": r["markers"], "qtype": r["qtype"], "n": r["n_tokens"],
            "fits": {"512": bool(r["fits"]["512/507"]), "1024": bool(r["fits"]["1024/512"])},
            "logits": r["logits"],
        })
    for r in parity["records"]:
        q = r["request"]
        n = r["n_tokens"]
        assert q["type"] == "choice" and len(q["options"]) <= 20 and all(o for o in q["options"])
        out.append({
            "id": f"parity_{r['index']:03d}", "type": "choice", "keys": [str(i) for i in range(len(q["options"]))], "gold": None,
            "state": q["state"], "question": q["question"], "options": q["options"],
            "ids": r["ids"], "markers": r["markers"], "qtype": r["qtype"], "n": n,
            # Short rows (<= 100 tokens, <= 20 options of <= 48 tokens): the head budget never binds.
            "fits": {"512": n <= 512, "1024": n <= 1024},
            "logits": r["logits"],
        })
    return out


def subset(rows, per_type=40, parity=20, seed=20260930):
    rnd = random.Random(seed)
    chosen = []
    typed = [r for r in rows if not r["id"].startswith("parity_")]
    for t in ("choice", "score", "noul"):
        rs = [r for r in typed if r["type"] == t and r["fits"]["512"]]
        rs.sort(key=lambda r: -r["n"])
        longest = rs[:10]
        rest = rs[10:]
        def top1(r):
            z = r["logits"]; m = max(z); e = [pow(2.718281828459045, x - m) for x in z]; return max(e) / sum(e)
        boundary = sorted(rest, key=top1)[:10]
        pool = [r for r in rest if r not in boundary]
        chosen += longest + boundary + rnd.sample(pool, per_type - 20)
    # rows the 512 window rejects (kept for the truncation check)
    chosen += [r for r in typed if not r["fits"]["512"]][:5]
    par = [r for r in rows if r["id"].startswith("parity_")]
    chosen += rnd.sample(par, parity)
    return chosen


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("typed"); ap.add_argument("parity")
    ap.add_argument("--device-out", required=True)
    ap.add_argument("--unit-out", default=None)
    a = ap.parse_args()
    typed = json.load(open(a.typed)); parity = json.load(open(a.parity))
    rows = rows_from(typed, parity)
    meta = {
        "source": {"typed": typed["env"] | {"encoding": typed["encoding"], "count": len(typed["records"])},
                   "parity": {"source": parity["source"], "count": parity["count"], "encoding": parity["encoding"]}},
        "note": "ids/markers/qtype from the publisher's julia/data.py sequence() (strict); logits = the publisher's runtime, CPU FP32, one request per forward",
    }
    with gzip.open(a.device_out, "wt", encoding="utf-8") as f:
        json.dump({"meta": meta, "rows": rows}, f, ensure_ascii=False)
    fit512 = sum(r["fits"]["512"] for r in rows); fit1024 = sum(r["fits"]["1024"] for r in rows)
    print(f"device rows: {len(rows)} (fit 512: {fit512}, fit 1024: {fit1024}) -> {a.device_out}")
    if a.unit_out:
        sub = subset(rows)
        with gzip.open(a.unit_out, "wt", encoding="utf-8") as f:
            json.dump({"meta": meta, "rows": sub}, f, ensure_ascii=False)
        print(f"unit subset: {len(sub)} rows -> {a.unit_out}")


if __name__ == "__main__":
    main()
