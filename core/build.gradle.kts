// hfmodels-core: ModelRef, resolver, HF client, cache, report, errors. No runtime dependency.
plugins {
    id("com.android.library")
    id("maven-publish")
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
    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}


dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
    // android.jar stubs org.json in JVM unit tests; the real implementation stands in for it.
    testImplementation("org.json:json:20250517")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "io.github.johnrocky.hfmodels"
            artifactId = "hfmodels-core"
            version = project.findProperty("hfmodelsVersion") as String? ?: "0.1.0-SNAPSHOT"
            afterEvaluate { from(components["release"]) }
        }
    }
    repositories {
        maven { name = "local"; url = uri(rootProject.layout.projectDirectory.dir("local-maven")) }
    }
}
