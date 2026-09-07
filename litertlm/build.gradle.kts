// hfmodels-litertlm: the LiteRT-LM adapter (ChatModel / ChatSession) on top of hfmodels-core.
plugins {
    id("com.android.library")
    id("maven-publish")
}

val litertlmVersion: String = providers.gradleProperty("litertlmVersion").get()
val coroutinesVersion: String = providers.gradleProperty("coroutinesVersion").get()

android {
    namespace = "io.github.johnrocky.hfmodels.litertlm"
    compileSdk = 36
    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The exact runtime this handler was compiled against; compared with the descriptor's runtime_range.
        buildConfigField("String", "LITERTLM_VERSION", "\"$litertlmVersion\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing {
        singleVariant("release") { withSourcesJar() }
    }
    // litertlm-android 0.17.0 ships Kotlin 2.4 metadata, which the Kotlin built into AGP 9.3.1 refuses.
    // -PskipKotlinMetadataCheck=true is a re-gate switch, not a supported configuration.
    if (providers.gradleProperty("skipKotlinMetadataCheck").isPresent) {
        kotlin { compilerOptions { freeCompilerArgs.add("-Xskip-metadata-version-check") } }
    }
}


dependencies {
    api(project(":core"))
    // `api`: the SDK hands out LiteRT-LM types (Contents, Content, Message, ConversationConfig, Backend).
    api("com.google.ai.edge.litertlm:litertlm-android:$litertlmVersion")
    // >= 1.11.0 is required: 0.16.1's Conversation calls SendChannel.close$default, which 1.9.0 (the POM's
    // declared version) does not have (LiteRT-LM #3334). `api` so a consumer cannot resolve lower.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "io.github.johnrocky.hfmodels"
            artifactId = "hfmodels-litertlm"
            version = project.findProperty("hfmodelsVersion") as String? ?: "0.1.0-SNAPSHOT"
            afterEvaluate { from(components["release"]) }
        }
    }
    repositories {
        maven { name = "local"; url = uri(rootProject.layout.projectDirectory.dir("local-maven")) }
    }
}
