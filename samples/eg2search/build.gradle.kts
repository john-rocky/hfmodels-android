// samples/eg2search: hold the button, say what is in a photo, and the matching photo of your own album comes up first.
// EmbeddingGemma 2 740M embeds the spoken clip and every photo into one 768-d space on the phone and ranks the album by
// cosine; nothing is transcribed. Photos come from the system photo picker (Add photos), copied into the app's files.
// LiteRT-LM directly, not the SDK: hfmodels 0.2.0 has no embedding task, and EmbeddingEngine came with LiteRT-LM 0.18.0.
// The model file is never in the APK: it is pushed with adb (README.md).
plugins {
    id("com.android.application")
}

// The runtime with EmbeddingEngine and EmbeddingGemma 2 (released 2026-10-06), named here and not in gradle.properties:
// the repo's litertlmVersion (0.16.1) is the SDK modules' pin and moves only with a re-gate of those modules.
val litertlmVersion = "0.18.0"

android {
    namespace = "io.github.johnrocky.hfmodels.samples.eg2search"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.johnrocky.hfmodels.samples.eg2search"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += setOf("arm64-v8a") }
        // Shown on screen as the runtime version.
        buildConfigField("String", "LITERTLM_VERSION", "\"$litertlmVersion\"")
        // For the device check (src/androidTest/.../Eg2DeviceCheck.kt).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // litertlm-android 0.18.0 (and the kotlin-reflect 2.4.0 it brings) carry Kotlin 2.4 metadata, which the Kotlin built
    // into AGP 9.3.1, this build's compiler for every module, refuses ("The binary version of its metadata is 2.4.0,
    // expected version is 2.2.0", 2026-10-09). This module alone skips the metadata version check; the SDK modules keep
    // their compiler and their 0.16.1 pin. An app built with Kotlin 2.4 or newer does not need the flag.
    kotlin { compilerOptions { freeCompilerArgs.add("-Xskip-metadata-version-check") } }
    testOptions {
        unitTests.all { it.testLogging { events("passed", "failed"); showStandardStreams = true } }
    }
    // An NPU backend loads Qualcomm's libraries by path from the app's native library dir (Backend.NPU(nativeLibraryDir)),
    // so they are extracted at install. They are never in this repository (src/main/jniLibs; README.md, NPU).
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    // Brings gson, kotlin-reflect 2.4.0 and kotlinx-coroutines-android 1.11.0 (its POM).
    implementation("com.google.ai.edge.litertlm:litertlm-android:$litertlmVersion")
    // ComponentActivity, the photo picker (PickMultipleVisualMedia) and the permission request.
    implementation("androidx.activity:activity:1.10.1")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
}
