#!/usr/bin/env python3
"""Print the catalog's model ids, smallest default-variant file first (the order tools/gate.sh runs them in).

    tools/catalog_order.py [catalog.json] [--task chat] [--sizes]

`--task <id>` keeps the entries whose descriptor declares that task: tools/gate.sh runs every entry through
Tasks.Chat, so it asks for `chat` (the decide, transcribe and speak entries have their own gates).
"""
import json, sys

args = sys.argv[1:]
task = args[args.index("--task") + 1] if "--task" in args else None
paths = [a for i, a in enumerate(args) if not a.startswith("--") and (i == 0 or args[i - 1] != "--task")]
c = json.load(open(paths[0] if paths else "core/src/main/assets/hfmodels/catalog.json"))


def size(e):
    d = e["descriptor"]
    v = next(x for x in d["variants"] if x["id"] == d["default_variant"])
    return sum(f["bytes"] for f in v["files"])


for e in sorted(c["entries"], key=size):
    if task is not None and task not in e["descriptor"]["tasks"]:
        continue
    print(e["model_id"] if "--sizes" not in args else f"{size(e) / 1e9:5.2f} GB  {e['model_id']}")
