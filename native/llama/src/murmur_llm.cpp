// murmur_llm.cpp - implementation of include/murmur_llm.h on top of llama.cpp (new llama_batch_ext /
// llama_process API; llama_decode/llama_batch_get_one are deprecated at the pinned commit).
#include "murmur_llm.h"

#include "ggml-backend.h"
#include "llama.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#ifdef __ANDROID__
#include <android/log.h>
#endif

namespace {

using clk = std::chrono::steady_clock;
double ms_since(clk::time_point t0) {
    return std::chrono::duration<double, std::milli>(clk::now() - t0).count();
}

int g_verbose = 0;

// What the backend loader picked, captured from ggml's log during mllm_global_init (no API exposes it).
std::mutex  g_info_mu;
std::string g_cpu_backend;   // file name of the CPU variant, e.g. "libggml-cpu-haswell.so"
std::string g_htp_arch;      // e.g. "v79"

void capture_runtime_info(const char * text) {
    static const char kCpu[] = "loaded CPU backend from ";
    static const char kArch[] = "Hexagon Arch version ";
    if (const char * p = strstr(text, kCpu)) {
        std::string path(p + sizeof(kCpu) - 1);
        while (!path.empty() && (path.back() == '\n' || path.back() == '\r')) path.pop_back();
        const size_t slash = path.find_last_of('/');
        std::lock_guard<std::mutex> lk(g_info_mu);
        g_cpu_backend = slash == std::string::npos ? path : path.substr(slash + 1);
    } else if (const char * a = strstr(text, kArch)) {
        std::string arch(a + sizeof(kArch) - 1);
        const size_t end = arch.find_first_of(", \n");
        std::lock_guard<std::mutex> lk(g_info_mu);
        g_htp_arch = arch.substr(0, end);
    }
}

void log_cb(ggml_log_level level, const char * text, void *) {
    if (level == GGML_LOG_LEVEL_INFO) capture_runtime_info(text);
    if (level == GGML_LOG_LEVEL_DEBUG && g_verbose < 2) return;
    if ((level == GGML_LOG_LEVEL_INFO || level == GGML_LOG_LEVEL_CONT) && g_verbose < 1) return;
#ifdef __ANDROID__
    int prio = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
             : level == GGML_LOG_LEVEL_WARN  ? ANDROID_LOG_WARN : ANDROID_LOG_INFO;
    __android_log_write(prio, "murmur_llm", text);
#endif
    fputs(text, stderr);
}

// Number of bytes at the end of s that form an incomplete UTF-8 sequence.
size_t utf8_incomplete_tail(const std::string & s) {
    size_t n = s.size();
    for (size_t k = 1; k <= 4 && k <= n; ++k) {
        unsigned char c = (unsigned char) s[n - k];
        if ((c & 0xC0) == 0x80) continue;           // continuation byte
        size_t need = (c & 0x80) == 0 ? 1 : (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : (c & 0xF8) == 0xF0 ? 4 : 1;
        return need > k ? k : 0;
    }
    return 0;
}

} // namespace

struct mllm_engine {
    llama_model *    model = nullptr;
    llama_context *  ctx   = nullptr;
    const llama_vocab * vocab = nullptr;
    llama_batch_ext * batch = nullptr;
    mllm_load_params lp {};
    std::string      device_name = "CPU";
    std::atomic<bool> cancel {false};
    std::string      err;

    // prefix cache
    std::vector<llama_token> prefix_tokens;
    std::vector<uint8_t>     snapshot;      // RAM / DEVICE (device: small header, data stays in backend buffers)
    bool prefix_live = false;               // SEQCP: seq 0 currently holds the prefix

