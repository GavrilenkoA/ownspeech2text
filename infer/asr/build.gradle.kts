import javax.inject.Inject

plugins {
    alias(libs.plugins.murmur.android.library)
}

// Prebuilt native libraries (ADR-008/009): never built by Gradle. native/sherpa/build-android.sh writes
// <murmur.nativeLibsDir>/sherpa/<abi>/libsherpa-onnx-jni.so (linked against the Maven ORT 1.32.0 below; that directory
// holds nothing else) and <murmur.nativeLibsDir>/qnn-probe/arm64-v8a/libmurmur-qnn-probe.so (QnnProbe).
// When the directories are absent the module still builds; the runtime then reports EngineRuntimeMissing.
val nativeLibsDir = providers.gradleProperty("murmur.nativeLibsDir")
    .orElse(rootDir.resolve(".cache/native/jniLibs").absolutePath)

// ADR-048: Qualcomm's QNN runtime ships behind -Pmurmur.qnn=true; its licence (read 2026-11-20) allows object-code
// distribution inside an application only, so it is packaged exclusively in APKs. Exactly four libraries of
// com.qualcomm.qti:qnn-runtime 2.52.0 are packaged (asr-ocr A3); the 89.6 MB
// libQnnHtpPrepare.so (on-device compile), the other skels and the GPU/DSP backends are left out.
val shipQnn = providers.gradleProperty("murmur.qnn").map { it.toBoolean() }.getOrElse(false)
val qnnLibs = listOf("libQnnHtp.so", "libQnnSystem.so", "libQnnHtpV79Stub.so", "libQnnHtpV79Skel.so")
// WP-21: Qualcomm's ONNX Runtime plugin EP (com.qualcomm.qti:onnxruntime-android-qnn 2.7.0, POM licence MIT; NEEDED
// liblog/libm/libdl/libc only) next to the Maven ORT 1.32.0 below and the QNN 2.52 libraries above, under the same
// -Pmurmur.qnn gate: one QNN stack per APK (ADR-005). It loads at run time through
// OrtEnvironment.registerExecutionProviderLibrary (qnnep/QnnEp.kt); nothing links against it.
val qnnEpLibs = listOf("libonnxruntime_providers_qnn.so")

// WP-21 host simulator suite (qnnep/*SimulatorTest): -Pmurmur.qnnSim=<onnxruntime_qnn package dir of the
// native/qnn-contexts venv> and -Pmurmur.qnnContexts=<compile.sh output dir>. Without them those tests are excluded
// from the unit tests (they need the x86_64 HTP simulator and the compiled contexts, both outside the repo).
val qnnSim = providers.gradleProperty("murmur.qnnSim").orNull
val qnnContexts = providers.gradleProperty("murmur.qnnContexts").orNull

android {
    sourceSets.named("main") {
        jniLibs.srcDir(nativeLibsDir.get() + "/sherpa")
        jniLibs.srcDir(nativeLibsDir.get() + "/qnn-probe")
    }
    // The Segmenter parity fixture (host unit test) doubles as the on-device VAD parity reference.
    sourceSets.named("androidTest") { assets.srcDir("src/test/resources") }
    // ADR-007: real files in nativeLibraryDir (ADSP_LIBRARY_PATH must name a directory holding the QNN skel). The app
    // sets the same in app/build.gradle.kts; this covers the module's own instrumented-test APK.
    packaging { jniLibs { useLegacyPackaging = true } }
}

/** Copies the four packaged QNN libraries out of the qnn-runtime AAR into a jniLibs directory (arm64-v8a only). */
abstract class ExtractQnnLibs : DefaultTask() {
    @get:InputFiles abstract val aar: ConfigurableFileCollection
    @get:Input abstract val keep: ListProperty<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val fs: FileSystemOperations
    @get:Inject abstract val archives: ArchiveOperations

    @TaskAction
    fun extract() {
        val names = keep.get()
        fs.sync {
            from(archives.zipTree(aar.singleFile)) {
                include(names.map { "jni/arm64-v8a/$it" })
                eachFile { path = "arm64-v8a/$name" }
                includeEmptyDirs = false
            }
            into(outputDir)
        }
        val missing = names.filterNot { outputDir.file("arm64-v8a/$it").get().asFile.isFile }
        check(missing.isEmpty()) { "qnn-runtime AAR lacks $missing" }
    }
}

