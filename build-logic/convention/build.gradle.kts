plugins {
    `kotlin-dsl`
}

dependencies {
    // compileOnly: the real plugin classes come from the root build's classpath (root build.gradle.kts
    // applies AGP / KGP with `apply false`), so build-logic never pins a second copy.
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("jvmLibrary") {
            id = "murmur.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
        register("androidLibrary") {
            id = "murmur.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "murmur.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
    }
}
