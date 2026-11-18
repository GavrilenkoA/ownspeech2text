plugins {
    alias(libs.plugins.murmur.jvm.library)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:audio"))
    api(project(":core:ocr"))
    implementation(libs.kotlinx.coroutines.core)
}
