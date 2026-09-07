# litert-api 2.2.0 -> com.google.android.play:ai-delivery -> WorkManager + Room. Under R8 (full mode,
# proguard-android-optimize.txt) the process dies in androidx.startup before any test runs:
# "Failed to create an instance of class androidx.work.impl.WorkDatabase.canonicalName" =
# InstantiationException on WorkDatabase_Impl, whose no-arg constructor R8 removed because the
# bundled Room consumer rule keeps the class but not `<init>()`. Measured 2026-09-07 on a Pixel 8a.
# This is a LiteRT (litert-api) integration cost, not a LiteRT-LM one; the SDK's hfmodels-litertlm
# does not pull litert-api. Enabled in the probe with -PkeepWork=true.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