    int fail(int code, const std::string & msg) {
        err = msg;
        log_cb(GGML_LOG_LEVEL_ERROR, ("murmur_llm: " + msg + "\n").c_str(), nullptr);
        return code;
    }
};

static bool abort_cb(void * data) {
    return static_cast<mllm_engine *>(data)->cancel.load(std::memory_order_relaxed);
}

extern "C" int mllm_global_init(const mllm_init_params * p) {
    static std::once_flag once;
    static int rc = MLLM_OK;
    std::call_once(once, [&] {
        mllm_init_params d {};
        if (!p) p = &d;
        g_verbose = p->verbose;
        // These are read when the backend registry initialises, i.e. before the first dlopen.
        if (p->htp_lib_dir && *p->htp_lib_dir) setenv("GGML_HEXAGON_LIBHTP_DIR", p->htp_lib_dir, 1);
        if (p->hex_power_mode && *p->hex_power_mode) setenv("GGML_HEXAGON_POWER_MODE", p->hex_power_mode, 1);
        if (p->verbose >= 2) setenv("GGML_HEXAGON_VERBOSE", "1", 0);
        llama_log_set(log_cb, nullptr);
        if (p->backend_dir && *p->backend_dir) {
            ggml_backend_load_all_from_path(p->backend_dir);
        } else {
            ggml_backend_load_all();
        }
        llama_backend_init();
        if (ggml_backend_dev_count() == 0) rc = MLLM_E_DEVICE;
    });
    return rc;
}

extern "C" const char * mllm_build_info(void) {
    static const std::string s = std::string("llama.cpp ") + MURMUR_LLAMA_TAG + " " + std::string(MURMUR_LLAMA_COMMIT).substr(0, 12)
                               + " ggml-hexagon " + MURMUR_BUILD_VARIANT;
    return s.c_str();
}

extern "C" int32_t mllm_runtime_info(char * buf, int32_t buf_len) {
    std::string s;
    {
        std::lock_guard<std::mutex> lk(g_info_mu);
        if (!g_cpu_backend.empty()) s += "cpu_backend=" + g_cpu_backend + ";";
        if (!g_htp_arch.empty())    s += "htp_arch=" + g_htp_arch + ";";
    }
    if (buf && buf_len > 0) snprintf(buf, (size_t) buf_len, "%s", s.c_str());
    return (int32_t) s.size();
}

extern "C" int32_t mllm_devices(char * buf, int32_t buf_len) {
    std::string s;
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t d = ggml_backend_dev_get(i);
        const char * t = ggml_backend_dev_type(d) == GGML_BACKEND_DEVICE_TYPE_CPU ? "cpu"
                       : ggml_backend_dev_type(d) == GGML_BACKEND_DEVICE_TYPE_GPU ? "gpu" : "other";
        s += std::string(ggml_backend_dev_name(d)) + "|" + ggml_backend_dev_description(d) + "|" + t + ";";
    }
    if (buf && buf_len > 0) {
        snprintf(buf, (size_t) buf_len, "%s", s.c_str());
    }
    return (int32_t) s.size();
}

static ggml_backend_dev_t find_device(mllm_device dev) {
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t d = ggml_backend_dev_get(i);
        const std::string name = ggml_backend_dev_name(d);
        const bool is_htp = name.rfind("HTP", 0) == 0;
        if (dev == MLLM_DEV_NPU && is_htp) return d;
        if (dev == MLLM_DEV_GPU && !is_htp && ggml_backend_dev_type(d) == GGML_BACKEND_DEVICE_TYPE_GPU) return d;
    }
    return nullptr;
}

