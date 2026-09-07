# Publishing a model for hfmodels

A repo on Hugging Face becomes loadable by id when its root carries `hfmodels.json` (schema 1). The SDK reads it at the resolved commit, verifies every file it lists against the sha256 / bytes in it, and only then initializes. Nothing in the file is a measurement; measured numbers stay in the repo's `litertlm_manifest.json` and the model card.

## 1. Write a spec (what only you know)

`catalog/specs/<owner>__<name>.json` in this repository is the shape (copy one). It names: `model_id`, the `revision` to pin (a commit; `main` is resolved and recorded), `tasks`, `license` (`id` + `url`), `default_variant`, and per variant: `runtime` (`litert_lm`), `handler` (`litertlm.conversation` ABI 1), `runtime_range`, `inputs`, `files` (id / role / path — no hashes), `default_profile`, `profiles` (components per backend, enabled inputs, requirements, fallback order, `default_selectable`), and `handler_config`.

`handler_config.metadata_source`:
- `litertlm_manifest` — the repo ships `litertlm_manifest.json`; the generator cross-checks each file's sha256 / size against it, refuses a profile whose language backend the manifest does not list as verified, imports `context_length`, and refuses a bundle whose manifest declares a thinking channel unless `handler_config.channels` spells it out;
- `publisher_declared` — no manifest; the spec's word stands, and the SDK reports the load as publisher-declared.

`handler_config.channels` (optional) — the reasoning channel(s) the SDK applies to every conversation, `[{"name": "thought", "start": "<think>", "end": "</think>"}]`. The generator reads every model file's `.litertlm` header from the Hub (two range requests, no weights) and refuses a bundle that declares channels in its own `LlmMetadata` unless the spec spells them, so a descriptor is complete on its own; when both are present and differ, the spec wins and the generator says so. Use the model's real markers, newlines included (Qwen3-style models emit `<think>\n` … `\n</think>`; Gemma 4 declares `<|channel>thought\n` … `<channel|>`). Add `"thinking_default": true` when the model reasons on every turn unless told not to (Qwen3, DeepSeek-R1-Distill, `*-Thinking`); leave it out for a model whose channel is declared but idle by default (Gemma 4, LFM2.5 instruct). The SDK's device gate then requires the reasoning to arrive in `Message.channels` for `thinking_default` entries and, for every entry with a channel, that no marker leaks into the text.

## 2. Generate, never type, the hashes

```sh
tools/hfmodels_descriptor.py catalog/specs/<owner>__<name>.json --validate-only   # check against the Hub
tools/hfmodels_descriptor.py catalog/specs/<owner>__<name>.json --out hfmodels.json
```

The tool resolves the revision to a commit, reads `bytes` and `sha256` from the Hub's LFS metadata for every listed file, and writes the descriptor inside a catalog entry (`descriptor` is the exact `hfmodels.json` to upload). It fails on: a file not in the repo, a non-LFS file (no sha256), a role / backend / input outside the schema, a fallback cycle, a manifest disagreement.

## 3. Upload

```sh
hf upload <owner>/<name> hfmodels.json hfmodels.json
```

Load with `ModelRef("<owner>/<name>")`. Pin `revision` to the commit that carries the file if you want an immutable pointer.

## 4. Verify on a device, and say so

Run the SDK's catalog gate or your own app and record the result as a verification record (`verification/*.json`: model, commit, variant, profile, device, OS build, runtime, PASS / FAIL, date). `tools/build_catalog.py` merges records into `profiles[].verification`; the SDK reports the level (`PUBLISHER_TESTED` / `MAINTAINER_TESTED` / `UNVERIFIED`) and never auto-selects an NPU profile without a PASS. A PASS on one phone says nothing about another: records carry the exact device and OS build.

## Rules the SDK enforces

- `model_id` must equal the repo; paths are relative, no `..`; `sha256` is 64 lower-case hex; `bytes` is non-negative.
- Every `profiles[].files` id exists; fallback profiles exist and form no cycle; `enabled_inputs` stay within the variant's `inputs`.
- `schema_version` other than 1 is `UNSUPPORTED_SCHEMA`; anything malformed is `MANIFEST_INVALID` — the SDK never falls back to an older descriptor silently.
- A disagreement between the descriptor and the Hub's LFS metadata at the same commit is `METADATA_MISMATCH`.

## Catalog entries (repos you cannot edit)

`catalog/entries/*.json` are external descriptors for repos whose owners did not add `hfmodels.json`; the bundled asset built from them is public at <https://raw.githubusercontent.com/john-rocky/hfmodels-android/main/core/src/main/assets/hfmodels/catalog.json>. Each pins one model commit and records its own origin (this repository) separately from the model origin, so the lock and the report say where each came from. Open a pull request with a spec under `catalog/specs/` and the generated entry.
