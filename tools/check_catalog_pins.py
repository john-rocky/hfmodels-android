#!/usr/bin/env python3
"""Check that no catalog entry pins a commit whose own hfmodels.json reads differently from the entry.

    tools/check_catalog_pins.py [--catalog core/src/main/assets/hfmodels/catalog.json] [--token $HF_TOKEN]

At one commit the SDK reads the repo's own `hfmodels.json` before the catalog's entry for that commit
(core/src/main/kotlin/io/github/johnrocky/hfmodels/resolve/Resolver.kt, findDescriptor: the descriptor cache, the
Hub, then the catalog). An entry is read online only when its commit carries no `hfmodels.json`; when the commit
carries one, that file is what every online load reads, so it must read the same as the entry. For each entry of
catalog/entries/*.json (or of a catalog.json-shaped file, --catalog) this asks the Hub's API whether the commit
exists and lists `hfmodels.json`, fetches the file the way the SDK does (<hub>/<repo>/resolve/<commit>/hfmodels.json)
and prints one row:

  hub hfmodels.json  none = the commit carries none, so the catalog's descriptor is the one read; present = compared
  descriptor         OK / FAIL: equal or not in the SDK's reading, verification records left out
  verification       the records per profile, compared on their own: tools/build_catalog.py merges verification/*.json
                     into the bundled asset, so a catalog can carry records the repo's file does not

Exit 1 when an entry FAILs or could not be checked, 0 otherwise.

The SDK's reading, from core/src/main/kotlin/io/github/johnrocky/hfmodels/descriptor/Descriptor.kt:
  - Descriptor.parse reads schema_version, model_id, tasks, default_variant, variants and license; any other key
    ($comment, revision, a record's note or evidence) is ignored. License.parse: url absent = null.
  - Variant.parse: id, runtime, handler {id, abi}, runtime_range {min_inclusive, max_exclusive absent = null},
    inputs (a set), files [{id, role, path, bytes, sha256}], default_profile, profiles, handler_config absent = {}.
  - Profile.parse: id, priority absent = 0, files, enabled_inputs (a set), components, requirements,
    fallback_profiles absent = [], default_selectable absent = true, context_tokens absent or null = null,
    verification absent = []. Requirements.parse: absent = {min_android_api 31, abis [arm64-v8a]}, and the same
    default per key.
  - Verification.parse: level, device, os_build (absent = null), runtime, result, date (absent = null).
  - Variants, a variant's files and its profiles are looked up by id (Descriptor.variant, Variant.file,
    Variant.profile), so their order is not compared; a profile's files and fallback_profiles keep their order.
"""
import argparse, glob, json, os, sys, urllib.error, urllib.parse, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HUB = os.environ.get("HF_ENDPOINT", "https://huggingface.co")
FILE_NAME = "hfmodels.json"


def sdk_reading(d):
    """The descriptor as Descriptor.kt reads it (see the module doc), verification records included."""
    def requirements(r):
        r = r if isinstance(r, dict) else {}
        return {"min_android_api": r.get("min_android_api", 31), "abis": r.get("abis", ["arm64-v8a"])}

    def record(v):
        return {k: v.get(k) for k in ("level", "device", "os_build", "runtime", "result", "date")}

    def profile(p):
        return {
            "priority": p.get("priority", 0), "files": p.get("files"), "enabled_inputs": sorted(set(p.get("enabled_inputs", []))),
            "components": p.get("components"), "requirements": requirements(p.get("requirements")),
            "fallback_profiles": p.get("fallback_profiles", []), "default_selectable": p.get("default_selectable", True),
            "context_tokens": p.get("context_tokens"), "verification": [record(v) for v in p.get("verification", [])],
        }

    def variant(v):
        rr, h, hc = v.get("runtime_range") or {}, v.get("handler") or {}, v.get("handler_config")
        return {
            "runtime": v.get("runtime"), "handler": {"id": h.get("id"), "abi": h.get("abi")},
            "runtime_range": {"min_inclusive": rr.get("min_inclusive"), "max_exclusive": rr.get("max_exclusive")},
            "inputs": sorted(set(v.get("inputs", []))),
            "files": {f.get("id"): {k: f.get(k) for k in ("role", "path", "bytes", "sha256")} for f in v.get("files", [])},
            "default_profile": v.get("default_profile"),
            "profiles": {p.get("id"): profile(p) for p in v.get("profiles", [])},
            "handler_config": hc if isinstance(hc, dict) else {},
        }

    lic = d.get("license") or {}
    return {
        "schema_version": d.get("schema_version"), "model_id": d.get("model_id"), "tasks": d.get("tasks"),
        "default_variant": d.get("default_variant"), "license": {"id": lic.get("id"), "url": lic.get("url")},
        "variants": {v.get("id"): variant(v) for v in d.get("variants", [])},
    }


def _records(reading):
    """Takes the verification records out of an sdk_reading(): {(variant, profile): [record, ...]}."""
    out = {}
    for vid, v in reading["variants"].items():
        for pid, p in v["profiles"].items():
            out[(vid, pid)] = p.pop("verification")
    return out