extern "C" int mllm_load(const mllm_load_params * p, mllm_engine ** out, mllm_load_info * info) {
    if (!p || !p->model_path || !out || p->n_ctx <= 0) return MLLM_E_ARG;   // n_ctx comes from the catalog (ADR-045)
    if (p->kv_type != MLLM_KV_F16 && p->kv_type != MLLM_KV_Q8_0) return MLLM_E_ARG;
    mllm_global_init(nullptr);
    auto * e = new mllm_engine();
    e->lp = *p;
    if (e->lp.n_batch <= 0)         e->lp.n_batch = 512;
    if (e->lp.n_threads <= 0)       e->lp.n_threads = 4;
    if (e->lp.n_threads_batch <= 0) e->lp.n_threads_batch = e->lp.n_threads;

    ggml_backend_dev_t devs[2] = {nullptr, nullptr};   // read only during llama_model_load_from_file
    llama_model_params mp = llama_model_default_params();
    ggml_backend_dev_t dev = nullptr;
    if (p->device != MLLM_DEV_CPU) {
        dev = find_device(p->device);
        if (!dev) { int rc = e->fail(MLLM_E_DEVICE, "requested device not registered"); *out = nullptr; delete e; return rc; }
    }
    devs[0] = dev;                         // {nullptr} => CPU only, no host-op offload
    mp.devices      = devs;
    mp.n_gpu_layers = dev ? -1 : 0;
    mp.load_mode    = (p->device == MLLM_DEV_CPU && p->use_mmap) ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;

    auto t0 = clk::now();
    e->model = llama_model_load_from_file(p->model_path, mp);
    double load_ms = ms_since(t0);
    if (!e->model) { int rc = e->fail(MLLM_E_LOAD, "llama_model_load_from_file failed"); *out = nullptr; delete e; return rc; }
    e->vocab = llama_model_get_vocab(e->model);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = (uint32_t) e->lp.n_ctx;
    cp.n_batch         = (uint32_t) e->lp.n_batch;
    cp.n_ubatch        = (uint32_t) e->lp.n_batch;
    cp.n_seq_max       = e->lp.prefix_mode == MLLM_PREFIX_SEQCP ? 2 : 1;
    // Every batch we submit asks for logits of its last token only. The default (0 = n_batch) makes the scheduler
    // reserve worst-case logits for all n_batch tokens: 248k vocab x 512 x 4 B = 0.5 GB for Qwen3.5.
    cp.n_outputs_max   = cp.n_seq_max;
    cp.kv_unified      = true;               // seq_cp shares KV cells instead of copying across streams
    cp.type_k          = e->lp.kv_type == MLLM_KV_Q8_0 ? GGML_TYPE_Q8_0 : GGML_TYPE_F16;
    cp.type_v          = cp.type_k;
    cp.n_threads       = e->lp.n_threads;
    cp.n_threads_batch = e->lp.n_threads_batch;
    cp.no_perf         = false;
    cp.abort_callback      = abort_cb;
    cp.abort_callback_data = e;
    t0 = clk::now();
    e->ctx = llama_init_from_model(e->model, cp);
    double ctx_ms = ms_since(t0);
    if (!e->ctx) { int rc = e->fail(MLLM_E_CTX, "llama_init_from_model failed"); mllm_unload(e); *out = nullptr; return rc; }
    e->batch = llama_batch_ext_init(e->ctx);
    e->device_name = dev ? ggml_backend_dev_name(dev) : "CPU";

    if (info) {
        memset(info, 0, sizeof(*info));
        info->load_ms  = load_ms;
        info->ctx_ms   = ctx_ms;
        info->n_layers = llama_model_n_layer(e->model);
        info->n_vocab  = llama_vocab_n_tokens(e->vocab);
        llama_model_meta_val_str(e->model, "general.architecture", info->arch, sizeof(info->arch));
        snprintf(info->device_name, sizeof(info->device_name), "%s", dev ? ggml_backend_dev_name(dev) : "CPU");
    }
    *out = e;
    return MLLM_OK;
}

static std::vector<llama_token> tokenize(const llama_vocab * vocab, const char * text) {
    const int32_t len = (int32_t) strlen(text);
    int32_t n = -llama_tokenize(vocab, text, len, nullptr, 0, /*add_special*/ false, /*parse_special*/ true);
    std::vector<llama_token> toks((size_t) std::max(n, 0));
    if (n > 0) llama_tokenize(vocab, text, len, toks.data(), n, false, true);
    return toks;
}

extern "C" int32_t mllm_tokenize_count(mllm_engine * e, const char * text) {
    return e && text ? (int32_t) tokenize(e->vocab, text).size() : MLLM_E_ARG;
}

