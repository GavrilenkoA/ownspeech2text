// Deterministic fakes for every contract (D6). JVM-only: usable from JVM unit tests, Android unit tests,
// instrumented UI tests and the debug app's "fake engines" mode.
plugins {
    alias(libs.plugins.murmur.jvm.library)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:catalog"))
    api(project(":core:context"))
    api(project(":core:prompt"))
    api(project(":core:ocr"))
    api(project(":core:audio"))
    api(project(":core:engine"))
    api(project(":core:store"))
    api(project(":core:benchmark"))
    api(project(":core:dictation"))
    api(libs.kotlinx.coroutines.core)
}
