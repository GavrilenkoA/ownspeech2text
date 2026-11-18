plugins {
    alias(libs.plugins.murmur.android.library)
    alias(libs.plugins.ksp)
}

ksp {
    // Room schema export for migration tests (data/db/schemas is checked in).
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    api(project(":core:store"))
    implementation(project(":core:model"))
    implementation(project(":core:catalog"))
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore)
    testImplementation(project(":testing"))
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.core)
}
