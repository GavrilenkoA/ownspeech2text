plugins {
    alias(libs.plugins.murmur.android.library)
    // The Compose compiler only for the instrumented test targets (a Compose TextField is one of the insertion
    // surfaces). The module's main code has no composables; the compiler only insists on finding the Compose runtime
    // on every compile classpath it runs over, hence compileOnly / testCompileOnly below (nothing of it is packaged or
    // exported).
    alias(libs.plugins.kotlin.compose)
}

dependencies {
    api(project(":core:dictation"))
    api(project(":core:context"))
    implementation(project(":core:store"))
    implementation(project(":core:ocr"))
    implementation(project(":platform:overlay"))
    implementation(libs.androidx.core.ktx)
    compileOnly(platform(libs.androidx.compose.bom))
    compileOnly(libs.androidx.compose.ui)
    testImplementation(project(":testing"))
    testCompileOnly(platform(libs.androidx.compose.bom))
    testCompileOnly(libs.androidx.compose.ui)

    androidTestImplementation(project(":testing"))
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.activity.compose)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui)
    androidTestImplementation(libs.androidx.compose.foundation)
    androidTestImplementation(libs.androidx.compose.material3)
}
