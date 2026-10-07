worker: hfmodels-android-a1 [c13646]

# ROUND 4 — lane close(EmbeddingGemma 2 740M「話す → 写真が出る」Android デモ)

launch: `~/code/standup/launch/2026-10-07-G-eg2-740m-demo-opus-r4-lane-close.md`(監督 = Fable `hfmodels-android-b4 [32505d]`)。
Memory の節の path = `/Users/majimadaisuke/.claude/projects/-Users-majimadaisuke-code-hfmodels-android/memory/`(launch の path と同じ、確認済み)。

## 経過
- 06:15 launch を全文読んだ。ListAgents の自分の名前 = hfmodels-android-a1 [c13646]。次 = skill opus-rounds の lane close 節と ROUND1〜3b・handoff を読む。
- 06:16〜06:26 skill opus-rounds の lane close 節、ROUND1・2・3・3b、handoff、lane memory 20 本(MEMORY.md、launch が名指した 10 本、重なりそうな 9 本。残り 9 本は MEMORY.md の行だけ)、litertlm-convert の memory 11 本と coreai の `feedback_no_attribution_media`(読むだけ)を読んだ。候補の一次資料を確かめた: AAR manifest 4 本(gradle cache)、`guard_quiet_window.py`、`npu_evidence.txt`・`npu2_evidence.txt`、`embedding_engine_impl.cc` の写真と音声の経路、take の log の keyevent 0 と Dozing → Awake、kev の take.sh の SLEEP / WAKEUP。
- 06:27 handoff に `## §知見` を書いた(§2 補 と §4 の間、訂正 19・罠 35・op 12 行、awk で数えた)。次 = memory。
- 06:37 監督に 1 通(停止条件: `reference_s26_demo_recording` に訂正を入れると 2,187 B)。監督の答え(06:37〜06:39 の間に届いた、時刻は未記録)= 案 A(2 本に分ける、key event の句の形の指定つき)。
- 06:39 memory 12 本を置いた(新規 6・書き直し 6、各 2,000 B 以下)。MEMORY.md は 34 行 8,944 B、索引と file は 1 対 1、`[[name]]` は全部解決(wc -c と script で確認)。
- 06:41 K の中間物(ROUND1〜3b が「消してよい」と印した物)を `scripts/r4_clean_k.zsh --delete` で消した: 93 path、644,004 kB、残り 0(`out/r4_clean_k.log`)。K は 1,006,140 kB → 362,140 kB(du -sk)。次 = 端末の片づけの script と dry run。
- 06:42〜06:45 端末の片づけの script `scripts/run_device_r4_cleanup.zsh` と dry run(`scripts/dryrun_r4.zsh` + `scripts/dryrun_adb_shim_r4.zsh`、偽の端末の木)を書いて通した: 正常 exit 0(偽の file 99 → 0、uninstall、hold 解放、queue 空、sleeper 消滅、adb 27 call)、知らない名前 exit 5(100 → 100、rm なし)、端末なし exit 4、watchdog(DEADLINE_S=2)exit 12、順番待ち切れ(他 pid の hold)exit 3(adb 0 call)。関数の外の裸の adb は 0(`grep -n -w adb`)。
- 06:46 `LANE_CLOSE.md` を書いた。次 = 監督に「Mac 側 PASS、端末待ち」、go を待つ。
- 06:47 監督の go(06:47 の状態: hold なし、queue 空、S26 は adb に見える。`~/.cache/eg2demo/` は触らない = 監督が user に聞く)。06:47:43 `scripts/run_device_r4_cleanup.zsh` を background で 1 本(`out/device_r4/run.log`)。
- 06:47:43 hold 取得(`queue_cli.py wait`、script 名 eg2demo-cleanup-r4、keeper = sleeper pid 98219)。端末の状態(`cleanup.log`): `uptime 470952.08 Thermal Status: 0   mWakefulness=Dozing /dev/block/dm-88 221G 211G   10G  96% /data/user/0`。uptime は r3b の 05:47:35 の 467343.14 s から 3608.94 s = 壁時計の差 3609 s = 再起動なし。`package before: package:com.mlboydaisuke.eg2demo`。
- 06:47:44 ls 前(`out/device_r4/ls_before.txt`、`cleanup.log` の行のまま): `BEFORE files/: 5 top-level files (embeddinggemma-2-740m.litertlm embeddinggemma-2-740m_Qualcomm_SM8850.litertlm index_embeddinggemma-2-740m.litertlm_cpu.json index_embeddinggemma-2-740m.litertlm_gpu.json index_embeddinggemma-2-740m_Qualcomm_SM8850.litertlm_npu.json), album 36 of 36, queries 44 of 44, mic 4, Documents 18, take mp4: none, unknown 0`。
- 06:47:45 名前で消した後の ls(`out/device_r4/ls_after.txt`): `AFTER deleting by name: files/ holds 0 names; take mp4: 0 left`(files/ は `.` と `..` だけ、4 つの dir は rmdir で消えた)。app が作った file(owner u0_a699、group ext_data_rw、0660)も shell の `rm -f` で消えた。
- 06:47:45 `uninstall: Success`。`AFTER uninstall: package line '', app dir gone`(`out/device_r4/ls_after_uninstall.txt`: `ls: /sdcard/Android/data/com.mlboydaisuke.eg2demo: No such file or directory`)。
- 06:47:45 release(`released (sleeper 98219 killed), held 2 s`)、`holder: none`、rc 0。06:48 に cat で読み直し: hold file なし、`.queue` は `[]`、sleeper 98219 は消えている。edge_enable・画面・他の app には触っていない。次 = handoff §知見 に r4 の 1 行、LANE_CLOSE.md の端末の節、round close。
- 06:48 handoff §知見 の罠に r4 の 2 行(片づけは名前で消し切れる、heredoc の本文が hook に止められた)を足した = 訂正 19・罠 37・op 12、計 68 行(awk)。「Mac 側 PASS」の 1 通の「66 行」は足す前の数。LANE_CLOSE.md の端末の節を書いた。次 = round close。
- 06:49 round close の 5 節を heredoc で足そうとして、また計測窓の hook に止められた(本文の「python の heredoc」と `gate.sh` が同じ区切り)→ Edit tool で足した。

