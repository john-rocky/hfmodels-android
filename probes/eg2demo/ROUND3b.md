worker: hfmodels-android-f9 [5052a5]

## 経過
- 05:31 開始。launch 全文、ROUND3.md 全文、script(run_device_r3.zsh・dryrun_r3.zsh・take.sh・make_media.sh・frames.sh・take_lines.py・numbers.py・ocr.swift)、MainActivity.kt の 3 行目(`backendLineText`)・LAYOUT(`recordLayout`)・`buildUi`・`setFooter`、memory 8 本を読んだ。
- 05:33 launch の事実(音声の経路)を `ref/litertlm/embedding_engine_impl.cc` で確かめた: 1485 行 `audio_preprocessor_->Preprocess` → 1500 行 `audio_executor_->Encode` → 1508 行 special token の位置 → 1582 行 `embedding_executor_->ComputeEmbedding`。
- 05:34 round 3 の READY(`out/device_r3/shot1_ready.png`)の 3 行目「photos on GPU · voice on CPU」(13 sp)は幅 529 px(x 62〜590、y 314〜351)、行の幅は 960 px。Vision の OCR は「·」を「•」と読む(`photos on GPU • voice on CPU`)。次 = 線の事前登録、app の 1 行。
- 05:35 app の 3 行目だけ直した(下の「APK」)。05:36 build PASS(`out/build_r3b_1.log` exit=0、quiet_wait 0 s)。
- 05:37〜05:46 script(下の「残した file」): gate.sh に `SHOTS_READY_ONLY=1`、新規 `run_device_r3b.zsh`・`dryrun_r3b.zsh`・`cut_r3b.zsh`、take_lines.py に線 (g)、numbers.py の 3 行目、fake_take.py の 3 行目と pill・写真の位置。
- 05:39〜05:46 Mac の検証: dry run(go あり)exit 0、adb の呼び出し 51 + 148、key event 0、`logcat -c` 0、run_queries 0、install 1、関数の外の裸の adb 0。go 無し(GO_WAIT_S=5)exit 10、release、take 0。round 3 の wrapper の回帰 exit 0(shots 3 枚)。偽の take: 線 (a)〜(g) 7 本 PASS、旧 3 行目の偽物は (g) だけ FAIL(負の試験)。`cut_r3b.zsh` の通し exit 0。
- 05:46 3 行目の幅の見積もり: round 3 の 529 px × Mac の 5 書体での新旧の幅の比(1.763〜1.800)= 13 sp で 933〜952 px(行は 960 px)。入らなければ app が 0.5 sp ずつ縮める。端末の READY で確かめる。Mac 側 PASS。次 = 監督に「Mac 側 PASS、端末待ち」、go を待つ。
- 05:47 監督の go(05:47 の状態: hold なし、queue 空、S26 は adb に見える)。05:47:31 に cat: hold file なし、queue `[]`。05:47:34 `scripts/run_device_r3b.zsh` を background で 1 本(PHASE=A)。
- 05:47:35 hold 取得(`queue_cli.py wait`、keeper = sleeper pid 55245)。端末の状態: uptime 467343.14 s(round 3 の 04:49:10 に 463838.24 s、差 3504.9 s = 壁時計の差 3505 s = 再起動なし)、Thermal Status 0、mWakefulness=Dozing。edge_enable 1 → 0。次 = install -r、file の数、READY の screenshot。
- 05:47:57 shots PASS(GPU、reindex、READY だけ、held 22 s): install -r、files は名前で数えて bundle 2・album 36・queries 44 = push 0 本。ENGINE_READY init 921.6 ms、INDEX_DONE 8646.2 ms(1 枚の中央値 237.6 ms)、WARMUP 74.3 ms。05:47:58 A_DONE。
- 05:48 READY の画面(`out/device_r3b/shot1_ready.png`): 3 行目は 1 行で 13 sp のまま(行の高さ 38 px = round 3、文字の幅 x 61〜1007 = 947 px / 960 px)、OCR `audio encoder on CPU • backbone and photos on GPU`(`out/device_r3b/shot1_ready_ocr.txt`)。LAYOUT は title_top_px 135・footer_bottom_px 2007・title_to_footer_px 1872・frame_px.top 111・pill_px.top 386・footer_lines 2 で round 3 と全部同じ。05:48:4x 監督に screenshot の 1 通。次 = 監督の go(05:57:58 まで)。
- 05:48:55 監督の go(shot1 を見た: 3 行目が 1 行、他は round 3 と同じ。t1 と t2 を撮って release)。go file → 05:48:56 take t1 開始。
- 05:48:56〜05:50:03 take t1(q01_a): 冷却 0 s、READY 後の回復待ち 05:49:08〜05:49:45(CPU の cap と kgsl の温度 = index の後の熱。cap も GPU の温度も全部消えてから先へ、「GPU だけ 60 s で先へ」は使っていない)、05:49:47 Awake、録画、PLAY_START 05:49:50.718、RESULT 05:49:53.495、rc 0、cgroup `/top-app`。
- 05:50:03〜05:51:18 take t2(q01_a,q13_a、gap 2500): 冷却 0 s、回復待ち 05:50:14〜05:50:56(CPU の cap、kgsl の cap 1100 / 1200 MHz と thermal_pwrlevel 2 / 1 が 05:50:21〜05:50:29、全部消えてから先へ)、05:50:58 Awake、PLAY_START 05:51:01.383 / 05:51:06.687、RESULT 05:51:04.153 / 05:51:08.950、rc 0、cgroup `/top-app`。
- 05:51:18 release(held 223 s = 3.7 分)、edge_enable を 1 に戻した(読み直して 1)、sleeper 55245 は消えた。05:51:23 の hold は次の lane(`migration-skill-npu-probe-s26`、05:51:21 から)、queue 空(自分の札なし)。次 = 切り出し(Mac)。
- 05:51〜05:59 切り出し: `quiet_wait.py -- zsh scripts/cut_r3b.zsh t1 t2`(Mac の計測窓 `et-r7-gpu-smoke` で 435 s 待ってから)。make_media.sh(END_AFTER_RESULT=3.0)→ take_lines.py → check_run.py → numbers.py、exit 0、線 (a)〜(g) は 2 本とも PASS(下の表)。`out/takes_r3b/cut_r3b.log`。
- 05:59〜06:01 最後の frame を目で見た: X 版の `frames_t1/end.png` は DONE・赤い自転車(a01)・「audio → vector 131 ms」・「cos 0.72」、`frames_t2/end.png` は DONE・雪だるま(a20)・「114 ms」・「cos 0.71」、どちらも 3 行目は `audio encoder on CPU · backbone and photos on GPU`、status bar は写っていない。contact sheet で READY → 再生(赤)→ EMBEDDING(青)→ DONE の順を見た。次 = round close。

