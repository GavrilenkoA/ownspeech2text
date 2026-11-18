// murmur_llm_jni.h - JNI surface of the `:llm` process (src/murmur_llm_jni.cpp, a thin layer over murmur_llm.h).
//
// Kotlin side (infer/llm/src/main/kotlin/app/murmur/infer/llm/LlmNative.kt):
//
//   object LlmNative {
//     external fun init(nativeLibDir: String, htpLibDir: String?, powerMode: String?, verbose: Int): Int
//     external fun buildInfo(): String                                  // "llama.cpp b12520 ff6b8d176186 ggml-hexagon npu"
//     external fun runtimeInfo(): String                                // "cpu_backend=libggml-cpu-haswell.so;htp_arch=v79;"
//     external fun devices(): String                                    // "CPU|...|cpu;HTP0|Hexagon|other;"
//     external fun load(path: String, device: Int, nCtx: Int, nBatch: Int, nThreads: Int, useMmap: Boolean,
//                       kvType: Int, out: LongArray, info: DoubleArray): Int
//     external fun deviceName(h: Long): String
//     external fun tokenCount(h: Long, utf8: ByteArray): Int
//     external fun prefixSet(h: Long, prefixUtf8: ByteArray, stats: DoubleArray): Int
//     external fun prefixSave(h: Long, file: String): Int
//     external fun prefixLoad(h: Long, file: String, stats: DoubleArray): Int
//     external fun generate(h: Long, suffixUtf8: ByteArray, maxTokens: Int, temperature: Float, repeatPenalty: Float,
//                           stopsUtf8: Array<ByteArray>?, sink: TokenSink, stats: DoubleArray): Int
//     external fun cancel(h: Long)                                     // any thread
//     external fun cancelClear(h: Long)
//     external fun bench(h: Long, nPrompt: Int, nGen: Int, reps: Int, out: DoubleArray): Int
//     external fun lastError(h: Long): String                          // fixed English messages, never user text
//     external fun unload(h: Long)
//   }
//   fun interface TokenSink { fun onPiece(utf8: ByteArray): Boolean }   // return false = stop
//
// Text in and out is raw UTF-8 bytes (Kotlin: s.encodeToByteArray() / bytes.decodeToString()). Prompt text is never a
// jstring: GetStringUTFChars yields *modified* UTF-8, which encodes emoji and other supplementary characters as two
// 3-byte surrogates (and U+0000 as 0xC0 0x80); llama.cpp's tokenizer then sees invalid UTF-8. The shim copies the
// byte[] and appends the terminating NUL. Paths stay jstring (ASCII app dirs).
//
// Threading:
//   * Every call except cancel() runs on ONE engine thread (the single-thread executor of LlmService). That thread is
//     a Java thread, so it is attached for its whole life; the shim uses the JNIEnv of the current call.
//   * generate() blocks that thread and calls TokenSink.onPiece() on it for every complete UTF-8 piece (pieces never
//     split a code point). The method id is resolved once in JNI_OnLoad. A Java exception in the sink stops the
//     generation (reported as a callback stop) and stays pending for the caller.
//
// Cancellation:
//   * cancel() flips an atomic (mllm_cancel). It is polled before every prefill chunk (n_batch tokens) and every
//     decoded token, and by ggml's abort callback, which only the CPU backend honours. Worst-case latency:
//     CPU = one graph node; NPU/GPU = one llama_process call (one prefill chunk or one token).
//   * The flag stays set until cancelClear(), which the owner calls on the engine thread before each request.
//   * generate() then returns MLLM_E_CANCELLED (-6) with stats[7] = 3; the prefix snapshot stays valid.
//   * cancel(h) must never race unload(h) (use-after-free): Kotlin reads the handle under the same lock as unload.
//
// Lifetime:
//   * init() once per process before anything else (sets GGML_HEXAGON_LIBHTP_DIR / power mode, dlopens the ggml
//     backends from nativeLibDir). It cannot be undone: to release the FastRPC session and rpcmem, unload() and
//     then let the :llm process exit.
//   * arrays: load info -> [load_ms, ctx_ms, n_layers, n_vocab];
//     prefixSet/prefixLoad -> [n_tokens, prefill_ms (load: file read ms), snapshot_ms, snapshot_bytes];
//     generate -> [n_prompt, n_gen, restore_ms, prefill_ms, ttft_ms, decode_ms, total_ms, stop_reason];
//     bench -> [pp_tps, tg_tps, pp_ms, tg_ms].
#ifndef MURMUR_LLM_JNI_H
#define MURMUR_LLM_JNI_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

#define MLLM_JNI(name) Java_app_murmur_infer_llm_LlmNative_##name

JNIEXPORT jint    JNICALL MLLM_JNI(init)(JNIEnv *, jobject, jstring nativeLibDir, jstring htpLibDir, jstring powerMode, jint verbose);
JNIEXPORT jstring JNICALL MLLM_JNI(buildInfo)(JNIEnv *, jobject);
JNIEXPORT jstring JNICALL MLLM_JNI(runtimeInfo)(JNIEnv *, jobject);
JNIEXPORT jstring JNICALL MLLM_JNI(devices)(JNIEnv *, jobject);
JNIEXPORT jint    JNICALL MLLM_JNI(load)(JNIEnv *, jobject, jstring path, jint device, jint nCtx, jint nBatch, jint nThreads,
                                         jboolean useMmap, jint kvType, jlongArray out, jdoubleArray info);
JNIEXPORT jstring JNICALL MLLM_JNI(deviceName)(JNIEnv *, jobject, jlong h);
JNIEXPORT jint    JNICALL MLLM_JNI(tokenCount)(JNIEnv *, jobject, jlong h, jbyteArray utf8);
JNIEXPORT jint    JNICALL MLLM_JNI(prefixSet)(JNIEnv *, jobject, jlong h, jbyteArray prefixUtf8, jdoubleArray stats);
JNIEXPORT jint    JNICALL MLLM_JNI(prefixSave)(JNIEnv *, jobject, jlong h, jstring file);
JNIEXPORT jint    JNICALL MLLM_JNI(prefixLoad)(JNIEnv *, jobject, jlong h, jstring file, jdoubleArray stats);
JNIEXPORT jint    JNICALL MLLM_JNI(generate)(JNIEnv *, jobject, jlong h, jbyteArray suffixUtf8, jint maxTokens, jfloat temperature,
                                             jfloat repeatPenalty, jobjectArray stopsUtf8, jobject sink, jdoubleArray stats);
JNIEXPORT void    JNICALL MLLM_JNI(cancel)(JNIEnv *, jobject, jlong h);
JNIEXPORT void    JNICALL MLLM_JNI(cancelClear)(JNIEnv *, jobject, jlong h);
JNIEXPORT jint    JNICALL MLLM_JNI(bench)(JNIEnv *, jobject, jlong h, jint nPrompt, jint nGen, jint reps, jdoubleArray out);
JNIEXPORT jstring JNICALL MLLM_JNI(lastError)(JNIEnv *, jobject, jlong h);
JNIEXPORT void    JNICALL MLLM_JNI(unload)(JNIEnv *, jobject, jlong h);

#ifdef __cplusplus
}
#endif
#endif // MURMUR_LLM_JNI_H
