worker: hfmodels-android-ed [ad5e86]

## 経過
- 01:55 開始。`df -g /` = Available 14 GiB(1858 G 中 Used 17、Capacity 56%)。K は監督が用意済み(.gitignore、ref/、空の app/ fixtures/ models/ out/ scripts/)。次 = A の DL 1 本目を background で始め、先に読む物を読む。
- 01:55〜01:58 A: base の 1 本目は 37 KiB/s(nettop 実測、0 byte のまま 70 s)で kill、同じ command の出し直しで約 1 分で完了。SM8850 は 1 回で完了(約 20 s)。sha256 2 本とも launch の値と一致 → `models/PATHS.json`。`df -g /` = 12 GiB。次 = 先に読む物の残り(kev script・memory)→ venv → B。
- 02:04 venv(`K/venv`、Homebrew の python3.14 = 3.14.6)に litert-lm 0.18.0 を入れた(native は litert-lm-api 0.18.0 の macosx_12_0_arm64 wheel が `liblitert-lm.dylib` 63 MB を持つ = 初回取得ではない)。`fetch_album.py search` 走行中。`make_queries.py` と `embed_mac.py` を書いた(採点はまだ走らせていない)。次 = 候補の一覧画像を見て album を決める。

## 線(事前登録、02:04 に launch から写した。採点の前)
音声 → 写真、声 A、top-1 ≥ 16/20 かつ top-3 ≥ 19/20 → 候補 A 続行(round 2 の端末採点へ)。声 B は参考表。割った時は 20 文の言い回しを **1 度だけ**直してよい(写真の主題をそのまま言う形に。元の wav は消さず、直した版は `q<NN>_a2.wav`、両方の数字を表に出し「直した」と書く)。それでも割ったら候補 B の線(文字 → 写真、最良の prefix で top-1 ≥ 18/20)を採点して、数字を添えて監督に SendMessage し止まる(判断が要る)。

## 経過(続き)
- 02:05 jniLibs の照合の途中で一時 file を `/tmp/eg2_sums_sel.txt` に 1 つ書き、すぐ消した(K の外への書き込みはこの 1 回だけ。以後の照合 file は `K/out/`)。
- 02:12 E: `assembleDebug` 1 回目 FAIL(KDoc の `queries/*.wav` が Kotlin の入れ子 comment を開いた = Unclosed comment)→ 2 回目 PASS → strip で .so 3 本が 8 byte 変わるのを見て `keepDebugSymbols` を足した 3 回目 PASS(APK 内の 10 本が SHA256SUMS と一致、`out/apk_so_shasum_check.txt`)。
- 02:27 B: album 36 枚で確定(全部を原寸で見た。灯台・桜・石橋は差し替え、気球・機関車・手漕ぎ舟は人か文字が残り主題ごと落とした。`fixtures/album.json`、捨てた物 22 行)。C: 20 主題と 20 文を採点の前に固定(`scripts/make_queries.py` の QUERIES)、wav 43 本(16 kHz mono PCM16)。次 = D の採点(quiet_wait 経由)。
- 02:29 D: Mac の採点(GPU = WebGPU、vision GPU、audio CPU、+ CPU 参照)。線 PASS(声 A top-1 20/20・top-3 20/20)。scores.json の行に per-call ms が無かったので script を直してもう 1 回走らせ、判定は同じ(1 回目は `out/mac/run1/`)。app に LAYOUT log を足して再 build PASS。次 = F の script。
- 02:38 F: gate.sh / take.sh(file・mic)を shadow adb で dry run(3 本とも exit 0、関数の外に裸の adb なし)。smoke(kev master の写し: settle.py は明るい画面で exit 1 = 想定、make_media.sh 音声なし・音声 + 字幕あり、frames.sh)を通して出力を消した。`out/mac/cache/`(589 MB)を消した。次 = G(この file)と lint、監督へ 1 通。

完了: bundle 2 本(sha256 一致)、album 36 枚(CC0、全部を原寸で目視)、query 20 文 × 2 声 + decoy 3 本、Mac の採点(線 PASS = 声 A top-1 20/20・top-3 20/20)、APK(assembleDebug PASS、litertlm-android 0.18.0、NPU の .so 10 本が SHA256SUMS と一致)、script 12 本(+ present_cut.py が読む card_track.py)と dry run 用の shim を K にそろえた。端末は触っていない。round 2(S26 の GPU / NPU で同じ採点)は gate.sh 1 本で回る形。
### album(`fixtures/album.json` から)
- 36 枚、全部 CC0(license=cc0 1.0)。出所: flickr 19, stocksnap 10, wikimedia 7。捨てた物の行 22(人 / 文字・番号 / 別物 / 主題の重なり)。

