worker: hfmodels-android-27 [31153f]

## 経過
- 04:02 開始。launch 全文、ROUND2.md・ROUND1.md、script(take.sh・make_media.sh・captions.py・frames.sh・settle.py・check_run.py・numbers.py・gate.sh・run_device_r2.zsh・dryrun_gate.zsh・dryrun_adb_shim.zsh)、MainActivity.kt・Wav.kt 全文、AndroidManifest.xml、shot1〜3、memory 12 本、ship-loop.md の先頭を読んだ。hold = `et-r5-s26-1.7B`(pid 50521、03:57:40 から)、queue 空(04:01、cat)。次 = app の直し。
- 04:05 launch の NPU の前提を AAR で確かめた: LiteRT 2.2.0 の AAR manifest に `libcdsprpc.so`(required=false)がある、litertlm-android 0.18.0 の AAR manifest の `<application>` は空。
- 04:14 app の直し 1 回目の build PASS(`out/build_r3_1.log`)。04:16 round 2 の shot2 から文字幅を測った(28 sp の title 1 行目 777 px、22 sp の結果行 697 px + 251 px、12 sp の footer 916 px)→ 24 sp の title は 1 行に約 863 px で入る、chip は 18 sp・padding 8 dp・間 10 dp で約 890 / 960 px。04:17 build 2 回目 PASS(`out/build_r3_2.log`)。次 = gate.sh・take.sh・make_media.sh。
- 04:18〜04:40 script: gate.sh(leg `npu2`・`mic`・`shots` の reindex と backend 選択・`PUSH_MODE=missing`・`SKIP_SETUP`・`GATE_LOG`)、take.sh(SLEEP / WAKEUP の key を消した・reindex・clip を comma で複数・`REC_SHELL_EPOCH`・GPU だけの回復待ちは 60 s で先へ)、新規 `run_device_r3.zsh`(keeper → npu2 + shots → A_DONE → mic → go file 待ち 10 分 → take 2 本 → edge_enable を戻す → release、29 分の watchdog)、`npu_pick.py`(NPU の判定と写真の backend)、`place_audio.py`(PILL 行と画面の pill の色で時計を合わせる)、`make_media.sh`(clip 複数・字幕は queries.json から・y0 と字幕の位置は LAYOUT から)、`take_lines.py`(線 a〜f を file から)、`ocr.swift`(Vision で画面の ms を読む)、`numbers.py`(新しい画面)、`fake_take.py`(偽の take)。
- 04:41〜04:47 Mac の検証: 偽の take で make_media.sh → take_lines.py が 6 本 PASS、JSON を壊すと (b)(c) が FAIL(負の試験)。npu_pick.py は 5 例(round 2 の npu = 未達、GPU の JSON を npu として = 未達、速い偽 = npu、遅い偽 = gpu、JSON 無し = 未達)。run_device_r3.zsh の dry run 4 本: go あり exit 0、go 無し exit 10、file 1 本欠け → その 1 本だけ push、watchdog exit 12。dry run で take.sh の query 数の bug(1 本の時に 9 と数えた)を見つけて直した。関数の外の裸の adb は 0、dry run の端末への key は 0。
- 04:48 hold は空き・queue 空(cat)。Mac 側 PASS。次 = 監督に「Mac 側 PASS、端末待ち」、go を待つ。
- 04:48 監督の go(04:48 の状態: hold なし、queue 空、S26 は adb に見える)。04:49:10 `scripts/run_device_r3.zsh` を background で 1 本(PHASE=A)。
- 04:49:10 hold 取得(`queue_cli.py wait`、keeper = sleeper pid 39099)。端末の状態: uptime 463838.24 s(round 2 の 03:37:59 に 459567.87 s = 再起動なし)、Thermal Status 0、mWakefulness=Dozing。edge_enable 1 → 0。次 = npu2 leg と shots(gate.sh)。
- 04:49:18 setup(held 8 s): install -r、files は名前で数えて bundle 2・album 36・queries 44 = push 0 本。04:49:20 leg npu2 = app が init 中に落ちた(SIGBUS、下の「NPU の判定」)。04:49:46 shots PASS(GPU、reindex)。04:49:46 A_DONE。04:49:59 mic PASS(300 ms → short、1.45 s の無音 → quiet)。
- 04:50 監督に screenshot 3 枚(path・LAYOUT・数字・NPU の 1 行)を送った。続けて mic の床が launch の線を割った事(round 2 の無音 clip の RMS 0.00339 > 既定 0.001)を 1 通。04:51 監督の go(篩を通す、写真は GPU、t1 と t2 を撮って release)。04:51:56 go file → take t1 開始。
- 04:51:56〜04:52:51 take t1(q01_a): 冷却 0 s、READY 後の回復待ち 04:52:09〜04:52:35(CPU の cap = index の後の熱)、Awake、録画、PLAY_START 04:52:39.179、RESULT 04:52:41.957、rc 0、cgroup `/top-app`。04:52:51〜04:53:52 take t2(q01_a,q13_a、gap 2500): 回復待ち 04:53:2x〜04:53:31(CPU cap)、PLAY_START 04:53:34.998 / 04:53:40.298、rc 0。
- 04:53:52 release(held 282 s = 4.7 分)、edge_enable を 1 に戻した(読み直して 1)、sleeper 39099 は消えた、04:54:03 の hold file なし・queue 空(自分の札なし)。
- 04:54〜04:59 切り出し(Mac、quiet_wait 経由): 1 回目の切り出しは t2 が 15.6 s で線 (a) を割った。原因 = 最後の結果の 2.5 s 後(gap_ms)に app の batch が終わり mic ボタンの alpha が戻る = それが raw の最後の変化で、そこから 3 s 持った。撮り直しはせず、同じ raw を「最後の結果の DONE frame から 3.0 s」で切り直した(`END_AFTER_RESULT=3.0`、t1 8.27 s・t2 13.10 s)。take_lines.py の (d) は 1 回目に FAIL(X 版と master の比較が 1 行ずれた: ffmpeg の crop は 4:2:0 で y0 111 を 110 に丸める)→ 偶数の行で比べる形に直して PASS、make_media.sh も y0 を偶数にした。線 (a)〜(f) は 2 本とも PASS(下の表)。

