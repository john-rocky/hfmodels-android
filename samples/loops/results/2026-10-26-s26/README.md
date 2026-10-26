# samples/loops on the Galaxy S26, 2026-10-26

The records behind "2026-10-26: Galaxy S26" in [`samples/loops/README.md`](../../README.md). One Galaxy S26 (SM-S942Q, Android 16, build BP4A.251205.006.S942QOPS1AZH9), litertlm-android 0.16.1, the app built at commit b4ab57f (the eight examples, with GSM8K test question 75 where 2026-10-23's had 54). Both model files were pushed with adb into the app's external files directory; the SDK checked each copy's sha256 against the bundled catalog's entry (Hub commit `ff0f6e32`) and imported it at its first load. Each `*.json` is the run record the app wrote (fields under "Recording mode" in the sample's README), unchanged; each `*.log` holds the app's and the SDK's own lines of that run (tags `loops` and `hfmodels`), unchanged.

- `b1.json`, `b1.log`: every example on both files, thinking off, the 1 loop file first (`--ez autostart true --es examples all --es run both --es order 12 --ez think false`). The phone had just come out of a long benchmark: thermal status 0 when the run started, 2 at its end.
- `b2.json`, `b2.log`: the same with the 2 loops file first (`--es order 21`), after 240 s of rest: thermal status 1 when the run started, 2 at its end.

The phone's uptime ran on from 914,747 s to 915,841 s across both (no restart); its thermal status never reached 4.