## 線(事前登録)
05:36 に launch から写した(撮る前)。
- (a) clip ≤ 15.0 秒(x1080x1920 と master、ffprobe)。
- (b) 画面の top-1 = gold(q01 → a01、q13 → a20)。
- (c) 画面の ms = JSON の embed_ms(同じ数字)。
- (d) 最後の frame = 結果の画面(DONE の pill と写真)。
- (e) 重ねた音声の開始と pill の「playing」の開始の差 ≤ 200 ms(frame の時刻と PLAY_START から)。
- (f) 字幕の文 = queries.json の text。
- (g) 3 行目の文字 = `audio encoder on CPU · backbone and photos on GPU`(frame から読む)。読み方(撮る前に決めた): 各 clip の結果の frame と master の最後の frame を `scripts/ocr.swift` で読み、2 行目(`Galaxy S26`)と pill(`READY` / `DONE` 等)の間にある行がちょうど 1 本で、その文字列の区切り記号(`•` `∙` `⋅` `·`)を `·` に、連続する空白を 1 つにした物が上の文字列と完全に一致。05:44 に実装で細かくした(撮る前): 「行」= OCR の観測のうち上端の差が 12 px 以内の物を左から繋いだ物、「pill の上」= LAYOUT の `pill_px.top`、X 版の最後の frame も読む、run JSON の 3 つの backend から組んだ文も同じ文字列(`take_lines.py` の `backend_line`)。
- 1 つでも割れたら撮り直し(枠の中なら同じ枠、外なら次の枠。監督に数字で 1 通)。2 回の撮り直しでも割れたら止めて監督に。

