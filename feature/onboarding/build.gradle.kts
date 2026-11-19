plugins {
    alias(libs.plugins.murmur.android.library)
    alias(libs.plugins.murmur.android.compose)
}

dependencies {
    implementation(project(":ui:design"))
    implementation(project(":core:model"))
    implementation(project(":core:store"))
    implementation(project(":core:catalog"))
    implementation(project(":core:context"))
    implementation(project(":core:dictation"))
    implementation(libs.androidx.core.ktx)
    // Runtime permission dialogs (ActivityResult), BackHandler.
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.material3.adaptive.layout)
    testImplementation(project(":testing"))
    androidTestImplementation(project(":testing"))
}
