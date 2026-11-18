plugins {
    alias(libs.plugins.murmur.android.library)
}

dependencies {
    api(project(":core:dictation"))
    implementation(project(":core:audio"))
    implementation(project(":core:store"))
    implementation(libs.androidx.core.ktx)
    testImplementation(project(":testing"))
}
