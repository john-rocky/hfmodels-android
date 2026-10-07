worker: hfmodels-android-57 [3a4dc2]

## 経過
- 02:48 開始。launch 全文、ROUND1.md、gate.sh / shim / check_run.py / take.sh、MainActivity.kt 全文、summary.json、memory 12 本、queue_cli.py / hold_cli.py / device_hold.py を読んだ。hold = `migration-skill-support-2-3-0-s26`(pid 39529、02:11:50 から)、queue 空(02:51、cat で読んだ)。Mac の計測窓 = `et-r5-mac timing pid 27897`(02:46:13 から)が開いている。次 = gate.sh に leg・fail・shots を足す。
- 02:57 launch の sleeper の形 `S=$(sleep 36000 & echo $!)` を zsh で `sleep 3` に置き換えて測った: 3.0 s 返らない(command substitution の pipe を background の sleep が持つ)。redirect 付き `S=$(sleep 36000 </dev/null >/dev/null 2>&1 & echo $!)` は 0.001 s。wrapper はこの形。
- 03:00 round 1 の gate.sh / shim / check_run.py を scratchpad に写してから書き換え。gate.sh: leg 5 本(`gpu npu npu_audio_npu gpu_audio_gpu cpu`)、`fail`(bundle / permission / mic / busy / back の 5 sub-step、証拠 = `fail_<name>.log`)、`shots`、hold の取得時刻から 25 分で新しい step を始めない(CARRY_OVER)・28 分で step を切る、停止条件(adb から消えた 4 / MemAvailable < 2,000,000 kB か lmkd の kill 行 5 / crash 2 leg 連続 6 / 10 分 ready にならない 7)を exit code に。leg は DONE / ERROR の他に 4 分 app の行が増えなければ stalled として次へ。install は 120 s で打ち切り。
- 03:07 dry run の残りの sleeper を `pkill -f "sleep 900"` で消した。pid 指定(10938)にすべきだった(launch の禁止 `pgrep -f` と同じ類)。他 lane の `sleep 900` を巻き込んだかは確認できていない(03:08 の `ps` に `sleep 900` は無く、hold の pid 39529 は生きている)。以後 pid で kill。
- 03:06〜03:13 dry run(`scripts/dryrun_gate.zsh` = 偽 hold・小さな偽 bundle・shim):round 2 の順の 12 step 全部 PASS、exit 0(`out/dryrun_gate_r2/`)。止まる道も shim で通した: 25 分枠 → exit 3 + CARRY_OVER、npu の crash 2 回 → exit 6、MemAvailable 1,500,000 kB → exit 5、28 分 → exit 8、stall → 次の leg、120 ms 押しが録音した時 → tap で MIC_REJECTED。take.sh MODE=file / mic の dry run も新しい shim で exit 0。関数の外の裸の adb は gate.sh / run_device_r2.zsh に無い(grep で `dev()` の 1 行と echo の文字列 2 行だけ)。
- 03:10 check_run.py を leg 横断の表に書き換え(`--device-dir`)。`scripts/fake_device_runs.py` で Mac の scores から偽の 5 leg(gpu / gpu_audio_gpu に 1 問の外れ / cpu / npu の init error / npu_audio_npu の run JSON 無し)を作って通した: exit 0、表は `out/dryrun_check/check_run.md`。
- 03:11 `scripts/run_device_r2.zsh`(sleeper → enqueue && wait → gate → release → sleeper kill を 1 process で、29 分の watchdog)を偽の hold file で通した: 取得 → 12 step PASS → release → queue 空 → sleeper 消滅、exit 0。queue の待ちが切れる道(WAIT_S=4、他 pid が保持)→ dequeue・sleeper kill・gate 未実行・exit 3。
- 03:14 Mac 側 PASS。監督に「Mac 側 PASS、端末待ち」を送って go を待つ。
- 03:15 監督の go(03:15 の状態: hold = migration-skill-support-2-3-0-s26 pid 39529、queue 空、thermal 0、SKIN 37.9、litert process 0)。`scripts/run_device_r2.zsh` を background で 1 本(順番は queue の wait)。
- 03:15:35 enqueue(`eg2demo-gate-r2`、keeper = sleeper pid 72629、queue_cli の waiter pid 72654、timeout 3600 s)。holder は migration-skill-support-2-3-0-s26(pid 39529、02:11:50 から、その owner は `time.sleep(4*3600)` の python)。次 = wait が hold を取ったら gate が自動で始まる。
- 03:37:59 hold 取得(`queue_cli.py wait`、migration-skill-support-2-3-0-s26 が返した後。03:15:35 から待った)。端末の状態(`out/device/gate.log` の「state before」): uptime 459567.87 s(監督が 02:4x に読んだ 456,302 s から続いている = 再起動なし)、Thermal Status 0、SKIN 37.9、CPU policy0 3628800 / 3628800・policy6 4742400 / 4742400(非 cap)、kgsl max_clock_mhz 1300・thermal_pwrlevel 0・temp 31400、MemAvailable 6108016 kB、litert process 0、mWakefulness Dozing、/data 空き 11G。`top -b -n 1` の上位: top 自身 17.8 %、com.samsung.android.smartsuggestions 7.1 %(TIME 42:18.78)、surfaceflinger 3.5 %、idle 768 % / 800 %。
- 03:38:36 setup 済み(held 37 s): install Success、RECORD_AUDIO grant、app が files/{album,queries,mic} を作った、push 81 本が全部 phone の sha256 と一致(base 484622336 B を 9.586 s = 48.2 MB/s、SM8850 559448064 B を 9.813 s = 54.4 MB/s)。
- 03:39:32〜03:42:11 leg 5 本。各 leg の前の ready 待ちは全部 0 s。gpu DONE 103 RESULT / npu DONE だが RESULT 0(computeEmbedding 139 回が全部失敗)/ npu_audio_npu ERROR init / gpu_audio_gpu DONE 103 / cpu DONE 103。lmkd の kill 行と crash buffer の行はどの leg も 0(`*_lmk.log`・`*_crash.log` が 0 byte)。cgroup は全 leg `cpuset:/top-app`。cap が出たのは cpu leg の直後の state だけ(policy0 3513600 / 3628800、policy6 4185600 / 4742400)。
- 03:42:13〜03:43:03 fail 5 本と shots が PASS。03:43:04 gate end rc=0(held 305 s)、CARRY_OVER なし。
- 03:43:05 release(`hold_cli.py release` → sleeper 72629 kill)。直後の `queue_cli.py list`: holder none、列は et-r5-s26-0.6B(edge-llm-bench-24、keeper waiting)だけ = 自分の札なし。03:43:06 に et-r5-s26-0.6B が hold を取った(hold file)。03:47 の `ps -p 72629` で sleeper は消えている。
- 03:44〜03:58 検証(Mac): check_run.py の表、`scripts/r2_extra.py`(表に無い数字)、NPU の logcat、fail の log、screenshot 3 枚と fail 画面 3 枚を目で見た。check_run.py の app 行の除外を直してから表を出した(端末の logcat は tag を 8 文字に詰めて `Eg2Demo :` と出す)。

