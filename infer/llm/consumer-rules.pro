# Kept for R8 in every APK that packages :infer:llm (release minify included).
# libmurmur_llm_jni.so binds by name: the Java_app_murmur_infer_llm_LlmNative_* exports need the class name and its
# native methods, and JNI_OnLoad looks up TokenSink.onPiece([B)Z, which only native code calls. Without these rules
# R8 renames or drops them, JNI_OnLoad fails and LlmNative.loadOrNull() quietly yields no engine.
-keep class app.murmur.infer.llm.LlmNative {
    native <methods>;
}
-keep interface app.murmur.infer.llm.TokenSink {
    boolean onPiece(byte[]);
}