## APK
- path: `K/app/app/build/outputs/apk/debug/app-debug.apk`、75,527,344 B、sha256 `dc6aaa9648d8987579e0e9ce654d6c52f9725ae1b6772fad1a5bbc14a1b1b36e`(05:36:42、`out/build_r3b_1.log` exit=0)。round 3 の APK(`7c2ed250…`、同じ bytes 数)から変わったのは MainActivity.kt の 3 行目だけ。
- 直した物(`app/app/src/main/kotlin/com/mlboydaisuke/eg2demo/MainActivity.kt`):
  - `backendLineText()`: round 3 の `"photos on ${vision} · voice on ${audio}"` を、init した 3 つの backend から組む形に(audio encoder = `audioName`、backbone = `backendName`、photos = `visionName`。同じ backend の部分は「and」で 1 句、3 つ同じなら `all on GPU`)。この round の起動(backend gpu・vision gpu・audio cpu)では `audio encoder on CPU · backbone and photos on GPU`。
  - 新規 `setBackendLine(text)`: 13 sp で行の幅(`backendLine.width` − padding)に入らなければ 0.5 sp ずつ小さく(下限 10 sp)。3 行目は `maxLines = 1` なので、入らない語は 2 行目に回って見えなくなる。
  - ENGINE_READY の後の 1 行: `backendLine.text = backendLineText()` → `setBackendLine(backendLineText())`。
- 確かめた事: classes3.dex に `audio encoder`・`backbone`・`all on ` があり、`photos on`・`voice on` は無い(`unzip -p … | strings`)。

## 線の判定
2 本とも (a)〜(g) PASS(`scripts/cut_r3b.zsh` の中の `take_lines.py`、`out/takes_r3b/t1_lines.md`・`t2_lines.md`・`t1_lines.json`・`t2_lines.json`)。(c) の画面の ms は Vision の OCR で結果の frame から読んだ数、(b) は結果の frame の写真の範囲を album 36 枚と照合した結果、(g) は結果の frame・master の最後の frame・X 版の最後の frame の 3 行目を OCR で読んだ文字列。check_run.py: top-1 = Mac GPU 1/1(t1)・2/2(t2)、同じ写真の top-1 cos の |Δ|(Mac GPU · CPU)は 0.0026 · 0.0033(t1)・0.0032 · 0.0033(t2)(`out/takes_r3b/t1_check_run.md`・`t2_check_run.md`。check_run.py の「line … FAIL」は round 2 の 20 本の線で、1〜2 本の take には当てはまらない。round 3 と同じ)。

t1(q01_a.wav、`out/takes_r3b/eg2_demo_s26_t1_1791406202.mp4`):

