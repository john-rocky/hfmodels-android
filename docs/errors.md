# Error codes and what to do

Every failure is a `ModelException` with a `code` (below), a `reason`, `retryable`, and `details` (never a token, a prompt, an image or generated text). Cancellation stays a `CancellationException`.

| code | what happened | what to do |
|---|---|---|
| `MODEL_NOT_REGISTERED` | The repo at that commit has no `hfmodels.json` and the bundled catalog has no entry for it. | Publisher: add `hfmodels.json` (see the publisher guide). App: pass a descriptor with `LoadOptions(descriptorJson = …)`, or pick a catalogued id. |
| `MODEL_NOT_FOUND_OR_INACCESSIBLE` | The Hub answered 404 for the repo or the file: missing, private, or not visible to this token. | Check the id; for a private repo give a token through `LoadOptions(credentials = …)`. The SDK does not guess whether a private repo exists. |
| `AUTH_REQUIRED` | 401: the repo needs a token. | Provide a `CredentialProvider`. `details["url"]` is the model page. |
| `ACCESS_DENIED` | 403: gated, or the token lacks access. | Accept the terms on the model page (`details["url"]`); the SDK never does that for you. |
| `REVISION_NOT_FOUND` | The repo exists but not that branch/tag/commit. | Fix the revision, or drop it to use the default branch. |
| `MANIFEST_INVALID` | `hfmodels.json` (or a catalog) is malformed: bad path, bad sha256, unknown role, fallback cycle, … | Publisher: run `tools/hfmodels_descriptor.py --validate-only`. The SDK never falls back to an older descriptor silently. |
| `UNSUPPORTED_SCHEMA` | `schema_version` is not one this SDK reads. | Update the SDK, or the publisher lowers the schema. |
| `VARIANT_NOT_FOUND` | `ModelRef.variant` is not in the descriptor. | Use one of the ids in the message. |
| `HANDLER_NOT_INSTALLED` | The variant needs a handler / runtime module the app does not ship (or a different ABI). | Add the `hfmodels-<runtime>` dependency named in the message. |
| `RUNTIME_VERSION_MISMATCH` | The linked runtime is outside the descriptor's `runtime_range`. | Pin the runtime version the descriptor names, or ask the publisher to widen the range after verifying. |
| `METADATA_MISMATCH` | The descriptor's sha256 / bytes disagree with what the Hub lists for that commit. | Publisher: regenerate the descriptor. Never edit hashes by hand. |
| `NO_COMPATIBLE_PROFILE` | No profile fits the device, the policy or `requiredInputs`; `details` lists each profile and why (including "a verification record says FAIL and none says PASS", which `Auto` skips). | Loosen the policy (`Auto`), or pick a variant that has a fitting profile; `RequireProfile(id)` names a FAIL-recorded profile knowingly. |
| `NATIVE_MODULE_MISSING` | A required native module (e.g. an NPU library) is not in the app. | Add the vendor module the descriptor names. (No NPU profile ships in this release.) |
| `DOWNLOAD_POLICY_BLOCKED` | The load would fetch more than `maxDownloadBytes`, or the network is metered under `Unmetered`. | Raise the limit, wait for Wi-Fi, or pass `NetworkPolicy.Any`. Nothing was fetched. |
| `ADDITIONAL_DOWNLOAD_REQUIRED` | A fallback profile needs a file that is not local. | Run `download` for the plan named in the message, or allow it with `allowAdditionalArtifacts`. |
| `NETWORK_ERROR` | Transfer failed after 3 attempts (cut connection, timeout, 5xx, 429). The partial stays on disk. | Call again later: the download resumes from the partial. |
| `STORAGE_FULL` | Not enough free space for the files plus a 256 MiB margin (checked before any transfer), or the disk filled during the write. | Free space or `evict(plan)` another model. |
| `CHECKSUM_MISMATCH` | Bytes did not match the descriptor's sha256 / size. The partial was deleted; a previously verified file is untouched. | Retry once; if it repeats, the publisher's descriptor or the file changed — never load an unverified file. |
| `OFFLINE_CACHE_MISS` | `NetworkPolicy.Offline`, and the descriptor, binding or a file is not cached. | Load once online (this saves the binding), or pin a commit and side-load with `importFile`. |
| `UNSUPPORTED_INPUT` | The content kind is not enabled by this load (image on a text-only profile, audio, two images). | Check `ChatModel.enabledInputs`; load with `requiredInputs = setOf(TEXT, IMAGE)` for a VLM. |
| `INVALID_INPUT` | Image missing / undecodable / over `maxImageBytes` or `maxImagePixels`. | Resize or fix the file. The session stays READY. |
| `UNSUPPORTED_CONFIGURATION` | A `ConversationConfig` feature outside this release (tools, response format, LoRA; 0.1.0 also refused channels and thinking), or `contextTokens` above the declared maximum. | Remove the option. Nothing was passed to the runtime. |
| `CONTEXT_LIMIT_EXCEEDED` | The prompt plus history exceed the context. | Start a new conversation or shorten the input. |
| `MODEL_BUSY` | A second `prepare` while a model is open, or a second generation while one runs. | Close / wait; one native model per client, one generation per model. |
| `MODEL_CLOSED` | The client, model or session is closing or closed. | Create a new one. |
| `SESSION_INVALIDATED` | The session was cancelled or failed; it cannot be reused (the runtime's own rule). | `createConversation()` again; pass earlier turns as `initialMessages` if you keep history. |
| `STREAM_ALREADY_COLLECTED` | A `Flow` from `stream()` was collected twice. | Call `stream()` again for the next generation. |
| `SLOW_CONSUMER` | The collector fell more than 1,024 chunks / 8 MiB behind; the native side was cancelled. No chunk was dropped silently. | Consume the flow promptly (append to a buffer, render later). |
| `INITIALIZATION_FAILED` | `Engine.initialize()` / `createConversation` failed; `details["stage"]` and `fallback_tried` say where. | Try another profile (`Require(CPU)`); check free RAM; keep the message for the publisher. |
| `INFERENCE_FAILED` | The runtime failed after generation started. The session is INVALID. | New conversation; if it repeats on the same input, keep the message for the publisher. |
| `NATIVE_STOP_TIMEOUT` | The runtime did not confirm a stop within 10 s. The model is UNUSABLE and its handle is not freed. | Restart the process; report the model id, runtime version and device. |
