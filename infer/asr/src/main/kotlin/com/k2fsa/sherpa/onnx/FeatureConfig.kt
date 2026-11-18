// Vendored from k2-fsa/sherpa-onnx v2.0.1 (commit af2d8d81), sherpa-onnx/kotlin-api/FeatureConfig.kt.
// Copyright the sherpa-onnx authors. Licensed under the Apache License, Version 2.0
// (http://www.apache.org/licenses/LICENSE-2.0). libsherpa-onnx-jni.so binds its JNI symbols and config field
// lookups to these exact class, field and method names: do not rename or remove fields.

package com.k2fsa.sherpa.onnx

data class FeatureConfig(
    var sampleRate: Int = 16000,
    var featureDim: Int = 80,
    var dither: Float = 0.0f
)

fun getFeatureConfig(sampleRate: Int, featureDim: Int): FeatureConfig {
    return FeatureConfig(sampleRate = sampleRate, featureDim = featureDim)
}
