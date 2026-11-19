package app.murmur.infer.api

import android.os.Process
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Runs right before an inference process exits on purpose. JNI-free on this side: the native packages implement it
 * with their own JNI call (`mallopt(M_PURGE_ALL)`, closing FastRPC / QNN sessions) and register it with
 * [InferProcessLifecycle.addBeforeExit]. Must not block for long: the process is killed right after.
 */
fun interface BeforeExitHook {
    fun beforeExit()
}

/**
 * Shared lifecycle rules of the inference services (ARCHITECTURE.md 4.2), used by :infer:asr and :infer:llm:
 *  - `onUnbind` -> [unloadAndExit]: unload every runtime -> [BeforeExitHook]s (native mallopt) -> killProcess(myPid).
 *    The kill is the guarantee that nothing stays in RAM (D5) and the only way to drop FastRPC/QNN contexts.
 *  - [installQuietCrashHandler]: an uncaught exception kills the process quietly (no crash dialog, platform spike f);
 *    :main sees the death and re-binds. Only the exception class and stack frames are logged, never its message
 *    (a message can carry user text, D9).
 *  - Native crashes (SIGSEGV) kill the process by themselves; :main sees onServiceDisconnected.
 */
object InferProcessLifecycle {
    private val hooks = CopyOnWriteArrayList<BeforeExitHook>()

    fun addBeforeExit(hook: BeforeExitHook) {
        hooks.addIfAbsent(hook)
    }

    fun removeBeforeExit(hook: BeforeExitHook) {
        hooks.remove(hook)
    }

    fun installQuietCrashHandler(tag: String) {
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { Log.e(tag, "fatal in inference process on ${thread.name}: ${describe(e)}") }
            Process.killProcess(Process.myPid())
        }
    }

    /** The service's `onUnbind`: [unload] (errors ignored), then [exitNow]. */
    fun unloadAndExit(unload: () -> Unit) {
        runCatching(unload)
        exitNow()
    }

    /** Runs the [BeforeExitHook]s (each failure ignored), then kills this process. */
    fun exitNow() {
        hooks.forEach { runCatching { it.beforeExit() } }
        Process.killProcess(Process.myPid())
    }

    /** Exception class names and frames of the whole cause chain; messages are left out on purpose. */
    fun describe(e: Throwable): String = buildString {
        var cur: Throwable? = e
        var depth = 0
        while (cur != null && depth < MAX_CAUSES) {
            if (depth > 0) append(" | caused by ")
            append(cur.javaClass.name)
            cur.stackTrace.take(MAX_FRAMES).forEach { append("\n  at ").append(it.toString()) }
            cur = cur.cause.takeIf { it !== cur }
            depth++
        }
    }

    private const val MAX_FRAMES = 12
    private const val MAX_CAUSES = 4
}
