// Bound service of the :llm process (llama.cpp b12520 + ggml-hexagon, alone in its process, ADR-005/009).
package app.murmur.infer.api;

import app.murmur.infer.api.ILlmStreamCallback;
import app.murmur.infer.api.IResultCallback;

interface ILlmService {
    int apiVersion();
    String probe();
    String memory();
    /** LoadSpec JSON -> LoadInfo JSON. */
    void load(String specJson, IResultCallback cb);
    /** PrefixSpec JSON -> PrefixInfo JSON (restores the persisted snapshot when the cache key matches). */
    void setPrefix(String prefixSpecJson, IResultCallback cb);
    /** LlmRequest JSON. One generation at a time; a second call while busy gets onError(Internal). */
    void generate(String requestJson, ILlmStreamCallback cb);
    /** Synthetic benchmark (mllm_bench): pp of nPrompt random tokens, tg of nGen tokens, median of reps -> SyntheticBench JSON. */
    void bench(int nPrompt, int nGen, int reps, IResultCallback cb);
    /** Any thread; never races unload (engine executor serializes them). */
    void cancel();
    void unload();
}
