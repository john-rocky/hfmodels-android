#!/usr/bin/env python3
"""Turn the catalog gate's RESULT lines (adb logcat -s hfmodels-a3) into verification records.

    adb logcat -d -s hfmodels-a3:I hfmodels-a3:E > gate.log
    tools/gate_to_verification.py gate.log --date 2026-09-07 --evidence litertlm/results/<log> > verification/<date>-<device>.json

Only what the line says is recorded: model / commit / variant / profile / device / build / runtime / ok.
A FAIL row is kept as FAIL (the resolver never auto-selects a profile with a FAIL and no PASS).
"""
import argparse, json, re, sys

R = re.compile(r"RESULT ok=(true|false) model=(\S+) commit=([0-9a-f]{8}) variant=(\S+) profile=(\S+).*?device=([^ ]+(?: [^ =]+)*?) build=(\S+)")
RT = re.compile(r"runtime=(litert_lm) (\S+)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("log")
    ap.add_argument("--date", required=True)
    ap.add_argument("--evidence", default=None)
    ap.add_argument("--commits", default="core/src/main/assets/hfmodels/catalog.json", help="catalog to expand 8-char commits from")
    ap.add_argument("--level", default="MAINTAINER_TESTED")
    a = ap.parse_args()
    cat = json.load(open(a.commits))
    full = {e["model_id"]: e["model_commit"] for e in cat["entries"]}
    out = []
    for line in open(a.log, errors="replace"):
        m = R.search(line)
        if not m: continue
        ok, model, c8, variant, profile, device, build = m.groups()
        rt = RT.search(line)
        commit = full.get(model)
        if not commit or not commit.startswith(c8): continue
        rec = {"model_id": model, "model_commit": commit, "variant": variant, "profile": profile, "level": a.level,
               "device": device, "os_build": build, "runtime": f"{rt.group(1)} {rt.group(2)}" if rt else "litert_lm", "result": "PASS" if ok == "true" else "FAIL", "date": a.date}
        if a.evidence: rec["evidence"] = a.evidence
        out.append(rec)
    json.dump(out, sys.stdout, indent=2); print()


if __name__ == "__main__":
    main()
