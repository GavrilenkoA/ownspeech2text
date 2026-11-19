plugins {
    alias(libs.plugins.murmur.android.library)
    alias(libs.plugins.murmur.android.compose)
}

dependencies {
    api(project(":core:dictation"))
    api(project(":core:store"))
    implementation(project(":ui:design"))
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.core.ktx)
    androidTestImplementation(project(":testing"))
    androidTestImplementation(libs.androidx.test.core)
}
