// hfmodels-litertlm: the LiteRT-LM adapter (ChatModel / ChatSession) on top of hfmodels-core.
plugins {
    id("com.android.library")
    id("com.vanniktech.maven.publish") version "0.33.0"
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

mavenPublishing {
    // Maven Central (Central Portal). Credentials and the signing key come from ~/.gradle/gradle.properties
    // (mavenCentralUsername / mavenCentralPassword / signingInMemoryKey / signingInMemoryKeyPassword), never from this file.
    publishToMavenCentral()
    signAllPublications()
    coordinates("io.github.john-rocky.hfmodels", "hfmodels-litertlm", project.findProperty("hfmodelsVersion") as String? ?: "0.1.0")
    pom {
        name.set("hfmodels-litertlm")
        description.set("hfmodels adapter for Google's LiteRT-LM runtime: ChatModel / ChatSession with a managed streaming bridge, cancel and release")
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
