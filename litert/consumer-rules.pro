# litert 2.2.0's own proguard.txt keeps only @UsedByReflection; its JNI (liblitert_jni.so) looks up the
# Kotlin API classes by name. Keep the runtime package un-renamed and un-stripped, as hfmodels-litertlm
# does for LiteRT-LM.
-keep class com.google.ai.edge.litert.** { *; }
-keepclasseswithmembernames class com.google.ai.edge.litert.** { native <methods>; }
# litert-api 2.2.0 -> com.google.android.play:ai-delivery -> WorkManager + Room. Under R8 the process dies in
# androidx.startup ("Failed to create an instance of class androidx.work.impl.WorkDatabase") because the
# bundled Room rule keeps the class but not its no-arg constructor (measured 2026-09-07, Pixel 8a, in
# probes/coexist). Kept here so an app with minifyEnabled=true starts.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
