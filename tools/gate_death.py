#!/usr/bin/env python3
"""Print one `RESULT ok=false` line per profile of an entry's default variant, for a gate run whose test process died
before it could print its own RESULT (tools/gate.sh calls this when gradle failed and no RESULT line was logged).
The line carries what the harness observed - the death - and nothing it did not measure.

    gate_death.py <catalog.json> <model-id> <device model> <build id> <runtime version> [profiles-csv]

`profiles-csv` limits the lines to the profiles that were being run when the process died (the test runs the default
variant's profiles in descriptor order, so a death during the first one says nothing about the later ones).
"""
import json, sys

cat, model, device, build, rt = sys.argv[1:6]
only = set(sys.argv[6].split(",")) if len(sys.argv) > 6 else None
e = next(x for x in json.load(open(cat))["entries"] if x["model_id"] == model)
d = e["descriptor"]
v = next(x for x in d["variants"] if x["id"] == d["default_variant"])
for p in v["profiles"]:
    if only is not None and p["id"] not in only: continue
    print(f'hfmodels-a3: RESULT ok=false model={model} commit={e["model_commit"][:8]} variant={v["id"]} profile={p["id"]} '
          f'error="test process died before RESULT (gradle rc != 0, no RESULT line; see the per-entry gradle output and lmkd lines)" '
          f'device={device} build={build} runtime=litert_lm {rt}')
