SPEED_LADDER n/a(デモ lane、監督 launch の指示)、/litert-data は回さない。

# LANE_CLOSE — [G] EmbeddingGemma 2 740M「話す → 写真が出る」Android デモ(lane close round 4、2026-10-08)

worker: hfmodels-android-a1 [c13646]、監督: hfmodels-android-b4 [32505d]。経過と round close は `ROUND4.md`。

## handoff
- `~/code/standup/handoffs/2026-10-07-eg2-740m-demo.md` の `## §知見`(§2 補 と §4 の間に新設): 訂正 19 行・罠と袋小路 37 行(r4 の 2 行を含む)・op と delegate の観察 12 行、計 68 行(awk で数えた)。各行に round 番号と証拠 path。lane memory に入れた行は「→ memory `<name>`」、別 lane の memory に折るべき行は【litertlm-convert memory 候補】の印。

## litert-compat
- なし(/litert-data は回さない = commit hash なし)。

## memory(`~/.claude/projects/-Users-majimadaisuke-code-hfmodels-android/memory/`、各 2,000 B 以下、wc -c)
新規 6 本:
- `litertlm-npu-needs-cdsprpc.md`(1,952 B): LiteRT-LM の NPU は app が libcdsprpc.so を宣言(AAR も SDK の litertlm manifest も宣言しない)、QNN の .so は runtime の pin の版、NPU の判定は結果の数・`QnnDsp <E>` 無し・DispatchDelegate。
- `embedding-engine-backend-per-stage.md`(1,641 B): 音声・写真は encoder(audio / visionBackend)→ backbone(backend)、画面と投稿は段ごとに名指す、ms は合計、ModelInfo の backend 一覧は実際と違う。
- `embedding-search-no-match-needs-input-gate.md`(1,496 B): 無音でも当たりの帯の cos で写真が出る、「該当なし」は長さと RMS の床で、床は端末で録った無音から。
- `cc0-photo-fixtures-openverse.md`(1,500 B): デモの写真は CC0 / PD・人と文字なし(coreai memory の user 規則への pointer)、Openverse API、候補は原寸で見る、Commons の縮小幅。
- `quiet-hook-traps.md`(1,642 B): Mac の計測窓の hook は python + keyword の区切りを止める(hold の path の `sweep`、heredoc の本文)、軽い作業は cat・Edit tool・script file、重い job は quiet_wait.py。
- `s26-demo-scene-traps.md`(1,799 B): `reference_s26_demo_recording` から分けた(監督の判断 = 案 A): chooser・第三者画面の個人情報・合成 SMS。Why と How to apply は 2026-10-03 typed-decisions の handoff §知見 の行から書いた。
書き直し 6 本(追記でなく書き直し):
- `s26-shared-device-hold.md`(2,115 → 1,996 B): sleeper は redirect 付きで起動(無いと `$(…)` が返らない)・pid で kill(`pkill -f sleep…` は他 lane の keeper も)・hold を cat で読む(quiet-hook-traps へ)。「release, tell the next in line」は skill opus-rounds の「連絡と待ち」(取得・返却の知らせは送らない)と食い違うので「the queue tells the next, no message」に直した。metadata の 3 行(node_type 等)は無い。
- `adb-run-script-traps.md`(1,998 → 1,996 B): 生存の判定 `[ -n "$P" ] && [ -d /proc/$P ]`(既存の行の穴: `[ -d /proc/ ]` は真)、`timeout adb` も本物の adb、logcat の tag の空白埋めと `--------- beginning of main`。既存の事実は全部残した(言い回しを詰めた)。metadata の 3 行を消した。
- `zsh-bash-tool-traps.md`(1,276 → 1,793 B): `$V:x` の修飾子・`$V[x]` の添字、`${#${(s:,:)x}}` の文字数、Claude Code が止める `zsh -c` と `rm -f $D/…`。
- `screenrecord-cut-drops-last-frame.md`(1,367 → 1,928 B): 最後の変化が結果とは限らない(DONE frame から数える)、crop の y は偶数、Vision の OCR は CPU で・「·」は「•」。
- `hf-upload-xet-stall.md`(1,442 → 1,705 B): download も 1 分で bytes を見る(xet off でも 37 KiB/s → 出し直しで約 1 分)。metadata の 3 行を消した。
- `reference_s26_demo_recording.md`(2,194 → 1,814 B): 「`KEYCODE_WAKEUP` first」を監督の指定の句に置き換え(WAKEUP / SLEEP / POWER の key を送らない、app が画面を点け Awake を読んでから screenrecord、r3・r3b の take 4 本は key event 0 で Dozing → Awake)、scene の 3 項目は `s26-demo-scene-traps` へ、両方を `[[name]]` で指す。metadata の 3 行を消した。
- `MEMORY.md`(7,257 → 8,944 B、28 → 34 行): 新規 6 行、上の 6 本の行の hook を書き直し。索引と file は 1 対 1、`[[name]]` は全部解決(litertlm-convert の 2 本を含む)。この dir で一番大きい file は手を付けていない `joint-encoder-packed-vs-per-question.md` の 2,002 B。
- 形の確認(sed と grep): 12 本とも frontmatter(name・description・metadata.type)あり。新規 6 本と feedback 型の書き直し 4 本は **Why** と **How to apply** の行あり。reference 型の `s26-shared-device-hold`(How to apply だけ)と `reference_s26_demo_recording`(どちらも無い)は元の形のまま。
- 元の版: `/private/tmp/claude-501/-Users-majimadaisuke-code-hfmodels-android/b894d8b6-e3b9-4bdc-bae9-0d329be31e00/scratchpad/orig/`(session 限り)。

