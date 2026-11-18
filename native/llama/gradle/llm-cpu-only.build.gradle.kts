// CPU-only Gradle path for native/llama (verified 2026-11-18: AGP 9.5.1, Gradle 9.9.1, NDK r30, CMake 4.1.2;
// clean :llm:assembleRelease 397 s at load ~28, no-op 7 s). NOT the recommended path: see docs/research/verify/llm-npu.md section 6.
// The NPU (Hexagon) build needs HEXAGON_SDK_ROOT and only runs through native/llama/build_android.sh.
// Here MURMUR_NATIVE_LLAMA = absolute path of native/llama; in the real app use rootProject.file("native/llama/CMakeLists.txt").
// Probe: build native/llama/CMakeLists.txt through AGP externalNativeBuild (CPU-only, arm64-v8a),
// llama.cpp fetched by CMake FetchContent from the sha256-pinned source archive (MURMUR_LLAMA_SRC empty).
plugins { alias(libs.plugins.android.library) }
android {
    namespace = "app.murmur.llmprobe"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    ndkVersion = libs.versions.ndk.get()
    defaultConfig {
        minSdk = 36
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DMURMUR_LLM_BENCH=OFF",
                    "-DGGML_NATIVE=OFF", "-DGGML_BACKEND_DL=ON", "-DGGML_CPU_ALL_VARIANTS=ON", "-DGGML_CPU_KLEIDIAI=ON",
                    "-DGGML_OPENMP=OFF", "-DGGML_LLAMAFILE=OFF", "-DLLAMA_BUILD_COMMON=OFF", "-DLLAMA_BUILD_TOOLS=OFF",
                    // shared server: cap ninja at 6 parallel jobs
                    "-DCMAKE_JOB_POOLS=murmur=6", "-DCMAKE_JOB_POOL_COMPILE=murmur", "-DCMAKE_JOB_POOL_LINK=murmur")
                // same flags as build_android.sh cpu-arm64 (upstream snapdragon preset minus -march)
                cFlags += listOf("-fvectorize", "-ffp-model=fast", "-fno-finite-math-only", "-flto", "-D_GNU_SOURCE")
                cppFlags += listOf("-fvectorize", "-ffp-model=fast", "-fno-finite-math-only", "-flto", "-D_GNU_SOURCE")
                // ggml backends are dlopen()ed MODULEs, not link deps: AGP packages only listed targets + link deps
                targets += listOf("murmur_llm", "ggml-cpu-android_armv8.0_1", "ggml-cpu-android_armv8.6_1")
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file(System.getenv("MURMUR_NATIVE_LLAMA") + "/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }
}
