#!/usr/bin/env python3
"""Generate an `hfmodels.json` (schema v1) from a curated spec + the Hugging Face Hub.

    tools/hfmodels_descriptor.py catalog/specs/<org>__<name>.json [--out hfmodels.json] [--validate-only]

The spec holds what only the publisher knows (variants, handler, profiles, license, runtime range).
Everything derivable is read, never typed: the model commit (revision -> sha), each file's `bytes`
and `sha256` (Hub LFS metadata), and, when the repo ships `litertlm_manifest.json` and a variant's
`handler_config.metadata_source` is `litertlm_manifest`, that file's `backends` (verified to
generate), `context_length` and thinking-channel declaration are cross-checked against the spec:
a profile may only use a backend the manifest lists for that file, and a bundle that declares a
thinking channel is refused for MVP-0 unless `handler_config.channels` spells it out.

Verification records are NOT written here; `tools/build_catalog.py` merges `verification/*.json`
(produced by the device gate) into the profiles when it builds the bundled catalog.
"""
import argparse, hashlib, json, os, sys, urllib.parse, urllib.request

HUB = os.environ.get("HF_ENDPOINT", "https://huggingface.co")
TASKS = {"chat", "object_detection"}
RUNTIMES = {"litert_lm", "litert"}
ROLES = {"model", "tokenizer", "labels", "processor_config"}
BACKENDS = {"cpu", "gpu", "npu"}
INPUTS = {"text", "image", "audio", "video"}


def get(url, token=None):
    req = urllib.request.Request(url, headers={"User-Agent": "hfmodels-descriptor/0.1", **({"Authorization": f"Bearer {token}"} if token else {})})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def repo_info(repo, revision, token):
    url = f"{HUB}/api/models/{repo}/revision/{urllib.parse.quote(revision, safe='')}?blobs=true"
    d = json.loads(get(url, token))
    files = {}
    for s in d.get("siblings", []):
        lfs = s.get("lfs") or {}
        files[s["rfilename"]] = {"sha256": lfs.get("sha256"), "size": lfs.get("size", s.get("size"))}
    return d["sha"], files, d.get("gated", False), (d.get("cardData") or {}).get("license")


