plugins {
    alias(libs.plugins.murmur.android.library)
}

dependencies {
    api(project(":core:dictation"))
    api(project(":core:context"))
    implementation(project(":core:store"))
    implementation(project(":core:ocr"))
    implementation(project(":platform:overlay"))
    implementation(libs.androidx.core.ktx)
    testImplementation(project(":testing"))
}
