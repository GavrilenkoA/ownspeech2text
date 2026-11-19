plugins {
    alias(libs.plugins.murmur.android.library)
    alias(libs.plugins.murmur.android.compose)
}

android {
    // Expected EN/RU texts shared by the JVM formatter test (strings read from res/ files) and the instrumented
    // one (strings from the real Resources, so Android's own plural rules are checked as well).
    sourceSets.getByName("test").kotlin.directories.add("src/sharedTest/kotlin")
    sourceSets.getByName("androidTest").kotlin.directories.add("src/sharedTest/kotlin")
}

dependencies {
    implementation(project(":ui:design"))
    implementation(project(":core:model"))
    implementation(project(":core:store"))
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.material3.adaptive.layout)
    testImplementation(project(":testing"))
    androidTestImplementation(project(":testing"))
}

tasks.withType<Test>().configureEach {
    // DashboardTextsTest reads res/values{,-ru}/strings.xml in place (the copy the APK ships).
    systemProperty("murmur.dashboardRes", projectDir.resolve("src/main/res").absolutePath)
    inputs.dir(projectDir.resolve("src/main/res")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("dashboardRes")
}
