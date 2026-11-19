plugins {
    alias(libs.plugins.murmur.android.library)
    alias(libs.plugins.murmur.android.compose)
}

dependencies {
    implementation(project(":ui:design"))
    implementation(project(":core:model"))
    implementation(project(":core:store"))
    implementation(project(":core:dictation"))
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.material3.adaptive.layout)
    // BackHandler (search, multi-select, pushed detail); already in the catalog (used by :app).
    implementation(libs.androidx.activity.compose)
    testImplementation(project(":testing"))
    androidTestImplementation(project(":testing"))
    androidTestImplementation(libs.androidx.test.core)
}
