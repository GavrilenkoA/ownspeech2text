plugins {
    alias(libs.plugins.murmur.android.library)
    // Compose only for the instrumented test targets (a Compose TextField is one of the insertion surfaces); the
    // module's main code has no composables, and the APK already carries Compose through :platform:overlay.
    alias(libs.plugins.murmur.android.compose)
}

dependencies {
    api(project(":core:dictation"))
    api(project(":core:context"))
    implementation(project(":core:store"))
    implementation(project(":core:ocr"))
    implementation(project(":platform:overlay"))
    implementation(libs.androidx.core.ktx)
    testImplementation(project(":testing"))

    androidTestImplementation(project(":testing"))
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.activity.compose)
}
