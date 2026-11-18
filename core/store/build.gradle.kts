plugins {
    alias(libs.plugins.murmur.jvm.library)
}

dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
    // Shared fakes and the reference SimpleDeliveryMetrics / the dashboard fixture (testing/contrib/Wp12*).
    testImplementation(project(":testing"))
}

tasks.withType<Test>().configureEach {
    // DeliveryMetricsTest reads the filler lexicon from prompts/guard.py in place (the source OutputGuard.fillers ports).
    systemProperty("murmur.repoRoot", rootDir.absolutePath)
    // ...so the file is a test input: a changed lexicon re-runs the tests instead of reusing an up-to-date result.
    inputs.file(rootDir.resolve("prompts/guard.py")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("guardPy")
}
