// Toolchain smoke test: proves NDK + CMake + JNI linkage for every ABI we ship.
#include <jni.h>
#include <string>

#if defined(__aarch64__)
#define MURMUR_ABI "arm64-v8a"
#elif defined(__x86_64__)
#define MURMUR_ABI "x86_64"
#else
#define MURMUR_ABI "unknown"
#endif

extern "C" JNIEXPORT jstring JNICALL
Java_app_murmur_NativeBridge_hello(JNIEnv* env, jobject /* this */) {
    std::string msg = std::string("native ok (") + MURMUR_ABI + ", clang " + __clang_version__ + ")";
    return env->NewStringUTF(msg.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_app_murmur_NativeBridge_add(JNIEnv* /* env */, jobject /* this */, jint a, jint b) {
    return a + b;
}
