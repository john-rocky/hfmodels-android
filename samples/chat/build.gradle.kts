// samples/chat: a chat screen on the SDK. Type a Hugging Face id, load, chat, stop, release.
plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.johnrocky.hfmodels.samples.chat"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.johnrocky.hfmodels.samples.chat"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += setOf("arm64-v8a") }
        // For the drop-in device check (src/androidTest/.../ChatDeviceCheck.kt); an app copies these two lines and the file.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
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
    // The same switch as litertlm/build.gradle.kts: "Ask a document" loads a file whose runtime floor is LiteRT-LM 0.19.0
    // (README.md), whose Kotlin 2.4 metadata the Kotlin built into AGP 9.3.1 refuses.
    if (providers.gradleProperty("skipKotlinMetadataCheck").isPresent) {
        kotlin { compilerOptions { freeCompilerArgs.add("-Xskip-metadata-version-check") } }
    }
}

dependencies {
    // In an app outside this repo these two lines become
    //   implementation("io.github.john-rocky.hfmodels:hfmodels-litertlm:0.2.0")
    // (hfmodels-core, litertlm-android and kotlinx-coroutines 1.11.0 come with it).
    implementation(project(":litertlm"))
    implementation("androidx.activity:activity:1.10.1")
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
}
