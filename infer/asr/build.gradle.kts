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

// ADR-048: Qualcomm's QNN runtime ships only in local device builds (-Pmurmur.qnn=true) until the owner has read its
// licence. Then exactly four libraries of com.qualcomm.qti:qnn-runtime 2.52.0 are packaged (asr-ocr A3); the 89.6 MB
// libQnnHtpPrepare.so (on-device compile), the other skels and the GPU/DSP backends are left out.
val shipQnn = providers.gradleProperty("murmur.qnn").map { it.toBoolean() }.getOrElse(false)
val qnnLibs = listOf("libQnnHtp.so", "libQnnSystem.so", "libQnnHtpV79Stub.so", "libQnnHtpV79Skel.so")

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
    androidComponents {
        onVariants { variant -> variant.sources.jniLibs?.addGeneratedSourceDirectory(extractQnn, ExtractQnnLibs::outputDir) }
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

    // Instrumented tests (real models on the x86_64 emulator): WER from the benchmark module (one normaliser).
    androidTestImplementation(project(":core:benchmark"))
    androidTestImplementation(project(":core:catalog"))
    androidTestImplementation(libs.androidx.test.core)
}
