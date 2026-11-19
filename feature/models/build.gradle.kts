plugins {
    alias(libs.plugins.murmur.android.library)
    alias(libs.plugins.murmur.android.compose)
}

dependencies {
    implementation(project(":ui:design"))
    implementation(project(":core:model"))
    implementation(project(":core:store"))
    implementation(project(":core:catalog"))
    implementation(project(":core:benchmark"))
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.material3.adaptive.layout)
    testImplementation(project(":testing"))
    androidTestImplementation(project(":testing"))
}

tasks.withType<Test>().configureEach {
    // NoModelLiteralsTest scans the module's own main sources (D3: no model-specific code).
    systemProperty("murmur.modelsSrc", projectDir.resolve("src/main").absolutePath)
    inputs.dir(projectDir.resolve("src/main")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("modelsSrc")
}