| id | 主題 | 大きさ | 出所 | query の正解 |
|---|---|---|---|---|
| a01 | red_bicycle | 1023x728 | flickr | yes |
| a02 | lighthouse | 1024x683 | flickr | yes |
| a03 | snow_mountain | 1024x671 | wikimedia | yes |
| a04 | sunflower_field | 960x640 | stocksnap | yes |
| a05 | sailboat | 960x640 | stocksnap | - |
| a06 | waterfall | 960x639 | stocksnap | yes |
| a07 | coffee_cup | 1024x576 | flickr | yes |
| a08 | pizza | 1024x768 | flickr | yes |
| a09 | sand_dunes | 1024x682 | flickr | - |
| a10 | sleeping_cat | 960x640 | stocksnap | yes |
| a11 | dog_beach | 960x555 | stocksnap | yes |
| a12 | red_barn | 1024x768 | flickr | - |
| a13 | wooden_pier | 1024x678 | flickr | - |
| a14 | city_night | 1024x669 | flickr | yes |
| a15 | forest_path | 1024x681 | wikimedia | - |
| a16 | strawberries | 960x690 | stocksnap | - |
| a17 | violin | 876x1024 | wikimedia | yes |
| a18 | stone_bridge | 1024x680 | wikimedia | - |
| a19 | tulip_field | 1024x768 | flickr | yes |
| a20 | snowman | 1024x768 | flickr | yes |
| a21 | campfire | 1023x739 | flickr | yes |
| a22 | rainbow | 960x640 | stocksnap | yes |
| a23 | windmill | 1023x457 | flickr | yes |
| a24 | castle_hill | 1023x679 | flickr | yes |
| a25 | cactus | 1024x683 | flickr | - |
| a26 | pumpkins | 1024x678 | flickr | - |
| a27 | canoe_lake | 960x721 | stocksnap | - |
| a28 | paper_lantern | 1024x682 | wikimedia | - |
| a29 | chess_board | 960x640 | stocksnap | yes |
| a30 | bananas | 1024x768 | flickr | - |
| a31 | lemon_slices | 960x640 | stocksnap | - |
| a32 | cherry_blossom | 1023x640 | flickr | - |
| a33 | umbrella_rain | 1024x682 | wikimedia | yes |
| a34 | autumn_leaves | 1024x683 | flickr | yes |
| a35 | pine_cone | 1024x676 | flickr | - |
| a36 | sliced_bread | 1024x704 | wikimedia | - |

### queries(`fixtures/queries.json` から、16 kHz mono PCM16、声 A = Samantha、声 B = Daniel)

| query | 文 | 正解 | 秒 A | 秒 B |
|---|---|---|---:|---:|
| q01 | a red bicycle leaning on a tree | a01 (red_bicycle) | 2.582 | 2.693 |
| q02 | a lighthouse on the coast | a02 (lighthouse) | 2.176 | 2.194 |
| q03 | a mountain with snow on top | a03 (snow_mountain) | 2.42 | 2.287 |
| q04 | a field full of sunflowers | a04 (sunflower_field) | 2.385 | 2.647 |
| q05 | a waterfall in the forest | a06 (waterfall) | 2.187 | 2.275 |
| q06 | a cup of coffee on the table | a07 (coffee_cup) | 2.408 | 2.391 |
| q07 | a slice of pizza | a08 (pizza) | 1.909 | 1.938 |
| q08 | the cat taking a nap | a10 (sleeping_cat) | 1.97 | 2.037 |
| q09 | a dog on the beach | a11 (dog_beach) | 1.897 | 1.904 |
| q10 | the city lights at night | a14 (city_night) | 1.994 | 2.188 |
| q11 | an old wooden violin | a17 (violin) | 2.152 | 2.136 |
| q12 | a field of tulips | a19 (tulip_field) | 1.967 | 2.031 |
| q13 | a snowman in the snow | a20 (snowman) | 2.106 | 2.078 |
| q14 | a campfire burning at night | a21 (campfire) | 2.361 | 2.449 |
| q15 | a rainbow over the field | a22 (rainbow) | 2.129 | 2.147 |
| q16 | an old windmill in a field | a23 (windmill) | 2.268 | 2.321 |
| q17 | the old castle by the lake | a24 (castle_hill) | 2.294 | 2.363 |
| q18 | a chess board with wooden pieces | a29 (chess_board) | 2.536 | 2.681 |
| q19 | an umbrella in the rain | a33 (umbrella_rain) | 2.002 | 2.089 |
| q20 | red autumn leaves on a tree | a34 (autumn_leaves) | 2.209 | 2.391 |
| d01 (decoy) | what time is it now | - | 1.914 | - |
| d02 (decoy) | turn the volume down | - | 1.947 | - |
| d03 (decoy) | remind me tomorrow morning | - | 2.314 | - |

