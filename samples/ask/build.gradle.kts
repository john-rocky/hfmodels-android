// samples/ask: your photo (the system photo picker) and your multiple-choice question, answered on the phone
// by a vision-language model with one letter; the photo goes in the first turn of a conversation, every further
// question about it is a text-only turn. Demo: one bar chart drawn by the app and five questions about it.
// The model file is never in the APK: the SDK downloads (or side-loads) it and checks its sha256.
plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.johnrocky.hfmodels.samples.ask"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.johnrocky.hfmodels.samples.ask"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += setOf("arm64-v8a") }
        // For the device check (src/androidTest/.../AskDeviceCheck.kt).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // AGP 9 creates JVM unit tests for the tested build type only. That is debug, the build the device check
    // instruments (R8 leaves a release build without the classes a test calls); the JVM tests touch no
    // Android code, so the build type does not change what they check.
    buildTypes {
        release {
            // R8 on: the SDK's consumer rules carry every keep the runtime needs.
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // The JVM tests print what they compared (e.g. the differing pixels per chart).
    testOptions {
        unitTests.all { it.testLogging { events("passed", "failed"); showStandardStreams = true } }
    }
}

dependencies {
    // In an app outside this repo:
    //   implementation("io.github.john-rocky.hfmodels:hfmodels-litertlm:0.2.0")
    // (hfmodels-core, litertlm-android and kotlinx-coroutines 1.11.0 come with it).
    implementation(project(":litertlm"))
    implementation("androidx.activity:activity:1.10.1")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
}
