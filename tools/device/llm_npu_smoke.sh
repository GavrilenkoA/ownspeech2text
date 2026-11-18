#!/usr/bin/env bash
# Murmur LLM smoke test on a real phone (Galaxy Z Fold7 / any SM8750): CPU vs NPU (Hexagon HTP) vs GPU (OpenCL).
#
#   tools/device/llm_npu_smoke.sh [--model PATH.gguf]... [--build-dir DIR] [--threads 4,6] [--quick] [--extended] [--geniex]
#
# What it does (each step is independent; a failure is recorded and the next step still runs):
#   1. device facts (model, SoC, Android, build fingerprint) + vendor FastRPC/OpenCL libraries visible to apps
#   2. pushes the arm64 build (native/llama/build_android.sh npu) and the GGUF(s) to /data/local/tmp/murmur-llm
#   3. llama-bench pp512/tg64 on CPU (per thread count), NPU (HTP0) and GPU (OpenCL)
#   4. murmur-llm-bench: the app's real flow (system-prefix snapshot -> TTFT for one dictation), CPU and NPU
#   5. NPU op placement: which graph ops the Hexagon backend accepted/rejected (GGML_HEXAGON_VERBOSE)
#   6. thermal status before/after
#   7. (--geniex) Qualcomm's own prebuilt geniex-bench v0.11.0 (same llama.cpp-Hexagon path, downloaded from GitHub,
#      sha256-pinned, ~100 MB) on the same GGUF, CPU vs NPU: tells "our build is broken" apart from "HTP is blocked"
# Output: a compact markdown report on stdout, plus report.md / report.json / raw logs in
#         .cache/device-reports/llm_npu_smoke-<timestamp>/   (send that folder back)
#
# Requirements: adb with exactly one device (or ANDROID_SERIAL set), USB debugging on, ~3 GB free on /data.
# Build first:   native/llama/build_android.sh npu          (default output: .cache/native-llama/out/npu)
set -uo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BUILD="${MURMUR_LLAMA_OUT:-$repo/.cache/native-llama/out}/npu"
# fall back to the spike build if the default output does not exist yet
[[ -d "$BUILD" || -n "${MURMUR_LLAMA_OUT:-}" ]] || { [[ -d "$repo/.cache/spikes/llm-npu/work/out/npu" ]] && BUILD="$repo/.cache/spikes/llm-npu/work/out/npu"; }
MODELS=()
THREADS="4,6"
QUICK=0
EXTENDED=0
GENIEX=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --model) MODELS+=("$2"); shift 2 ;;
        --build-dir) BUILD="$2"; shift 2 ;;
        --threads) THREADS="$2"; shift 2 ;;
        --quick) QUICK=1; shift ;;
        --extended) EXTENDED=1; shift ;;
        --geniex) GENIEX=1; shift ;;
        -h|--help) sed -n '2,19p' "$0"; exit 0 ;;
        *) echo "unknown argument: $1 (see --help)"; exit 2 ;;
    esac