## 線(事前登録)
02:54 に launch から写した(端末で採点する前)。
- GPU leg(`gpu`、vision GPU・audio CPU、base bundle)、声 A: top-1 ≥ 16/20 かつ top-3 ≥ 19/20 → round 3(撮影)へ。割ったら数字を添えて監督に SendMessage(判断)。
- NPU・CPU の線は無い(表に出すだけ)。
- NPU を「載った」と書けるのは、logcat に dispatch / QNN / HTP の行(`Replacing` か backend 名の行)がある時だけ。init の例外なら `ERROR init` の文を貼って「未達」。

## 線の判定
PASS → round 3(撮影)へ。GPU leg の声 A は top-1 20/20、top-3 20/20(下の表の gpu 行と line 行、`out/device/check_run.md`)。声 B(参考)は 19/20・20/20 で、外れは round 1 と同じ q08_b(正解 a10 の猫が 2 位、1 位は a11 の犬)。

## 表(check_run.py の出力)
`venv/bin/python -I scripts/check_run.py --device-dir out/device --json out/device/check_run.json > out/device/check_run.md` の先頭。leg ごとの詳細(group の表、backend 行の表)は同じ file にある。

| leg | asked backend (vision / audio) | init ms | 1 photo ms median (n) | audio embed_ms median / p90 (n) | text embed_ms median (n) | voice A hit1 · hit3 | voice B hit1 · hit3 | text card · devsite · none hit1 | top-1 = Mac GPU | max abs Δcos vs Mac GPU · CPU | decoy max cos | QNN · HTP · dispatch lines | error |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| gpu | gpu (gpu / cpu) | 3292.8 | 237.4 (36) | 104.0 / 116.0 (43) | 40.5 (60) | 20/20 · 20/20 | 19/20 · 20/20 | 20/20 · 20/20 · 20/20 | 102/103 | 0.0140 · 0.0116 | 0.5926 | 0 · 0 · 1 |  |
| npu | npu (npu / cpu) | 304.3 | - | - / - (0) | - (0) | - | - | - · - · - | 0/0 | - · - | - | 155 · 102 · 58 | 103 row errors |
| npu_audio_npu | npu (npu / npu) | - | - | - / - (0) | - (0) | - | - | - · - · - | 0/0 | - · - | - | 0 · 0 · 1 | init: com.google.ai.edge.litertlm.LiteRtLmJniException: Failed to create EmbeddingEngineImpl: INVALID_ARGUMENT: ERROR: [third_party/odml/litert_lm/runtime/core/ |
| gpu_audio_gpu | gpu (gpu / gpu) | 2242.4 | cached | 125.1 / 139.6 (43) | 41.1 (60) | 20/20 · 20/20 | 19/20 · 20/20 | 20/20 · 20/20 · 20/20 | 102/103 | 0.0665 · 0.0654 | 0.5984 | 0 · 0 · 1 |  |
| cpu | cpu (cpu / cpu) | 546.5 | 757.6 (36) | 146.4 / 181.0 (43) | 38.7 (60) | 20/20 · 20/20 | 20/20 · 20/20 | 20/20 · 20/20 · 20/20 | 102/103 | 0.0152 · 0.0090 | 0.5913 | 0 · 0 · 1 |  |

line (gpu): voice A top-1 20/20, top-3 20/20 -> PASS (audio -> photo, voice A, top-1 >= 16/20 and top-3 >= 19/20)

- 「1 photo ms」= album の index を作った時の写真 1 枚の computeEmbedding の wall ms。gpu_audio_gpu は gpu leg の index cache を使ったので cached。「audio embed_ms」= clip 1 本(約 2 秒)の computeEmbedding の wall ms = 画面の「audio → vector」。
- 表に無い数字(`scripts/r2_extra.py` → `out/device/r2_extra.txt`):

```
## gpu (gpu_eg2-demo-1791398317210.json)
- top-1 different from the Mac GPU run: d01_a phone a14 0.5761 / Mac a11 0.5822
- largest abs delta of the top-1 cos (same photo): q11_t_card 0.0140, q18_a 0.0127, q07_t_none 0.0120; audio median 0.0032
- voice A: gap 1st - 2nd min 0.0272, median 0.1020; lowest cos of a hit 0.6412
- q08_a: gold rank 1, a10 0.6412, a11 0.6139
- q08_b: gold rank 2, a11 0.6505, a10 0.6422
## gpu_audio_gpu (gpu_audio_gpu_eg2-demo-1791398413512.json)
- top-1 different from the Mac GPU run: d01_a phone a14 0.5766 / Mac a11 0.5822
- largest abs delta of the top-1 cos (same photo): q18_a 0.0665, q11_t_card 0.0140, q07_t_none 0.0120; audio median 0.0043
- voice A: gap 1st - 2nd min 0.0274, median 0.0925; lowest cos of a hit 0.6397
- q08_a: gold rank 1, a10 0.6397, a11 0.6123
- q08_b: gold rank 2, a11 0.6470, a10 0.6465
## cpu (cpu_eg2-demo-1791398460931.json)
- top-1 different from the Mac GPU run: q08_b phone a10 0.6404 / Mac a11 0.6561
- largest abs delta of the top-1 cos (same photo): d01_a 0.0152, q11_t_card 0.0137, q18_a 0.0101; audio median 0.0032
- voice A: gap 1st - 2nd min 0.0288, median 0.1031; lowest cos of a hit 0.6429
- q08_a: gold rank 1, a10 0.6429, a11 0.6142
- q08_b: gold rank 1, a10 0.6404, a11 0.6401
## fail_bundle_screen.png: 14505/17388 px of the pill box (60, 475, 207 x 84) within 25 of #E53935 (83%)
```

## NPU の判定: 未達
- npu(vision NPU・audio CPU、SM8850 bundle): engine の init は通った(ENGINE_READY init_ms=304.3)。しかし QNN の HTP backend が起動できず(`Failed to load skel, error: 4000` → `Failed to initialize QNN backend`)、写真 36 枚と query 103 本の computeEmbedding が全部 `Failed to allocate tensors` で失敗した(album 0 枚、RESULT 0)。dispatch / QNN / HTP の行はあるが、全部「起動に失敗した」行で、HTP で計算した行は無い。だから「載った」とは書けない。init は通るので `ERROR init` には出ない(launch の想定と違う。訂正に書いた)。
- 証拠の行(`out/device/npu_app_logcat.log`・`npu_demo.log` から抜き出し、`out/device/npu_evidence.txt`):

```
10-08 03:39:33.245 27435 27455 I litert  : [litert_dispatch.cc:186] Loading shared library: /data/app/~~sULHG0UqLXqQFHrUEsKGEA==/com.mlboydaisuke.eg2demo-pbtkOSm1pjuVXTHlVCIy7A==/lib/arm64/libLiteRtDispatch_Qualcomm.so
10-08 03:39:33.245 27435 27455 W litert  : [compiled_model.cc:815] Compiler plugin path is provided in the environment, but the model is pre-compiled. Plugins won't be applied.
10-08 03:39:33.246 27435 27455 I litert  :   BackendType              : Htp(2)
10-08 03:39:33.246 27435 27455 I litert  :   EnableJustInTime         : false
10-08 03:39:33.246 27435 27455 I litert  : [qnn_manager.cc:488] Adding shared library dir to path: /data/app/~~sULHG0UqLXqQFHrUEsKGEA==/com.mlboydaisuke.eg2demo-pbtkOSm1pjuVXTHlVCIy7A==/lib/arm64
10-08 03:39:33.258 27435 27455 I litert  : [qnn_manager.cc:165] Loaded qnn shared library
10-08 03:39:33.262 27435 27455 E QnnDsp  : QnnDsp <E> loadRemoteSymbols failed with err 4000
10-08 03:39:33.262 27435 27455 E QnnDsp  : QnnDsp <E> Failed to load skel, error: 4000
10-08 03:39:33.262 27435 27455 E QnnDsp  : QnnDsp <E> Transport layer setup failed: 14001
10-08 03:39:33.262 27435 27455 E litert  : [dispatch_api.cc:145] Failed to initialize QNN backend
10-08 03:39:33.262 27435 27455 E litert  : [compiled_model.cc:1063] Failed to get hooks from accelerator: 3
10-08 03:39:33.510 27435 27455 I tflite  : Replacing 1 out of 1 node(s) with delegate (DispatchDelegate) node, yielding 1 partitions for subgraph 0 (encoder_1x1024).
10-08 03:39:33.262 27435 27455 I tflite  : Replacing 1 out of 1 node(s) with delegate (DispatchDelegate) node, yielding 1 partitions for subgraph 0 (vision_140).
10-08 03:39:34.091 27435 27455 E Eg2Demo : ERROR query d01_a ComputeEmbedding failed: UNKNOWN: ERROR: [third_party/odml/litert_lm/runtime/core/embedding_engine_impl.cc:1598]
10-08 03:39:34.091 27435 27455 E Eg2Demo : └ ERROR: [third_party/odml/litert_lm/runtime/executor/embedding_litert_compiled_model_executor.cc:600]
10-08 03:39:34.091 27435 27455 E Eg2Demo : └ ERROR: [third_party/odml/litert_lm/runtime/executor/embedding_litert_compiled_model_executor.cc:476]
10-08 03:39:34.091 27435 27455 E Eg2Demo : └ Failed to invoke the compiled model
10-08 03:39:34.091 27435 27455 E Eg2Demo : └ Failed to allocate tensors
10-08 03:39:34.016 27435 27455 I Eg2Demo : INDEX_DONE n=0 total_ms=498.7 per_image_ms_median=- cached=false
10-08 03:40:09.050 27435 27455 I Eg2Demo : DONE json=/storage/emulated/0/Android/data/com.mlboydaisuke.eg2demo/files/Documents/eg2-demo-1791398372588.json
```

- 読める理由: 失敗は DSP 側の skel(`libQnnHtpV81Skel.so`)を FastRPC で開く段で、bundle の QNN context を読む前に止まっている。だから QAIRT 2.47(APK の .so)と 2.50(LiteRT-LM v0.18.0 の pin)の版の差は、この run では試されていない = 未確認のまま。app の log には lib dir を LD_LIBRARY_PATH に足した行(`qnn_manager.cc:488`、`dynamic_loading.cc:143`)はあるが、ADSP_LIBRARY_PATH(DSP が skel を探す path)の行は無い。kev lane の NPU 実行は CLI で `ADSP_LIBRARY_PATH='<stage>;/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp'` を明示していた(`~/code/litertlm-convert/kev_work/device/r13/kev_s26_r13_N2_npucpu_cached_b128.cmd.txt`)。app の process に skel の探索 path が無い事が原因かは未確認(app を変えて試していない)。
- npu_audio_npu(audio も NPU): init で拒否された = SM8850 bundle の audio encoder は NPU を許さない。

```
10-08 03:40:11.664 28004 28025 E Eg2Demo : ERROR init Failed to create EmbeddingEngineImpl: INVALID_ARGUMENT: ERROR: [third_party/odml/litert_lm/runtime/core/embedding_engine_impl.cc:402]
10-08 03:40:11.664 28004 28025 E Eg2Demo : └ ERROR: [third_party/odml/litert_lm/runtime/engine/embedding_engine_settings.cc:312]
10-08 03:40:11.664 28004 28025 E Eg2Demo : └ Audio backend constraint mismatch. Model requires one of [cpu,gpu] but Audio backend is NPU
```

- vision と audio がどの backend で動いたか(`Replacing` 行、`out/device/delegates.txt`): gpu leg は text の encoder(`encoder_1x128`〜`encoder_1x1024`)と vision(`vision_70` / `vision_140`)が GPU(LITERT_CL、全 node)、audio の `main`(1600 node)は XNNPACK 1436 / 1600(226 partitions、残りは TFLite の CPU kernel)。gpu_audio_gpu は audio の `main` も GPU(1600 / 1600)。npu leg は encoder と vision が DispatchDelegate 1 / 1(AOT の QNN context が 1 node)、audio は XNNPACK。cpu leg は全部 XNNPACK。

```
gpu (XNNPACK rms_norm subgraphs: 109)
  encoder_1x1024: 1011/1011 -> LITERT_CL (1 partitions)
  encoder_1x128: 1011/1011 -> LITERT_CL (1 partitions)
  encoder_1x256: 1011/1011 -> LITERT_CL (1 partitions)
  encoder_1x512: 1011/1011 -> LITERT_CL (1 partitions)
  main: 1436/1600 -> TfLiteXNNPackDelegate (226 partitions)
  main: 2/5 -> TfLiteXNNPackDelegate (2 partitions)
  main: 2/8 -> TfLiteXNNPackDelegate (2 partitions)
  vision_140: 1477/1477 -> LITERT_CL (1 partitions)
  vision_70: 1477/1477 -> LITERT_CL (1 partitions)
  vision_adapter_140: 1/3 -> TfLiteXNNPackDelegate (2 partitions)
  vision_adapter_140: 13/13 -> TfLiteXNNPackDelegate (1 partitions)
  vision_adapter_70: 1/3 -> TfLiteXNNPackDelegate (2 partitions)
npu (XNNPACK rms_norm subgraphs: 109)
  encoder_1x1024: 1/1 -> DispatchDelegate (1 partitions)
  encoder_1x128: 1/1 -> DispatchDelegate (1 partitions)
  encoder_1x256: 1/1 -> DispatchDelegate (1 partitions)
  encoder_1x512: 1/1 -> DispatchDelegate (1 partitions)
  main: 1436/1600 -> TfLiteXNNPackDelegate (226 partitions)
  main: 2/5 -> TfLiteXNNPackDelegate (2 partitions)
  main: 2/8 -> TfLiteXNNPackDelegate (2 partitions)
  vision_140: 1/1 -> DispatchDelegate (1 partitions)
  vision_70: 1/1 -> DispatchDelegate (1 partitions)
  vision_adapter_140: 1/3 -> TfLiteXNNPackDelegate (2 partitions)
  vision_adapter_70: 1/3 -> TfLiteXNNPackDelegate (2 partitions)
gpu_audio_gpu (XNNPACK rms_norm subgraphs: 1)
  encoder_1x1024: 1011/1011 -> LITERT_CL (1 partitions)
  encoder_1x128: 1011/1011 -> LITERT_CL (1 partitions)
  encoder_1x256: 1011/1011 -> LITERT_CL (1 partitions)
  encoder_1x512: 1011/1011 -> LITERT_CL (1 partitions)
  main: 1600/1600 -> LITERT_CL (1 partitions)
  main: 2/5 -> TfLiteXNNPackDelegate (2 partitions)
  main: 2/8 -> TfLiteXNNPackDelegate (2 partitions)
  vision_140: 1477/1477 -> LITERT_CL (1 partitions)
  vision_70: 1477/1477 -> LITERT_CL (1 partitions)
  vision_adapter_140: 1/3 -> TfLiteXNNPackDelegate (2 partitions)
  vision_adapter_70: 1/3 -> TfLiteXNNPackDelegate (2 partitions)
cpu (XNNPACK rms_norm subgraphs: 109)
  encoder_1x1024: 765/1011 -> TfLiteXNNPackDelegate (339 partitions)
  encoder_1x128: 2649/2749 -> TfLiteXNNPackDelegate (3 partitions)
  encoder_1x128: 765/1011 -> TfLiteXNNPackDelegate (339 partitions)
  encoder_1x256: 2649/2749 -> TfLiteXNNPackDelegate (3 partitions)
  encoder_1x256: 765/1011 -> TfLiteXNNPackDelegate (339 partitions)
  encoder_1x512: 765/1011 -> TfLiteXNNPackDelegate (339 partitions)
  main: 1436/1600 -> TfLiteXNNPackDelegate (226 partitions)
  main: 2/5 -> TfLiteXNNPackDelegate (2 partitions)
  main: 2/8 -> TfLiteXNNPackDelegate (2 partitions)
  vision_140: 1315/1477 -> TfLiteXNNPackDelegate (163 partitions)
  vision_140: 1987/2037 -> TfLiteXNNPackDelegate (4 partitions)
  vision_70: 1315/1477 -> TfLiteXNNPackDelegate (163 partitions)
  vision_adapter_140: 1/3 -> TfLiteXNNPackDelegate (2 partitions)
  vision_adapter_140: 13/13 -> TfLiteXNNPackDelegate (1 partitions)
  vision_adapter_70: 1/3 -> TfLiteXNNPackDelegate (2 partitions)
```

## 失敗の道
証拠 = `out/device/fail_<name>.log`(抜き出し = `out/device/fail_evidence.txt`)。5 本とも PASS。

```
## 03:42:13 STEP fail_bundle PASS ERROR line, process alive (held 254 s)
## 03:42:22 STEP fail_permission PASS MIC_PERMISSION missing, granted back (held 263 s)
## 03:42:31 STEP fail_mic PASS 1.5 s press -> RESULT; reject from the tap (held 272 s)
## 03:42:35 STEP fail_busy PASS one IGNORED, one RESULT (held 276 s)
## 03:42:47 STEP fail_back PASS activity ended, no FATAL / IllegalStateException (process 10 s after: alive) (held 288 s)
## 03:43:03 STEP shots PASS shot1_ready.png shot2_q01.png shot3_q13.png (held 304 s)

## 1500 ms press at x=540 y=2080 (device time 10-08 03:42:23.000) -> 10-08 03:42:25.241 29947 29965 I Eg2Demo : RESULT kind=audio id=mic-1791398543548 seconds=1.35 embed_ms=82.4 rank_ms=1.48 top1=a12 top1_cos=0.6330 top3=a12:0.6330,a31:0.6307,a11:0.6287 gold=- hit1=- hit3=-
## 120 ms press (device time 10-08 03:42:27.000) -> 10-08 03:42:27.644 29947 29965 I Eg2Demo : RESULT kind=audio id=mic-1791398547406 seconds=0.05 embed_ms=51.6 rank_ms=1.46 top1=a21 top1_cos=0.6782 top3=a21:0.6782,a09:0.6751,a17:0.6739 gold=- hit1=- hit3=-
## tap (device time 10-08 03:42:29.000) -> 10-08 03:42:29.953 29947 29947 W Eg2Demo : MIC_REJECTED bytes=0 error=-
## since 10-08 03:42:32.000: IGNORED busy source=intent x1, RESULT q01_a x1, 10-08 03:42:34.841 29947 29965 I Eg2Demo : DONE json=/storage/emulated/0/Android/data/com.mlboydaisuke.eg2demo/files/Documents/eg2-demo-1791398534311.json
## line: 10-08 03:42:11.417 29821 29840 E Eg2Demo : ERROR bundle missing /storage/emulated/0/Android/data/com.mlboydaisuke.eg2demo/files/no-such.litertlm
## process 29821 after the line: alive
## press x=540 y=2080 600 ms -> 10-08 03:42:17.785 29947 29947 W Eg2Demo : MIC_PERMISSION missing
## top activity after the press: topResumedActivity=ActivityRecord{50262609 u0 com.google.android.permissioncontroller/com.android.permissioncontroller.permission.ui.GrantPermissionsActivity t1364} (screencap fail_permission_screen.png)
## pm grant, then BACK only if the dialog was still in front: 10-08 03:42:20.018 29947 29947 I Eg2Demo : MIC_PERMISSION granted=true
## after: android.permission.RECORD_AUDIO: granted=true, flags=[ USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]; top activity: topResumedActivity=ActivityRecord{49246236 u0 com.mlboydaisuke.eg2demo/.MainActivity t1364} -> topResumedActivity=ActivityRecord{49246236 u0 com.mlboydaisuke.eg2dem
## events:
## top activity 3 s after: ResumedActivity: ActivityRecord{228760873 u0 android.template/.ui.MainActivity t1355}
## process 3 s after: alive, 10 s after: alive
## FATAL / IllegalStateException / close failed lines: none
## crash buffer lines: none
10-08 03:42:36.901  3448  7771 I wm_finish_activity: [0,49246236,1364,com.mlboydaisuke.eg2demo/.MainActivity,app-request]
10-08 03:42:36.924  3448  3792 I wm_destroy_activity: [0,49246236,1364,com.mlboydaisuke.eg2demo/.MainActivity,finish-idle]
10-08 03:42:36.928 29947 29947 I wm_on_destroy_called: [49246236,com.mlboydaisuke.eg2demo.MainActivity,performDestroy,1]
10-08 03:42:37.215 29947 29965 I litert  : [accelerator_registry.cc:43] DestroyAccelerator: ptr=0xb400006f6283af30, name=CpuAccelerator
10-08 03:42:37.215 29947 29965 I litert  : [accelerator_registry.cc:43] DestroyAccelerator: ptr=0xb400006f62835510, name=LiteRT GPU
```

- (a) bundle 無し: `ERROR bundle missing …` の後も process は生きていて、pill は赤(`fail_bundle_screen.png`。pill の箱の画素の割合は `out/device/r2_extra.txt` の最後の行)。
- (b) permission: revoke の後、mic ボタンを押すと `MIC_PERMISSION missing`、Samsung の許可 sheet が出た(日本語、`fail_permission_screen.png`)。`pm grant` で sheet は自分で閉じ、app に `MIC_PERMISSION granted=true` が届いた。BACK は送っていない(送っていたら app の activity が終わっていた)。
- (c) mic: 1500 ms 押しで録音、RESULT、`files/mic/` に wav が 1 本できた。120 ms 押しは 50 ms を録って RESULT になった(`MIC_REJECTED` にはならない)。tap で `MIC_REJECTED bytes=0`。
- (d) busy: 2 本の intent のうち 1 本目が PLAN、2 本目が `IGNORED busy source=intent`(同じ秒の中、行の時刻は上)。RESULT は 1 本。
- (e) BACK: `wm_finish_activity`(app-request)→ `wm_destroy_activity` → `wm_on_destroy_called`。engine の解放(`DestroyAccelerator` の CpuAccelerator と LiteRT GPU)が 1 回ずつ。FATAL / IllegalStateException / `close failed` / crash buffer の行は無い。process は 3 秒後も 10 秒後も残った(cached。launch の「process が消える」は起きない、訂正)。

## screenshot(round 3 の篩の 3 枚)
- `out/device/shot1_ready.png`: READY、album 36 枚の一覧(4 列、最後の行は下で切れる)、「Hold to talk」。
- `out/device/shot2_q01.png`: q01_a(a red bicycle leaning on a tree)→ 赤い自転車、「audio → vector 216 ms」「cos 0.72」、次の候補 2 枚(松ぼっくり、紅葉)。
- `out/device/shot3_q13.png`: q13_a(a snowman in the snow)→ 雪だるま、「audio → vector 102 ms」「cos 0.71」、次の候補 2 枚(焚き火、雪山)。
- 目で見て気づいた事(篩の判断材料。直すかどうかは監督): (1) 結果画面で ms と cos の文字がくっつく(「216 mscos 0.72」)。(2) 起動後 1 回目の query は 216 ms、2 回目は 102 ms(`out/device/shots.log` の RESULT 行)。(3) 状態 bar に他の app の通知 icon(YouTube が 3 つ)が写る。(4) title が 2 行に折れる(「EmbeddingGemma 2 / 740M」)。(5) 120 ms 押し(50 ms の録音)でも無音 1.35 s でも写真が出て、cos は 0.6782 と 0.6330(`fail_mic.log`)= gpu leg の当たりの最小 cos 0.6412 と区別がつかない → mic の撮影で短い押しや無音は「それらしい外れ」を出す。(6) airplane mode は off だった(run JSON の airplane_mode_at_start=false。take.sh は既定で on を要る)。

## 投稿に使える数字の候補
「audio → vector」の中央値 104.0 ms(gpu leg = text と vision が GPU・audio が CPU、clip 約 2 秒 43 本、p90 116.0)と、GPU の写真 1 枚 237.4 ms(中央値、36 枚)。どちらも Galaxy S26 1 台・2026-10-08・1 回の run(`out/device/check_run.md` の gpu 行)。NPU は載っていないので数字なし。

## 持ち越し
なし(12 step 全部が 1 枠で終わった)。

## 訂正
- launch「`LAYOUT` log を出す箇所(mic ボタンの px)」→ LAYOUT の JSON は `screen_px`・`density`・`pill_px`・`level_px`・`pill_live_rgb` だけで、mic ボタンの px は無い。gate.sh は level bar の top + (62 + 27 × font_scale) dp × density を押す点にした(ボタンは 72 dp、status 行が 1 行でも 2 行でも中に入る)。S26 は density 3、level top 1813 → 押す点 y 2080 で `MIC_PERMISSION missing` が出た = 当たった。証拠: `app/app/src/main/kotlin/com/mlboydaisuke/eg2demo/MainActivity.kt` の `recordLayout()`、`out/device/gate.log` の「mic button press point」行。APK は作り直していない(sha256 a2cdf02d… のまま)。
- launch「`S=$(sleep 36000 & echo $!)`」→ zsh ではこの形が sleep の終わりまで返らない(`sleep 3` で 3.0 s、redirect を付けると 0.001 s)。証拠: `scripts/run_device_r2.zsh` の head comment。
- launch「`input swipe x y x y 120` → `MIC_REJECTED`(短すぎ)」→ app は空の録音(0 byte)だけを拒否し、長さの下限は無い(`startRecording()` の `bytes.isEmpty()`)。端末では 120 ms 押しが 50 ms(1600 byte)を録って RESULT になった。`MIC_REJECTED` は tap で出た。証拠: `out/device/fail_mic.log`。
- launch「NPU が載らない時の証拠 = `ERROR init …` の文」→ npu leg は init が通り(ENGINE_READY init_ms=304.3)、失敗は計算ごとに出た(computeEmbedding 139 回が `Failed to allocate tensors`、QNN は `Failed to load skel, error: 4000`)。証拠: `out/device/npu_evidence.txt`。gate.sh は DONE の leg も STEP 行に RESULT の数を出すので、0 本で気づける。
- launch「audio encoder が NPU に載るかも未確認」→ SM8850 bundle は audio を [cpu,gpu] に限る(`Audio backend constraint mismatch …`)。証拠: `out/device/npu_audio_npu_evidence.txt`。
- launch「(e) process が消える」→ BACK で activity は終わり engine も解放されたが、process は 10 秒後も残った(Android は cached で残す)。証拠: `out/device/fail_back.log`。
- launch の事実「hold = migration-skill-support-2-3-0-s26 … queue 空」(02:4x)→ その holder の owner は `time.sleep(4*3600)` の python で、03:37:59 まで返らなかった(03:15:35 から待った)。証拠: `out/device/run_device_r2.log`。
- launch「`check_run.py`(numpy)」→ check_run.py は numpy を使わない(json / statistics だけ)。

## 罠と袋小路
- Mac の計測窓の hook(`~/code/standup/tools/hooks/guard_quiet_window.py`): Bash の command 行に `python3` と keyword(hold の path の `s2_npu_sweep` の `sweep`、heredoc の中の `gate`)が並ぶと、軽い処理でも拒否される。hold と queue は `cat` で読む、file の書き換えは Edit tool、queue / hold の操作は zsh の script の中から呼ぶ(監督の go の指示とも同じ)。
- `S=$(sleep 36000 & echo $!)` は zsh で 10 時間返らない → `</dev/null >/dev/null 2>&1` を付けて起動する。
- dry run の sleeper を `pkill -f "sleep 900"` で消した(他 lane の同じ名前の process を巻き込みうる)→ pid で kill する。
- 端末の logcat は 8 文字に満たない tag を詰める: app の行は `Eg2Demo :`。`Eg2Demo:` で除外する grep は効かない。shim の偽の行は `Eg2Demo:` だったので dry run では見えず、端末の後で check_run.py と gate.sh を `\sEg2Demo\s*:` に直した。
- `pm grant` は表示中の許可 sheet を自分で閉じる(granted=true が app に届く)。続けて BACK を送ると app の activity が終わる → BACK は sheet がまだ前面にある時だけ(gate.sh の fail_permission は前面を読み直してから)。
- `logcat -s <tag>` の出力の先頭には `--------- beginning of main` が付く → lmk の file が空にならず、停止条件が毎回立つ所だった。`grep -v '^-----'` で消す。
- `[ -d /proc/ ]`(pid が空)は真 → 生存の判定は pid が空なら偽にする。
- shim の case の順: pull の行に `/mic/` が入ると mic の case に吸われて pull 先の file ができない → pull の case を先に置く。
- `timeout` / `gtimeout` で adb を包むと、dry run でも本物の adb の binary が走る(shim は zsh の関数)→ install の打ち切りは background と kill の loop で作る。
- `dumpsys … | grep -m1` は dumpsys 側に `Failed to write while dumping service …: Broken pipe` を出す(無害、`gate_stdout.log` に残る)。
- NPU は init が通っても計算で全部落ちる: 「ENGINE_READY が出た = 載った」と読まない。RESULT の数と `QnnDsp <E>` の行を見る。

## op と delegate の観察
- GPU(gpu leg): text の encoder 4 本(`encoder_1x128` / `256` / `512` / `1024`)と vision 2 本(`vision_70` / `vision_140`)は LITERT_CL(OpenCL)に全 node。audio の `main`(1600 node)は audio_backend=cpu で XNNPACK 1436 / 1600・226 partitions(残り 164 node は TFLite の CPU kernel)と小さな rms_norm の subgraph 109 本。`vision_adapter_*` は XNNPACK 1 / 3。init 3292.8 ms(GPU の cache が空)。2 回目以降の gpu 起動の init は 2242.4 ms(gpu_audio_gpu)と 926.5 ms(fail_permission、index cache あり)。
- GPU で audio も(gpu_audio_gpu): audio の `main` も LITERT_CL 1600 / 1600。audio embed_ms の中央値は 125.1 ms で、audio CPU の 104.0 ms より遅い。cos は q18_a だけ Mac から 0.0665 ずれた(順位は同じ)= audio の GPU は数値が揃わない所がある。
- CPU(cpu leg): 全部 XNNPACK。encoder は 765 / 1011(339 partitions)と 2649 / 2749(3 partitions)、vision は 1315 / 1477(163 partitions)と 1987 / 2037(4 partitions)の行がある。init 546.5 ms。
- NPU(npu leg): encoder と vision は DispatchDelegate 1 / 1(AOT の QNN context。`Compiler plugin path is provided … the model is pre-compiled`、`EnableJustInTime : false`、`BackendType : Htp(2)`)。QNN の HTP backend の起動で `loadRemoteSymbols failed with err 4000` → `Failed to load skel, error: 4000` → `Transport layer setup failed: 14001` → `Failed to initialize QNN backend` → `Failed to get hooks from accelerator: 3`。invoke は全部 `Failed to allocate tensors`。NPU accelerator の 2 回目の登録で `NPU accelerator could not be loaded and registered: kLiteRtStatusErrorNotFound`(1 回目は `NPU accelerator registered.`)。QAIRT の版の差は未確認(この段に届いていない)。
- S26 GPU と Mac GPU(WebGPU): top-1 は 103 中 102 が同じ(違うのは decoy の d01_a)、同じ写真での top-1 cos の |Δ| は最大 0.0140(q11_t_card)、audio の中央値 0.0032。S26 CPU と Mac CPU は 100 中 99 が同じ(違うのは q08_b で、S26 CPU の方が正解)。fp16 / fp32 の別は app から読めない(未確認)。
- ModelInfo の `supportedBackends`(MODEL_INFO の backends_vision / backends_audio が CPU だけ)は実際に動いた物と合わない: vision は GPU で動いた、audio の制約文は [cpu,gpu]。SM8850 bundle の max_context_tokens は 1024(base は 8192)、signatures は [128,256,512,1024](base は [128,256,512,1024,2048,8192])。
- runtime: litertlm-android 0.18.0(BuildConfig)。QNN の .so は APK の 10 本(QAIRT 2.47.0.260601、round 1)。端末の Adreno driver: Driver Version 0842.19.8(app の logcat)。

## 使った環境
- Mac: Apple M4 Max、macOS 27.0、zsh。`K/venv`(Homebrew python 3.14.6): check_run.py・r2_extra.py・fake_device_runs.py(pillow 12.3.0 は r2_extra.py の画素で使う)。adb = `~/Library/Android/sdk/platform-tools/adb`(版は読んでいない)。
- device: Galaxy S26 SM-S942Q(RFGL80R6A6H、`m1q`)、SoC SM8850、Android 16、build BP4A.251205.006.S942QOPS1AZH9。app = `app-debug.apk`(sha256 a2cdf02d…、round 1 の build、作り直していない)、litertlm-android 0.18.0。
- hold: あり。`queue_cli.py wait` で 03:37:59 に取得、03:43:05 に release(`scripts/run_device_r2.zsh` の 1 process)。

## 残した file
- 書き換え: `scripts/gate.sh`(leg 5 本・fail・shots・枠と停止条件)、`scripts/dryrun_adb_shim.zsh`(時刻つきの logcat、DRY_* で失敗を注入)、`scripts/check_run.py`(leg 横断の表、app 行の除外)。round 1 の版は scratchpad にだけ写した(session 限り)。
- 新規: `scripts/run_device_r2.zsh`(keeper → gate → release)、`scripts/dryrun_gate.zsh`(gate の dry run)、`scripts/fake_device_runs.py`(check_run の偽の入力)、`scripts/r2_extra.py`(表に無い数字)。
- `out/device/`(round 2 の証拠、残す): `gate.log`・`gate_stdout.log`・`run_device_r2.log`、leg ごとの `<leg>_eg2-demo-*.json`・`<leg>_demo.log`・`<leg>_app_logcat.log`・`<leg>_backend_lines.txt`・`<leg>_first_errors.txt`・`<leg>_crash.log`・`<leg>_lmk.log`・`<leg>_screen.png`(npu 系は `<leg>_npu_all_pids.txt`、ERROR の leg は `<leg>_error.txt`)、`fail_*.log`・`fail_bundle_screen.png`・`fail_permission_screen.png`、`shots.log`・`shots_demo.log`・`shots_app_logcat.log`・`shots_eg2-demo-*.json`・`shot1_ready.png`・`shot2_q01.png`・`shot3_q13.png`、`check_run.md`・`check_run.json`・`check_run_summary.txt`・`r2_extra.txt`・`npu_evidence.txt`・`npu_audio_npu_evidence.txt`・`delegates.txt`・`fail_evidence.txt`。`hold_sleeper.pid` は消してよい。
- 消してよい: `out/dryrun_gate_r2*`・`out/dryrun_run_r2*`・`out/dryrun_take_*_r2`・`out/dryrun_check/`(dry run の証拠)。
- 端末に残した物(round 3 が使う。lane の終わりに名前で消して uninstall): app `com.mlboydaisuke.eg2demo`(GPU の cache 入り)、`/sdcard/Android/data/com.mlboydaisuke.eg2demo/files/` の bundle 2 本・album 36 枚・wav 43 本・queries.json・index cache(app が書く。端末で ls はしていない)・`Documents/eg2-demo-*.json`・`mic/` の録音 2 本(無音の 1.35 s と 0.05 s)。
