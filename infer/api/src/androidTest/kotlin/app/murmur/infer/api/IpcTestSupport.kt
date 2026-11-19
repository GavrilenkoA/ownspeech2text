package app.murmur.infer.api

import android.content.Context
import android.content.ContextWrapper
import android.content.ServiceConnection
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import app.murmur.core.engine.LoadSpec
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.ModelId
import app.murmur.core.model.ModelKind
import app.murmur.infer.api.fake.FakeAsrService
import app.murmur.infer.api.fake.FakeLlmService
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/** Shared helpers of the IPC tests: shell pidof, fake clients, results logged under [TAG] for the report. */
object Ipc {
    const val TAG = "MurmurIpcTest"
    val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val ctx: Context get() = instrumentation.targetContext
    val asrProcess get() = "${ctx.packageName}:fakeasr"
    val llmProcess get() = "${ctx.packageName}:fakellm"

    fun shell(cmd: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(cmd))
            .bufferedReader().use { it.readText() }.trim()

    /** `pidof` as the shell sees it (the acceptance check), not our own bookkeeping. */
    fun pidof(name: String): Int? = shell("pidof $name").split(' ').firstOrNull()?.toIntOrNull()

    /** Polls [cond] every 10 ms; returns the elapsed ms when it held, or null after [timeoutMs]. */
    fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Long? {
        val t0 = SystemClock.elapsedRealtime()
        while (true) {
            if (cond()) return SystemClock.elapsedRealtime() - t0
            if (SystemClock.elapsedRealtime() - t0 > timeoutMs) return null
            Thread.sleep(10)
        }
    }

    fun report(key: String, value: Any) {
        Log.i(TAG, "RESULT $key=$value")
    }

    fun asr(timeouts: () -> BinderTimeouts = { BinderTimeouts() }, bind: () -> Context = { ctx }) =
        AsrClient(bind, timeouts, FakeAsrService::class.java.name)

    fun llm(timeouts: () -> BinderTimeouts = { BinderTimeouts() }, bind: () -> Context = { ctx }) =
        LlmClient(bind, timeouts, serviceClassName = FakeLlmService::class.java.name)

    fun spec(kind: ModelKind, unit: ComputeUnit = ComputeUnit.CPU) = LoadSpec(
        kind = kind, modelId = ModelId("fake-${kind.name.lowercase()}"), artifactId = ArtifactId("fake@cpu"),
        runtime = "fake", unit = unit, dir = ctx.filesDir.absolutePath, layout = mapOf("model" to "model.bin"),
    )

    /** Kills whatever a failed test left running in the fake processes. */
    fun killFakes() {
        listOfNotNull(pidof(asrProcess), pidof(llmProcess)).forEach { android.os.Process.killProcess(it) }
        waitUntil(2_000) { pidof(asrProcess) == null && pidof(llmProcess) == null }
    }
}

/** A bind context that records which context bound and unbound (the client must unbind on the context it bound). */
class CountingContext(base: Context) : ContextWrapper(base) {
    val binds = AtomicInteger()
    val unbinds = AtomicInteger()

    override fun bindService(service: Intent, flags: Int, executor: Executor, conn: ServiceConnection): Boolean {
        binds.incrementAndGet()
        return super.bindService(service, flags, executor, conn)
    }

    override fun unbindService(conn: ServiceConnection) {
        unbinds.incrementAndGet()
        super.unbindService(conn)
    }
}
