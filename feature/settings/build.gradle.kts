plugins {
    alias(libs.plugins.murmur.android.library)
    alias(libs.plugins.murmur.android.compose)
}

/**
 * The design frames the screenshot test compares against (docs/design/screens, DESIGN 6.5 settings frames), copied
 * into the androidTest assets under frames/. Only these frames, not the whole directory.
 */
abstract class SettingsFrameAssets : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val frames: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = outDir.get().asFile.resolve("frames")
        outDir.get().asFile.deleteRecursively()
        dir.mkdirs()
        frames.files.forEach { it.copyTo(dir.resolve(it.name)) }
    }
}

android {
    // Fixtures shared by the JVM view-model tests and the instrumented screenshot tests (the frames' data).
    sourceSets.getByName("test").kotlin.directories.add("src/sharedTest/kotlin")
    sourceSets.getByName("androidTest").kotlin.directories.add("src/sharedTest/kotlin")
}

dependencies {
    implementation(project(":ui:design"))
    implementation(project(":core:model"))
    implementation(project(":core:store"))
    implementation(project(":core:prompt"))
    implementation(project(":core:catalog"))
    implementation(project(":core:dictation"))
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.material3.adaptive.layout)
    // BackHandler (prompt editor, Tone per app); already in the catalog (used by :app and :feature:history).
    implementation(libs.androidx.activity.compose)
    testImplementation(project(":testing"))
    androidTestImplementation(project(":testing"))
}

tasks.withType<Test>().configureEach {
    // ToneRulesTest checks the tone list against prompts/tones/*.json in place (the files the APK packages).
    systemProperty("murmur.repoRoot", rootDir.absolutePath)
    inputs.dir(rootDir.resolve("prompts/tones")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("promptTones")
}

val settingsFrames = tasks.register<SettingsFrameAssets>("settingsFrameAssets") {
    frames.from(
        fileTree(rootDir.resolve("docs/design/screens")) {
            include("settings--*.png", "settings-off--*.png", "settings-dark--*.png", "prompt--*.png", "prompt-err--*.png")
            include("prompt-reset--*.png", "tone--*.png", "retention--*.png")
        },
    )
    outDir.set(layout.buildDirectory.dir("generated/settingsFrames"))
}
androidComponents {
    onVariants { v -> v.deviceTests.values.forEach { it.sources.assets?.addGeneratedSourceDirectory(settingsFrames, SettingsFrameAssets::outDir) } }
}
