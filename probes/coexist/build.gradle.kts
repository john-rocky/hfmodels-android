// A0 coexistence probe: litertlm-android + litert + litert-api in ONE release APK with R8, driven from
// the shell (probes/coexist/run.sh) on a named device. Not part of the SDK; kept so the record can be re-run.
//
//   export ANDROID_SERIAL=<serial>
//   probes/coexist/run.sh nokeep                     # R8 on, no keep rules
//   probes/coexist/run.sh keepwork                   # + Room keep for litert-api's transitive WorkManager
//   probes/coexist/run.sh keepwork-keepjni           # + litertlm/consumer-rules.pro
//   probes/coexist/run.sh keepwork-keepjni 0.17.0    # re-gate on another LiteRT-LM (adds -PskipKotlinMetadataCheck)
plugins {
    id("com.android.application")
}

val litertlmVersion: String = providers.gradleProperty("litertlmVersion").get()
val litertVersion: String = providers.gradleProperty("litertVersion").get()
val coroutinesVersion: String = providers.gradleProperty("coroutinesVersion").get()

android {
    namespace = "io.github.johnrocky.hfmodels.probe.coexist"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.johnrocky.hfmodels.probe.coexist"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "a0"
        ndk { abiFilters += setOf("arm64-v8a") }
        buildConfigField("String", "LITERTLM_VERSION", "\"$litertlmVersion\"")
        buildConfigField("String", "LITERT_VERSION", "\"$litertVersion\"")
        buildConfigField("boolean", "KEEP_JNI", providers.gradleProperty("keepJni").isPresent.toString())
        buildConfigField("boolean", "KEEP_WORK", providers.gradleProperty("keepWork").isPresent.toString())
    }
    buildFeatures { buildConfig = true }
    // Release (R8 on) signed with the debug key so it installs on the test phone.
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            val files = mutableListOf(getDefaultProguardFile("proguard-android-optimize.txt"), file("proguard-rules.pro"))
            // The SDK's own consumer rules, applied here only on request so the "without" run is measurable.
            if (providers.gradleProperty("keepJni").isPresent) files += rootProject.file("litertlm/consumer-rules.pro")
            // The Room/WorkManager keep that litert-api's transitive Play ai-delivery needs under R8.
            if (providers.gradleProperty("keepWork").isPresent) files += file("proguard-work.pro")
            proguardFiles(*files.toTypedArray())
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    if (providers.gradleProperty("skipKotlinMetadataCheck").isPresent) {
        kotlin { compilerOptions { freeCompilerArgs.add("-Xskip-metadata-version-check") } }
    }
}

dependencies {
    implementation("com.google.ai.edge.litertlm:litertlm-android:$litertlmVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")
    implementation("com.google.ai.edge.litert:litert:$litertVersion")
    // litert-api comes transitively from litert (its POM); declaring both changes nothing below.
}