| line | verdict | numbers |
|---|---|---|
| (a) clip <= 15.0 s | PASS | x1080x1920 8.333 s, master 8.333 s |
| (b) top-1 on screen = gold | PASS | q01_a: screen a01 (d 20.05, 2nd ['a08', 50.05]), json a01, gold a01 |
| (c) ms on screen = embed_ms | PASS | q01_a: screen 131 ms / json 131.396458 -> 131; cos 0.72 / 0.7203807234764099 |
| (d) last frame = result | PASS | pill green True [42, 124, 47], photo a01 (gold a01), ms 131 (json 131), x vs master crop 0.46262393904320986 |
| (e) audio vs red pill <= 200 ms | PASS | q01_a: 0.0 ms (offset -1791406188.2110891, 3 logged colour changes paired in order with the video's) |
| (f) caption = queries.json | PASS | q01_a: "a red bicycle leaning on a tree" |
| (g) third line = `audio encoder on CPU · backbone and photos on GPU` | PASS | result q01_a: ['audio encoder on CPU · backbone and photos on GPU']; end master: ['audio encoder on CPU · backbone and photos on GPU']; end x1080x1920: ['audio encoder on CPU · backbone and photos on GPU']; run JSON gives "audio encoder on CPU · backbone and photos on GPU" |

t2(q01_a.wav,q13_a.wav、`out/takes_r3b/eg2_demo_s26_t2_1791406278.mp4`):

| line | verdict | numbers |
|---|---|---|
| (a) clip <= 15.0 s | PASS | x1080x1920 13.133 s, master 13.133 s |
| (b) top-1 on screen = gold | PASS | q01_a: screen a01 (d 20.04, 2nd ['a08', 50.04]), json a01, gold a01; q13_a: screen a20 (d 10.92, 2nd ['a17', 48.43]), json a20, gold a20 |
| (c) ms on screen = embed_ms | PASS | q01_a: screen 132 ms / json 131.999115 -> 132; cos 0.72 / 0.7203807234764099; q13_a: screen 114 ms / json 113.996823 -> 114; cos 0.71 / 0.7127302289009094 |
| (d) last frame = result | PASS | pill green True [42, 124, 47], photo a20 (gold a20), ms 114 (json 114), x vs master crop 0.397578125 |
| (e) audio vs red pill <= 200 ms | PASS | q01_a: 2.6 ms; q13_a: -3.0 ms (offset -1791406258.8617945, 6 logged colour changes paired in order with the video's) |
| (f) caption = queries.json | PASS | q01_a: "a red bicycle leaning on a tree"; q13_a: "a snowman in the snow" |
| (g) third line = `audio encoder on CPU · backbone and photos on GPU` | PASS | result q01_a: ['audio encoder on CPU · backbone and photos on GPU']; result q13_a: ['audio encoder on CPU · backbone and photos on GPU']; end master: ['audio encoder on CPU · backbone and photos on GPU']; end x1080x1920: ['audio encoder on CPU · backbone and photos on GPU']; run JSON gives "audio encoder on CPU · backbone and photos on GPU" |

## clip(切り出し)
| take | 版 | path | 長さ | bytes | sha256(先頭 16) |
|---|---|---|---:|---:|---|
| t2 | X 用 1080×1920 | `out/takes_r3b/eg2_demo_s26_t2_1791406278_x1080x1920.mp4` | 13.133 s | 2,198,546 | 572b89cb963d9786 |
| t2 | master 1080×2340 | `out/takes_r3b/eg2_demo_s26_t2_1791406278_trim.mp4` | 13.133 s | 1,828,755 | 55d12815e1a97604 |
| t2 | raw(screenrecord、VFR) | `out/takes_r3b/eg2_demo_s26_t2_1791406278.mp4` | 13.255 s | 2,764,156 | eabb0204511de911 |
| t1 | X 用 1080×1920 | `out/takes_r3b/eg2_demo_s26_t1_1791406202_x1080x1920.mp4` | 8.333 s | 2,099,113 | 9e93209f43876a5e |
| t1 | master 1080×2340 | `out/takes_r3b/eg2_demo_s26_t1_1791406202_trim.mp4` | 8.333 s | 1,658,853 | 96d8fb9a7c726a11 |
| t1 | raw(screenrecord、VFR) | `out/takes_r3b/eg2_demo_s26_t1_1791406202.mp4` | 10.312 s | 1,981,181 | b494c57ab243a09d |

## 画面の数字(投稿用、numbers.py の出力のまま)

t2(`out/takes_r3b/t2_numbers.md`、run JSON `take_t2_eg2-demo-1791406203794.json`):

| on screen | value | from |
|---|---|---|
| subtitle | Galaxy S26 · LiteRT-LM 0.18.0 | `device.shown_as`, `runtime.litertlm_android` |
| backends line | audio encoder on CPU · backbone and photos on GPU | `runtime.audio_backend`, `runtime.backend`, `runtime.vision_backend` |
| footer | album 36 photos · indexed in 8.6 s on GPU · no network needed | `index.total_ms = 8597.21234`, `index.n`, `runtime.vision_backend` |
| q01_a: audio → vector | 132 ms | `rows[id=q01_a].embed_ms = 131.999115` |
| q01_a: top-1 photo | a01 (gold a01) | `rows[].top1` |
| q01_a: cos | 0.72 | `rows[].top1_cos = 0.7203807234764099` |
| q01_a: next match 2 | a35 cos 0.59 | `rows[].top3[1]` |
| q01_a: next match 3 | a34 cos 0.59 | `rows[].top3[2]` |
| q13_a: audio → vector | 114 ms | `rows[id=q13_a].embed_ms = 113.996823` |
| q13_a: top-1 photo | a20 (gold a20) | `rows[].top1` |
| q13_a: cos | 0.71 | `rows[].top1_cos = 0.7127302289009094` |
| q13_a: next match 2 | a21 cos 0.64 | `rows[].top3[1]` |
| q13_a: next match 3 | a03 cos 0.59 | `rows[].top3[2]` |

clip の長さ(`eg2_demo_s26_t2_1791406278_x1080x1920.mp4`): 13.133 s(numbers.py の --clip、ffprobe)。

t1(`out/takes_r3b/t1_numbers.md`、run JSON `take_t1_eg2-demo-1791406137370.json`):

| on screen | value | from |
|---|---|---|
| subtitle | Galaxy S26 · LiteRT-LM 0.18.0 | `device.shown_as`, `runtime.litertlm_android` |
| backends line | audio encoder on CPU · backbone and photos on GPU | `runtime.audio_backend`, `runtime.backend`, `runtime.vision_backend` |
| footer | album 36 photos · indexed in 8.7 s on GPU · no network needed | `index.total_ms = 8726.417809`, `index.n`, `runtime.vision_backend` |
| q01_a: audio → vector | 131 ms | `rows[id=q01_a].embed_ms = 131.396458` |
| q01_a: top-1 photo | a01 (gold a01) | `rows[].top1` |
| q01_a: cos | 0.72 | `rows[].top1_cos = 0.7203807234764099` |
| q01_a: next match 2 | a35 cos 0.59 | `rows[].top3[1]` |
| q01_a: next match 3 | a34 cos 0.59 | `rows[].top3[2]` |

clip の長さ(`eg2_demo_s26_t1_1791406202_x1080x1920.mp4`): 8.333 s(numbers.py の --clip、ffprobe)。

## 訂正
- launch「memory(読むだけ): round 3 と同じ(8 本)」→ この project の memory にあるのは 4 本(`reference_s26_demo_recording`・`screenrecord-cut-drops-last-frame`・`adb-run-script-traps`・`s26-shared-device-hold`)。`keyguard-background-cpuset-demo-trap`・`android-thermal-freq-cap-before-bench`・`shared-device-coordination` は litertlm-convert の memory、`x-post-constraints` は `~/code` の memory にあった(8 本とも読んだ)。証拠: `~/.claude/projects/-Users-majimadaisuke-code-litertlm-convert/memory/`、`~/.claude/projects/-Users-majimadaisuke-code/memory/x-post-constraints.md`。
- launch「`shots` は READY 1 枚だけ(`shot1_ready.png`)」→ round 3 の gate.sh の `shots` は READY・q01・q13 の 3 枚を必ず撮る形で、1 枚だけにする指定が無かった。`SHOTS_READY_ONLY=1` を足した(既定は round 3 のまま。round 3 の wrapper の dry run は exit 0、shots 3 枚)。証拠: `scripts/gate.sh` の `shots()`、`out/dryrun_r3b_regress_r3/run.log`。

## 罠と袋小路
- 偽の take(`scripts/fake_take.py`、round 3 作)の pill は top 330 で、3 行目(y 308〜347)の左に重なっていた。OCR が `er on CPU · …` と読み、(g) の正の試験が FAIL した → pill を round 3 の実機の LAYOUT(top 386)、写真を RESULT_LAYOUT(top 500・高さ 579)に直して PASS。偽の画面に新しい線を足す時は、偽の配置を実機の LAYOUT と先に突き合わせる(約 3 分)。
- macOS Vision の OCR は「·」を「•」と読む(3 行目・footer)。2 行目では区切りを落とす(`Galaxy S26 LiteRT-LM 0.18.0`)。文字の一致を見る線は区切り記号を正規化してから比べる(`take_lines.py` の `SEPS`)。
- t2 の 2 本目の clip の間(clip の 7.8〜9.9 s)、字幕(level bar の上、crop の y 1339)が前の結果の「next matches」の行(cos の数字)に重なる。round 3 の t2 も同じ(`out/takes_r3/frames_t2/contact_1s.png` の 8・9 秒)。launch が他を変えないので字幕の位置は round 3 のまま。直すなら字幕を写真の上に移すか、2 本目の間だけ next matches を隠す(どちらも未試行)。
- END_AFTER_RESULT=3.0 の切り方では、最後の結果の 2.5 s 後に mic ボタンの alpha が 0.4 → 1.0 に戻る所が clip の終わりの 0.5 s に入る(raw の最後の変化 t1 7.81 s、t2 12.94 s)。最後の frame は結果の画面のまま(DONE・写真・ms)で線 (d) は PASS。round 3 と同じ。
- zsh が `grep -r --include=*.kts` の glob を Mac 側で展開して止まった(`no matches found`)→ 引数を引用符で囲む(memory zsh-bash-tool-traps と同じ罠)。
- quiet_wait.py が Mac の計測窓(`et-r7-gpu-smoke`、05:49:16 から)で 435 s 待った。切り出しは hold を返した後なので端末の枠には響かない。
- check_run.py の「QNN · HTP · dispatch lines」の dispatch 1 行は `WindowOnBackDispatcher`(Android の window の log)で、backend の行ではない(t1・t2 とも)。

## op と delegate の観察
- GPU(shots・t1・t2、base bundle、backend gpu・vision gpu・audio cpu): text の encoder 4 本(`encoder_1x128`・`1x256`・`1x512`・`1x1024`)と vision 2 本(`vision_70`・`vision_140`)は LITERT_CL に全 node(1011 / 1011、1477 / 1477、各 1 partition)。audio の `main` は XNNPACK 1436 / 1600(226 partitions)。round 3 と同じ。XNNPACK に入らなかった 164 node の実行先は logcat に無い(未確認)。証拠: `out/device_r3b/shots_app_logcat.log`、`out/takes_r3b/take_t1_app_logcat.log`・`take_t2_app_logcat.log`。
- 3 行目の文言は logcat の delegate の行と合う: audio encoder(`main`)= XNNPACK(CPU)、backbone(`encoder_1x*`)と photos(`vision_*`)= LITERT_CL(GPU)。「audio → vector」は audio encoder と backbone の合計(`ref/litertlm/embedding_engine_impl.cc` の 1485〜1582 行)。2 つの内訳は測っていない(未確認)。
- init 921.6(shots)/ 921.7(t1)/ 912.3(t2)ms(GPU の cache あり)。index 36 枚の総時間 8646.2 / 8726.4 / 8597.2 ms、1 枚の中央値 237.6 / 239.3 / 235.8 ms。WARMUP 74.3 / 89.5 / 85.0 ms。Adreno driver 0842.19.8。
- audio の embed_ms: 起動後 1 回目 131.4(t1)/ 132.0(t2)ms、2 回目 114.0(t2)ms。round 3 の take は 135.1 / 132.5 ms、116.4 ms。
- 録画の前の回復待ち: index の後に CPU の cap(policy0・policy6)と kgsl の温度が残った(t1 05:49:08〜05:49:45、t2 05:50:14〜05:50:56。t2 は kgsl の cap 1100 / 1200 MHz と thermal_pwrlevel 2 / 1 も 05:50:21〜05:50:29)。全部消えてから録画した。query の前後の thermal status は 0(run JSON の `thermal_before` / `thermal_after`)。
- NPU: この round は使っていない。fp16 と fp32 の差は測っていない。runtime: litertlm-android 0.18.0(BuildConfig と run JSON)。

## 使った環境
- Mac: Apple M4 Max、macOS 27.0(26A428)、zsh。`K/venv`(Python 3.14.6、numpy 2.5.3、pillow 12.3.0): take_lines.py・numbers.py・check_run.py・place_audio.py・fake_take.py。ffmpeg 9.0.1、Swift 6.4(ocr.swift、macOS Vision、CPU で実行)。Android build: AGP 9.3.1、Gradle 9.7.0、KGP 2.4.0、JDK Temurin 17.0.16、litertlm-android 0.18.0。build と切り出しは `~/code/standup/tools/quiet/quiet_wait.py` 経由(build は待ち 0 s、切り出しは 435 s)。
- device: Galaxy S26 SM-S942Q(RFGL80R6A6H、SoC SM8850)、Android 16(SDK 36)、build BP4A.251205.006.S942QOPS1AZH9、uptime 467343 s(再起動なし)、airplane mode off(触っていない。APK に INTERNET permission は無い)。
- hold: あり。`run_device_r3b.zsh` の 1 process(script 名 `eg2demo-take-r3b`、keeper = sleeper pid 55245)で 05:47:35 取得(queue_cli wait)、05:51:18 release(223 s)。edge_enable 1 → 0 → 1。

## 残した file
- `ROUND3b.md`(この file)。
- app: `app/app/src/main/kotlin/com/mlboydaisuke/eg2demo/MainActivity.kt`(3 行目: `backendLineText`・`setBackendLine`・ENGINE_READY の後の 1 行)。APK は `app/app/build/`(作り直せる、消してよい)。`out/build_r3b_1.log`。
- script(書き換え): `scripts/gate.sh`(`SHOTS_READY_ONLY`)、`scripts/take_lines.py`(線 (g)、OCR の位置)、`scripts/numbers.py`(3 行目を run JSON の 3 つの backend から)、`scripts/fake_take.py`(3 行目、pill と写真の位置)。round 3 の版は scratchpad にだけ写した(session 限り)。
- script(新規): `scripts/run_device_r3b.zsh`(keeper → shots の READY → A_DONE → go 待ち → take 2 本 → release)、`scripts/dryrun_r3b.zsh`、`scripts/cut_r3b.zsh`(切り出しの 5 工程)。
- `out/device_r3b/`(残す): `run_device_r3b.log`、`gate.log`・`gate_stdout.log`、`shots.log`・`shots_demo.log`・`shots_app_logcat.log`・`shots_eg2-demo-1791406063903.json`、`shot1_ready.png`・`shot1_ready_ocr.txt`。`hold_sleeper.pid`・`go_takes`・`released` は消してよい。
- `out/takes_r3b/`(残す): raw 2 本、master `*_trim.mp4`、X 用 `*_x1080x1920.mp4`、`*_audio.json`・`*_pill.log`・`*_caption*.png`・`*_settle.json`、`*_lines/`(take_lines.py が読んだ frame)、`frames_t1/`・`frames_t2/`、`take_t1.log`・`take_t2.log`・`take_*_demo.log`・`take_*_app_logcat.log`・`take_*_eg2-demo-*.json`・`take_*_screenrecord.out`・`take_*_stdout.log`、`t1_lines.md/json`・`t2_lines.md/json`・`t1_numbers.md`・`t2_numbers.md`・`t1_check_run.md`・`t2_check_run.md`、`make_media_t1.log`・`make_media_t2.log`・`cut_r3b.log`。
- 消してよい: `out/dryrun_r3b_go/`・`out/dryrun_r3b_nogo/`・`out/dryrun_r3b_regress_r3/`・`out/dryrun_media_r3b_pos/`・`out/dryrun_media_r3b_neg/`・`out/dryrun_cut_r3b/`(dry run の証拠)。
- 端末に残した物(round 3 と同じ。user の声の round が使う。lane の終わりに名前で消して uninstall): app `com.mlboydaisuke.eg2demo`(この round の APK、GPU の cache 入り)、`/sdcard/Android/data/com.mlboydaisuke.eg2demo/files/` の bundle 2 本・album 36 枚・wav 43 本・queries.json・index cache・`Documents/eg2-demo-*.json`(この round の 3 本が増えた)・`mic/` の録音。

## lint
`bash ~/code/standup/tools/opus/round_close_lint.sh K/ROUND3b.md` → `PASS round close: ROUND3b.md`(06:03)
