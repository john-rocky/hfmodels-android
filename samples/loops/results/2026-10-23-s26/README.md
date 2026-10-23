# samples/loops on the Galaxy S26, 2026-10-23

The records behind "2026-10-23: Galaxy S26" in [`samples/loops/README.md`](../../README.md). One Galaxy S26 (SM-S942Q, Android 16, build BP4A.251205.006.S942QOPS1AZH9), litertlm-android 0.16.1, the app and its check built at commit edc8fbf. Both model files were pushed with adb into the app's external files directory; the SDK checked each copy's sha256 against the bundled catalog's entry (Hub commit `ff0f6e32`) and imported it at its first load. Each `*.json` is the run record the app wrote (fields under "Recording mode" in the sample's README), unchanged; each `*.log` holds the app's and the SDK's own lines of that run (tags `loops` and `hfmodels`), unchanged.

- `b1.json`, `b1.log`: every example on both files, thinking off, the 1 loop file first (`--ez autostart true --es examples all --es run both --es order 12 --ez think false`), started at thermal status 0.
- `b2.json`, `b2.log`: the same with the 2 loops file first (`--es order 21`), started after the phone had cooled back to thermal status 0 (130 s).
- `a.json`, `a.log`: your own question through the screen, right after `b2` (thermal status 3): the app started with `--ez record true`, "A book has 380 pages. If I read 25 pages a day, how many days do I need to finish it?" typed into the box with `adb shell input text`, Both, Run.
- `d1.json`, `d1.log`: with only the 1 loop file on the phone and `--es network offline`: the 1 loop column answers, the 2 loops column says the file is not on the phone.
- `d2.json`, `d2.log`: the 2 loops file downloading from the Hub (`--es run 2 --es network any --ei stop_after_mb 100`), stopped by the app's own Stop at 100 MB.
- `d3.json`, `d3.log`: the next run (`--ei stop_after_mb 160`): the SDK continues from the part `d2` kept (`resume from 105257839`) and the app stops it at 160 MB.
- `check.log`: `LoopsDeviceCheck`'s `RESULT` lines and the SDK's lines, unchanged, after the 2 loops file was pushed; the last line is `RESULT ok=true failed=[]`.

The phone's uptime ran on from 673,805 s to 675,084 s across all of these (no restart); its thermal status reached 3 at the end of `b2` and never 4.