def _diff(a, b, path=""):
    if isinstance(a, dict) and isinstance(b, dict):
        for k in sorted(set(a) | set(b), key=str):
            p = f"{path}.{k}" if path else str(k)
            if k not in b:
                yield f"{p}: only in the catalog"
            elif k not in a:
                yield f"{p}: only in the repo's file"
            else:
                yield from _diff(a[k], b[k], p)
    elif a != b:
        yield f"{path}: catalog {json.dumps(a, ensure_ascii=False)[:120]} / repo {json.dumps(b, ensure_ascii=False)[:120]}"


def compare(catalog_descriptor, repo_descriptor):
    """(descriptor differences without the verification records, verification differences), two lists of strings;
    both empty when the SDK reads the two the same."""
    a, b = sdk_reading(catalog_descriptor), sdk_reading(repo_descriptor)
    ra, rb = _records(a), _records(b)
    descriptor = list(_diff(a, b))
    verification = []
    for key in sorted(set(ra) & set(rb)):
        ka = sorted(json.dumps(r, sort_keys=True) for r in ra[key])
        kb = sorted(json.dumps(r, sort_keys=True) for r in rb[key])
        only_a = [r for r in ka if r not in kb]
        only_b = [r for r in kb if r not in ka]
        if only_a or only_b:
            verification.append(f"{key[0]}/{key[1]}: catalog +{len(only_a)}, repo +{len(only_b)}")
    return descriptor, verification


def _get(url, token):
    """Body bytes, or None on HTTP 404 (the SDK's 'no such file'); other HTTP errors raise."""
    req = urllib.request.Request(url, headers={"User-Agent": "hfmodels-check-pins/0.1", **({"Authorization": f"Bearer {token}"} if token else {})})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.read()
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise RuntimeError(f"HTTP {e.code} {e.headers.get('X-Error-Code') or ''} from {url}".strip()) from e


def check(entry, token):
    """One row: {model_id, commit, hub, descriptor, verification, details, status} with status OK / NONE / FAIL / ERROR."""
    repo, commit = entry["model_id"], entry["model_commit"]
    row = {"model_id": repo, "commit": commit, "hub": "", "descriptor": "", "verification": "", "details": [], "status": "ERROR"}
    try:
        info = _get(f"{HUB}/api/models/{repo}/revision/{urllib.parse.quote(commit, safe='')}", token)
        if info is None:
            row["hub"] = "commit not found"
            return row
        info = json.loads(info)
        if info.get("sha") != commit:
            row["hub"] = f"API answers {info.get('sha')}"
            return row
        listed = FILE_NAME in {s.get("rfilename") for s in info.get("siblings", [])}
        text = _get(f"{HUB}/{repo}/resolve/{commit}/{FILE_NAME}", token)
    except (RuntimeError, OSError, ValueError) as e:
        row["hub"] = f"unreadable: {e}"
        return row
    if listed != (text is not None):
        row["hub"] = f"the API {'lists' if listed else 'does not list'} {FILE_NAME}, resolve {'answers 404' if text is None else 'serves it'}"
        return row
    if text is None:
        row.update(hub="none", descriptor="-", verification="-", status="NONE")
        return row
    row["hub"] = f"present ({len(text)} B)"
    try:
        repo_descriptor = json.loads(text)
    except ValueError as e:
        row.update(descriptor="FAIL", details=[f"the repo's file is not JSON: {e}"], status="FAIL")
        return row
    descriptor, verification = compare(entry["descriptor"], repo_descriptor)
    row["descriptor"] = "OK" if not descriptor else f"FAIL ({len(descriptor)})"
    row["verification"] = "same" if not verification else "; ".join(verification)
    row["details"] = descriptor
    row["status"] = "OK" if not descriptor else "FAIL"
    return row


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--catalog", default=None, help="a catalog.json-shaped file to check instead of catalog/entries/*.json")
    ap.add_argument("--token", default=os.environ.get("HF_TOKEN"))
    a = ap.parse_args()
    if a.catalog:
        entries = json.load(open(a.catalog))["entries"]
        source = os.path.relpath(a.catalog, ROOT) if os.path.isabs(a.catalog) else a.catalog
    else:
        entries = [json.load(open(f)) for f in sorted(glob.glob(os.path.join(ROOT, "catalog", "entries", "*.json")))]
        source = "catalog/entries/*.json"
    rows = [check(e, a.token) for e in entries]
    print(f"{source}: {len(rows)} entries against {HUB}")
    print()
    print("| entry | pinned commit | hub hfmodels.json | descriptor | verification | status |")
    print("|---|---|---|---|---|---|")
    for r in rows:
        print(f"| {r['model_id']} | {r['commit'][:8]} | {r['hub']} | {r['descriptor']} | {r['verification']} | {r['status']} |")
    for r in rows:
        for d in r["details"]:
            print(f"{r['model_id']}@{r['commit'][:8]}: {d}")
    counts = {s: sum(r["status"] == s for r in rows) for s in ("OK", "NONE", "FAIL", "ERROR")}
    print()
    print(f"OK {counts['OK']}, none {counts['NONE']}, FAIL {counts['FAIL']}, error {counts['ERROR']}")
    sys.exit(1 if counts["FAIL"] or counts["ERROR"] else 0)


if __name__ == "__main__":
    main()
