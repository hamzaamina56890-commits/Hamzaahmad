// Root Gradle build file for the Chinese-boot Android client.
// The `core` module is plain Kotlin/JVM (buildable and testable without the
// Android SDK). The `app` module requires the Android Gradle Plugin and the
// Android SDK to build/run and depends on `core` for its business logic.
plugins {
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
}