## 訂正
- launch の候補 (1)「`loadRemoteSymbols failed 4000` / `Failed to create transport`」→ 実際の行は `Transport layer setup failed: 14001` で、`Failed to create transport` という文は無い。memory と §知見 は実際の文で書いた。証拠 `K/out/device/npu_evidence.txt`。
- launch の候補 (3)「hook は Bash の command 行の `python3` を止める」→ 止めるのは同じ区切りに python と keyword が並ぶ時か、区切りが litert-lm・gradle・java などで始まる時(heredoc の本文も数える)。keyword の無い python は通る。証拠 `~/code/standup/tools/hooks/guard_quiet_window.py` の `PY`・`KEYS`・`PROGS`・`heavy_segment`。
- launch の折り込み先のうち `reference_s26_demo_recording` は最初から 2,194 B、`s26-shared-device-hold` は 2,115 B で、どちらも 2 KB を超えていた。前者は停止条件として監督に聞き、案 A(2 本に分ける)。書き直し後 1,814 B と 1,996 B。証拠 この file の経過 06:37・06:39、元の版 `/private/tmp/claude-501/-Users-majimadaisuke-code-hfmodels-android/b894d8b6-e3b9-4bdc-bae9-0d329be31e00/scratchpad/orig/`(session 限り)。
- 既存 memory `adb-run-script-traps` の生存の判定 `[ -d /proc/$P ]` は pid が空だと真(r2 の罠)→ pid が空でない事も見る形に直した。証拠 `K/ROUND2.md` の罠、`K/scripts/gate.sh`。
- 既存 memory `reference_s26_demo_recording` の「`KEYCODE_WAKEUP` first」は r3・r3b の take 4 本(key event 0 で Dozing → Awake)と食い違う → 監督の指定の句に置き換えた。証拠 `K/out/takes_r3b/take_t1.log`、`K/scripts/take.sh` の head comment。
- 既存 memory `s26-shared-device-hold` の「release, tell the next in line」は skill opus-rounds の「連絡と待ち」(取得・返却の知らせは送らない)と食い違う → 「the queue tells the next, no message」に直した。証拠 `~/.claude/skills/opus-rounds/SKILL.md` の「連絡と待ち」節。

