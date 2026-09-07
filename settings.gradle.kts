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
include(":probes:coexist")
include(":samples:chat")