### Mac の採点(`out/mac/summary.json` と `scores.json` から)
- engine: main GPU(WebGPU)、vision GPU、audio CPU、init 514.4 ms。options = EmbeddingOptions(normalize=True, output_size=768); no prefix on images and audio。
- norm: album min 0.999999 / max 1.0、全 embedding min 0.999999 / max 1.000001。normalize=None(C++ の既定)の norm: image 1.0、audio 1.0、text 1.0 = 既定でも L2 正規化される。

| group | top-1 | top-3 | n | 当たりの最小 cos | 外れ(正解の順位、1 位との差) |
|---|---:|---:|---:|---:|---|
| audio_voice_a | 20 | 20 | 20 | 0.6421 | なし |
| audio_voice_b | 19 | 20 | 20 | 0.6972 | q08_b 正解 a10 順位 2 1位 a11 差 0.0132 |
| text_prefix_card | 20 | 20 | 20 | 0.7016 | なし |
| text_prefix_devsite | 20 | 20 | 20 | 0.7181 | なし |
| text_prefix_none | 20 | 20 | 20 | 0.6926 | なし |

- decoy 3 本の最大 cos: 0.5957(d01_a → a11 0.5822, d02_a → a04 0.5913, d03_a → a35 0.5957)。声 A の当たりの最小 cos 0.6421 より下。
- 声 A の 1 位と 2 位の差: 最小 0.0157(q08_a、2 位 a11)、中央値 0.1039。
- 音声と同じ文の文字(prefix なし)が 20 文の中で 1 位: 40/40。
- 線: audio -> photo, voice A, top-1 >= 16/20 and top-3 >= 19/20 → 声 A top-1 20/20、top-3 20/20 → PASS。候補 B の線(参考、採点は要らなかったが同じ run で出た): best prefix card top-1 20/20。
- GPU と CPU(同じ入力): album cos min 0.987127 / mean 0.996231 / max|Δ| 0.019836, queries cos min 0.991281 / mean 0.998601 / max|Δ| 0.02091, decoys cos min 0.998649 / mean 0.998796 / max|Δ| 0.007142, texts cos min 0.998672 / mean 0.999178 / max|Δ| 0.01032。audio の top-1 が GPU と同じ: 40/40。CPU の表: audio_voice_a 20/20, audio_voice_b 19/20, text_prefix_card 20/20, text_prefix_devsite 20/20, text_prefix_none 20/20。
- 1 call の ms(混んだ Mac、参考値。card・投稿に使わない): GPU image median 78.0 / audio 83.0 / text 8.7、CPU image 376.1 / audio 101.7 / text 32.3(per-call は scores.json の embed_ms_by_backend と summary.json の image_ms)。

## 線の判定
音声 → 写真、声 A: top-1 20/20、top-3 20/20(`out/mac/summary.json` の line)→ **PASS = 候補 A 続行**。言い回しの直しはしていない(`q<NN>_a2.wav` なし)。声 B(参考): top-1 19/20、top-3 20/20。