## 入れなかった候補(1 行 1 件、理由)
監督の 10 件のうち:
- (6) AGP の strip が .so を 8 byte 変える → `keepDebugSymbols`: 効くのは packaged .so を SHA256SUMS と byte で照合する時だけで、この SDK の NPU の手順(`tools/fetch_npu_libs.sh`)に照合は無い。直し方と理由は `K/app/app/build.gradle.kts` の comment と handoff §知見 にある。
- (8) KDoc の `/*` が入れ子 comment: compiler が `Unclosed comment` と行を示し、直しは 1 行、損は build 1 回。
- (2) のうち数値(audio encoder の CPU 104 / GPU 125 ms): 数値は handoff。memory には「GPU の方が遅かった」だけ。
- (1)(2)(3)(4)(5)(7)(9)(10) は入れた(上の一覧)。
自分で見つけた物のうち:
- `pm grant` が許可 sheet を閉じ、続く BACK が app を終わらせる: 許可を外す gate を書く時だけ効き、adb-run-script-traps は 2 KB の上限。handoff §知見(r2)。
- shim の case の順(pull が mic の case に吸われる): この shim に固有。
- `dumpsys … | grep -m1` の Broken pipe: 無害な文。
- 偽の take の配置と実機の LAYOUT のずれ、2 本目の clip で字幕が next matches に重なる: このデモの checker と画面に固有。
- warm-up の後も 1 回目の query が遅い: 数値で、原因は未確認。
- app の GPU index の後に CPU / kgsl の cap が 20〜40 s 残る: `s26-gpu-heat-inside-a-gate` と litertlm-convert の `android-thermal-freq-cap-before-bench` が既に言っている。
- 中央値が 2 通り(app の上側の中央値と statistics.median)、aapt2 の `uses-native-library-not-required` の書式、`run_queries` と autoplay、LAYOUT の mic px、r2 の APK に長さの下限が無い、BACK 後も process が cached: 一度きりか、この app に固有。
- normalize の既定 true、litert-lm-api の wheel に dylib、前処理の警告、SM8850 bundle の audio [cpu,gpu]、FastRPC の Permission denied 行は致命でない、Mac は WebGPU で NPU なし: runtime と bundle の事実 = handoff §知見 に【litertlm-convert memory 候補】の印(この round は別 lane の dir に書かない)。
- GO 依頼の Artifact page が 1 時間で消えた: 原因が未確認の 1 回。
- quiet_wait が 435 s 待った、launch の「memory 8 本」の数え違い: 状態と経緯。
別の memory に向く物(この round は書かない、監督の判断):
- `quiet-hook-traps` の中身は standup の hook(`~/code/standup/tools/hooks/guard_quiet_window.py`)の性質で、全 project の session に効く = standup memory 候補。
- kev の `~/code/litertlm-convert/kev_work/demo/take.sh` が 160 行で KEYCODE_SLEEP、197 行で KEYCODE_WAKEUP を送る = litertlm-convert memory 候補(handoff §知見 に印。take.sh は触っていない)。

