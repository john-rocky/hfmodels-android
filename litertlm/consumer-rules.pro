# litertlm-android ships no proguard.txt, and its JNI reaches Kotlin by name in more than one place
# (measured 2026-09-07, Pixel 8a, release + proguard-android-optimize.txt, litertlm-android 0.16.1 and 0.17.0):
#   - FindClass + NewObject on InputData$Text / $Image / $Audio, LiteRtLmJniException, BenchmarkInfo
#     (0.17.0 adds EmbeddingResponse): without a keep the first getBenchmarkInfo() aborts the process
#     with "JNI DETECTED ERROR IN APPLICATION: mid == null in call to NewObjectV" - a SIGABRT, not a
#     Java exception, so nothing in the app can catch it;
#   - GetMethodID("onMessage", "(Ljava/lang/String;)V") / "onDone" / "onError" / "onNext" on the
#     internal callback object handed to nativeSendMessageAsync: with only the classes above kept,
#     streaming aborts with 'NoSuchMethodError: no non-static method "Lr30;.onMessage(Ljava/lang/String;)V"'.
# The lookups are by original name, so the whole runtime package is kept un-renamed and un-stripped.
# Cost: the AAR's classes.jar only (the 21 MB of native code is unaffected).
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclasseswithmembernames class com.google.ai.edge.litertlm.** { native <methods>; }
