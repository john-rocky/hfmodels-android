# Getting started

**First model: [LFM2.5-1.2B-Instruct](https://huggingface.co/litert-community/LFM2.5-1.2B-Instruct).**
This walkthrough was checked with the published **0.1.1** SDK ("Verification" below); the
Gradle line names the current release, **0.3.0**. Start on a physical **Pixel 8a
(8 GB RAM), Android 16 / API 36, build CP1A.260505.005**, the configuration verified below
for 0.1.1; with LiteRT-LM 0.19.1 the same steps ran on a Pixel 11 Pro.
The SDK's installation floor is Android 12 / API 31, `arm64-v8a`; other devices and OS
versions are outside this walkthrough's device verification.

Install Android SDK platform 36 and use JDK 17, AGP 9.3.1 and Gradle 9.7.0 (the existing
[sample](../samples/chat) uses these versions). The published AAR requires **compileSdk 36**
as well as **minSdk 31**. Have Wi-Fi for the first download: **736,220,768 bytes**.
The download preflight requires the file size plus **256 MiB** of free storage; leave
additional space for the runtime's compile cache. The 8 GB RAM device is a tested target,
not a measured minimum: peak memory and the minimum free RAM/storage for this model have
not been measured by this check.

```kotlin
// settings.gradle.kts: repositories google() and mavenCentral()
// app/build.gradle.kts
android {
    compileSdk = 36
    defaultConfig {
        minSdk = 31
        ndk { abiFilters += "arm64-v8a" }
    }
}
dependencies { implementation("io.github.john-rocky.hfmodels:hfmodels-litertlm:0.3.0") }
```

That one line brings `hfmodels-core`, `litertlm-android` and `kotlinx-coroutines-android 1.11.0` (the version the runtime's bytecode needs). Toolchain this was built with: AGP 9.3.1, Gradle 9.7.0, compileSdk 36, JDK 17, Kotlin 2.3.21 on the root buildscript's classpath (`classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")`). An app that applies `org.jetbrains.kotlin.plugin.compose` gives it the same version. An app needs Kotlin 2.3.0 or newer: `litertlm-android` carries Kotlin 2.4 metadata, and AGP 9.3.1's built-in Kotlin, 2.2.10, refuses it. Do not apply the standalone `org.jetbrains.kotlin.android` plugin. The SDK's manifest declares the GPU's `uses-native-library` entries and `INTERNET` (the first download only); its consumer R8 rules keep what the runtime's JNI looks up by name — with `minifyEnabled true` and nothing else, generation works.

Check the resolved versions in an existing app before running it:

```sh
./gradlew :app:dependencies --configuration debugRuntimeClasspath
```

This walkthrough was checked with `hfmodels-core` **0.1.1**, `hfmodels-litertlm` **0.1.1**,
`litertlm-android` **0.16.1** and `kotlinx-coroutines-android` / `core-jvm` **1.11.0**.
Version 0.3.0 brings `litertlm-android` **0.19.1**, the same coroutines version, and Kotlin stdlib / reflect **2.4.0**.
If the report selects other versions than your release brings, reconcile the app's existing pins first.
These versions come from the [published SDK POM](https://repo1.maven.org/maven2/io/github/john-rocky/hfmodels/hfmodels-litertlm/0.1.1/hfmodels-litertlm-0.1.1.pom)
and AAR, checked on 2026-09-09. The sample's Maven dependency graph also resolves
Gson **2.13.2**, Kotlin reflect / stdlib **2.2.21**, and AndroidX Activity **1.10.1**.
The repository's development `SDK_VERSION` may be a
SNAPSHOT; installing a published version does not install development code or a newer catalog.

### First reply, with the model and backend pinned

Call this suspend function from your app's coroutine. It downloads and verifies the file,
initializes, appends the streamed text to Logcat, and releases the model even on failure
or cancellation. The existing [chat screen](../samples/chat/src/main/kotlin/io/github/johnrocky/hfmodels/samples/chat/MainActivity.kt)
shows the UI and lifecycle wiring.

```kotlin
import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import io.github.johnrocky.hfmodels.BackendPolicy
import io.github.johnrocky.hfmodels.HfModels
import io.github.johnrocky.hfmodels.LoadOptions
import io.github.johnrocky.hfmodels.ModelException
import io.github.johnrocky.hfmodels.ModelRef
import io.github.johnrocky.hfmodels.Tasks
import io.github.johnrocky.hfmodels.litertlm.text
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

suspend fun firstReply(context: Context) {
    val models = HfModels(context.applicationContext)
    try {
        val chat = models.fromPretrained(
            ModelRef(
                "litert-community/LFM2.5-1.2B-Instruct",
                revision = "8dde2fe485d2127f2a3861c3184cc882ba3764eb",
                variant = "int4_gpu",
            ),
            Tasks.Chat,
            LoadOptions(backendPolicy = BackendPolicy.RequireProfile("gpu")),
        ) { event -> Log.i("first-reply", event.toString()) }
        val session = chat.createConversation(
            ConversationConfig(systemInstruction = Contents.of("You are a helpful assistant.")),
        )
        val answer = StringBuilder()
        session.stream(Contents.of("What is 17 + 25? Answer briefly.")).collect { message ->
            answer.append(message.text)
            Log.i("first-reply", answer.toString())
        }
    } catch (e: ModelException) {
        Log.e("first-reply", "${e.code}: ${e.reason}")
        throw e
    } finally {
        withContext(NonCancellable) { models.closeAndJoin() }
        Log.i("first-reply", "Released")
    }
}
```

The pinned repo's [`hfmodels.json`](https://huggingface.co/litert-community/LFM2.5-1.2B-Instruct/blob/8dde2fe485d2127f2a3861c3184cc882ba3764eb/hfmodels.json)
selects `LFM2.5-1.2B-Instruct_int4_gpu.litertlm`, SHA-256
`8d3a4d73e121166ad377e9c51148d38ee383c223661f4219e01f22a0fe1d4a1a`.
The GPU language profile uses a 4,096-token context and the default 256-token output cap.
This pins the file and profile the bundled catalog pins; it does not follow later card
edits. A GPU failure is reported by
`RequireProfile("gpu")`; a CPU retry is a separate configuration.

In Android Studio Logcat, select your app and the `first-reply` / `hfmodels` tags.
Success is a `Ready` event, a nonempty streamed answer containing **42**, and **Released**.
The recorded answer was **`17 + 25 equals 42.`**; wording and chunk count may vary.
For Stop, cancel the coroutine collecting `stream`, then create a new conversation before
the next turn. The existing [ChatDeviceCheck](../samples/chat/src/androidTest/kotlin/io/github/johnrocky/hfmodels/check/ChatDeviceCheck.kt)
also checks Stop and release in the app's process; its invocation is in [the existing procedure](../skills/hfmodels-android/SKILL.md#step-3-verify-in-this-order).
That general-purpose check accepts a model id and resolves it at run time; use the pinned
`ModelRef` above in it when comparing against this walkthrough.

**Verification:** the [2026-09-08 raw device log](https://github.com/john-rocky/hfmodels-android/blob/6410dfc48c53d13364ab0b911f3d790e6ef8585b/litertlm/results/2026-09-08-4C131JEKB15210-0.16.1-device-check.log)
records the HF download, SHA-256 check, `Ready` on GPU, the answer, Stop (`INVALID`) and
release, all passing on the Pixel 8a above. It used release code `046da93` via the sample's
project dependency; the published 0.1.1 sources and runtime/build settings match that code.
The later release catalog adds verification records. These documentation changes reuse
that run; they do not constitute another device run or an independent user's success.
That run was at revision `f45d8d8`. On 2026-11-11 `ChatDeviceCheck` loaded this model at revision `8dde2fe`,
the one pinned above, with LiteRT-LM 0.19.1 on a Pixel 11 Pro, and passed the same steps.
The function above was compiled verbatim in the existing sample against Maven Central
0.1.1 on 2026-09-09; that compilation did not run the app or repeat native inference.
The runtime reports GPU initialization but no per-operation execution report. This check
does not establish offline restart, other phones, OS versions, long conversations or speed.

**If it fails:** start with [`docs/errors.md`](errors.md). Check storage for
`STORAGE_FULL`, Wi-Fi for `NETWORK_ERROR`, the resolved coroutines version for
`NoSuchMethodError`, and the merged GPU native-library entries / free RAM for initialization
failure. For help, [open an SDK issue](https://github.com/john-rocky/hfmodels-android/issues)
with device + Android build, the four dependency versions above, model revision/variant,
selected profile, and the first error or the `Ready` / answer / release lines. Remove tokens
and private prompts. The model card is the place for file/descriptor mismatches.
