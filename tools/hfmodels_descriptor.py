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

The bundle header of every model file is also read (two HTTP range requests, no weights): a bundle
that declares channels in its own LlmMetadata is refused unless the spec spells them (the SDK would
apply the bundle's own otherwise, but the descriptor should say so), and a spec whose channels differ
from the bundle's is reported. `--no-bundle-header` skips that read.

Verification records are NOT written here; `tools/build_catalog.py` merges `verification/*.json`
(produced by the device gate) into the profiles when it builds the bundled catalog.
"""
import argparse, hashlib, json, os, struct, sys, urllib.parse, urllib.request

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


# ---- .litertlm header: section table (FlatBuffer) + LlmMetadata (protobuf), same layout the SDK reads ----
def _range(url, token, a, b):
    req = urllib.request.Request(url, headers={"User-Agent": "hfmodels-descriptor/0.1", "Range": f"bytes={a}-{b}", **({"Authorization": f"Bearer {token}"} if token else {})})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def _fb_sections(buf):
    u32 = lambda p: struct.unpack_from("<I", buf, p)[0]
    u16 = lambda p: struct.unpack_from("<H", buf, p)[0]
    i32 = lambda p: struct.unpack_from("<i", buf, p)[0]
    def field(table, index):
        vt = table - i32(table)
        slot = 4 + index * 2
        if slot + 2 > u16(vt): return -1
        off = u16(vt + slot)
        return table + off if off else -1
    tab = lambda p: p + u32(p)
    root = u32(0)
    sm = field(root, 1)
    if sm < 0: raise ValueError("no section_metadata")
    objs = field(tab(sm), 0)
    vec = tab(objs)
    n = u32(vec)
    out = []
    for i in range(n):
        o = tab(vec + 4 + i * 4)
        b = struct.unpack_from("<Q", buf, field(o, 1))[0] if field(o, 1) >= 0 else 0
        e = struct.unpack_from("<Q", buf, field(o, 2))[0] if field(o, 2) >= 0 else 0
        t = buf[field(o, 3)] if field(o, 3) >= 0 else 0
        out.append((t, b, e))
    return out


def _pb_fields(buf, a, b):
    p = a
    def varint():
        nonlocal p
        shift = v = 0
        while True:
            x = buf[p]; p += 1
            v |= (x & 0x7F) << shift
            if not x & 0x80: return v
            shift += 7
    while p < b:
        tag = varint(); num, wire = tag >> 3, tag & 7
        if wire == 0: yield num, wire, varint()
        elif wire == 1: yield num, wire, buf[p:p + 8]; p += 8
        elif wire == 2:
            n = varint(); yield num, wire, buf[p:p + n]; p += n
        elif wire == 5: yield num, wire, buf[p:p + 4]; p += 4
        else: raise ValueError(f"wire type {wire}")


def bundle_header(url, token):
    """{'channels': [{name,start,end}], 'model_prefix': str, 'jinja': bool} from a .litertlm URL, or raises."""
    head = _range(url, token, 0, 65535)
    if head[:8] != b"LITERTLM": raise ValueError("bad magic")
    hdr_end = struct.unpack_from("<Q", head, 24)[0]
    if hdr_end > len(head): head = _range(url, token, 0, hdr_end + 64)
    sections = _fb_sections(head[32:hdr_end])
    meta = next(((b, e) for t, b, e in sections if t == 5), None)
    if meta is None: return {"channels": [], "model_prefix": "", "jinja": False}
    raw = _range(url, token, meta[0], meta[1] - 1)
    out = {"channels": [], "model_prefix": "", "jinja": False}
    for num, wire, v in _pb_fields(raw, 0, len(raw)):
        if num == 3 and wire == 2:
            for n2, w2, v2 in _pb_fields(v, 0, len(v)):
                if n2 == 2 and w2 == 2:
                    for n3, w3, v3 in _pb_fields(v2, 0, len(v2)):
                        if n3 == 1 and w3 == 2: out["model_prefix"] = v3.decode("utf-8", "replace")
        elif num == 7 and wire == 2: out["jinja"] = len(v) > 0
        elif num == 8 and wire == 2:
            c = {"name": "", "start": "", "end": ""}
            for n2, w2, v2 in _pb_fields(v, 0, len(v)):
                if w2 == 2 and n2 in (1, 2, 3): c[("name", "start", "end")[n2 - 1]] = v2.decode("utf-8", "replace")
            out["channels"].append(c)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("spec")
    ap.add_argument("--out", default=None, help="where to write hfmodels.json (default: catalog/entries/<org>__<name>.json)")
    ap.add_argument("--token", default=os.environ.get("HF_TOKEN"))
    ap.add_argument("--validate-only", action="store_true")
    ap.add_argument("--no-bundle-header", action="store_true", help="skip reading each model file's .litertlm header from the Hub")
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
        model_file = next(f for f in vfiles if f["role"] == "model")
        if not a.no_bundle_header:
            try:
                bh = bundle_header(f"{HUB}/{repo}/resolve/{commit}/{model_file['path']}", a.token)
            except Exception as e:  # noqa: BLE001
                fail(f"variant {v['id']}: could not read the .litertlm header of '{model_file['path']}': {type(e).__name__}: {e} (use --no-bundle-header to skip)")
            spec_ch = [{"name": c.get("name"), "start": c.get("start"), "end": c.get("end", "")} for c in hc.get("channels", [])]
            if "thinking_default" in hc and (not isinstance(hc["thinking_default"], bool) or (hc["thinking_default"] and not spec_ch)):
                fail(f"variant {v['id']}: handler_config.thinking_default must be a bool and needs handler_config.channels")
            esc = lambda s: s.replace("\n", "\\n")
            desc = lambda chs: "[" + ",".join(f"{c['name']} {esc(c['start'])}..{esc(c['end'])}" for c in chs) + "]"
            if bh["channels"] and not spec_ch:
                fail(f"variant {v['id']}: '{model_file['path']}' declares channels {desc(bh['channels'])} in its header; spell them in handler_config.channels")
            if bh["channels"] and spec_ch and bh["channels"] != spec_ch:
                print(f"note: variant {v['id']}: handler_config.channels {desc(spec_ch)} override the bundle's {desc(bh['channels'])}", file=sys.stderr)
            print(f"note: variant {v['id']}: bundle header channels={desc(bh['channels'])} model.prefix={bh['model_prefix']!r} jinja={'yes' if bh['jinja'] else 'no'}", file=sys.stderr)
        profiles = []
        allowed_backends = None
        if hc.get("metadata_source") == "litertlm_manifest":
            if manifest is None: fail(f"variant {v['id']}: metadata_source=litertlm_manifest but the repo ships no litertlm_manifest.json")
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