## APK
- path: `K/app/app/build/outputs/apk/debug/app-debug.apk`、75,527,344 B、sha256 `a2cdf02df9a9db093744e044d4845c7905bf72ffee570079567b2e6d1df0f491`(02:32 build。`out/build5.log` exit=0)。
- 中身: `lib/arm64-v8a/` に liblitertlm_jni.so と Qualcomm の 10 本(SHA256SUMS と照合 10/10 OK = `out/apk_so_shasum_check.txt`)、RECORD_AUDIO、uses-native-library libOpenCL.so / libvndksupport.so(required=false)、targetSdk 36(aapt2 badging)。install はしていない。
- build の回数: 5 回(1 回目 FAIL = Unclosed comment、2 回目 PASS、3 回目 = keepDebugSymbols、4 回目 = LAYOUT log、5 回目 = RUN_JSON log。2 回目以降すべて PASS)。

## script(`K/scripts/`)
| file | 役割 | 使い方 |
|---|---|---|
| fetch_album.py | Openverse の CC0 写真の候補集め・一覧画像・album の組み立て | `venv/bin/python -I scripts/fetch_album.py search`、`search_more <slug> "<query>"`、`build`(`fixtures/album_picks.json` を読む) |
| make_queries.py | `say` → 16 kHz mono PCM16 wav、gain 正規化、前後 0.3 s 無音、queries.json | `venv/bin/python -I scripts/make_queries.py`(直しは `--rev 2`、今回は未使用) |
| embed_mac.py | Mac の採点(GPU、`--also-cpu` で CPU 参照) | `~/code/standup/tools/quiet/quiet_wait.py -- venv/bin/python -I scripts/embed_mac.py --backend gpu --also-cpu` |
| gate.sh | round 2 の端末 gate(install・grant・file push と sha256・GPU leg・NPU leg・backend 行・片づけ) | hold を取った後 `scripts/gate.sh`(`KEEP_FILES=1` で round 3 用に残す)。dry run: `ADB_SHIM=scripts/dryrun_adb_shim.zsh OUT_DIR=… HOLD_FILE=… scripts/gate.sh` |
| take.sh | round 3 の撮影(冷却・READY・回復・Awake・screenrecord・file / mic) | `MODE=file scripts/take.sh t1 q01_a.wav`、`MODE=mic scripts/take.sh t2` |
| make_media.sh | master `_trim`(1080×2340)+ `_x1080x1920`、query の音声を pill の赤の frame(clock で照合)に重ね、話している間の字幕 | `scripts/make_media.sh <raw.mp4> <take.log> <run.json> <wav> "<文>"`(`NO_AUDIO=1`、`CUT_T`、`Y0`) |
| captions.py | 字幕 PNG `You say: "<文>"`(ffmpeg に drawtext が無い) | make_media.sh から呼ぶ |
| check_run.py | 端末の run JSON と `out/mac/scores.json` の照合(top-1 一致、hit、cos の差、embed_ms、backend 行) | `venv/bin/python -I scripts/check_run.py <leg json> --logcat out/device/<leg>_app_logcat.log` |
| numbers.py | take の画面の数字の表(投稿用) | `venv/bin/python -I scripts/numbers.py <take json>` |
| settle.py / present_cut.py(+ card_track.py)/ frames.sh | kev_work/demo から写した(path だけ変更)。present_cut は kev の layout 用 | kev と同じ |
| dryrun_adb_shim.zsh | dry run 用の adb 関数(端末に触らない) | `ADB_SHIM=` で読ませる |

dry run: gate.sh exit 0(push 82 本 = bundle 2・写真 36・wav 43・queries.json、leg gpu / npu、片づけ)、take.sh MODE=file exit 0、MODE=mic(DRY_MIC=1)exit 0(`out/dryrun_*/`)。裸の adb: `grep -n -w adb scripts/gate.sh scripts/take.sh` = コメント 3 行と `dev()` の 2 行だけ。

## 未確認
- S26 の GPU(OpenCL)で EmbeddingEngine が initialize できるか、embed_ms(round 2)。
- SM8850 bundle が kev_work/npu/libs の .so(QAIRT 2.47)で載るか(LiteRT-LM 0.18.0 の pin は QAIRT 2.50.0.260828)。vision も NPU に載るか(app の MODEL_INFO log で round 2 に読む)。
- app の実行時の動き全部(mic 録音、AudioTrack 再生、index cache、失敗の道 = bundle 無し・init 例外・busy・0 byte・permission)。端末で未実行。
- S26 の toybox `date +%s%3N` が ms を返すか(take.sh は秒 × 1000 に落ちる fallback あり)。
- make_media.sh の pill の赤の検出が実 take で当たるか(smoke は clock 側だけ通った)。
- 声 B の q08 の外れ(cat → dog、差 0.0132)が端末でも出るか。card の float16 警告が S26 の GPU で順位を変えるか。

