import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * Pure Kotlin/JVM module: contracts, value types, pure logic. Unit tests run on the host JVM
 * (`./gradlew :core:x:test`, also part of `testDebugUnitTest` aggregation through `check`).
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")

        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.toVersion(JVM_TARGET)
            targetCompatibility = JavaVersion.toVersion(JVM_TARGET)
        }
        extensions.configure<KotlinJvmProjectExtension> {
            compilerOptions {
                jvmTarget.set(JvmTarget.fromTarget(JVM_TARGET.toString()))
                freeCompilerArgs.add("-Xjsr305=strict")
            }
        }
        tasks.withType<Test>().configureEach {
            useJUnit()
            maxParallelForks = 1
        }
        // `testDebugUnitTest` is the Android task name the team runs everywhere (tools/gw assembleDebug
        // testDebugUnitTest). Give JVM modules the same entry point so that one command covers both.
        tasks.register("testDebugUnitTest") {
            group = "verification"
            description = "Alias of :test for JVM modules (same command as Android modules)."
            dependsOn("test")
        }
        dependencies {
            add("implementation", libs.lib("kotlinx-coroutines-core"))
            add("implementation", libs.lib("kotlinx-serialization-json"))
            add("testImplementation", libs.lib("junit"))
            add("testImplementation", libs.lib("kotlinx-coroutines-test"))
        }
    }
}
