// samples/finder: one sentence sets five recipe filters. Five typed decisions about the sentence (GLiNER2.5-Decide through
// EncoderDecisions: meal, diet, time, an ingredient to leave out, spice) become filter chips, and a list of 60 made-up recipes
// narrows on the spot. The model is loaded by its id; its files are never in the APK: the SDK downloads them on the first
// search or imports a copy pushed with adb (README.md).
plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.johnrocky.hfmodels.samples.finder"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.johnrocky.hfmodels.samples.finder"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += setOf("arm64-v8a") }
        // For the device check (src/androidTest/.../FinderDeviceCheck.kt).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release {
            // R8 on: the SDK modules' consumer rules carry the keeps the runtimes need.
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.all { it.testLogging { events("passed", "failed"); showStandardStreams = true } }
    }
    // The NPU loads the Qualcomm runtime from the app's native library dir: extract it at install (src/main/jniLibs,
    // never committed: tools/fetch_npu_libs.sh, README.md "NPU"). Without those files the app loads on the GPU.
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    // In an app outside this repo:
    //   implementation("io.github.john-rocky.hfmodels:hfmodels-litert:0.1.2")   // hfmodels-core, litert 2.2.0 come with it
    // and gradle.properties: android.uniquePackageNames=false (litert 2.2.0 / litert-api 2.2.0 share a namespace on AGP 9).
    implementation(project(":litert"))
    implementation("androidx.activity:activity:1.10.1")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
}
