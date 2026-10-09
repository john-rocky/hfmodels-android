// smsseed: a development helper that puts synthetic texts into the phone's real SMS store, so the
// inbox screen of samples/decide has something to sort. Not a sample and not part of the SDK. Android
// lets only the default SMS app write to the SMS provider, so this app qualifies for the SMS role and
// takes it for the few seconds of a seed or a clear (README.md has the commands).
plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.johnrocky.hfmodels.probes.smsseed"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.johnrocky.hfmodels.probes.smsseed"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "s1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // The generator lives with the inbox screen in samples/decide; an app module cannot depend on another
    // app module, so its source directory is compiled in here as well.
    sourceSets["main"].kotlin.srcDir("../../samples/decide/src/sms/kotlin")
}
