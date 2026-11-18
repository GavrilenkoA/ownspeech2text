import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Android library module. AGP 9 built-in Kotlin compiles the Kotlin sources (do NOT apply
 * org.jetbrains.kotlin.android). Namespace = "app.murmur." + the Gradle path with ':' -> '.'
 * (e.g. :infer:api -> app.murmur.infer.api).
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")

        extensions.configure<LibraryExtension> {
            namespace = "app.murmur" + path.replace(':', '.').replace('-', '_')
            compileSdk {
                version = release(libs.int("compileSdk")) {
                    minorApiLevel = libs.int("compileSdkMinor")
                }
            }
            buildToolsVersion = libs.findVersion("buildTools").get().requiredVersion
            defaultConfig {
                minSdk = libs.int("minSdk")
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                // D10: the product name comes from gradle.properties only (used as ${'$'}{productName} in manifests).
                manifestPlaceholders["productName"] = providers.gradleProperty("murmur.productName").getOrElse("Murmur")
            }
            compileOptions {
                sourceCompatibility = JavaVersion.toVersion(JVM_TARGET)
                targetCompatibility = JavaVersion.toVersion(JVM_TARGET)
            }
            testOptions {
                unitTests.isReturnDefaultValues = true
            }
            lint {
                abortOnError = true
                checkReleaseBuilds = false
            }
        }
        dependencies {
            add("implementation", libs.lib("kotlinx-coroutines-android"))
            add("implementation", libs.lib("kotlinx-serialization-json"))
            add("testImplementation", libs.lib("junit"))
            add("testImplementation", libs.lib("kotlinx-coroutines-test"))
            add("androidTestImplementation", libs.lib("androidx-test-ext-junit"))
            add("androidTestImplementation", libs.lib("androidx-test-runner"))
        }
    }
}
