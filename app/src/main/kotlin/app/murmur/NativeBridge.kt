package app.murmur

/** Thin JNI facade over libmurmur_native.so (toolchain smoke test only). */
object NativeBridge {
    init {
        System.loadLibrary("murmur_native")
    }

    external fun hello(): String

    external fun add(a: Int, b: Int): Int
}