if (shipQnn) {
    val qnnRuntime = configurations.create("qnnRuntime") {
        isCanBeConsumed = false
        isTransitive = false
    }
    // Requested for gradle/libs.versions.toml (architect): com.qualcomm.qti:qnn-runtime:2.52.0 (Maven Central, POM licence
    // "Qualcomm AI Hub Model License"). Resolved only with -Pmurmur.qnn=true.
    dependencies { qnnRuntime("com.qualcomm.qti:qnn-runtime:2.52.0@aar") }
    val extractQnn = tasks.register<ExtractQnnLibs>("extractQnnLibs") {
        aar.from(qnnRuntime)
        keep.set(qnnLibs)
        outputDir.set(layout.buildDirectory.dir("generated/qnn/jniLibs"))
    }
    val qnnEpPlugin = configurations.create("qnnEpPlugin") {
        isCanBeConsumed = false
        isTransitive = false
    }
    // Requested for gradle/libs.versions.toml (architect, WP-21): com.qualcomm.qti:onnxruntime-android-qnn:2.7.0 (Maven
    // Central). Resolved only with -Pmurmur.qnn=true.
    dependencies { qnnEpPlugin("com.qualcomm.qti:onnxruntime-android-qnn:2.7.0@aar") }
    val extractQnnEp = tasks.register<ExtractQnnLibs>("extractQnnEpPlugin") {
        aar.from(qnnEpPlugin)
        keep.set(qnnEpLibs)
        outputDir.set(layout.buildDirectory.dir("generated/qnn-ep/jniLibs"))
    }
    androidComponents {
        onVariants { variant ->
            variant.sources.jniLibs?.addGeneratedSourceDirectory(extractQnn, ExtractQnnLibs::outputDir)
            variant.sources.jniLibs?.addGeneratedSourceDirectory(extractQnnEp, ExtractQnnLibs::outputDir)
        }
    }
}

// JVM unit tests run ONNX Runtime for real (WP-21 OCR/GigaAM sessions, the simulator suite): the desktop build of the
// same ORT 1.32.0 (same Java API, linux-x64 natives) replaces the Android AAR on the unit-test runtime classpath.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
}

// Main checkout = the owner of the shared model cache (a worktree's `.git` is a file "gitdir: <main>/.git/worktrees/<id>").
val mainCheckout: File = rootDir.resolve(".git").let { git ->
    if (git.isFile) File(git.readText().trim().removePrefix("gitdir:").trim()).parentFile.parentFile.parentFile else rootDir
}

tasks.withType<Test>().configureEach {
    systemProperty("murmur.repoRoot", rootDir.absolutePath)
    systemProperty(
        "murmur.modelsDir",
        providers.environmentVariable("MURMUR_MODELS").getOrElse(mainCheckout.resolve(".cache/models").absolutePath),
    )
    maxHeapSize = "2g"
    if (qnnSim != null && qnnContexts != null) {
        systemProperty("murmur.qnnSim", qnnSim)
        systemProperty("murmur.qnnContexts", qnnContexts)
        // the simulator's own threads do the work; one fork keeps the server load bounded
        maxParallelForks = 1
    } else {
        filter { excludeTestsMatching("*SimulatorTest") }
    }
}

dependencies {
    implementation(project(":infer:api"))
    implementation(project(":core:engine"))
    implementation(project(":core:audio"))
    implementation(project(":core:ocr"))
    implementation(project(":core:catalog"))
    // The one ONNX Runtime of the APK (sherpa-onnx links against this exact libonnxruntime.so; OCR and VAD use its Java API).
    implementation(libs.onnxruntime.android)

    // JVM tests with real ONNX Runtime sessions (see the classpath swap above).
    testImplementation("com.microsoft.onnxruntime:onnxruntime:${libs.versions.onnxruntime.get()}")
    testImplementation(libs.kotlinx.serialization.json)

    // Instrumented tests (real models on the x86_64 emulator): WER from the benchmark module (one normaliser).
    androidTestImplementation(project(":core:benchmark"))
    androidTestImplementation(project(":core:catalog"))
    androidTestImplementation(libs.androidx.test.core)
}