## 罠と袋小路
- memory の下書きと round close を heredoc で書こうとして、Mac の計測窓(`et-r7b-mac timing pid 90369`)の hook に 2 回止められた。1 回目は本文の `…; LiteRT-LM 0.18.0 pins …` の `;` の後が litert-lm の CLI と読まれ、2 回目は本文の python の語と `gate.sh` が同じ区切りに並んだ → file の編集は Edit tool で(各 1 分)。memory `quiet-hook-traps` に入れた。
- 書き直した memory を 2,000 B に収めるのに 1 本 2〜4 回の手直しが要った(`s26-shared-device-hold` は 2,155 → 1,996 B、`adb-run-script-traps` は 2,244 → 1,996 B)。「2 KB」は 2,048 でなく 2,000 として扱った(既存の 2,000 B ちょうどの file に合わせた)。事実は落とさず言い回しを詰め、使われていない metadata の 3 行を消した。
- 失敗の道の dry run 4 本の出力に偽の file の名前が並び、tool の出力が 31 KB で切られた → 判定の行は grep で読んだ(全文は `out/dryrun_r4_*/run.log`)。
- 経過の行に「罠 37」と手で書いた(その時点の実数は 35)→ awk で数え直して直した。数は command の出力から写す。

## op と delegate の観察
- なし(この round は model を走らせていない)。r1〜r3b の観察は handoff §知見 の「op と delegate の観察」12 行に統合した。

## 使った環境
- Mac: Apple M4 Max、macOS 27.0、zsh 5.9。script は zsh、queue_cli.py・hold_cli.py は script の中の python3(pyenv の shim、Python 3.14.6)。K の venv は使っていない。adb = `~/Library/Android/sdk/platform-tools/adb`(1.0.41、36.0.0-13206524。版は release の後に `adb version` で読んだ = 端末に触らない)。
- device: Galaxy S26 SM-S942Q(RFGL80R6A6H)、Android 16、uptime 470952.08 s(r3b から再起動なし)、Thermal Status 0、Dozing。
- hold: あり。`scripts/run_device_r4_cleanup.zsh` の 1 process(script 名 eg2demo-cleanup-r4、keeper = sleeper pid 98219)、06:47:43 取得(queue_cli wait)→ 06:47:45 release(2 s)。edge_enable・画面・airplane には触っていない。

## 残した file
- `ROUND4.md`(この file)、`LANE_CLOSE.md`(lane close の diff の一覧)。
- script(新規): `scripts/r4_clean_k.zsh`(K の中間物の一覧と削除)、`scripts/run_device_r4_cleanup.zsh`(端末の片づけ)、`scripts/dryrun_r4.zsh`・`scripts/dryrun_adb_shim_r4.zsh`(偽の端末の木の dry run)。
- `out/r4_clean_k.log`(消した 93 path と大きさ)、`out/device_r4/`(`run.log`・`cleanup.log`・`ls_before.txt`・`ls_after.txt`・`ls_after_uninstall.txt`。`hold_sleeper.pid`・`released` は消してよい)。
- 消してよい: `out/dryrun_r4_ok/`・`out/dryrun_r4_unknown/`・`out/dryrun_r4_gone/`・`out/dryrun_r4_watchdog/`・`out/dryrun_r4_held/`(dry run の証拠)。
- K の外: handoff `~/code/standup/handoffs/2026-10-07-eg2-740m-demo.md` の `## §知見`(68 行)、lane memory 12 本と MEMORY.md(一覧は `LANE_CLOSE.md`)。
- 端末に残した物: なし(file は名前で消し、app は uninstall した)。

## lint
`bash ~/code/standup/tools/opus/round_close_lint.sh K/ROUND4.md` → `PASS round close: ROUND4.md`(06:50:13)
