plugins {
    alias(libs.plugins.murmur.android.library)
}

dependencies {
    api(project(":core:catalog"))
    api(project(":core:store"))
    implementation(project(":core:model"))
    implementation(project(":core:engine"))
    implementation(project(":core:dictation"))
    // BenchScores (ADR-071/080): the one selection score and measured LLM speed, owned by WP-14.
    implementation(project(":core:benchmark"))
    implementation(project(":data:db"))
    implementation(libs.androidx.room.runtime)   // MurmurDatabase's supertype, for RoomCustomModelStore(db)
    implementation(libs.commons.compress)
    testImplementation(project(":testing"))
    testImplementation(libs.kotlinx.coroutines.test)   // ADR-093: the device-profile retry on virtual time
}
