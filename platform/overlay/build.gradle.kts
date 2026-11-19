plugins {
    alias(libs.plugins.murmur.android.library)
    alias(libs.plugins.murmur.android.compose)
}

dependencies {
    api(project(":core:dictation"))
    api(project(":core:store"))
    implementation(project(":ui:design"))
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // OverlayHost is its own ViewModelStoreOwner (ViewModelStore, setViewTreeViewModelStoreOwner): the debug classpath
    // got lifecycle-viewmodel transitively, the release one did not (:app:assembleRelease failed, WP-20 finding).
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.core.ktx)
    androidTestImplementation(project(":testing"))
    androidTestImplementation(libs.androidx.test.core)
}
