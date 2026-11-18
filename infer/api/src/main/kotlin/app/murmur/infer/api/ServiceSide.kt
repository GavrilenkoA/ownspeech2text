package app.murmur.infer.api

import android.os.Process

/**
 * Shared lifecycle rules of the inference services (ARCHITECTURE.md 4.2), used by :infer:asr and :infer:llm:
 *  - onUnbind -> unload every runtime -> (native) mallopt(M_PURGE_ALL) -> Process.killProcess(myPid()).
 *    The kill is the guarantee that nothing stays in RAM (D5) and the only way to drop FastRPC/QNN contexts.
 *  - A default UncaughtExceptionHandler that kills the process quietly (no crash dialog, platform spike f).
 *  - Native crashes (SIGSEGV) kill the process by themselves; :main sees onServiceDisconnected.
 */
object InferProcessLifecycle {
    fun installQuietCrashHandler(tag: String) {
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            android.util.Log.e(tag, "fatal in inference process", e)
            Process.killProcess(Process.myPid())
        }
    }

    fun exitNow() = Process.killProcess(Process.myPid())
}