## 消した中間物(K の中、ROUND1〜3b の「残した file」で「消してよい」と印のあった物だけ)
- `scripts/r4_clean_k.zsh --delete`(06:40:58〜06:40:59): 93 path、644,004 kB、残り 0。一覧と大きさは `out/r4_clean_k.log`。K は 1,006,140 kB → 362,140 kB(du -sk)。
- 中身: ROUND1 = `out/mac/run1/`・`app/app/build/`(APK の build dir、作り直せる)・`out/aar/`・`out/round1_tables.md`・`out/ov_probe.json`・`out/ov_headers.txt`・`out/dryrun_gate/`・`out/dryrun_take_file/`・`out/dryrun_take_mic/`・`out/zoom_*.jpg` 7・`out/fetch_album_*.log` 3・`out/album_candidates/` の主題ごとの 640 px の写真の dir 44(`candidates.json` と一覧画像 20 枚は残した)。ROUND2 = `out/device/hold_sleeper.pid`・`out/dryrun_gate_r2*` 7・`out/dryrun_run_r2*` 2・`out/dryrun_take_*_r2` 2・`out/dryrun_check/`。ROUND3 = `out/device_r3/` の `hold_sleeper.pid`・`go_takes`・`released`、`out/dryrun_r3_*` 4・`out/dryrun_media_r3/`。ROUND3b = `out/device_r3b/` の同じ 3 file、`out/dryrun_r3b_*` 3・`out/dryrun_media_r3b_*` 2・`out/dryrun_cut_r3b/`。
- 消さなかった物: clip・run JSON・ROUND・script・fixtures(写真・wav・manifest)・`out/device*`・`out/takes_r3*`・`out/mac/` の採点・`out/qairt/`・build と DL の log。
- これで切れた証拠の path: `ROUND3.md` の訂正の `out/dryrun_r3_go/takes/adb_calls.log`、`ROUND3b.md` の訂正の `out/dryrun_r3b_regress_r3/run.log`(どちらも元の round が「消してよい」と印。事実は ROUND の本文と script の head comment に残る)。
- K の外に残る物(launch の範囲外なので触っていない、監督の判断): `~/.cache/eg2demo/`(996M、bundle 2 本と HF の metadata。ROUND1 は metadata を「消してよい」と印)。

## 端末の片づけ
- 手順 = `scripts/run_device_r4_cleanup.zsh`(script 名 `eg2demo-cleanup-r4`、hold から 540 s で watchdog)。dry run = `scripts/dryrun_r4.zsh` + `scripts/dryrun_adb_shim_r4.zsh`(偽の端末の木): 正常 exit 0(偽の file 99 → 0、app 消去、hold 解放、sleeper 消滅)、知らない名前 exit 5(何も消さない)、端末なし exit 4、watchdog exit 12、順番待ち切れ exit 3(端末に 0 call)。関数の外の裸の adb は 0。
- 本番(監督の go の後): hold 06:47:43〜06:47:45(2 s)、rc 0、再起動なし(uptime の差 3608.94 s = 壁時計 3609 s)。消す前の ls = top-level 5(bundle 2・index 3)、album 36、queries 44、mic 4、Documents 18、take の mp4 なし、知らない名前 0。名前で消した後の files/ は 0 件、`adb uninstall` は Success、package の行は空、app の dir は無い。証拠 `out/device_r4/`(`cleanup.log`・`ls_before.txt`・`ls_after.txt`・`ls_after_uninstall.txt`・`run.log`)。hold file なし・queue `[]`・sleeper 消滅を cat と ps で確認。
- 端末に残した物: なし(この lane が置いた file と app は全部消えた。edge_enable・画面・他の app には触っていない)。
