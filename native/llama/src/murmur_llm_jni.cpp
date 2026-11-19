// murmur_llm_jni.cpp - JNI shim of the :llm process over murmur_llm.h. Contract and threading rules:
// include/murmur_llm_jni.h. Text crosses as UTF-8 byte[] (never jstring, D11); results go into caller arrays.
#include "murmur_llm_jni.h"
#include "murmur_llm.h"

#include <algorithm>
#include <cstdint>
#include <initializer_list>
#include <cstring>
#include <string>
#include <vector>

namespace {

jmethodID g_on_piece = nullptr;   // TokenSink.onPiece([B)Z, resolved once in JNI_OnLoad

mllm_engine * engine(jlong h) { return reinterpret_cast<mllm_engine *>(static_cast<intptr_t>(h)); }

// byte[] -> NUL-terminated std::string (the bytes are UTF-8 already).
std::string bytes(JNIEnv * env, jbyteArray a) {
    if (!a) return {};
    const jsize n = env->GetArrayLength(a);
    std::string s(static_cast<size_t>(n), '\0');
    if (n > 0) env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(&s[0]));
    return s;
}

// jstring (ASCII paths) -> std::string; empty for null.
std::string chars(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

void put(JNIEnv * env, jdoubleArray a, std::initializer_list<double> v) {
    if (!a) return;
    const jsize n = std::min<jsize>(env->GetArrayLength(a), static_cast<jsize>(v.size()));
    env->SetDoubleArrayRegion(a, 0, n, v.begin());
}

struct SinkCtx {
    JNIEnv * env;
    jobject  sink;
};

// Runs on the engine thread inside mllm_generate (same thread and JNIEnv as the enclosing JNI call).
int on_piece(void * user, const char * piece, int32_t len) {
    auto * c = static_cast<SinkCtx *>(user);
    JNIEnv * env = c->env;
    jbyteArray arr = env->NewByteArray(len);
    if (!arr) return 1;                                   // OOM: OutOfMemoryError pending, stop
    env->SetByteArrayRegion(arr, 0, len, reinterpret_cast<const jbyte *>(piece));
    const jboolean keep = env->CallBooleanMethod(c->sink, g_on_piece, arr);
    env->DeleteLocalRef(arr);
    if (env->ExceptionCheck()) return 1;                  // leave the exception pending for Kotlin
    return keep ? 0 : 1;
}

} // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM * vm, void *) {
    JNIEnv * env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass sink = env->FindClass("app/murmur/infer/llm/TokenSink");
    if (!sink) return JNI_ERR;
    g_on_piece = env->GetMethodID(sink, "onPiece", "([B)Z");
    env->DeleteLocalRef(sink);
    return g_on_piece ? JNI_VERSION_1_6 : JNI_ERR;
}

