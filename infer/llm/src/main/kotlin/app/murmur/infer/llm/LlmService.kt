package app.murmur.infer.llm

import android.app.Service
import android.content.Intent
import android.os.IBinder
import app.murmur.core.engine.ProcessProbe
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.InferProcess
import app.murmur.infer.api.ILlmService
import app.murmur.infer.api.ILlmStreamCallback
import app.murmur.infer.api.IResultCallback
import app.murmur.infer.api.InferApi
import app.murmur.infer.api.InferProcessLifecycle

/**
 * The :llm process (WP-10). One single-thread engine executor owns the llama context; cancel() is the only
 * cross-thread call. Unload = process exit (only way to drop the FastRPC session + rpcmem, llm spike D7).
 */
class LlmService : Service() {
    override fun onCreate() {
        super.onCreate()
        InferProcessLifecycle.installQuietCrashHandler("murmur-llm")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        runCatching { binder.unload() }
        InferProcessLifecycle.exitNow()
        return false
    }

    private val binder = object : ILlmService.Stub() {
        override fun apiVersion(): Int = InferApi.API_VERSION
        override fun probe(): String = InferApi.encode(
            ProcessProbe.serializer(),
            ProcessProbe(InferProcess.LLM, runtimes = emptySet(), units = setOf(ComputeUnit.CPU),
                facts = mapOf("skeleton" to "true", "jni" to LlmNative.ensureLoaded().toString())),
        )
        override fun memory(): String = TODO("WP-10")
        override fun load(specJson: String?, cb: IResultCallback?) = TODO("WP-10: mllm_global_init once, mllm_load (use_mmap=0 on CPU)")
        override fun setPrefix(prefixSpecJson: String?, cb: IResultCallback?) = TODO("WP-10: prefixLoad(file) or prefixSet + prefixSave")
        override fun generate(requestJson: String?, cb: ILlmStreamCallback?) = TODO("WP-10")
        override fun bench(nPrompt: Int, nGen: Int, reps: Int, cb: IResultCallback?) = TODO("WP-10: LlmNative.bench")
        override fun cancel() { /* WP-10: LlmNative.cancel(handle) under the handle lock */ }
        override fun unload() { /* WP-10: submit unload to the engine executor */ }
    }
}
