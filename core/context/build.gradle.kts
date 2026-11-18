plugins {
    alias(libs.plugins.murmur.jvm.library)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:ocr"))
}

tasks.withType<Test>().configureEach {
    systemProperty("murmur.repoRoot", rootDir.absolutePath)
}
