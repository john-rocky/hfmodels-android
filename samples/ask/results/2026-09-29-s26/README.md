# samples/ask on the Galaxy S26, 2026-09-29

The records behind the "Numbers" section of [`samples/ask/README.md`](../../README.md). One picture per run, the model side-loaded, LiteRT-LM 0.16.1.

| run | the app's file | chart | backend | note |
|---|---|---|---|---|
| r2b_gpu_a | ask-result-1790652938.json | chart_00 | GPU | first load after the pushed file was hashed and imported |
| r3_take1 | ask-result-1790653191.json | chart_02 | GPU | screen recorded, airplane mode on, delay 10 s |
| r3_take2 | ask-result-1790653457.json | chart_01 | GPU | screen recorded, airplane mode on, delay 10 s |
| r2b_cpu_c | ask-result-1790653685.json | chart_02 | CPU | |
| r2b_gpu_c | ask-result-1790653942.json | chart_02 | GPU | |

- `<run>.json`: the record the app wrote for the picture, unchanged. The fields are listed under "The record" in the sample's README.
- `<run>.parity.json`: the phone's answers compared with three things: the same five turns sent on a Mac with a new engine per chart (`same_as_mac_conv`), a per-question reference readout (`same_as_ref`, with its top probability `ref_p_top`), and the chart's data (`matches_data`). `png_max_abs_diff` compares the PNG the phone sent with the chart PNG the Mac used. `result` is the record's path on the machine that ran the check.
