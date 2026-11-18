// Root build file.
// AGP 9 compiles Kotlin itself ("built-in Kotlin"); the org.jetbrains.kotlin.android
// plugin must NOT be applied. To control which Kotlin compiler is used we put the
// Kotlin Gradle Plugin on the root classpath at the catalog version; Gradle's
// conflict resolution then picks it over the 2.2.10 that AGP 9.5.1 depends on.
// Convention plugins live in build-logic/ (murmur.jvm.library / murmur.android.library / murmur.android.compose).
buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