## APK
- path: `K/app/app/build/outputs/apk/debug/app-debug.apk`、75,527,344 B、sha256 `7c2ed250bd31a93ab631fcd8b5f2008a7b3052677aa753a6c02751c16432dd98`(04:17、`out/build_r3_2.log` exit=0)。
- `aapt2 dump badging`(build-tools 36.0.0): `uses-native-library-not-required:'libcdsprpc.so'`(libOpenCL.so・libvndksupport.so も同じ形)、permission は RECORD_AUDIO だけ(INTERNET なし = app は network を使えない)。
- 直した物: manifest に libcdsprpc.so、画面は status bar の下の 9:16 の枠(S26 で 1,920 px)に title(24 sp・1 行)・「Galaxy S26 · LiteRT-LM 0.18.0」・「photos on <vision> · voice on <audio>」(ENGINE_READY の後)・pill・中段(READY は 6 列 × 6 行 = 36 枚、結果は写真 + 「audio → vector N ms」と「cos 0.NN」の 2 chip + next matches 2 枚)・level bar・status・ボタン 64 dp・footer。title 上端から footer 下端は計算で 1,872 px(枠 1,920 − 上下 8 dp)。footer は `--ez reindex true` の起動で「album 36 photos · indexed in N.N s on GPU · no network needed」(入らない時は最後の「 · 」で改行)。READY の前に 1.0 秒の無音を 1 回 embed(`WARMUP ms=`)。mic は 0.5 秒未満 `reason=short`、RMS < 0.001(−60 dBFS、`--ef min_rms` で変更可)`reason=quiet`、pill「too short」「too quiet」、録音は棄却分も wav を残す。`PLAY_START`・`PLAY_HEAD`・`PILL`(色が変わる度)・`RESULT_LAYOUT`・LAYOUT の `title_top_px`・`footer_bottom_px`・`frame_px`・`mic_px`。autoplay の `query` は comma で複数。

