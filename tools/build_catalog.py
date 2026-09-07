#!/usr/bin/env python3
"""Build the bundled catalog asset from catalog/entries/*.json plus verification/*.json.

    tools/build_catalog.py [--out core/src/main/assets/hfmodels/catalog.json]

The catalog's own origin is this repository at the last commit that touched catalog/entries (so the
value is stable across builds that do not change an entry). Verification records (one JSON per
device gate run: {model_id, model_commit, variant, profile, level, device, os_build, runtime,
result, date, evidence}) are merged into `profiles[].verification`; only records whose
model_commit and descriptor variant still match are kept.
"""
import argparse, glob, json, os, subprocess

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def git(*args):
    return subprocess.check_output(["git", "-C", ROOT, *args], text=True).strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(ROOT, "core/src/main/assets/hfmodels/catalog.json"))
    a = ap.parse_args()
    entries = []
    records = []
    for f in sorted(glob.glob(os.path.join(ROOT, "verification", "*.json"))):
        d = json.load(open(f))
        records.extend(d if isinstance(d, list) else [d])
    commit = git("log", "-1", "--format=%H", "--", "catalog/entries") or "0" * 40
    if len(commit) != 40:
        commit = "0" * 40
    for f in sorted(glob.glob(os.path.join(ROOT, "catalog", "entries", "*.json"))):
        e = json.load(open(f))
        d = e["descriptor"]
        for v in d["variants"]:
            for p in v["profiles"]:
                ver = [
                    {"level": r["level"], "device": r["device"], "os_build": r.get("os_build"), "runtime": r["runtime"], "result": r["result"], "date": r.get("date"), **({"evidence": r["evidence"]} if r.get("evidence") else {})}
                    for r in records
                    if r["model_id"] == e["model_id"] and r["model_commit"] == e["model_commit"] and r["variant"] == v["id"] and r["profile"] == p["id"]
                ]
                if ver:
                    p["verification"] = ver
        entries.append({"model_id": e["model_id"], "model_commit": e["model_commit"], "path": e["path"], "provenance": e.get("provenance", ""), "descriptor": d})
    out = {"schema_version": 1, "catalog_id": "hfmodels-bundled", "origin": {"repo": "john-rocky/hfmodels-android", "commit": commit}, "entries": entries}
    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    open(a.out, "w").write(json.dumps(out, indent=2, ensure_ascii=False) + "\n")
    print(f"wrote {os.path.relpath(a.out, ROOT)}: {len(entries)} entries, {len(records)} verification records, origin commit {commit[:8]}")


if __name__ == "__main__":
    main()
