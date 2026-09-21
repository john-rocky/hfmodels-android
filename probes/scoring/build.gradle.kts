// Text-scoring probe: the LiteRT-LM Kotlin API from a source branch that adds Session.runTextScoring /
// saveCheckpoint / rewindToCheckpoint / rewindToStep / currentStep and SessionConfig.applyPromptTemplate
// (john-rocky/LiteRT-LM branch kotlin-text-scoring), with the JNI library built from that branch.
// Not part of the SDK; it measures "one prefill, N decisions" on a language-model bundle and compares
// the scores with a published oracle. The Kotlin sources under src/main/kotlin/com/google/ai/edge/litertlm
// are that branch's; the JNI library and the accelerator plugins come from -PscoringJniLibs=<dir with arm64-v8a/>.
//
//   export ANDROID_SERIAL=<serial>
//   ./gradlew :probes:scoring:connectedDebugAndroidTest -PscoringJniLibs=/path/to/jniLibs \
//     -Pandroid.testInstrumentationRunnerArguments.model=/data/local/tmp/hfmodels/Qwen3-0.6B.litertlm \
//     -Pandroid.testInstrumentationRunnerArguments.backend=gpu
plugins {
    id("com.android.application")
}

val coroutinesVersion: String = providers.gradleProperty("coroutinesVersion").get()
val jniLibsDir: String = providers.gradleProperty("scoringJniLibs").orNull ?: "jniLibs"

android {
    namespace = "io.github.johnrocky.hfmodels.probe.scoring"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.johnrocky.hfmodels.probe.scoring"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "p1"
        ndk { abiFilters += setOf("arm64-v8a") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets { named("main") { jniLibs.srcDir(jniLibsDir) } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // What the branch's Kotlin sources need (the same as the published AAR's POM, with the coroutines floor).
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.2.21")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
}