## 線(事前登録)
04:02 に launch から写した(撮る前)。
- (a) clip ≤ 15.0 秒。
- (b) 画面の top-1 = gold(q01 → a01、q13 → a20)。
- (c) 画面の ms = JSON の embed_ms(同じ数字)。
- (d) 最後の frame = 結果の画面(DONE の pill と写真)。
- (e) 重ねた音声の開始と pill の「playing」の開始の差 ≤ 200 ms(frame の時刻と PLAY_START から)。
- (f) 字幕の文 = queries.json の text。
- 1 つでも割れたら撮り直し(枠の中なら同じ枠、外なら次の枠。監督に数字で 1 通)。
- NPU が「載った」と書けるのは: `QnnDsp <E>` が無く、RESULT が 103 本出て、encoder / vision の実行時間が GPU と違う時だけ(`Replacing … DispatchDelegate` だけでは不足)。載らなければ 2 回目の試行はしない。
- 写真の backend = NPU が載って index の総時間が GPU より短い時だけ NPU、それ以外は GPU。

## 訂正
- launch「`--es run_queries q01_a.wav` の形は今のまま(speaker で再生 → embed → 結果)」→ run_queries は再生しない(`runIntent` で `Job.AudioFile(it, play = false)`)。speaker で再生するのは `--ez autoplay true --es query <wav>` だけ。take は autoplay の `query` を comma で複数にした(app の 3 行)。証拠: `app/app/src/main/kotlin/com/mlboydaisuke/eg2demo/MainActivity.kt` の `runIntent`。
- launch「閾値は fail_mic.log の無音 1.35 s の RMS より上」→ `out/device/fail_mic.log` に RMS は無い(RESULT 行と `ls -l` だけ)。wav は端末の `files/mic/1791398543548.wav` にあり Mac には無い。既定 0.001(−60 dBFS)は CDD の VOICE_RECOGNITION の較正(90 dB SPL = RMS 2500 / 32768)から見積もった値で、枠の中で wav を pull して測る(gate.sh の `mic`)。
- launch「`aapt2 dump badging` で `uses-native-library: name='libcdsprpc.so'`」→ aapt2(build-tools 36.0.0)の書式は required=false の時 `uses-native-library-not-required:'libcdsprpc.so'`。証拠: 上の「APK」節。
- launch の禁止「POWER / SLEEP / WAKEUP の key」→ round 1 の take.sh は step 2 で `KEYCODE_SLEEP`、step 3 と 6 で `KEYCODE_WAKEUP` を送る形だった。round 3 の take.sh から消した(画面は app の setTurnScreenOn、Awake は dumpsys を読むだけ)。証拠: `scripts/take.sh` の head comment、dry run の adb の呼び出しに keyevent 0(`out/dryrun_r3_go/takes/adb_calls.log`)。
- launch「`shot1_ready.png`…を pull」「`K/out/device/npu2_*`」→ `out/device/` には round 2 の同じ名前の shot があり上書きになる。round 3 の端末の file は `out/device_r3/`、take は `out/takes_r3/` に置く。
- launch の事実「CPU leg: 写真 762.6 ms」と ROUND2.md の表「757.6」は同じ run の別の中央値: app の JSON の `per_image_ms_median` は上側の中央値(36 枚の sorted[18] = 762.6)、check_run.py は statistics.median(中央 2 つの平均 = 757.6)。証拠: `out/device/cpu_eg2-demo-1791398460931.json`。
- APK の mic の床(既定 `MIC_MIN_RMS` = 0.001、−60 dBFS)は launch の「無音の RMS より上」を満たさない: round 2 の無音 1.35 s(`out/device_r3/r2_silent_1791398543548.wav`、枠の中で pull)の RMS は 0.00339(−49.4 dBFS、50 ms 窓の中央値 0.00122・最大 0.0092)。round 3 の無音 1.45 s(`out/device_r3/mic_check_1791402595398.wav`)は 0.00091(−60.8 dBFS)で reason=quiet(余裕 0.8 dB)。普通の声の RMS はこの端末で未測定。監督の判断(04:5x): この枠では rebuild しない。user の声の round は launch の extras に `--ef min_rms 0.005`(−46 dBFS、round 2 の無音より 3.4 dB 上)を渡し、最初の 1 本の `MIC_RECORDED … rms=` で確かめる。測り方: `venv/bin/python -I scripts/wav_rms.py <wav>`(app と同じ全体の RMS)。

