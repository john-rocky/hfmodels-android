#!/usr/bin/env python3
"""Print the catalog's model ids, smallest default-variant file first (the order tools/gate.sh runs them in)."""
import json, sys

c = json.load(open(sys.argv[1] if len(sys.argv) > 1 else "core/src/main/assets/hfmodels/catalog.json"))


def size(e):
    d = e["descriptor"]
    v = next(x for x in d["variants"] if x["id"] == d["default_variant"])
    return sum(f["bytes"] for f in v["files"])


for e in sorted(c["entries"], key=size):
    print(e["model_id"] if "--sizes" not in sys.argv else f"{size(e) / 1e9:5.2f} GB  {e['model_id']}")
