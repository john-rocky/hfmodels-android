// samples/eg2search: hold the button, say what is in a photo, and the matching photo of your own album comes up first.
// EmbeddingGemma 2 740M embeds the spoken clip and every photo into one 768-d space on the phone and ranks the album by
// cosine; nothing is transcribed. Photos come from the system photo picker (Add photos), copied into the app's files.
// LiteRT-LM directly, not the SDK: hfmodels has no embedding task.
// The model file is never in the APK: it is pushed with adb (README.md).
plugins {
    id("com.android.application")
}

// The SDK modules' pin (litertlmVersion in gradle.properties); it has EmbeddingEngine.
val litertlmVersion: String = providers.gradleProperty("litertlmVersion").get()

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
