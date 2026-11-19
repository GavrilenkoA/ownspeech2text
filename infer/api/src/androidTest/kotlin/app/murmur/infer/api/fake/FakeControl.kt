package app.murmur.infer.api.fake

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import app.murmur.core.engine.ProcessMemory
import java.io.File
import java.util.Properties

/**
 * How a test steers the fake services in their own processes: a properties file per fake in the shared files dir,
 * written by the test before the call and read by the fake at every call. Keeps the AIDL payloads honest (no test
 * switches smuggled into LoadSpec or AsrSessionOptions).
 */
object FakeControl {
    const val ASR = "asr"
    const val LLM = "llm"

    private fun dir(ctx: Context) = File(ctx.filesDir, "fake-control")
    private fun file(ctx: Context, fake: String) = File(dir(ctx), "$fake.properties")

    fun set(ctx: Context, fake: String, vararg values: Pair<String, Any>) {
        val props = read(ctx, fake)
        values.forEach { (k, v) -> props.setProperty(k, v.toString()) }
        dir(ctx).mkdirs()
        val tmp = File(dir(ctx), "$fake.tmp")
        tmp.outputStream().use { props.store(it, null) }
        check(tmp.renameTo(file(ctx, fake)))
    }

    fun clear(ctx: Context) {
        dir(ctx).deleteRecursively()
    }

    fun get(ctx: Context, fake: String, key: String): String? = read(ctx, fake).getProperty(key)
    fun long(ctx: Context, fake: String, key: String): Long? = get(ctx, fake, key)?.toLongOrNull()

    private fun read(ctx: Context, fake: String): Properties = Properties().apply {
        val f = file(ctx, fake)
        if (f.exists()) f.inputStream().use { load(it) }
    }

    /** The real memory numbers of the calling process (the fakes answer memory() honestly). */
    fun memory(ctx: Context): ProcessMemory {
        val status = runCatching { File("/proc/self/status").readLines() }.getOrDefault(emptyList())
        fun kb(key: String) = status.firstOrNull { it.startsWith("$key:") }
            ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull() ?: 0L
        val info = ActivityManager.MemoryInfo()
        ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return ProcessMemory(kb("VmHWM"), kb("VmRSS"), Debug.getPss(), info.availMem / (1024 * 1024))
    }
}
