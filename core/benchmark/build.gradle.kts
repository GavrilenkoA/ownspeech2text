plugins {
    alias(libs.plugins.murmur.jvm.library)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:engine"))
    api(project(":core:store"))
    api(project(":core:catalog"))
}
