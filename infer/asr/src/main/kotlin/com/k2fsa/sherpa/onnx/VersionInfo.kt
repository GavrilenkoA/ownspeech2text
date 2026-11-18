// Vendored from k2-fsa/sherpa-onnx v2.0.1 (commit af2d8d81), sherpa-onnx/kotlin-api/VersionInfo.kt.
// Copyright the sherpa-onnx authors. Licensed under the Apache License, Version 2.0
// (http://www.apache.org/licenses/LICENSE-2.0). libsherpa-onnx-jni.so binds its JNI symbols and config field
// lookups to these exact class, field and method names: do not rename or remove fields.

package com.k2fsa.sherpa.onnx

class VersionInfo {
    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }

        val version: String
            get() = getVersionStr2()

        val gitSha1: String
            get() = getGitSha12()

        val gitDate: String
            get() = getGitDate2()

        val onnxruntimeVersion: String
            get() = getOnnxruntimeVersionStr2()

        external fun getVersionStr2(): String
        external fun getGitSha12(): String
        external fun getGitDate2(): String
        external fun getOnnxruntimeVersionStr2(): String
    }
}
