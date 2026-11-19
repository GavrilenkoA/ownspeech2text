plugins {
    alias(libs.plugins.murmur.jvm.library)
}

dependencies {
    api(project(":core:model"))
    // Host end-to-end parity (OcrHostPipelineTest): the desktop build of the APK's ONNX Runtime, same version as
    // libs.onnxruntime.android (same Java API, same CPU kernels as ml/ocr's onnxruntime 1.32.0). Test-only.
    testImplementation("com.microsoft.onnxruntime:onnxruntime:${libs.versions.onnxruntime.get()}")
}

// Main checkout = the one that owns the shared model cache; a git worktree's `.git` is a file "gitdir: <main>/.git/worktrees/<id>".
val mainCheckout: File = rootDir.resolve(".git").let { git ->
    if (git.isFile) File(git.readText().trim().removePrefix("gitdir:").trim()).parentFile.parentFile.parentFile else rootDir
}

// Parity material lives in the repo (owned by ml/ocr): tests read it in place, never copy it.
// Models for the host end-to-end test come from tools/models/fetch: $MURMUR_MODELS or <main checkout>/.cache/models.
tasks.withType<Test>().configureEach {
    systemProperty("murmur.repoRoot", rootDir.absolutePath)
    systemProperty(
        "murmur.modelsDir",
        providers.environmentVariable("MURMUR_MODELS").getOrElse(mainCheckout.resolve(".cache/models").absolutePath),
    )
    maxHeapSize = "2g"
}