## disk
- 前: `df -g /` Available 14 GiB(01:55)。後: 13 GiB(02:38)。`du -sh`: `K/out` 67 M、`~/.cache/eg2demo` 996 M、`K/venv` 147 M、`K/app/app/build` 493 M。

## 訂正
- launch「python3.14 = pyenv 3.14.6」→ 実際は Homebrew の `/opt/homebrew/bin/python3.14`(3.14.6)。証拠: `which python3.14`、`out/mac/summary.json` の runtime。
- launch「wheel は py3-none-any 97 KB = native は初回に取りに行く形か未確認」→ litert-lm 0.18.0 が引く litert-lm-api 0.18.0(macosx_12_0_arm64)に `liblitert-lm.dylib` 65,705,728 B が入っている = 初回の取得なし。証拠: `venv/lib/python3.14/site-packages/litert_lm_api-0.18.0.dist-info/WHEEL`、`out/mac/pip_freeze.txt`。
- launch「NPU の .so は QAIRT 2.47」→ LiteRT-LM v0.18.0 の WORKSPACE は litert `26895c9f` を pin し、その `third_party/qairt/workspace.bzl` は QAIRT 2.50.0.260828(DL はしていない)。v0.18.0 に MODULE.bazel は無い(404)。証拠: `out/qairt/WORKSPACE`、`out/qairt/qairt_workspace.bzl`。
- launch「EmbeddingOptions の null が L2 正規化か未確認」→ normalize=None で image / audio / text とも norm 1.0、C API の既定も normalize = true。app と script は normalize = true を明示のまま。証拠: `out/mac/summary.json` の engine.default_options_norms、`ref/litertlm/embedding_engine.h:204`。
- launch「長辺 1024 px」→ stocksnap の Openverse url は CDN の 960w 版 = 10 枚は長辺 960 px(拡大はしない)。証拠: `fixtures/album.json` の width / height。

## 罠と袋小路
- hf download の 1 本目が 37 KiB/s(0 byte のまま 70 s、同じ URL の curl は 3.5 MB/s)→ kill して同じ command を出し直すと約 1 分。1 分で bytes を見る規則(memory hf-upload-xet-stall)は download にも効く。
- Openverse は長い句だと source 付きで 0 件("red bicycle leaning on a wall")→ 2〜3 語の検索語。"castle hill" は NZ の Castle Hill(岩)を返す → "castle"。
- Wikimedia Commons の縮小版 URL は 1024px を 400("Use thumbnail sizes listed …")で拒否、1280px は通る。原寸は数十 MB で逐次 DL が 5 分を超えた → 1280px 版 + 並列 DL。
- 候補の一覧(640 px)で見えなかった人・文字: 灯台の戸口の 2 人、気球のかごの操縦者と番号 16、石橋の刻印 1882、機関車の横の小屋の SLOW 15、手漕ぎ舟の左端の人、桜の幹の陰の人影 → 原寸と拡大(`out/zoom_*.jpg`)で見る。気球・機関車・手漕ぎ舟は候補が尽きて主題ごと落とした(36 枚 = 下限)。
- Kotlin の KDoc に `queries/*.wav` と書くと入れ子 comment が開いて Unclosed comment(compile 1 回目 FAIL)。comment に `/*` を書かない。
- AGP の strip が jniLibs の .so 3 本を 8 byte 変える(libLiteRtCompilerPlugin_Qualcomm / libLiteRtDispatch_Qualcomm / liblitertlm_jni)→ `packaging.jniLibs.keepDebugSymbols += "**/*.so"` で byte 一致。
- litertlm-android 0.18.0 の AAR manifest に `uses-native-library` が無い → GPU 用に app が libOpenCL.so を宣言(kev_gate と同じ)。端末での要否は round 2 で確認。
- zsh: `$CAP_Y:enable` の `:e` が修飾子になり ffmpeg の y が "nable" に、`$X_FIT[v]` が配列添字になる → `${CAP_Y}:` と `${X_FIT}[v]`。
- settle.py は暗い app 用: 明るい kev の master では "no frame … luma below 60" で exit 1(smoke は `CUT_T=0`)。

