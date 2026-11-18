import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/** Shared accessors for the convention plugins. All numbers come from gradle/libs.versions.toml. */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.int(alias: String): Int = findVersion(alias).get().requiredVersion.toInt()

internal fun VersionCatalog.lib(alias: String) = findLibrary(alias).get()

/** JVM bytecode level of every module (JDK 21 runs the build; ADR-004). */
internal const val JVM_TARGET = 17
