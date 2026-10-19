// samples/translate: an English-Spanish conversation through one phone. "Habla español": Spanish speech becomes English
// text and an English voice. "Speak English": English speech becomes Spanish text in large type. Parakeet-TDT-0.6B-v3
// hears both languages (LiteRT Interpreter, CPU), Hy-MT2-1.8B translates (Tasks.Chat on LiteRT-LM), Kitten speaks
// English (Speak), with the seconds from the end of speech to the English voice on the screen. Model files are never in
// the APK (README.md).
plugins {
    id("com.android.application")
    // AGP 9.3.1 builds Kotlin itself with Kotlin 2.2.10; the Compose compiler plugin must be the same version.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
}

val litertVersion: String = providers.gradleProperty("litertVersion").get()
val litertlmVersion: String = providers.gradleProperty("litertlmVersion").get()

android {
    namespace = "io.github.johnrocky.hfmodels.samples.translate"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.johnrocky.hfmodels.samples.translate"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += setOf("arm64-v8a") }
        // The parakeet handler's runtime version (its descriptor's runtime_range is checked against it) and the screen's
        // line with the runtimes.
        buildConfigField("String", "LITERT_VERSION", "\"$litertVersion\"")
        buildConfigField("String", "LITERTLM_VERSION", "\"$litertlmVersion\"")
        // For the device check (src/androidTest/.../TranslateDeviceCheck.kt).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    buildTypes {
        release {
            // R8 on: the SDK modules' consumer rules carry the keeps the runtimes need (hfmodels-litert's keep the Interpreter
            // API that the parakeet transcriber runs on).
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
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            // The parity tests against the Mac's Python host read its dumps and the model's tokenizer.json from a local
            // copy (README.md, "JVM tests"); without them those tests are skipped and say so.
            it.systemProperty("translate.fixturesRoot", System.getProperty("translate.fixturesRoot") ?: (System.getenv("TRANSLATE_FIXTURES_ROOT") ?: ""))
            it.systemProperty("translate.parakeetRoot", System.getProperty("translate.parakeetRoot") ?: (System.getenv("TRANSLATE_PARAKEET_ROOT") ?: ""))
            it.maxHeapSize = "2g"
        }
    }
}

dependencies {
    // In an app outside this repo:
    //   implementation("io.github.john-rocky.hfmodels:hfmodels-voice:0.2.0")   // hfmodels-litert (LiteRT) and hfmodels-litertlm come with it
    // and gradle.properties: android.uniquePackageNames=false (litert 2.2.0 / litert-api 2.2.0 share a namespace on AGP 9).
    implementation(project(":voice"))
    implementation(platform("androidx.compose:compose-bom:2025.12.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.12.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    testImplementation(libs.junit)
    // The tokenizer.json parser runs on the JVM in the tests: the real org.json, not Android's stubs.
    testImplementation("org.json:json:20250517")
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
}
