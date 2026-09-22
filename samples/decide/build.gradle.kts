// samples/decide: three screens on TypedDecisions (voice gate, clipboard, query x passage) with the
// measured milliseconds on screen. Model files are never in the APK: the decision model is loaded
// through the SDK (side-loaded during development, see README.md), the extraction model's files are
// fetched by GlinerAssets with a sha256 check.
plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.johnrocky.hfmodels.samples.decide"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.johnrocky.hfmodels.samples.decide"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += setOf("arm64-v8a") }
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
}

dependencies {
    // In an app outside this repo:
    //   implementation("io.github.john-rocky.hfmodels:hfmodels-litert:0.1.2")   // hfmodels-core, litert 2.2.0 come with it
    // and gradle.properties: android.uniquePackageNames=false (litert 2.2.0 / litert-api 2.2.0 share a namespace on AGP 9).
    implementation(project(":litert"))
    implementation("androidx.activity:activity:1.10.1")
}
