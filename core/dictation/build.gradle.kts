plugins {
    alias(libs.plugins.murmur.jvm.library)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:catalog"))
    api(project(":core:context"))
    api(project(":core:prompt"))
    api(project(":core:engine"))
    api(project(":core:store"))
    api(project(":core:audio"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(project(":testing"))
}
