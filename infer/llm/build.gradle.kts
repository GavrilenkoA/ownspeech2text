plugins {
    alias(libs.plugins.murmur.android.library)
}

// Prebuilt llama.cpp b12520 set (ADR-008/009): native/llama/build_android.sh writes
// <murmur.nativeLibsDir>/llama/<abi>/*.so (arm64: CPU variants + OpenCL + ggml-hexagon + libggml-htp-v79.so;
// x86_64: CPU variants incl. ivybridge/haswell for the emulator) plus the JNI shim libmurmur_llm_jni.so.
// Without the directory the module still builds; the :llm probe then reports the runtime as missing.
val nativeLibsDir = providers.gradleProperty("murmur.nativeLibsDir")
    .orElse(rootDir.resolve(".cache/native/jniLibs").absolutePath)
// ADR-048: the Hexagon-SDK-built DSP skel ships only in local device builds (-Pmurmur.htp=true), here for this
// module's own test APK just as app/build.gradle.kts does for the app.
val shipHtp = providers.gradleProperty("murmur.htp").map { it.toBoolean() }.getOrElse(false)
android {
    sourceSets.named("main") {
        jniLibs.srcDir(nativeLibsDir.get() + "/llama")
    }
    packaging {
        jniLibs {
            // ADR-007: ggml-hexagon finds its DSP skel and ggml its CPU variants in a real directory.
            useLegacyPackaging = true
            if (!shipHtp) excludes += "**/libggml-htp-*.so"
        }
    }
}

dependencies {
    implementation(project(":infer:api"))
    implementation(project(":core:engine"))
    implementation(project(":core:catalog"))
}