## NPU の判定: 未達(1 回、2 回目はしない)
- npu2(vision NPU・audio CPU、SM8850 bundle、APK に `libcdsprpc.so` の宣言): app の process が `EmbeddingEngine.initialize` の中で落ちた(04:49:19.976、SIGBUS BUS_ADRALN、`pc 0x0000000000000001`、frame #01〜#10 は `liblitertlm_jni.so`、#10 = `Java_com_google_ai_edge_litertlm_LiteRtLmJni_nativeCreateEmbeddingEngine+2644`)。RESULT 0、run JSON は pull できず(process が 1 秒で消えた)。
- round 2 との差: 宣言で FastRPC が開き、DSP 側の skel まで読めた。nativeloader の `uses_libraries=libOpenCL.so:libcdsprpc.so`、`multidsplib_env_init: libcdsprpc.so loaded`、`Created user PD on domain 3 … Unsigned:Y`(04:49:19.914)、`remote_handle64_open: opened handle … for file:///libQnnHtpV81Skel.so`(19.960)、`dspqueue_create: created Queue 0`(19.972)、QoS の設定(19.973)の 3 ms 後に落ちた。round 2 の `loadRemoteSymbols failed with err 4000` / `Failed to load skel` は出ていない。`QnnDsp` の行は 0 本(crash した process 4721)。
- 落ちた時の register x6・x7・x12・x13 は ASCII で `v2.47.0.` `26060111` `7.0.2606` `01114230`(= QAIRT 2.47.0.260601 の版の文字列、APK の QNN .so は QAIRT 2.47、LiteRT-LM 0.18.0 の pin は 2.50)。版の差が原因かは未確認(版を変えた試行はしていない)。
- 証拠: `out/device_r3/npu2_evidence.txt`(抜き出し)、`npu2_crash.log`(tombstone の全体)、`npu2_npu_all_pids.txt`、`npu2_summary.txt`(npu_pick.py の判定 = NOT LOADED、写真は gpu)。
- 写真の backend: GPU(launch の規則: NPU が載って index の総時間が GPU より短い時だけ NPU)。

## 線の判定
2 本とも (a)〜(f) PASS(`venv/bin/python -I scripts/take_lines.py …`、`out/takes_r3/t1_lines.md`・`t2_lines.md`・`t1_lines.json`・`t2_lines.json`)。check_run.py: top-1 = Mac GPU 1/1(t1)・2/2(t2)、同じ写真の top-1 cos の |Δ| は最大 0.0026・0.0032(`out/takes_r3/t1_check_run.md`・`t2_check_run.md`)。(c) の画面の ms は Vision の OCR で結果の frame から読んだ数、(b) は結果の frame の写真の範囲を album 36 枚と照合した結果。

t1(q01_a 1 本、`out/takes_r3/eg2_demo_s26_t1_1791402771.mp4`):

| line | verdict | numbers |
|---|---|---|
| (a) clip <= 15.0 s | PASS | x1080x1920 8.267 s, master 8.267 s |
| (b) top-1 on screen = gold | PASS | q01_a: screen a01 (d 20.05, 2nd ['a08', 50.05]), json a01, gold a01 |
| (c) ms on screen = embed_ms | PASS | q01_a: screen 135 ms / json 135.05125 -> 135; cos 0.72 / 0.7203807234764099 |
| (d) last frame = result | PASS | pill green True [42, 124, 47], photo a01 (gold a01), ms 135 (json 135), x vs master crop 0.45913001543209875 |
| (e) audio vs red pill <= 200 ms | PASS | q01_a: 10.8 ms (offset -1791402756.7371721, 3 logged colour changes paired in order with the video's) |
| (f) caption = queries.json | PASS | q01_a: "a red bicycle leaning on a tree" |

t2(q01_a → q13_a、gap 2500、`out/takes_r3/eg2_demo_s26_t2_1791402831.mp4`):

| line | verdict | numbers |
|---|---|---|
| (a) clip <= 15.0 s | PASS | x1080x1920 13.100 s, master 13.100 s |
| (b) top-1 on screen = gold | PASS | q01_a: screen a01 (d 20.04, 2nd ['a08', 50.04]), json a01, gold a01; q13_a: screen a20 (d 10.91, 2nd ['a17', 48.43]), json a20, gold a20 |
| (c) ms on screen = embed_ms | PASS | q01_a: screen 132 ms / json 132.466719 -> 132; cos 0.72 / 0.7203807234764099; q13_a: screen 116 ms / json 116.401666 -> 116; cos 0.71 / 0.7127302289009094 |
| (d) last frame = result | PASS | pill green True [42, 124, 47], photo a20 (gold a20), ms 116 (json 116), x vs master crop 0.38774450231481483 |
| (e) audio vs red pill <= 200 ms | PASS | q01_a: 6.2 ms; q13_a: -8.5 ms (offset -1791402812.4982946, 6 logged colour changes paired in order with the video's) |
| (f) caption = queries.json | PASS | q01_a: "a red bicycle leaning on a tree"; q13_a: "a snowman in the snow" |

## clip(切り出し)
| take | 版 | path | 長さ | bytes | sha256(先頭 16) |
|---|---|---|---:|---:|---|
| t2 | X 用 1080×1920 | `out/takes_r3/eg2_demo_s26_t2_1791402831_x1080x1920.mp4` | 13.100 s | 2,360,732 | 9af16d89797e0bd3 |
| t2 | master 1080×2340 | `out/takes_r3/eg2_demo_s26_t2_1791402831_trim.mp4` | 13.100 s | 1,898,849 | f64c68df27983186 |
| t1 | X 用 1080×1920 | `out/takes_r3/eg2_demo_s26_t1_1791402771_x1080x1920.mp4` | 8.267 s | 2,013,332 | c6e72d8871bc68c3 |
| t1 | master 1080×2340 | `out/takes_r3/eg2_demo_s26_t1_1791402771_trim.mp4` | 8.267 s | 1,619,532 | ec73322f3447c97b |
| t2 | raw(screenrecord、VFR) | `out/takes_r3/eg2_demo_s26_t2_1791402831.mp4` | 15.089 s | 2,673,866 | 9643230eb3c940ae |
| t1 | raw(screenrecord、VFR) | `out/takes_r3/eg2_demo_s26_t1_1791402771.mp4` | 10.226 s | 2,017,151 | 67f244e7047d5f99 |

- 作り方: `END_AFTER_RESULT=3.0 scripts/make_media.sh <raw> <take.log> <run.json> <demo.log> <wav[,wav]>`(CUT_T 0 = READY の画面から、crop y0 110、字幕は level bar の上 = crop の y 1339、音声は PLAY_START + 時計の合わせ)。frames: `out/takes_r3/frames_t1/`・`frames_t2/`(start / mid / end + 1 秒おきの contact sheet)。最後の frame は 2 本とも結果の画面(DONE の pill・写真・ms)を目で見た。音声の山(100 ms 窓で RMS > 0.005): t1 2.7〜4.5 s、t2 2.8〜4.6 s と 8.1〜9.4 s(clip の 0.3 s の頭の無音を足すと置いた位置と合う)。
- master の status bar(y 0〜111)には他の app の通知 icon(YouTube・Play ストア・フォルダ)が写る。X 用は y 110 から切るので写らない。投稿は X 用だけ。

## 画面の数字(投稿用、`numbers.py` の出力から)
| take | 画面の文字 | 値 | JSON |
|---|---|---|---|
| t2 | audio → vector(q01_a、起動後 1 回目) | 132 ms | embed_ms 132.466719 |
| t2 | cos(q01_a → a01) | 0.72 | top1_cos 0.72038 |
| t2 | audio → vector(q13_a、2 回目) | 116 ms | embed_ms 116.401666 |
| t2 | cos(q13_a → a20) | 0.71 | top1_cos 0.71273 |
| t2 | footer | album 36 photos · indexed in 8.6 s on GPU · no network needed | index.total_ms 8588.3 |
| t1 | audio → vector(q01_a) | 135 ms | embed_ms 135.05125 |
| t1 | cos(q01_a → a01) | 0.72 | top1_cos 0.72038 |
| t1 | footer | album 36 photos · indexed in 8.9 s on GPU · no network needed | index.total_ms 8869.9 |

- 2 行目・3 行目は 2 本とも「Galaxy S26 · LiteRT-LM 0.18.0」「photos on GPU · voice on CPU」。clip の長さ: t2 13.100 s、t1 8.267 s。全文: `out/takes_r3/t1_numbers.md`・`t2_numbers.md`。card の S26 Ultra の数字は書いていない。

## 罠と袋小路
- zsh: `${#${(s:,:)x}}` は名前が 1 つの時に文字列の長さを返す(`q01_a.wav` → 9)。take.sh が RESULT を 9 本待ち、dry run で 9 分止まった → `QL=("${(@s:,:)x}"); NQ=${#QL}`。端末の前に dry run で見つけた。
- zsh: `"$AF[$k:a]"` は配列の添字、`[$k:a]` の `:a` は path の修飾子(絶対 path になる)→ `${AF}[${k}:a]`。ffmpeg の filter 文字列を zsh で組む時は全部 `${}` で囲む。`echo ======` は `=` で始まる語として command を探して止まる。
- ffmpeg の `crop` は yuv420p で y を偶数に丸める(111 → 110)。crop した X 版と master を比べる時、字幕の位置を計算する時は偶数の y0 を使う(1 行ずれで平均差 6.2、合わせると 0.45)。
- 「最後の変化から 3 s 持つ」切り方は、app の batch の終わり(最後の結果の gap_ms 2500 後に mic ボタンの alpha が 0.4 → 1.0)を最後の変化として拾い、2 query の clip が 15.6 s になった → 最後の結果の DONE frame から数えて切る(`END_AFTER_RESULT`)。
- macOS Vision の OCR は sandbox の shell で Neural Engine の経路が `e5rtError … 13` で落ちる → request の compute device を CPU に指定すると読める(1 枚約 12 s)。
- `rm -f $D/…` は Claude Code の安全確認に止められる(変数が空なら / になる形)。make_media の出力は `-y` で上書きされるので消す必要はなかった。dry run の dir は `"${D:?}"` の形で消す。
- `Bash` の command 行で `zsh -c '…'` を使うと中身を読めないとして止まる → 小さな script file にして実行。
- index(GPU、約 8.7 s)の直後は CPU の cap(policy0 3.40 GHz / 3.63、policy6 3.86 GHz / 4.74)が 20〜30 s 残る。take.sh の回復待ちで消えるのを待ってから録画した。GPU だけが温かい・cap の時は 60 s で先へ(query は CPU)。
- warm-up(1.0 s の無音)の後も、起動後 1 回目の query は 2 回目より遅い(121 / 99 ms、t2 132 / 116 ms)。round 2 の 216 / 102 より差は小さい。warm-up の長さを query と同じにしたら消えるかは未確認。

## op と delegate の観察
- GPU(shots・t1・t2、base bundle): text の encoder 4 本(`encoder_1x128`〜`1x1024`)と vision 2 本(`vision_70` / `vision_140`)は LITERT_CL に全 node(1011 / 1011、1477 / 1477)。audio の `main` は XNNPACK 1436 / 1600(226 partitions)。round 2 と同じ。init 904.8 / 922.0 / 924.7 ms(GPU の cache あり)。index 36 枚の総時間 8781.5 / 8869.9 / 8588.3 ms、1 枚の中央値 240.9 / 239.5 / 236.3 ms。WARMUP 74.8 / 74.9 / 82.7 ms。Adreno driver 0842.19.8。
- audio(CPU)の embed_ms: 起動後 1 回目 121.2 / 135.1 / 132.5 ms、2 回目 98.9(shots)/ 116.4(t2)ms。take の query は 2.6 s の再生(CPU がほぼ空く)の直後に走る。round 2 の batch(間 300 ms、中央値 104.0 ms)より遅い理由が CPU の周波数の立ち上がりかは未確認。
- NPU(npu2、SM8850 bundle、`libcdsprpc.so` を宣言): FastRPC は開いた(`libcdsprpc.so loaded`、CDSP に unsigned の user PD、`libQnnHtpV81Skel.so` と `libdspqueue_rpc_skel.so` の handle、dspqueue)。最後の行(QoS、04:49:19.973)の 3 ms 後に `nativeCreateEmbeddingEngine` の中で SIGBUS(pc 0x1、BUS_ADRALN)。register に QAIRT の版の文字列 `v2.47.0.2606…`。APK の QNN .so は QAIRT 2.47.0.260601、LiteRT-LM 0.18.0 の pin は QAIRT 2.50.0.260828(round 1)。版の差が原因かは未確認。round 2(宣言なし)は init が通り計算が全部 `Failed to allocate tensors`、round 3(宣言あり)は init で process が落ちた。
- QnnDsp の行: crash 前の process 4721 に `QnnDsp <E>` は無い。FastRPC 側の E 行は `remote_handle_control_domain failed … Permission denied`(default device を開けず HAL 経由に切り替え)と `open_shell failed for domain 3 … Permission denied`、どちらの後も user PD の作成まで進んだ(`out/device_r3/npu2_evidence.txt`)。
- runtime: litertlm-android 0.18.0(BuildConfig)。fp16 と fp32 の差は測っていない。

## 使った環境
- Mac: Apple M4 Max、macOS 27.0、zsh。`K/venv`(Homebrew python 3.14.6、numpy・pillow): place_audio.py・take_lines.py・numbers.py・npu_pick.py・wav_rms.py・fake_take.py。ffmpeg 9.0.1、Swift 6.4(ocr.swift、macOS Vision)。Android build: AGP 9.3.1、Gradle 9.7.0、KGP 2.4.0、JDK 17、litertlm-android 0.18.0、build-tools 36.0.0(aapt2)。build と切り出しは `~/code/standup/tools/quiet/quiet_wait.py` 経由(待ち 0 s)。
- device: Galaxy S26 SM-S942Q(RFGL80R6A6H)、Android 16、build BP4A.251205.006.S942QOPS1AZH9、uptime 463838 s(再起動なし)、airplane mode off(触っていない。APK に INTERNET permission は無い)。
- hold: あり。`run_device_r3.zsh` の 1 process で 04:49:10 取得(queue_cli wait)、04:53:52 release(282 s)。edge_enable 1 → 0 → 1。

## 残した file
- `ROUND3.md`(この file)。
- app: `app/app/src/main/AndroidManifest.xml`(libcdsprpc.so の 1 行)、`app/app/src/main/kotlin/com/mlboydaisuke/eg2demo/MainActivity.kt`(layout・warm-up・mic の床・PLAY_START / PILL / RESULT_LAYOUT・reindex・autoplay の複数)。APK は `app/app/build/`(作り直せる、消してよい)。`out/build_r3_1.log`・`out/build_r3_2.log`。
- script(書き換え): `scripts/gate.sh`、`scripts/take.sh`、`scripts/make_media.sh`、`scripts/numbers.py`、`scripts/dryrun_adb_shim.zsh`。round 2 の版は scratchpad にだけ写した(session 限り)。
- script(新規): `scripts/run_device_r3.zsh`(keeper → npu2 + shots → A_DONE → mic → go 待ち → take → release)、`scripts/npu_pick.py`、`scripts/place_audio.py`、`scripts/take_lines.py`、`scripts/ocr.swift`、`scripts/wav_rms.py`、`scripts/fake_take.py`、`scripts/dryrun_r3.zsh`。
- `out/device_r3/`(残す): `run_device_r3.log`、`gate.log`・`gate_stdout.log`・`gate_mic.log`・`gate_mic_stdout.log`、`npu2_crash.log`・`npu2_npu_all_pids.txt`・`npu2_evidence.txt`・`npu2_summary.txt`(と空の `npu2_demo.log` 等)、`photos_backend.txt`、`shots.log`・`shots_demo.log`・`shots_app_logcat.log`・`shots_eg2-demo-*.json`・`shot1_ready.png`・`shot2_q01.png`・`shot3_q13.png`、`mic_check.log`・`mic_check_1791402591612.wav`・`mic_check_1791402595398.wav`、**`r2_silent_1791398543548.wav`(残す: user の声の round の床 0.005 の根拠)**。`hold_sleeper.pid`・`go_takes`・`released` は消してよい。
- `out/takes_r3/`(残す): raw `eg2_demo_s26_t1_1791402771.mp4`・`eg2_demo_s26_t2_1791402831.mp4`、master `*_trim.mp4`、X 用 `*_x1080x1920.mp4`、`*_audio.json`・`*_pill.log`・`*_caption*.png`・`*_settle.json`、`*_lines/`(take_lines.py が読んだ frame)、`frames_t1/`・`frames_t2/`、`take_t1.log`・`take_t2.log`・`take_*_demo.log`・`take_*_app_logcat.log`・`take_*_eg2-demo-*.json`・`take_*_screenrecord.out`・`take_*_stdout.log`、`t1_lines.md/json`・`t2_lines.md/json`・`t1_numbers.md`・`t2_numbers.md`・`t1_check_run.md`・`t2_check_run.md`、`make_media_t1.log`・`make_media_t2.log`。
- 消してよい: `out/dryrun_r3_go/`・`out/dryrun_r3_nogo/`・`out/dryrun_r3_missing/`・`out/dryrun_r3_watchdog/`・`out/dryrun_media_r3/`(dry run の証拠)。
- 端末に残した物(user の声の round が使う。lane の終わりに名前で消して uninstall): app `com.mlboydaisuke.eg2demo`(新 APK、GPU の cache 入り)、`/sdcard/Android/data/com.mlboydaisuke.eg2demo/files/` の bundle 2 本・album 36 枚・wav 43 本・queries.json・index cache・`Documents/eg2-demo-*.json`・`mic/` の録音(round 2 の 2 本 + round 3 の 2 本)。

## lint
`bash ~/code/standup/tools/opus/round_close_lint.sh K/ROUND3.md` → `PASS round close: ROUND3.md`(04:59)
