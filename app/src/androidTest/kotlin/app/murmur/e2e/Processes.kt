package app.murmur.e2e

/**
 * The inference processes (ARCHITECTURE 4.1). The instrumentation runs as the app's uid, so it may SIGKILL its own
 * :asr / :llm processes exactly like `kill -9 $(pidof app.murmur:asr)` from a root shell would (the shell user may
 * not signal app processes).
 */
object Processes {
    const val ASR = "${E2e.PKG}:asr"
    const val LLM = "${E2e.PKG}:llm"

    /** `pidof <name>`; null when the process does not run. */
    fun pidOf(name: String): Int? = E2e.shell("pidof $name").trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull()

    /** SIGKILL (no cleanup, no unbind: a native crash as the binder client sees it). Returns the killed pid. */
    fun kill9(name: String, timeoutMs: Long = 10_000): Int {
        val pid = E2e.waitFor("$name running", timeoutMs, pollMs = 100) { pidOf(name) }
        android.os.Process.sendSignal(pid, android.os.Process.SIGNAL_KILL)
        E2e.waitUntil("$name (pid $pid) gone", 5_000, pollMs = 50) { pidOf(name) != pid }
        E2e.note("killed $name pid=$pid")
        return pid
    }

    fun awaitGone(name: String, timeoutMs: Long) =
        E2e.waitUntil("$name exited (pid ${pidOf(name)})", timeoutMs, pollMs = 500) { pidOf(name) == null }
}
