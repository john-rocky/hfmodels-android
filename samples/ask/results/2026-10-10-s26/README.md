# samples/ask on the Galaxy S26, 2026-10-10

The records behind "2026-10-10: your photo" in [`samples/ask/README.md`](../../README.md). One Galaxy S26 (SM-S942Q, SM8850, Android 16, build BP4A.251205.006.S942QOPS1AZH9), litertlm-android 0.16.1, hfmodels 0.2.1-SNAPSHOT, the `int8` file pushed into the app's files directory and imported on the first load, GPU profile. The phone came from another run at thermal status 2; the check read 1 at its start and 2 at its end.

- `check.log`: the `RESULT` lines of `AskDeviceCheck` (`-e backend gpu -e network offline -e keep_photo true`), unchanged. One line per step and a last line `RESULT ok=true … failure_paths_ok=7`.
- `app.log`: the app's own lines (tags `ask` and `hfmodels`) while the screen was driven by hand right after the check: the photo picked in the system photo picker, "What is this a photo of?" with a car, a boat and a bicycle, then "What colour is the bicycle?" with blue, red and green.
- `demo_button.json`, `demo_recording.json`: the records the Demo wrote, unchanged (the fields are listed under "Demo" in the sample's README): once from the Demo button (chart_00) and once in recording mode (`--ez autostart true --ei delay 1 --ei chart 1 --es backend gpu --ei pictures 1`, chart_01).

The photo is the CC0 photo of a red bicycle by Bernard Spragg on Flickr, the 1023 x 728 copy `a01_red_bicycle.jpg` of [`probes/eg2demo/fixtures/album.json`](../../../../probes/eg2demo/fixtures/album.json) (sha256 `4147220d…`); it is not in the repository.
