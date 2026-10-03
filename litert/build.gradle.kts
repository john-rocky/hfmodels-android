// hfmodels-litert: typed decisions on classic LiteRT (CompiledModel) decision encoders, on top of hfmodels-core.
// Separate from hfmodels-litertlm on purpose: the litert AAR adds about 9 MB of native code, merges
// FOREGROUND_SERVICE permissions into the app and needs android.uniquePackageNames=false on AGP 9;
// a chat-only app should not pay for that.
plugins {
    id("com.android.library")
    id("com.vanniktech.maven.publish") version "0.33.0"
}

val litertVersion: String = providers.gradleProperty("litertVersion").get()
val coroutinesVersion: String = providers.gradleProperty("coroutinesVersion").get()

android {
    namespace = "io.github.johnrocky.hfmodels.litert"
    compileSdk = 36
    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The exact runtime this handler was compiled against; compared with the descriptor's runtime_range.
        buildConfigField("String", "LITERT_VERSION", "\"$litertVersion\"")
    }
    buildFeatures { buildConfig = true }
    // The device tests side-load descriptors as test-APK assets: the development one (catalog/dev) and the bundled entries.
    sourceSets { named("androidTest") { assets.srcDir(rootProject.file("catalog/dev")); assets.srcDir(rootProject.file("catalog/entries")) } }
    // Their npu runs load the Qualcomm runtime from the test APK's native library dir: extracted, not mapped from the APK
    // (src/androidTest/jniLibs, never committed: tools/fetch_npu_libs.sh). Applies to the test APK only, not to the AAR.
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            // Parity tests need the publisher's tokenizer files and fixtures; point at a local copy.
            it.systemProperty("hfmodels.layaRoot", System.getProperty("hfmodels.layaRoot") ?: (System.getenv("HFMODELS_LAYA_ROOT") ?: ""))
            it.systemProperty("hfmodels.julia1Root", System.getProperty("hfmodels.julia1Root") ?: (System.getenv("HFMODELS_JULIA1_ROOT") ?: ""))
            it.systemProperty("hfmodels.glinerDecideRoot", System.getProperty("hfmodels.glinerDecideRoot") ?: (System.getenv("HFMODELS_GLINER_DECIDE_ROOT") ?: ""))
            it.systemProperty("hfmodels.gliclassRoot", System.getProperty("hfmodels.gliclassRoot") ?: (System.getenv("HFMODELS_GLICLASS_ROOT") ?: ""))
            it.systemProperty("hfmodels.debertaDecisionRoot", System.getProperty("hfmodels.debertaDecisionRoot") ?: (System.getenv("HFMODELS_DEBERTA_DECISION_ROOT") ?: ""))
            it.maxHeapSize = "3g"
        }
    }
}

dependencies {
    api(project(":core"))
    // `api`: an app may want Accelerator / Environment for its own CompiledModel next to the SDK's.
    api("com.google.ai.edge.litert:litert:$litertVersion")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
    testImplementation("org.json:json:20250517")
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates("io.github.john-rocky.hfmodels", "hfmodels-litert", project.findProperty("hfmodelsVersion") as String? ?: "0.1.2")
    pom {
        name.set("hfmodels-litert")
        description.set("hfmodels adapter for Google's LiteRT runtime: typed decisions (choice / score / noul with calibrated probabilities) on a decision encoder, one forward per question")
        url.set("https://github.com/john-rocky/hfmodels-android")
        licenses { license { name.set("Apache-2.0"); url.set("https://www.apache.org/licenses/LICENSE-2.0.txt") } }
        developers { developer { id.set("john-rocky"); name.set("Daisuke Majima"); url.set("https://github.com/john-rocky") } }
        scm {
            url.set("https://github.com/john-rocky/hfmodels-android")
            connection.set("scm:git:https://github.com/john-rocky/hfmodels-android.git")
            developerConnection.set("scm:git:git@github.com:john-rocky/hfmodels-android.git")
        }
    }
}

publishing {
    repositories { maven { name = "local"; url = uri(rootProject.layout.projectDirectory.dir("local-maven")) } }
}