extern "C" {

JNIEXPORT jint JNICALL MLLM_JNI(init)(JNIEnv * env, jobject, jstring nativeLibDir, jstring htpLibDir, jstring powerMode, jint verbose) {
    const std::string lib = chars(env, nativeLibDir), htp = chars(env, htpLibDir), power = chars(env, powerMode);
    mllm_init_params p {};
    p.backend_dir    = lib.empty() ? nullptr : lib.c_str();
    p.htp_lib_dir    = htp.empty() ? nullptr : htp.c_str();
    p.hex_power_mode = power.empty() ? nullptr : power.c_str();
    p.verbose        = verbose;
    return mllm_global_init(&p);
}

JNIEXPORT jstring JNICALL MLLM_JNI(buildInfo)(JNIEnv * env, jobject) {
    return env->NewStringUTF(mllm_build_info());
}

JNIEXPORT jstring JNICALL MLLM_JNI(runtimeInfo)(JNIEnv * env, jobject) {
    std::vector<char> buf(static_cast<size_t>(mllm_runtime_info(nullptr, 0)) + 1);
    mllm_runtime_info(buf.data(), static_cast<int32_t>(buf.size()));
    return env->NewStringUTF(buf.data());
}

JNIEXPORT jstring JNICALL MLLM_JNI(devices)(JNIEnv * env, jobject) {
    std::vector<char> buf(static_cast<size_t>(mllm_devices(nullptr, 0)) + 1);
    mllm_devices(buf.data(), static_cast<int32_t>(buf.size()));
    return env->NewStringUTF(buf.data());
}

JNIEXPORT jint JNICALL MLLM_JNI(load)(JNIEnv * env, jobject, jstring path, jint device, jint nCtx, jint nBatch, jint nThreads,
                                      jboolean useMmap, jint kvType, jlongArray out, jdoubleArray info) {
    if (!out || env->GetArrayLength(out) < 1) return MLLM_E_ARG;
    const std::string model = chars(env, path);
    mllm_load_params lp {};
    lp.model_path      = model.c_str();
    lp.device          = static_cast<mllm_device>(device);
    lp.n_ctx           = nCtx;
    lp.n_batch         = nBatch;
    lp.n_threads       = nThreads;
    lp.n_threads_batch = nThreads;
    lp.use_mmap        = useMmap ? 1 : 0;
    lp.prefix_mode     = MLLM_PREFIX_RAM;              // D5: per-sequence RAM snapshot of the system prefix
    lp.kv_type         = static_cast<mllm_kv_type>(kvType);
    mllm_engine * e = nullptr;
    mllm_load_info li {};
    const int rc = mllm_load(&lp, &e, &li);
    const jlong h = rc == MLLM_OK ? static_cast<jlong>(reinterpret_cast<intptr_t>(e)) : 0;
    env->SetLongArrayRegion(out, 0, 1, &h);
    if (rc == MLLM_OK) put(env, info, {li.load_ms, li.ctx_ms, static_cast<double>(li.n_layers), static_cast<double>(li.n_vocab)});
    return rc;
}

JNIEXPORT jstring JNICALL MLLM_JNI(deviceName)(JNIEnv * env, jobject, jlong h) {
    // mllm_load_info is only returned by mllm_load; the engine keeps the device it was created on.
    return env->NewStringUTF(h ? mllm_device_name(engine(h)) : "");
}

JNIEXPORT jint JNICALL MLLM_JNI(tokenCount)(JNIEnv * env, jobject, jlong h, jbyteArray utf8) {
    if (!h) return MLLM_E_ARG;
    return mllm_tokenize_count(engine(h), bytes(env, utf8).c_str());
}

JNIEXPORT jint JNICALL MLLM_JNI(prefixSet)(JNIEnv * env, jobject, jlong h, jbyteArray prefixUtf8, jdoubleArray stats) {
    if (!h) return MLLM_E_ARG;
    const std::string prefix = bytes(env, prefixUtf8);
    mllm_prefix_info pi {};
    const int rc = mllm_prefix_set(engine(h), prefix.c_str(), &pi);
    put(env, stats, {static_cast<double>(pi.n_tokens), pi.prefill_ms, pi.snapshot_ms, static_cast<double>(pi.snapshot_bytes)});
    return rc;
}

JNIEXPORT jint JNICALL MLLM_JNI(prefixSave)(JNIEnv * env, jobject, jlong h, jstring file) {
    if (!h) return MLLM_E_ARG;
    return mllm_prefix_save(engine(h), chars(env, file).c_str());
}

JNIEXPORT jint JNICALL MLLM_JNI(prefixLoad)(JNIEnv * env, jobject, jlong h, jstring file, jbyteArray prefixUtf8, jdoubleArray stats) {
    if (!h || !prefixUtf8) return MLLM_E_ARG;
    const std::string prefix = bytes(env, prefixUtf8);
    mllm_prefix_info pi {};
    const int rc = mllm_prefix_load(engine(h), chars(env, file).c_str(), prefix.c_str(), &pi);
    put(env, stats, {static_cast<double>(pi.n_tokens), pi.prefill_ms, pi.snapshot_ms, static_cast<double>(pi.snapshot_bytes)});
    return rc;
}

JNIEXPORT jint JNICALL MLLM_JNI(generate)(JNIEnv * env, jobject, jlong h, jbyteArray suffixUtf8, jint maxTokens, jfloat temperature,
                                          jfloat repeatPenalty, jobjectArray stopsUtf8, jobject sink, jdoubleArray stats) {
    if (!h || !sink) return MLLM_E_ARG;
    const std::string suffix = bytes(env, suffixUtf8);
    std::vector<std::string> stops;
    const jsize n_stop = stopsUtf8 ? env->GetArrayLength(stopsUtf8) : 0;
    for (jsize i = 0; i < n_stop; ++i) {
        auto * b = static_cast<jbyteArray>(env->GetObjectArrayElement(stopsUtf8, i));
        std::string s = bytes(env, b);
        env->DeleteLocalRef(b);
        if (!s.empty()) stops.push_back(std::move(s));
    }
    std::vector<const char *> stop_ptrs;
    for (const auto & s : stops) stop_ptrs.push_back(s.c_str());

    mllm_gen_params gp {};
    gp.max_tokens     = maxTokens;
    gp.temperature    = temperature;
    gp.repeat_penalty = repeatPenalty;
    gp.stop           = stop_ptrs.empty() ? nullptr : stop_ptrs.data();
    gp.n_stop         = static_cast<int32_t>(stop_ptrs.size());
    SinkCtx ctx {env, sink};
    mllm_gen_stats st {};
    const int rc = mllm_generate(engine(h), suffix.c_str(), &gp, on_piece, &ctx, &st);
    put(env, stats, {static_cast<double>(st.n_prompt), static_cast<double>(st.n_gen), st.restore_ms, st.prefill_ms,
                     st.ttft_ms, st.decode_ms, st.total_ms, static_cast<double>(st.stop_reason)});
    return rc;
}

JNIEXPORT void JNICALL MLLM_JNI(cancel)(JNIEnv *, jobject, jlong h) {
    if (h) mllm_cancel(engine(h));
}

JNIEXPORT void JNICALL MLLM_JNI(cancelClear)(JNIEnv *, jobject, jlong h) {
    if (h) mllm_cancel_clear(engine(h));
}

JNIEXPORT jint JNICALL MLLM_JNI(bench)(JNIEnv * env, jobject, jlong h, jint nPrompt, jint nGen, jint reps, jdoubleArray out) {
    if (!h) return MLLM_E_ARG;
    mllm_bench_result r {};
    const int rc = mllm_bench(engine(h), nPrompt, nGen, reps, &r);
    if (rc == MLLM_OK) put(env, out, {r.pp_tps, r.tg_tps, r.pp_ms, r.tg_ms});
    return rc;
}

JNIEXPORT jstring JNICALL MLLM_JNI(lastError)(JNIEnv * env, jobject, jlong h) {
    return env->NewStringUTF(h ? mllm_last_error(engine(h)) : "");
}

JNIEXPORT void JNICALL MLLM_JNI(unload)(JNIEnv *, jobject, jlong h) {
    if (h) mllm_unload(engine(h));
}

} // extern "C"
