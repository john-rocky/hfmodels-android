// hfmodels-voice: the voice loop's pieces on top of the transcriber (hfmodels-litert) and the chat model
// (hfmodels-litertlm). This round holds the Endpointer only. Depending on hfmodels-litert, an app needs
// android.uniquePackageNames=false on AGP 9 (gradle.properties).
plugins {
    id("com.android.library")
    id("com.vanniktech.maven.publish") version "0.33.0"
}

val coroutinesVersion: String = providers.gradleProperty("coroutinesVersion").get()

android {
    namespace = "io.github.johnrocky.hfmodels.voice"
    compileSdk = 36
    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // The device gate (TranscribeDeviceTest: the transcriber and the Endpointer in one process) side-loads the
    // development descriptor as a test-APK asset. It lives here, not in hfmodels-litert, because the Endpointer
    // is in this module and this module depends on hfmodels-litert.
    sourceSets { named("androidTest") { assets.srcDir(rootProject.file("catalog/dev")) } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":litert"))
    api(project(":litertlm"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates("io.github.john-rocky.hfmodels", "hfmodels-voice", project.findProperty("hfmodelsVersion") as String? ?: "0.1.2")
    pom {
        name.set("hfmodels-voice")
        description.set("hfmodels voice loop: endpointing over microphone audio, on top of the LiteRT transcriber and the LiteRT-LM chat model")
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