def fail(msg):
    print(f"error: {msg}", file=sys.stderr)
    sys.exit(2)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("spec")
    ap.add_argument("--out", default=None, help="where to write hfmodels.json (default: catalog/entries/<org>__<name>.json)")
    ap.add_argument("--token", default=os.environ.get("HF_TOKEN"))
    ap.add_argument("--validate-only", action="store_true")
    a = ap.parse_args()
    spec = json.load(open(a.spec))
    repo = spec["model_id"]
    revision = spec.get("revision", "main")
    commit, files, gated, card_license = repo_info(repo, revision, a.token)
    if len(revision) == 40 and revision != commit:
        fail(f"revision {revision} did not resolve to itself ({commit})")
    manifest = None
    if "litertlm_manifest.json" in files:
        manifest = json.loads(get(f"{HUB}/{repo}/resolve/{commit}/litertlm_manifest.json", a.token))

    out = {
        "schema_version": 1,
        "model_id": repo,
        "tasks": spec["tasks"],
        "default_variant": spec["default_variant"],
        "license": spec["license"],
        "variants": [],
    }
    for t in out["tasks"]:
        if t not in TASKS: fail(f"unknown task {t}")
    if out["license"]["id"] == "other" and not out["license"].get("url"): fail("license 'other' needs a url")
    if card_license and out["license"]["id"] not in (card_license, "other") and card_license != "other":
        print(f"note: card says license '{card_license}', spec says '{out['license']['id']}'", file=sys.stderr)

    for v in spec["variants"]:
        if v["runtime"] not in RUNTIMES: fail(f"variant {v['id']}: runtime {v['runtime']}")
        vfiles = []
        for f in v["files"]:
            if f["role"] not in ROLES: fail(f"file {f['id']}: role {f['role']}")
            meta = files.get(f["path"])
            if meta is None: fail(f"{repo}@{commit[:8]} has no file '{f['path']}'")
            if not meta["sha256"]: fail(f"'{f['path']}' is not an LFS file; no sha256 from the Hub")
            vfiles.append({"id": f["id"], "role": f["role"], "path": f["path"], "bytes": int(meta["size"]), "sha256": meta["sha256"]})
        hc = dict(v.get("handler_config", {}))
        profiles = []
        allowed_backends = None
        if hc.get("metadata_source") == "litertlm_manifest":
            if manifest is None: fail(f"variant {v['id']}: metadata_source=litertlm_manifest but the repo ships no litertlm_manifest.json")
            model_file = next(f for f in vfiles if f["role"] == "model")
            mv = next((m for m in manifest.get("variants", []) if m.get("file") == model_file["path"]), None)
            if mv is None: fail(f"litertlm_manifest.json has no variant for '{model_file['path']}'")
            if mv.get("sha256") != model_file["sha256"] or int(mv.get("size_bytes", -1)) != model_file["bytes"]:
                fail(f"litertlm_manifest.json disagrees with the Hub on '{model_file['path']}' (sha256/size)")
            allowed_backends = set(mv.get("backends", []))
            ctx = (manifest.get("model") or {}).get("context_length")
            if ctx and "context_tokens" not in hc: hc["context_tokens"] = int(ctx)
            thinking = ((manifest.get("model") or {}).get("capabilities") or {}).get("thinking") or {}
            if thinking.get("declared") and "channels" not in hc:
                fail(f"'{model_file['path']}' declares a thinking channel {thinking.get('channel')}; MVP-0 does not ship thinking models (set handler_config.channels explicitly to override)")
            hc["manifest_min_runtime_version"] = mv.get("min_runtime_version")
        for p in v["profiles"]:
            comps = p["components"]
            for k, b in comps.items():
                if b not in BACKENDS: fail(f"profile {p['id']}: backend {b}")
                if allowed_backends is not None and b not in allowed_backends and k == "language":
                    fail(f"profile {p['id']}: language backend '{b}' is not in litertlm_manifest.json backends {sorted(allowed_backends)} for this file")
            for i in p["enabled_inputs"]:
                if i not in INPUTS or i not in v["inputs"]: fail(f"profile {p['id']}: enabled input {i}")
            for fid in p["files"]:
                if fid not in {f["id"] for f in vfiles}: fail(f"profile {p['id']}: file id {fid}")
            prof = {
                "id": p["id"], "priority": p.get("priority", 0), "files": p["files"], "enabled_inputs": p["enabled_inputs"],
                "components": comps, "requirements": p.get("requirements", {"min_android_api": 31, "abis": ["arm64-v8a"]}),
                "fallback_profiles": p.get("fallback_profiles", []), "default_selectable": p.get("default_selectable", True),
            }
            if "context_tokens" in p: prof["context_tokens"] = p["context_tokens"]
            profiles.append(prof)
        if v["default_profile"] not in {p["id"] for p in profiles}: fail(f"variant {v['id']}: default_profile")
        out["variants"].append({
            "id": v["id"], "runtime": v["runtime"], "handler": v["handler"], "runtime_range": v["runtime_range"],
            "inputs": v["inputs"], "files": vfiles, "default_profile": v["default_profile"], "profiles": profiles, "handler_config": hc,
        })
    if out["default_variant"] not in {v["id"] for v in out["variants"]}: fail("default_variant")

    entry = {
        "model_id": repo,
        "model_commit": commit,
        "gated": bool(gated),
        "path": f"catalog/entries/{repo.replace('/', '__')}.json",
        "provenance": spec.get("provenance", ""),
        "descriptor": out,
    }
    text = json.dumps(entry, indent=2, ensure_ascii=False) + "\n"
    if a.validate_only:
        print(f"ok: {repo}@{commit[:8]} {len(out['variants'])} variant(s)")
        return
    dest = a.out or entry["path"]
    os.makedirs(os.path.dirname(dest) or ".", exist_ok=True)
    open(dest, "w").write(text)
    print(f"wrote {dest}: {repo}@{commit[:8]} sha256(descriptor)={hashlib.sha256(json.dumps(out, indent=2, ensure_ascii=False).encode()).hexdigest()[:12]}")


if __name__ == "__main__":
    main()
