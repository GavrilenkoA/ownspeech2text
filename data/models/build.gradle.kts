plugins {
    alias(libs.plugins.murmur.android.library)
}

dependencies {
    api(project(":core:catalog"))
    api(project(":core:store"))
    implementation(project(":core:model"))
    implementation(project(":core:engine"))
    implementation(project(":core:dictation"))
    implementation(project(":data:db"))
    implementation(libs.androidx.room.runtime)   // MurmurDatabase's supertype, for RoomCustomModelStore(db)
    implementation(libs.commons.compress)
    testImplementation(project(":testing"))
}
