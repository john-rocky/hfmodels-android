// AGP 9 has Kotlin built in; do not add org.jetbrains.kotlin.android (hard error on AGP >= 9).
// The Kotlin AGP 9.3.1 brings (2.2.10) refuses the Kotlin 2.4 metadata of LiteRT-LM 0.19.1, so the build uses a newer one.
buildscript {
    dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21") }
}
plugins {
    id("com.android.application") version "9.3.1" apply false
    id("com.android.library") version "9.3.1" apply false
}
