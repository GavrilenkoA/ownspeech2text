package app.murmur.spike

import android.app.ActivityManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import android.os.SharedMemory
import java.nio.ByteBuffer

/**
 * Stand-in for the :infer process. LOAD allocates and touches N MB of native (direct) memory,
 * like a model runtime would. On last unbind the process frees everything and kills itself:
 * that is the guarantee that RAM goes back to the system.
 */
class InferService : Service() {
    /** Native (non-Java-heap) memory, like a model runtime: SharedMemory = ashmem/memfd, mapped RW. */
    private val held = ArrayList<Pair<SharedMemory, ByteBuffer>>()

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val reply = Bundle()
            when (msg.what) {
                MSG_LOAD -> {
                    val mb = msg.arg1
                    val t0 = SystemClock.uptimeMillis()
                    repeat(mb / 16) {
                        val shm = SharedMemory.create("w", 16 shl 20); val b = shm.mapReadWrite()
                        var i = 0; while (i < b.capacity()) { b.put(i, 1); i += 4096 }
                        held += shm to b
                    }
                    reply.putLong("loadMs", SystemClock.uptimeMillis() - t0)
                }
                MSG_RECORD_PROBE -> {
                    // Negative control: a process that is NOT the accessibility service records.
                    val r = Recorder("infer-process")
                    val ok = r.start(SystemClock.uptimeMillis())
                    postDelayed({ val res = r.stop(); Ev.log("infer_record_probe", "started" to ok, "result" to res.toString()) }, 1500)
                }
                MSG_CRASH -> throw IllegalStateException("spike: simulated runtime crash in :infer")
                MSG_NATIVE_CRASH -> Process.sendSignal(Process.myPid(), 11) // SIGSEGV, like a crashing NPU runtime
            }
            reply.putInt("pid", Process.myPid())
            reply.putLong("pssKb", pssKb(this@InferService))
            msg.replyTo?.send(Message.obtain(null, msg.what).also { it.data = reply })
        }
    }
    private val messenger = Messenger(handler)

    override fun onCreate() {
        super.onCreate()
        Ev.log("infer_created", "process" to "infer")
        // Java crash in :infer -> log and exit quietly (no "keeps stopping" dialog); the client sees onServiceDisconnected.
        if (QUIET_CRASH) Thread.setDefaultUncaughtExceptionHandler { _, e ->
            Ev.log("infer_uncaught", "err" to e.toString()); Process.killProcess(Process.myPid()) }
    }
    override fun onBind(intent: Intent?): IBinder { Ev.log("infer_bound"); return messenger.binder }

    override fun onUnbind(intent: Intent?): Boolean {
        Ev.log("infer_unbound_exiting", "heldMb" to held.size * 16, "pssKb" to pssKb(this))
        held.forEach { (shm, b) -> SharedMemory.unmap(b); shm.close() }; held.clear()
        // Real runtime: close sessions, mallopt(M_PURGE_ALL) via JNI, then exit.
        Process.killProcess(Process.myPid())
        return false
    }

    companion object {
        const val MSG_LOAD = 1
        const val MSG_RECORD_PROBE = 2
        const val MSG_CRASH = 3
        const val MSG_NATIVE_CRASH = 4
        const val QUIET_CRASH = true
        fun pssKb(c: Context): Long =
            c.getSystemService(ActivityManager::class.java)
                .getProcessMemoryInfo(intArrayOf(Process.myPid()))[0].totalPss.toLong()
    }
}

/** Client side, lives in the accessibility-service process. Binds on demand, unbinds on idle. */
class InferClient(private val ctx: Context) {
    private var service: Messenger? = null
    private var bound = false
    private val main = Handler(Looper.getMainLooper())
    var idleMs = 10_000L
    private val replies = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            Ev.log("infer_reply", "what" to msg.what, "inferPid" to msg.data.getInt("pid"),
                "pssKb" to msg.data.getLong("pssKb"), "loadMs" to msg.data.getLong("loadMs"))
        }
    })
    private val pending = ArrayList<Message>()

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            service = Messenger(b); Ev.log("infer_connected")
            pending.forEach { send(it) }; pending.clear()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null; Ev.log("infer_disconnected") }
        override fun onBindingDied(name: ComponentName?) { Ev.log("infer_binding_died") }
    }

    private val idle = Runnable { unbind("idle") }

    fun request(what: Int, arg1: Int = 0) {
        main.removeCallbacks(idle)
        val m = Message.obtain(null, what, arg1, 0).also { it.replyTo = replies }
        if (!bound) {
            val t0 = SystemClock.uptimeMillis()
            bound = ctx.bindService(Intent(ctx, InferService::class.java), conn,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
            Ev.log("infer_bind", "ok" to bound, "ms" to SystemClock.uptimeMillis() - t0)
        }
        if (service == null) pending += m else send(m)
        main.postDelayed(idle, idleMs)
    }

    private fun send(m: Message) = runCatching { service?.send(m) }.onFailure { Ev.log("infer_send_failed", "err" to it.toString()) }

    fun unbind(reason: String) {
        if (!bound) return
        main.removeCallbacks(idle)
        // runCatching: the binding is gone if the owning service context was destroyed meanwhile.
        runCatching { ctx.unbindService(conn) }.onFailure { Ev.log("infer_unbind_failed", "err" to it.toString()) }
        bound = false; service = null
        Ev.log("infer_unbind", "reason" to reason)
    }
}
