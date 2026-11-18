plugins {
    alias(libs.plugins.murmur.android.library)
}

android {
    buildFeatures { aidl = true }
}

dependencies {
    api(project(":core:engine"))
    api(project(":core:model"))
    implementation(libs.androidx.core.ktx)
}
