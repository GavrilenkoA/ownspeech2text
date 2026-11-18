plugins {
    alias(libs.plugins.murmur.android.library)
}

// Prebuilt native libraries (ADR-008/009): never built by Gradle. native/sherpa/build-android.sh writes
// <murmur.nativeLibsDir>/sherpa/<abi>/libsherpa-onnx-jni.so (linked against the Maven ORT 1.32.0 below).
// When the directory is absent the module still builds; the runtime then reports EngineRuntimeMissing.
val nativeLibsDir = providers.gradleProperty("murmur.nativeLibsDir")
    .orElse(rootDir.resolve(".cache/native/jniLibs").absolutePath)
android {
    sourceSets.named("main") {
        jniLibs.srcDir(nativeLibsDir.get() + "/sherpa")
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
    // QNN 2.52 (com.qualcomm.qti:qnn-runtime) is added by WP-06 behind -Pmurmur.qnn=true after the licence check (needsFromOwner).
}