// Prefill toks at positions [pos0, pos0+n) into seq; logits only for the last token.
static int prefill(mllm_engine * e, const std::vector<llama_token> & toks, llama_pos pos0, llama_seq_id seq) {
    const size_t nb = (size_t) e->lp.n_batch;
    for (size_t i = 0; i < toks.size(); i += nb) {
        if (e->cancel.load()) return MLLM_E_CANCELLED;
        const size_t n = std::min(nb, toks.size() - i);
        llama_batch_ext_clear(e->batch);
        for (size_t j = 0; j < n; ++j) {
            const int32_t idx = llama_batch_ext_add_token(e->batch, seq, toks[i + j]);
            const llama_pos pos = pos0 + (llama_pos) (i + j);
            llama_batch_ext_set_pos(e->batch, idx, &pos);
        }
        if (i + n == toks.size()) llama_batch_ext_set_output_logits(e->batch, (int32_t) n - 1, true);
        const int32_t rc = llama_process(e->ctx, LLAMA_PROCESS_TYPE_DECODE, e->batch);
        if (rc == 2) return MLLM_E_CANCELLED;
        if (rc != 0) return e->fail(MLLM_E_DECODE, "llama_process(prefill) returned " + std::to_string(rc));
    }
    return MLLM_OK;
}

static int take_snapshot(mllm_engine * e, mllm_prefix_info * info) {
    auto t0 = clk::now();
    e->snapshot.clear();
    if (e->lp.prefix_mode == MLLM_PREFIX_RAM || e->lp.prefix_mode == MLLM_PREFIX_DEVICE) {
        const llama_state_seq_flags fl = e->lp.prefix_mode == MLLM_PREFIX_DEVICE ? LLAMA_STATE_SEQ_FLAGS_ON_DEVICE : 0;
        const size_t sz = llama_state_seq_get_size_ext(e->ctx, 0, fl);
        e->snapshot.resize(sz);
        const size_t got = llama_state_seq_get_data_ext(e->ctx, e->snapshot.data(), sz, 0, fl);
        if (got == 0) return e->fail(MLLM_E_STATE, "llama_state_seq_get_data_ext failed");
        e->snapshot.resize(got);
    }
    e->prefix_live = true;
    if (info) {
        info->snapshot_ms    = ms_since(t0);
        info->snapshot_bytes = e->snapshot.size();
    }
    return MLLM_OK;
}

extern "C" int mllm_prefix_set(mllm_engine * e, const char * prefix, mllm_prefix_info * info) {
    if (!e || !prefix) return MLLM_E_ARG;
    if (info) memset(info, 0, sizeof(*info));
    e->prefix_tokens = tokenize(e->vocab, prefix);
    e->snapshot.clear();
    e->prefix_live = false;
    if (e->lp.prefix_mode == MLLM_PREFIX_NONE) {
        if (info) info->n_tokens = (int32_t) e->prefix_tokens.size();
        return MLLM_OK;                       // baseline: prefix is re-prefilled on every generate
    }
    llama_memory_clear(llama_get_memory(e->ctx), true);
    auto t0 = clk::now();
    int rc = prefill(e, e->prefix_tokens, 0, 0);
    if (rc != MLLM_OK) return rc;
    if (info) {
        info->n_tokens   = (int32_t) e->prefix_tokens.size();
        info->prefill_ms = ms_since(t0);
    }
    return take_snapshot(e, info);
}

extern "C" int mllm_prefix_save(mllm_engine * e, const char * path) {
    if (!e || !path || e->prefix_tokens.empty()) return MLLM_E_ARG;
    // Make seq 0 hold exactly the prefix, then write it with the token list for validation on load.
    llama_memory_t mem = llama_get_memory(e->ctx);
    if (e->lp.prefix_mode == MLLM_PREFIX_RAM || e->lp.prefix_mode == MLLM_PREFIX_DEVICE) {
        const llama_state_seq_flags fl = e->lp.prefix_mode == MLLM_PREFIX_DEVICE ? LLAMA_STATE_SEQ_FLAGS_ON_DEVICE : 0;
        llama_memory_seq_rm(mem, 0, -1, -1);
        if (!llama_state_seq_set_data_ext(e->ctx, e->snapshot.data(), e->snapshot.size(), 0, fl))
            return e->fail(MLLM_E_STATE, "restore before save failed");
    }
    const size_t n = llama_state_seq_save_file(e->ctx, path, 0, e->prefix_tokens.data(), e->prefix_tokens.size());
    return n ? MLLM_OK : e->fail(MLLM_E_STATE, "llama_state_seq_save_file failed");
}

