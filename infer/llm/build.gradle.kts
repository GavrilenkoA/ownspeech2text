plugins {
    alias(libs.plugins.murmur.android.library)
}

// Prebuilt llama.cpp b12520 set (ADR-009): native/llama/build_android.sh jnilibs writes
// <murmur.nativeLibsDir>/llama/<abi>/*.so (arm64: CPU variants + OpenCL + ggml-hexagon + libggml-htp-v79.so;
// x86_64: CPU variants incl. ivybridge for the emulator) plus the JNI shim libmurmur_llm_jni.so.
val nativeLibsDir = providers.gradleProperty("murmur.nativeLibsDir")
    .orElse(rootDir.resolve(".cache/native/jniLibs").absolutePath)
android {
    sourceSets.named("main") {
        jniLibs.srcDir(nativeLibsDir.get() + "/llama")
    }
}

dependencies {
    implementation(project(":infer:api"))
    implementation(project(":core:engine"))
    implementation(project(":core:catalog"))
}