## op と delegate の観察
- Mac(M4 Max、litert-lm 0.18.0 Python): GPU = WebGPU(log "Initializing WebGPU-based API from serialized data"、accelerator "GPU WebGPU")。NPU accelerator は Mac では登録されない(kLiteRtStatusErrorNotFound)。落ちた op の行は log に無い。
- GPU と CPU(同じ 36 枚・43 clip・60 文、audio encoder は両方 CPU): cos min image 0.987127 / audio 0.991261 / text 0.998672、max|Δ| 0.019836 / 0.021043 / 0.01032、audio の top-1 は 40/40 同じ。card の float16 警告は Mac の WebGPU では順位を変えていない。S26 は未確認。
- vision の前処理は 1024x682 の写真を 672x432 に縮める("1134 patches to fit the max_num_patches: 1260 limit")。audio の前処理に "Missing 10 bands … mel_channel_count: 128" の警告(結果は上の表)。bundle に SP_Tokenizer section と tf_lite_per_layer_embedder が無いという警告(動作に影響は見えない)。
- GPU の init: 1 回目 4,837.7 ms(cache 空)、2 回目 514.4 ms(同じ cache_dir)。CPU 参照の init 133.4 ms。
- runtime: litert-lm 0.18.0(Mac)、litertlm-android 0.18.0(APK、端末は未実行)。

## 使った環境
- Mac: Apple M4 Max、macOS 27.0(26A428)。venv `K/venv`(Homebrew python 3.14.6): litert-lm 0.18.0、litert-lm-api 0.18.0、numpy 2.5.3、pillow 12.3.0、requests 2.34.2(`out/mac/pip_freeze.txt`)。hf CLI(huggingface_hub 0.36.2、HF_HUB_DISABLE_XET=1)。ffmpeg 9.0.1(drawtext なし)。`say` の声 Samantha(en_US)・Daniel(en_GB)。
- Android build: AGP 9.3.1、Gradle 9.7.0(wrapper)、KGP 2.4.0、JDK 17.0.16、litertlm-android 0.18.0(Google Maven、gradle cache)、compileSdk / targetSdk 36、minSdk 31。
- device: 使っていない(adb なし)。hold: 取っていない。

## 残した file
- `ROUND1.md`(この file)。
- `models/PATHS.json`(bundle 2 本の path / bytes / sha256)。bundle 本体は `~/.cache/eg2demo/`(round 2 で push する。`.cache/huggingface/` の metadata は消してよい)。
- `fixtures/album/*.jpg`(36 枚、git に入れない)、`fixtures/album.json`(出所・目視・捨てた物)、`fixtures/album_picks.json`(目で選んだ記録 = build の入力)。
- `fixtures/queries/*.wav`(43 本、git に入れない)、`fixtures/queries.json`(文・正解・秒・sha256)。
- `out/mac/summary.json`・`scores.json`・`scores_cpu.json`・`album_emb.npy`・`album_ids.json`・`embed_mac.log`・`pip_freeze.txt`(採点の正)、`out/mac/run1/`(1 回目、判定は同じ。消してよい)。
- `app/`(Gradle 一式、Kotlin 2 本、jniLibs 10 本 = git に入れない)、APK は `app/app/build/`(build dir は消してよい、作り直せる)。
- `scripts/` 13 本 + shim(上の表)。
- `out/album_candidates/`(候補の 640 px と一覧画像、candidates.json = 選んだ根拠。写真は消してよい)、`out/zoom_*.jpg`(拡大で見た証拠、消してよい)、`out/fetch_album_*.log`(消してよい)。
- `out/dl_base.log`・`out/dl_sm8850.log`、`out/qairt/`(QAIRT の版の証拠)、`out/aar/`(AAR の manifest と jni、消してよい)、`out/build1〜5.log`、`out/jnilibs_SHA256SUMS.selected`・`out/jnilibs_shasum_check.txt`・`out/apk_so_shasum_check.txt`、`out/round1_tables.md`(表の生成物、消してよい)、`out/dryrun_gate/`・`out/dryrun_take_file/`・`out/dryrun_take_mic/`(dry run の証拠、消してよい)、`out/ov_probe.json`・`out/ov_headers.txt`(消してよい)。

## lint
`bash ~/code/standup/tools/opus/round_close_lint.sh K/ROUND1.md` → `PASS round close: ROUND1.md`(02:41)
