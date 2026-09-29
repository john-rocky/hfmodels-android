# samples/pong on the Galaxy S26, 2026-09-29

The records behind "Known problem: answers after the first decision" in [`samples/pong/README.md`](../../README.md). 60 decisions per run, the model side-loaded, LiteRT-LM 0.16.1.

| run | the app's file | backend | seed |
|---|---|---|---|
| r2_gpu_a | pong-result-1790647214.json | GPU | 7 |
| r2_gpu_b | pong-result-1790648091.json | GPU | 11 |
| r2_cpu_a | pong-result-1790648375.json | CPU | 7 |

- `<run>.json`: the record the app wrote, unchanged. The fields are listed under "The record" in the sample's README.
- `<run>.parity.json`: each decision's letter compared with a reference readout of the same PNG and prompt on a Mac, one decision at a time from a fresh state (`summary.agree`; `differ` lists the step, the phone's letter, the reference letter and the reference probabilities), and each PNG compared with the Mac's render of the same game state (`png_max_abs_diff_vs_mac_render`). `result` is the record's path on the machine that ran the check.
