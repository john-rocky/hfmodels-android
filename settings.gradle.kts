pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // LiteRT-LM and LiteRT AARs are on Google Maven (dl.google.com), not Maven Central.
        google()
        mavenCentral()
    }
}

rootProject.name = "hfmodels-android"
include(":core")
include(":litertlm")
include(":litert")
include(":voice")
include(":probes:coexist")
include(":probes:scoring")
include(":probes:smsseed")
include(":samples:ask")
include(":samples:chat")
include(":samples:decide")
include(":samples:finder")
include(":samples:pong")
include(":samples:voice")
include(":samples:promises")
include(":samples:eg2search")
