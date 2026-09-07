// AGP 9 has Kotlin built in; do not add org.jetbrains.kotlin.android (hard error on AGP >= 9).
plugins {
    id("com.android.application") version "9.3.1" apply false
    id("com.android.library") version "9.3.1" apply false
}
