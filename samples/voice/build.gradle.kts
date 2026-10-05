// samples/voice: one screen with one microphone button. Speak; the phone hears it (Zipformer), acts on it with its
// tools (Gemma 4 E2B: the alarm lands in the Clock app) and answers aloud (Kitten), with the milliseconds from the end
// of the utterance to the first sound on the screen. Model files are never in the APK (README.md).
plugins {
    id("com.android.application")
    // AGP 9.3.1 builds Kotlin itself with Kotlin 2.2.10; the Compose compiler plugin must be the same version.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
}

android {
    namespace = "io.github.johnrocky.hfmodels.samples.voice"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.johnrocky.hfmodels.samples.voice"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += setOf("arm64-v8a") }
        // For the drop-in device check (src/androidTest/.../VoiceDeviceCheck.kt).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
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
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    // In an app outside this repo:
    //   implementation("io.github.john-rocky.hfmodels:hfmodels-voice:0.2.0")   // hfmodels-litert and hfmodels-litertlm come with it
    // and gradle.properties: android.uniquePackageNames=false (litert 2.2.0 / litert-api 2.2.0 share a namespace on AGP 9).
    implementation(project(":voice"))
    implementation(platform("androidx.compose:compose-bom:2025.12.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.12.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
}
