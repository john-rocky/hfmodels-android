# probes/smsseed: synthetic texts in the phone's SMS store

A development helper for recordings of the inbox screen of `samples/decide` (`InboxActivity`), not a sample and not part of the SDK. It needs the SMS role (the phone's default SMS app) for the seconds of a seed or a clear, so it is not for everyday use: the screen's Paste and its device check (`InboxDeviceCheck`) need neither the role nor this app. The screen reads the phone's real SMS store; this app fills it with invented texts so a recording never shows a real message. The texts come from `SmsGenerator` (`samples/decide/src/sms`, compiled into this app too): English texts of the kinds a phone receives (receipts and promotions, one-time codes, deliveries, bills, appointments, people asking something, and a few that ask for money, a code or a login), invented senders only (`samples/decide/NAMES.txt`, numbers in the 555-0100 to 555-0199 range kept for fiction, links under `.example`). The same seed gives the same texts.

Android lets only the default SMS app write to the SMS store (another app's insert is silently dropped), so the helper declares the four components the SMS role asks for and takes the role for the seconds of a seed or a clear. Note the current holder first and give the role back to it afterwards:

```sh
./gradlew :probes:smsseed:assembleDebug
adb install -r probes/smsseed/build/outputs/apk/debug/smsseed-debug.apk
adb shell cmd role get-role-holders --user 0 android.app.role.SMS          # the app to give the role back to

# seed: take the role, insert 300 unread texts (seed 7), give the role back
adb shell cmd role add-role-holder --user 0 android.app.role.SMS io.github.johnrocky.hfmodels.probes.smsseed
adb shell am start -n io.github.johnrocky.hfmodels.probes.smsseed/.SeedActivity --ei count 300 --el seed 7
adb logcat -d -s smsseed                                                    # RESULT {"count":300,"first_id":…,"last_id":…,"elapsed_ms":…}
adb shell cmd role add-role-holder --user 0 android.app.role.SMS com.google.android.apps.messaging

# clear: take the role again, delete exactly the rows the seeds inserted, give the role back
adb shell cmd role add-role-holder --user 0 android.app.role.SMS io.github.johnrocky.hfmodels.probes.smsseed
adb shell am start -n io.github.johnrocky.hfmodels.probes.smsseed/.SeedActivity --ez clear true
adb shell cmd role add-role-holder --user 0 android.app.role.SMS com.google.android.apps.messaging
```

- `--ez scams false` leaves out the texts that ask for money, a code or a login (the generator's `scam` kind), for a screen that does not ask about them; the same seed then gives other texts.
- A seed keeps the `_id` of every row it inserts (SharedPreferences, written row by row); a clear deletes those rows and nothing else, and the provider drops the threads that become empty. Seeds add up until the next clear.
- Each run writes `seed-result.json` to `/sdcard/Android/data/io.github.johnrocky.hfmodels.probes.smsseed/files/` (count, seed, first and last id, elapsed ms, and `written_as`: how many texts the generator wrote as each kind, which is not a model answer) and logs the same `RESULT` line under tag `smsseed`. Without the role the run stops with an error in the same file instead of inserting nothing silently.
- The seeded texts are unread (`read=0`, `seen=0`), in the inbox (`type=1`), dated over the last 14 days, the first generated text the newest. The phone's own texts are not touched; the inbox screen shows unread texts only, so check that the phone's own are read before a recording: `adb shell "content query --uri content://sms/inbox --projection _id:read"`.
- While the helper holds the role, a real text that arrives is stored in the inbox as the default app would store it (not a seeded row, so a clear keeps it); an MMS notification is not downloaded (logged under `smsseed`). Keep the window short.
- The provider stores `555-0100` as `5550100` (dashes dropped); the generator therefore writes numbers as `+1NXX55501xx`, which it keeps as given, like alphanumeric sender ids.

Checked on 2026-09-27 on a Galaxy S26 (SM-S942Q, Android 16 BP4A.251205.006): the role change by `cmd role`, the insert visible to `content query`, the clear, and the role back to Google Messages.