done
if [[ ${#MODELS[@]} -eq 0 ]]; then
    for m in "$repo/.cache/ml/llm/LFM3-1.7B-QAD-Q4_0.gguf" "$repo/.cache/ml/llm/Qwen3.5-0.8B-Q4_0.gguf"; do
        [[ -f "$m" ]] && MODELS+=("$m")
    done
fi

TS="$(date +%Y%m%d-%H%M%S)"
R="${SMOKE_REPORT_DIR:-$repo/.cache/device-reports/llm_npu_smoke-$TS}"
mkdir -p "$R"
D=/data/local/tmp/murmur-llm
REPS=3; PP=512; TG=64; MAXTOK=128
[[ $QUICK == 1 ]] && { REPS=1; PP=256; TG=32; MAXTOK=64; THREADS="${THREADS%%,*}"; }

say()  { printf '\033[1m[smoke] %s\033[0m\n' "$*" >&2; }
fail() { printf '[smoke] FAILED: %s\n' "$*" >&2; echo "$*" >> "$R/failures.txt"; }
ADB=(adb); [[ -n "${ANDROID_SERIAL:-}" ]] && ADB=(adb -s "$ANDROID_SERIAL")
sh_dev() { timeout "${T:-900}" "${ADB[@]}" shell "$@"; }      # run on device with a host-side timeout
ENV_DEV="cd $D && export LD_LIBRARY_PATH=$D/lib ADSP_LIBRARY_PATH='$D/lib;/vendor/lib64/rfs/dsp/snap;/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/vendor/dsp'"
# Upstream llama-bench calls ggml_backend_load_all(), which only searches the executable's dir and the cwd.
# The backends live in $D/lib, so llama-bench must run with cwd=$D/lib (otherwise: "CPU backend is not loaded").
LB="$ENV_DEV && cd $D/lib && ../bin/llama-bench"

# ------------------------------------------------------------------ preflight (fatal checks only)
command -v adb >/dev/null || { echo "adb not found (install platform-tools, add to PATH)"; exit 1; }
command -v python3 >/dev/null || { echo "python3 not found on host"; exit 1; }
n_dev=$("${ADB[@]}" devices | awk 'NR>1 && $2=="device"' | wc -l)
if [[ -z "${ANDROID_SERIAL:-}" && "$n_dev" -ne 1 ]]; then
    echo "need exactly one adb device in state 'device' (found $n_dev). Plug in the phone, allow USB debugging, or set ANDROID_SERIAL."
    "${ADB[@]}" devices; exit 1
fi
[[ -x "$BUILD/bin/murmur-llm-bench" ]] || {
    echo "build not found in $BUILD"; echo "run: native/llama/build_android.sh npu   (or pass --build-dir)"; exit 1; }
[[ -f "$BUILD/lib/libggml-hexagon.so" ]] || echo "[smoke] note: $BUILD has no Hexagon backend (CPU-only build?) - NPU steps will report as failed" >&2
[[ ${#MODELS[@]} -gt 0 ]] || { echo "no GGUF given and none found in .cache/ml/llm (use --model)"; exit 1; }

# ------------------------------------------------------------------ 1. device facts
say "device facts"
{
    for p in ro.product.manufacturer ro.product.model ro.product.device ro.soc.manufacturer ro.soc.model ro.board.platform \
             ro.build.version.release ro.build.version.sdk ro.build.fingerprint ro.build.version.oneui ro.vendor.build.fingerprint; do
        printf '%s=%s\n' "$p" "$(T=20 sh_dev getprop "$p" | tr -d '\r')"
    done
    echo "mem_total_kb=$(T=20 sh_dev "awk '/MemTotal/{print \$2}' /proc/meminfo" | tr -d '\r')"
    echo "mem_available_kb=$(T=20 sh_dev "awk '/MemAvailable/{print \$2}' /proc/meminfo" | tr -d '\r')"
    echo "data_free=$(T=20 sh_dev "df -h /data | tail -1" | tr -d '\r')"
} > "$R/device.txt"
cat "$R/device.txt" >&2
grep -q 'ro.soc.model=SM8750' "$R/device.txt" || fail "ro.soc.model is not SM8750 (results still useful, but not the Fold7 SoC)"

say "vendor FastRPC / OpenCL libraries"
T=30 sh_dev "ls -l /vendor/lib64/libcdsprpc.so /vendor/lib64/libadsprpc.so /vendor/lib64/libOpenCL.so /system/vendor/lib64/libcdsprpc.so 2>&1;
             echo '--- public.libraries (what an app may dlopen):'; cat /vendor/etc/public.libraries.txt 2>/dev/null | grep -E -i 'cdsprpc|adsprpc|OpenCL' || echo '(none listed)';
             echo '--- fastrpc device nodes:'; ls -l /dev/fastrpc-* /dev/adsprpc-* 2>&1;
             echo '--- Samsung DSP lib dir:'; ls /vendor/lib64/rfs/dsp/snap 2>&1 | head -5;
             echo '--- cdsp skels present on vendor:'; ls /vendor/dsp/cdsp 2>&1 | head -10" > "$R/vendor.txt" 2>&1
cat "$R/vendor.txt" >&2

# ------------------------------------------------------------------ 2. push
say "push build ($(du -sh "$BUILD" | cut -f1)) and models to $D"
T=60 sh_dev "mkdir -p $D/lib $D/bin $D/models $D/prompts" || fail "mkdir on device"
timeout 600 "${ADB[@]}" push --sync "$BUILD/lib/." "$D/lib/" > "$R/push.log" 2>&1 || fail "push libs"
timeout 600 "${ADB[@]}" push --sync "$BUILD/bin/." "$D/bin/" >> "$R/push.log" 2>&1 || fail "push bins"
T=30 sh_dev "chmod 755 $D/bin/*"
for m in "${MODELS[@]}"; do
    say "push $(basename "$m") ($(du -h "$m" | cut -f1))"
    timeout 1800 "${ADB[@]}" push --sync "$m" "$D/models/" >> "$R/push.log" 2>&1 || fail "push $(basename "$m")"
done
for t in lfm3 qwen3.5; do
    python3 -I "$repo/native/llama/tools/make_prompt.py" --template "$t" \
        --out-prefix "$R/$t.prefix.txt" --out-suffix "$R/$t.suffix.txt" > /dev/null || fail "render prompt $t"
    "${ADB[@]}" push "$R/$t.prefix.txt" "$R/$t.suffix.txt" "$D/prompts/" >> "$R/push.log" 2>&1
done

# ------------------------------------------------------------------ 3. devices + llama-bench
thermal() { T=30 sh_dev "dumpsys thermalservice 2>/dev/null | grep -m1 -i 'Thermal Status'; dumpsys battery | grep -m1 temperature" | tr -d '\r' | tr '\n' ' '; }
echo "before: $(thermal)" > "$R/thermal.txt"

say "registered ggml devices"
T=120 sh_dev "$LB --list-devices" > "$R/devices.txt" 2>&1 || fail "llama-bench --list-devices"
cat "$R/devices.txt" >&2
# parse only the device list ("Available devices:" then "  HTP0: ...", "  GPUOpenCL: ..."), not log lines that mention HTP
dev_list() { sed -n '/^Available devices:/,$p' "$R/devices.txt" | grep -o -E "^[[:space:]]+$1[0-9]*(:[0-9]+)?:" | head -1 | tr -d '[:space:]' | sed 's/:$//'; }
HTP_DEV=$(dev_list HTP)
GPU_DEV=$(dev_list GPUOpenCL)
[[ -n "$HTP_DEV" ]] || fail "no HTP device registered (Hexagon backend did not come up; see devices.txt)"
[[ -n "$GPU_DEV" ]] || fail "no OpenCL GPU device registered"

: > "$R/llama-bench.jsonl"
for m in "${MODELS[@]}"; do
    mb="$D/models/$(basename "$m")"
    IFS=, read -r -a TLIST <<< "$THREADS"
    for t in "${TLIST[@]}"; do
        say "llama-bench CPU t=$t  $(basename "$m")"
        T=900 sh_dev "$LB -m $mb -dev none -ngl 0 -t $t -p $PP -n $TG -r $REPS -o jsonl" \
            >> "$R/llama-bench.jsonl" 2>> "$R/llama-bench.stderr.log" || fail "llama-bench CPU t=$t $(basename "$m")"
    done
    if [[ -n "$HTP_DEV" ]]; then
        say "llama-bench NPU ($HTP_DEV)  $(basename "$m")"
        T=900 sh_dev "$LB -m $mb -dev $HTP_DEV -ngl 99 -t ${TLIST[0]} -p $PP -n $TG -r $REPS -o jsonl" \
            >> "$R/llama-bench.jsonl" 2>> "$R/llama-bench.stderr.log" || fail "llama-bench NPU $(basename "$m")"
        if [[ $EXTENDED == 1 ]]; then
            for pm in sustained_high_performance balanced; do
                say "llama-bench NPU power=$pm"
                T=900 sh_dev "$ENV_DEV && cd $D/lib && GGML_HEXAGON_POWER_MODE=$pm ../bin/llama-bench -m $mb -dev $HTP_DEV -ngl 99 -t ${TLIST[0]} -p $PP -n $TG -r $REPS -o jsonl" \
                    | sed "s/^{/{\"power_mode\":\"$pm\",/" >> "$R/llama-bench.jsonl" 2>> "$R/llama-bench.stderr.log" || fail "llama-bench NPU power=$pm"
            done
        fi
    fi
    if [[ -n "$GPU_DEV" ]]; then
        say "llama-bench GPU ($GPU_DEV)  $(basename "$m")"
        T=900 sh_dev "$LB -m $mb -dev $GPU_DEV -ngl 99 -t ${TLIST[0]} -p $PP -n $TG -r $REPS -o jsonl" \
            >> "$R/llama-bench.jsonl" 2>> "$R/llama-bench.stderr.log" || fail "llama-bench GPU $(basename "$m")"
    fi
done

# ------------------------------------------------------------------ 4. app flow: prefix snapshot + TTFT
: > "$R/murmur-bench.jsonl"
for m in "${MODELS[@]}"; do
    mb="$D/models/$(basename "$m")"
    case "$(basename "$m")" in LFM3*|lfm3*) tpl=lfm3 ;; Qwen3.5*|qwen3.5*|Qwen3_5*) tpl=qwen3.5 ;; *) tpl="" ;; esac
    [[ -z "$tpl" ]] && { say "skip app-flow bench for $(basename "$m") (unknown template)"; continue; }
    for dev in cpu npu; do
        [[ $dev == npu && -z "$HTP_DEV" ]] && continue
        say "murmur-llm-bench $dev $(basename "$m")"
        T=1200 sh_dev "$ENV_DEV && ./bin/murmur-llm-bench -m $mb --device $dev -t ${THREADS%%,*} --backend-dir $D/lib \
            --prefix-file prompts/$tpl.prefix.txt --suffix-file prompts/$tpl.suffix.txt --modes ram,seqcp,none \
            --reps $REPS --max-tokens $MAXTOK --pp $PP --tg $TG --print-output" \
            2> "$R/murmur-bench.$dev.$(basename "$m").stderr.log" \
            | sed "s/^{/{\"model\":\"$(basename "$m")\",/" >> "$R/murmur-bench.jsonl" || fail "murmur-llm-bench $dev $(basename "$m")"
    done
    # CPU footprint without mmap: on the host the CPU weight repack duplicated the mmapped weights (LFM3 RSS 1.9 GB
    # with mmap vs 1.15 GB without); this row shows the phone's numbers. Reported as model "<name> no-mmap".
    say "murmur-llm-bench cpu --no-mmap $(basename "$m")"
    T=900 sh_dev "$ENV_DEV && ./bin/murmur-llm-bench -m $mb --device cpu -t ${THREADS%%,*} --backend-dir $D/lib --no-mmap \
        --prefix-file prompts/$tpl.prefix.txt --suffix-file prompts/$tpl.suffix.txt --modes ram --reps 1 --max-tokens $MAXTOK" \
        2> "$R/murmur-bench.cpu-nommap.$(basename "$m").stderr.log" \
        | sed "s/^{/{\"model\":\"$(basename "$m") no-mmap\",/" >> "$R/murmur-bench.jsonl" || fail "murmur-llm-bench cpu --no-mmap $(basename "$m")"
done

# ------------------------------------------------------------------ 5. NPU op placement
if [[ -n "$HTP_DEV" ]]; then
    for m in "${MODELS[@]}"; do
        mb="$D/models/$(basename "$m")"
        case "$(basename "$m")" in LFM3*) tpl=lfm3 ;; *) tpl=qwen3.5 ;; esac
        say "NPU op placement $(basename "$m")"
        T=900 sh_dev "$ENV_DEV && GGML_HEXAGON_VERBOSE=1 GGML_SCHED_DEBUG=1 ./bin/murmur-llm-bench -m $mb --device npu -t ${THREADS%%,*} \
            --backend-dir $D/lib --prefix-file prompts/$tpl.prefix.txt --suffix-file prompts/$tpl.suffix.txt --modes none --reps 1 --max-tokens 4 -vv" \
            > "$R/npu-verbose.$(basename "$m").log" 2>&1 || fail "NPU verbose run $(basename "$m")"
    done
fi
echo "after: $(thermal)" >> "$R/thermal.txt"

# ------------------------------------------------------------------ 7. optional: Qualcomm geniex-bench cross-check
if [[ $GENIEX == 1 ]]; then
    GX_URL=https://github.com/qualcomm/GenieX/releases/download/v0.11.0/geniex-bench-android-arm64-v0.11.0.tar.gz
    GX_SHA=7173d3ca43cf924b083c8c9fda479e7e3f4d388ee2d83c2e2fa3fe887e7a4e78
    GX_DIR="$repo/.cache/device-tools"; GX_TGZ="$GX_DIR/geniex-bench-android-arm64-v0.11.0.tar.gz"
    mkdir -p "$GX_DIR"
    if ! echo "$GX_SHA  $GX_TGZ" | sha256sum -c --quiet 2>/dev/null; then
        say "download geniex-bench v0.11.0 (100 MB)"
        curl -fL --retry 3 -o "$GX_TGZ.part" "$GX_URL" && mv "$GX_TGZ.part" "$GX_TGZ" || fail "download geniex-bench"
    fi
    if echo "$GX_SHA  $GX_TGZ" | sha256sum -c --quiet 2>/dev/null; then
        rm -rf "$GX_DIR/geniex-bench-android-arm64-v0.11.0"; tar -xzf "$GX_TGZ" -C "$GX_DIR"
        G=$D/geniex
        T=60 sh_dev "rm -rf $G && mkdir -p $G"
        timeout 900 "${ADB[@]}" push --sync "$GX_DIR/geniex-bench-android-arm64-v0.11.0/." "$G/" >> "$R/push.log" 2>&1 || fail "push geniex-bench"
        T=30 sh_dev "chmod 755 $G/bin/geniex-bench"
        GX_ENV="cd $G && export LD_LIBRARY_PATH=$G/lib:$G/lib/llama_cpp ADSP_LIBRARY_PATH='$G/lib/llama_cpp;/vendor/lib64/rfs/dsp/snap;/vendor/dsp/cdsp;/vendor/dsp'"
        for m in "${MODELS[@]}"; do
            mb="$D/models/$(basename "$m")"
            for dev in cpu npu; do
                say "geniex-bench llama_cpp $dev $(basename "$m")"
                T=900 sh_dev "$GX_ENV && ./bin/geniex-bench --plugin llama_cpp --device $dev -m $mb -p $PP -n $TG -r $REPS --log info --output-json $G/out-$dev.json; echo exit=\$?; cat $G/out-$dev.json" \
                    > "$R/geniex.$dev.$(basename "$m").log" 2>&1 || fail "geniex-bench $dev $(basename "$m")"
            done
        done
    else
        fail "geniex-bench sha256 mismatch or download failed"
    fi
fi

# ------------------------------------------------------------------ 6. report
python3 -I - "$R" <<'PY'
import glob, json, os, re, sys, collections
R = sys.argv[1]
def jl(p):
    out = []
    for line in open(p, encoding="utf-8", errors="replace") if os.path.exists(p) else []:
        line = line.strip()
        if line.startswith("{"):
            try: out.append(json.loads(line))
            except Exception: pass
    return out
dev = dict(l.split("=", 1) for l in open(f"{R}/device.txt").read().splitlines() if "=" in l)
md = [f"# Murmur LLM smoke: {dev.get('ro.product.model','?')} ({dev.get('ro.soc.model','?')}), Android {dev.get('ro.build.version.release','?')} / SDK {dev.get('ro.build.version.sdk','?')}",
      f"build `{dev.get('ro.build.fingerprint','?')}`", ""]
fails = open(f"{R}/failures.txt").read().splitlines() if os.path.exists(f"{R}/failures.txt") else []
md += ["**Failures:** " + ("none" if not fails else "; ".join(fails)), ""]
vend = open(f"{R}/vendor.txt").read()
pub = vend.split("--- public.libraries", 1)[1].split("---", 1)[0] if "--- public.libraries" in vend else ""
md += ["**App-visible vendor libs** (/vendor/etc/public.libraries.txt): " + (", ".join(sorted(set(re.findall(r"lib(?:cdsprpc|adsprpc|OpenCL)\.so", pub)))) or "none listed"), ""]
md += ["## llama-bench (pp = prefill tok/s, tg = decode tok/s)", "", "| model | backend | threads | power | test | t/s |", "|---|---|---|---|---|---|"]
summary = {"device": dev, "failures": fails, "llama_bench": [], "app_flow": [], "npu_ops": {}}
for r in jl(f"{R}/llama-bench.jsonl"):
    test = f"pp{r.get('n_prompt')}" if r.get("n_prompt") else f"tg{r.get('n_gen')}"
    be = r.get("devices") or "?"
    if be in ("none", "", "?"): be = "CPU (" + str(r.get("backends", "?")) + ")"
    md.append(f"| {os.path.basename(r.get('model_filename','?'))} | {be} | {r.get('n_threads')} | {r.get('power_mode','default')} | {test} | {r.get('avg_ts',0):.1f} ± {r.get('stddev_ts',0):.1f} |")
    summary["llama_bench"].append({k: r.get(k) for k in ("model_filename","devices","backends","n_threads","n_prompt","n_gen","avg_ts","stddev_ts","power_mode")})
md += ["", "## App flow (fixed ~1.1k-token system prefix snapshot, then one dictation)", "",
       "| model | device | prefix mode | prefix tok | prefix prefill ms | suffix tok | TTFT ms | decode tok/s | output = first mode | load ms | peak RSS MB |", "|---|---|---|---|---|---|---|---|---|---|---|"]
rows = jl(f"{R}/murmur-bench.jsonl")
# events per (model, device, mode): load -> mem_after_load -> prefix -> generate* -> summary -> [bench] -> mem_end
loads, mems, prefixes, summaries, benches = {}, {}, {}, [], []
cur_dev = {}
for r in rows:
    ev, mdl = r.get("event"), r.get("model")
    if ev == "load": cur_dev[mdl] = r["device"]; loads[(mdl, r["device"], r["mode"])] = r
    elif ev == "prefix": prefixes[(mdl, cur_dev.get(mdl), r["mode"])] = r
    elif ev == "mem_end": mems[(mdl, cur_dev.get(mdl), r["mode"])] = r
    elif ev == "summary": summaries.append(r)
    elif ev == "bench": benches.append(r)
for r in summaries:
    k = (r["model"], r["device"], r["mode"]); p = prefixes.get(k, {}); l = loads.get(k, {}); mm = mems.get(k, {})
    md.append(f"| {r['model']} | {r['device']} | {r['mode']} | {r['prefix_tokens']} | {p.get('prefill_ms',0):.0f} | {r['suffix_tokens']} | {r['ttft_ms_median']:.0f} | {r['decode_tps_median']:.1f} | {r['output_matches_first_mode']} | {l.get('load_ms',0):.0f} | {mm.get('hwm_kb',0)/1024:.0f} |")
    summary["app_flow"].append({**r, "prefix_prefill_ms": p.get("prefill_ms"), "load_ms": l.get("load_ms"), "hwm_kb": mm.get("hwm_kb"), "memavailable_drop_kb": mm.get("memavailable_drop_kb")})
for r in benches:
    md.append(f"| {r['model']} | {r['device']} | synthetic pp{r['pp']}/tg{r['tg']} | | {r['pp_tps']:.0f} tok/s | | | {r['tg_tps']:.1f} | | | |")
    summary["app_flow"].append(r)
outs = sorted({(r["model"], r["device"], r.get("output_head", "")) for r in summaries})
if outs:
    md += ["", "Output heads (greedy; check they are clean answers with no `<think>`):"] + [f"- {m} / {d}: `{o[:120]}`" for m, d, o in outs]
cpu_var = sorted(set(re.findall(r"loaded CPU backend from \S*/(libggml-cpu-[^/\s]+\.so)", "".join(open(p, errors="replace").read() for p in glob.glob(f"{R}/*.stderr.log")))))
md += ["", "**CPU variant picked by ggml:** " + (", ".join(cpu_var) or "not logged")]
summary["cpu_variant"] = cpu_var
md += ["", "## NPU op placement (GGML_HEXAGON_VERBOSE supports-op)", ""]
for p in sorted(glob.glob(f"{R}/npu-verbose.*.log")):
    name = os.path.basename(p)[len("npu-verbose."):-4]
    txt = open(p, encoding="utf-8", errors="replace").read()
    yes, no = collections.Counter(), collections.Counter()
    for m in re.finditer(r"supports-op ([A-Z_0-9]+)[^\n]*\|(yes|no)\s*$", txt, re.M):
        (yes if m.group(2) == "yes" else no)[m.group(1)] += 1
    hw = re.findall(r"ggml-hex: .*(?:Arch version|hwinfo|new session|failed|error)[^\n]*", txt)
    splits = len(re.findall(r"^## SPLIT", txt, re.M))
    md.append(f"- **{name}**: accepted {sum(yes.values())}, rejected {sum(no.values())}; rejected by op: {dict(no) or 'none'}; graph splits logged: {splits}")
    for h in hw[:6]: md.append(f"  - `{h.strip()[:160]}`")
    bufs = sorted(set(re.findall(r"(\S+ (?:model|KV|RS|compute|output) buffer size =\s*[\d.]+ MiB)", txt)))
    for b in bufs[:10]: md.append(f"  - buffer: `{b}`")
    summary["npu_ops"][name] = {"accepted": dict(yes), "rejected": dict(no), "hex_lines": hw[:20], "splits": splits, "buffers": bufs}
gx = sorted(glob.glob(f"{R}/geniex.*.log"))
if gx:
    md += ["", "## Qualcomm geniex-bench v0.11.0 cross-check (llama_cpp plugin; raw JSON in the logs)", ""]
    for p in gx:
        txt = open(p, encoding="utf-8", errors="replace").read()
        nums = re.findall(r'"(?:pp|tg|prefill|decode|tokens_per_second|prompt_tps|gen_tps|[a-z_]*tps[a-z_]*)"\s*:\s*([\d.]+)', txt)
        ex = re.findall(r"exit=(\d+)", txt)
        tail = [l for l in txt.splitlines() if re.search(r"t/s|tok/s|tps|error|ERROR|HTP|ggml-hex", l)][-4:]
        md.append(f"- **{os.path.basename(p)[7:-4]}**: exit {ex[-1] if ex else '?'}; " + "; ".join(t.strip()[:140] for t in tail))
    summary["geniex"] = {os.path.basename(p): open(p, errors="replace").read()[-4000:] for p in gx}
md += ["", "**Thermal:** " + open(f"{R}/thermal.txt").read().replace("\n", " | "), "", f"Raw logs: `{R}`"]
open(f"{R}/report.md", "w").write("\n".join(md) + "\n")
json.dump(summary, open(f"{R}/report.json", "w"), indent=1)
print("\n".join(md))
PY
echo >&2; say "report: $R/report.md  (json: $R/report.json). Device files left in $D (remove: adb shell rm -rf $D)"
