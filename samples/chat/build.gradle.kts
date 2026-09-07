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
}

dependencies {
    // In an app outside this repo these two lines become
    //   implementation("io.github.johnrocky.hfmodels:hfmodels-litertlm:<version>")
    // (hfmodels-core, litertlm-android and kotlinx-coroutines 1.11.0 come with it).
    implementation(project(":litertlm"))
    implementation("androidx.activity:activity:1.10.1")
}