extern "C" int mllm_prefix_load(mllm_engine * e, const char * path, mllm_prefix_info * info) {
    if (!e || !path) return MLLM_E_ARG;
    if (info) memset(info, 0, sizeof(*info));
    llama_memory_clear(llama_get_memory(e->ctx), true);
    std::vector<llama_token> toks((size_t) e->lp.n_ctx);
    size_t n_tok = 0;
    auto t0 = clk::now();
    if (!llama_state_seq_load_file(e->ctx, path, 0, toks.data(), toks.size(), &n_tok))
        return e->fail(MLLM_E_STATE, "llama_state_seq_load_file failed");
    toks.resize(n_tok);
    e->prefix_tokens = toks;
    if (info) { info->n_tokens = (int32_t) n_tok; info->prefill_ms = ms_since(t0); }
    return take_snapshot(e, info);
}

// Restore prefix into the working sequence; returns seq id to generate on, or < 0 on error.
static int restore_prefix(mllm_engine * e, llama_pos * n_past) {
    llama_memory_t mem = llama_get_memory(e->ctx);
    *n_past = 0;
    if (e->prefix_tokens.empty()) { llama_memory_clear(mem, true); return 0; }
    switch (e->lp.prefix_mode) {
        case MLLM_PREFIX_NONE:
            llama_memory_clear(mem, true);
            return 0;                          // caller prefills prefix + suffix
        case MLLM_PREFIX_RAM:
        case MLLM_PREFIX_DEVICE: {
            const llama_state_seq_flags fl = e->lp.prefix_mode == MLLM_PREFIX_DEVICE ? LLAMA_STATE_SEQ_FLAGS_ON_DEVICE : 0;
            llama_memory_seq_rm(mem, 0, -1, -1);
            if (!llama_state_seq_set_data_ext(e->ctx, e->snapshot.data(), e->snapshot.size(), 0, fl))
                return e->fail(MLLM_E_STATE, "llama_state_seq_set_data_ext failed");
            *n_past = (llama_pos) e->prefix_tokens.size();
            return 0;
        }
        case MLLM_PREFIX_SEQCP:
            if (!e->prefix_live) {             // invalidated (bench) -> rebuild seq 0
                llama_memory_clear(mem, true);
                int rc = prefill(e, e->prefix_tokens, 0, 0);
                if (rc != MLLM_OK) return rc;
                e->prefix_live = true;
            }
            llama_memory_seq_rm(mem, 1, -1, -1);
            llama_memory_seq_cp(mem, 0, 1, -1, -1);
            *n_past = (llama_pos) e->prefix_tokens.size();
            return 1;
    }
    return MLLM_E_ARG;
}

