plugins {
    alias(libs.plugins.murmur.jvm.library)
}

dependencies {
    api(project(":core:model"))
}

// Parity material lives in the repo (owned by ml/ocr): tests read it in place, never copy it.
tasks.withType<Test>().configureEach {
    systemProperty("murmur.repoRoot", rootDir.absolutePath)
}
