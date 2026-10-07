// EmbeddingGemma 2 740M on-device demo: say one sentence, the matching photo of the album comes up first. The spoken
// clip and the photos are embedded by the same model into one 768-d space and ranked by cosine; no transcription.
plugins {
    id("com.android.application")
}

val litertlmVersion: String = providers.gradleProperty("litertlmVersion").get()

android {
    namespace = "com.mlboydaisuke.eg2demo"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.mlboydaisuke.eg2demo"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += setOf("arm64-v8a") }
        buildConfigField("String", "LITERTLM_VERSION", "\"$litertlmVersion\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // The Qualcomm NPU libraries (src/main/jniLibs, not in git: QAIRT licence = inside an APK only) are dlopen'ed by
    // path from nativeLibraryDir (Backend.NPU(nativeLibraryDir)): legacy packaging has the installer extract them there.
    // keepDebugSymbols: the libraries go into the APK byte for byte (the strip step changed three of them by 8 bytes),
    // so the APK's copies match kev_work/npu/libs/SHA256SUMS.
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/*.so"
        }
    }
}

dependencies {
    // Brings gson, kotlin-reflect 2.4.0 and kotlinx-coroutines-android 1.11.0 (its POM).
    implementation("com.google.ai.edge.litertlm:litertlm-android:$litertlmVersion")
}