extern "C" int mllm_generate(mllm_engine * e, const char * suffix, const mllm_gen_params * gp,
                             mllm_token_cb cb, void * user, mllm_gen_stats * st) {
    if (!e || !suffix || !gp) return MLLM_E_ARG;
    mllm_gen_stats s {};
    const auto t_start = clk::now();
    // cancelled before the first token (restore or prefill): report it like a decode-time cancel
    auto cancelled_early = [&]() {
        s.stop_reason = 3;
        s.total_ms = ms_since(t_start);
        if (st) *st = s;
        return MLLM_E_CANCELLED;
    };
    if (e->cancel.load()) return cancelled_early();

    llama_pos n_past = 0;
    auto t0 = clk::now();
    const int seq = restore_prefix(e, &n_past);
    if (seq == MLLM_E_CANCELLED) return cancelled_early();
    if (seq < 0) return seq;
    s.restore_ms = ms_since(t0);

    std::vector<llama_token> toks = tokenize(e->vocab, suffix);
    if (e->lp.prefix_mode == MLLM_PREFIX_NONE && !e->prefix_tokens.empty()) {
        toks.insert(toks.begin(), e->prefix_tokens.begin(), e->prefix_tokens.end());
    }
    s.n_prompt = (int32_t) toks.size();
    if (toks.empty()) return e->fail(MLLM_E_ARG, "empty prompt");
    if (n_past + (int) toks.size() + gp->max_tokens > e->lp.n_ctx) return e->fail(MLLM_E_OVERFLOW, "prompt + max_tokens > n_ctx");

    t0 = clk::now();
    int rc = prefill(e, toks, n_past, seq);
    if (rc == MLLM_E_CANCELLED) { s.prefill_ms = ms_since(t0); return cancelled_early(); }
    if (rc != MLLM_OK) return rc;
    s.prefill_ms = ms_since(t0);
    n_past += (llama_pos) toks.size();

    auto sp = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sp);
    if (gp->repeat_penalty > 0.f && gp->repeat_penalty != 1.f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(e->vocab), 64, gp->repeat_penalty, 0.f, 0.f));
    }
    if (gp->temperature <= 0.f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        if (gp->top_k > 0) llama_sampler_chain_add(smpl, llama_sampler_init_top_k(gp->top_k));
        if (gp->top_p > 0.f && gp->top_p < 1.f) llama_sampler_chain_add(smpl, llama_sampler_init_top_p(gp->top_p, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(gp->temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(gp->seed));
    }

    size_t max_stop = 0;
    for (int i = 0; i < gp->n_stop; ++i) max_stop = std::max(max_stop, strlen(gp->stop[i]));

    std::string pending;     // generated text not yet delivered (incomplete UTF-8 or possible stop prefix)
    std::string all;         // full generated text (for stop matching)
    clk::time_point t_first {};
    bool first = true;
    s.stop_reason = 1;
    rc = MLLM_OK;

    auto deliver = [&](size_t n) -> bool {
        if (n == 0) return true;
        if (first) { s.ttft_ms = ms_since(t_start); t_first = clk::now(); first = false; }
        const bool stop = cb && cb(user, pending.data(), (int32_t) n) != 0;
        pending.erase(0, n);
        return !stop;
    };

    for (int i = 0; i < gp->max_tokens; ++i) {
        if (e->cancel.load()) { s.stop_reason = 3; rc = MLLM_E_CANCELLED; break; }
        const llama_token tok = llama_sampler_sample(smpl, e->ctx, -1);
        if (s.n_gen < 8 && getenv("MLLM_TRACE_TOKENS")) {   // debug: show first tokens incl. special ones (<think> etc.)
            char sb[128];
            const int sn = llama_token_to_piece(e->vocab, tok, sb, sizeof(sb), 0, /*special*/ true);
            fprintf(stderr, "mllm_trace: gen[%d] id=%d piece='%.*s'%s\n", s.n_gen, tok, sn > 0 ? sn : 0, sb,
                    llama_vocab_is_eog(e->vocab, tok) ? " (EOG)" : "");
        }
        if (llama_vocab_is_eog(e->vocab, tok)) { s.stop_reason = 0; break; }
        s.n_gen++;
        char buf[256];
        const int n = llama_token_to_piece(e->vocab, tok, buf, sizeof(buf), 0, /*special*/ false);
        if (n > 0) { pending.append(buf, (size_t) n); all.append(buf, (size_t) n); }

        bool hit = false;
        for (int k = 0; k < gp->n_stop && !hit; ++k) {
            const size_t pos = all.find(gp->stop[k]);
            if (pos != std::string::npos) {
                // drop everything from the stop string on
                const size_t cut = all.size() - pos;          // bytes of `all` past the match start
                pending.resize(pending.size() > cut ? pending.size() - cut : 0);
                hit = true;
            }
        }
        if (hit) { deliver(pending.size() - utf8_incomplete_tail(pending)); s.stop_reason = 2; break; }

        size_t ready = pending.size() - utf8_incomplete_tail(pending);
        ready = ready > (max_stop ? max_stop - 1 : 0) ? ready - (max_stop ? max_stop - 1 : 0) : 0;
        // the stop-string hold-back counts bytes: never cut inside a multi-byte code point (Cyrillic, emoji)
        while (ready > 0 && ((unsigned char) pending[ready] & 0xC0) == 0x80) --ready;
        if (!deliver(ready)) { s.stop_reason = 4; break; }

        // feed the token back
        llama_batch_ext_clear(e->batch);
        const int32_t idx = llama_batch_ext_add_token(e->batch, seq, tok);
        const llama_pos pos = n_past++;
        llama_batch_ext_set_pos(e->batch, idx, &pos);
        llama_batch_ext_set_output_logits(e->batch, idx, true);
        const int32_t prc = llama_process(e->ctx, LLAMA_PROCESS_TYPE_DECODE, e->batch);
        if (prc == 2) { s.stop_reason = 3; rc = MLLM_E_CANCELLED; break; }
        if (prc != 0) { rc = e->fail(MLLM_E_DECODE, "llama_process(decode) returned " + std::to_string(prc)); break; }
    }
    if (s.stop_reason != 2 && s.stop_reason != 4 && !pending.empty()) deliver(pending.size());
    llama_sampler_free(smpl);

    if (!first) s.decode_ms = ms_since(t_first);
    s.total_ms = ms_since(t_start);
    if (e->lp.prefix_mode == MLLM_PREFIX_SEQCP) llama_memory_seq_rm(llama_get_memory(e->ctx), 1, -1, -1);
    if (st) *st = s;
    return rc;
}

extern "C" void mllm_cancel(mllm_engine * e) {
    if (e) e->cancel.store(true, std::memory_order_relaxed);
}

extern "C" void mllm_cancel_clear(mllm_engine * e) {
    if (e) e->cancel.store(false, std::memory_order_relaxed);
}

extern "C" const char * mllm_last_error(const mllm_engine * e) {
    return e ? e->err.c_str() : "null engine";
}

extern "C" const char * mllm_device_name(const mllm_engine * e) {
    return e ? e->device_name.c_str() : "";
}

extern "C" void mllm_unload(mllm_engine * e) {
    if (!e) return;
    if (e->batch) llama_batch_ext_free(e->batch);
    if (e->ctx)   llama_free(e->ctx);
    if (e->model) llama_model_free(e->model);
    delete e;
}

extern "C" int mllm_bench(mllm_engine * e, int32_t n_prompt, int32_t n_gen, int32_t reps, mllm_bench_result * r) {
    if (!e || !r || n_prompt <= 0 || n_gen <= 0 || n_prompt + n_gen > e->lp.n_ctx) return MLLM_E_ARG;
    const int32_t nv = llama_vocab_n_tokens(e->vocab);
    std::vector<llama_token> toks((size_t) n_prompt);
    uint32_t x = 12345;
    for (auto & t : toks) { x = x * 1664525u + 1013904223u; t = (llama_token) (x % (uint32_t) nv); }
    llama_memory_t mem = llama_get_memory(e->ctx);
    std::vector<double> pp, tg;
    for (int rep = 0; rep <= std::max(1, reps); ++rep) {         // rep 0 = warm-up
        llama_memory_clear(mem, true);
        auto t0 = clk::now();
        int rc = prefill(e, toks, 0, 0);
        if (rc != MLLM_OK) return rc;
        llama_synchronize(e->ctx);
        const double t_pp = ms_since(t0);
        llama_memory_clear(mem, true);
        t0 = clk::now();
        for (int i = 0; i < n_gen; ++i) {
            llama_batch_ext_clear(e->batch);
            const int32_t idx = llama_batch_ext_add_token(e->batch, 0, toks[(size_t) i % toks.size()]);
            const llama_pos pos = i;
            llama_batch_ext_set_pos(e->batch, idx, &pos);
            llama_batch_ext_set_output_logits(e->batch, idx, true);
            if (llama_process(e->ctx, LLAMA_PROCESS_TYPE_DECODE, e->batch) != 0) return e->fail(MLLM_E_DECODE, "bench decode failed");
        }
        llama_synchronize(e->ctx);
        const double t_tg = ms_since(t0);
        if (rep > 0) { pp.push_back(t_pp); tg.push_back(t_tg); }
    }
    std::sort(pp.begin(), pp.end());
    std::sort(tg.begin(), tg.end());
    r->pp_ms  = pp[pp.size() / 2];
    r->tg_ms  = tg[tg.size() / 2];
    r->pp_tps = 1000.0 * n_prompt / r->pp_ms;
    r->tg_tps = 1000.0 * n_gen / r->tg_ms;
    e->prefix_live = false;      // memory was cleared; RAM/DEVICE snapshots stay valid, SEQCP rebuilds lazily
    llama_memory_clear(mem, true);
    return MLLM_OK;
}
