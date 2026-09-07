// hfmodels-core: ModelRef, resolver, HF client, cache, report, errors. No runtime dependency.
plugins {
    id("com.android.library")
    id("com.vanniktech.maven.publish") version "0.33.0"
}

val coroutinesVersion: String = providers.gradleProperty("coroutinesVersion").get()

android {
    namespace = "io.github.johnrocky.hfmodels"
    compileSdk = 36
    defaultConfig {
        // Product decision (spec v1.0 §2): API 31, so the verified set is one OS generation.
        // The LiteRT-LM and LiteRT AARs themselves declare minSdk 24.
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}


dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
    // android.jar stubs org.json in JVM unit tests; the real implementation stands in for it.
    testImplementation("org.json:json:20250517")
}

mavenPublishing {
    // Maven Central (Central Portal). Credentials and the signing key come from ~/.gradle/gradle.properties
    // (mavenCentralUsername / mavenCentralPassword / signingInMemoryKey / signingInMemoryKeyPassword), never from this file.
    publishToMavenCentral()
    signAllPublications()
    coordinates("io.github.john-rocky.hfmodels", "hfmodels-core", project.findProperty("hfmodelsVersion") as String? ?: "0.1.0")
    pom {
        name.set("hfmodels-core")
        description.set("hfmodels core: Hugging Face model ids to verified local files, descriptor and catalog readers, typed errors (no runtime dependency)")
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

// The E1 harness resolves the SDK from a local directory: ./gradlew publishAllPublicationsToLocalRepository -PhfmodelsVersion=0.1.0-local
publishing {
    repositories { maven { name = "local"; url = uri(rootProject.layout.projectDirectory.dir("local-maven")) } }
}
