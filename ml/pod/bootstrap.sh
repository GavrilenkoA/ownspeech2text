#!/usr/bin/env bash
# From a clean CUDA image to a ready environment on the 1x RTX 5090 pod. Idempotent and cheap to
# re-run after every pod start: volume-backed steps (uv, Python, venv, llama.cpp, base weights) leave
# a marker in $W/.bootstrap and are skipped; container-disk steps (apt) are re-checked each start.
# Delete a marker to redo a step. Expects the code at $REPO (ml/pod/push_code.sh).
#   bash ml/pod/bootstrap.sh                        # everything the training stages need
#   MODELS="LiquidAI/LFM3-1.7B" bash ml/pod/bootstrap.sh      # MODELS="" skips the base weights
# Image: any Ubuntu 22.04/24.04 CUDA image (e.g. runpod/pytorch:*), host driver >= 580 (CUDA 13.x):
# torch 2.15.0 ships only CUDA 13.2 wheels (PyPI default; download.pytorch.org cu128 stops at 2.11).
set -euo pipefail
cd "$(dirname "$0")/../.."
source ml/pod/env.sh
step() { [[ -f "$2/$1" ]] && { log "skip $1"; return 1; }; log "run $1"; return 0; }

# ---- 0. GPU + driver (fail fast, before any download)
drv=$(nvidia-smi --query-gpu=driver_version --format=csv,noheader | head -1)
cuda=$(nvidia-smi | sed -n 's/.*CUDA Version: \([0-9]*\)\.\([0-9]*\).*/\1\2/p' | head -1)
log "GPU: $(nvidia-smi --query-gpu=name,memory.total --format=csv,noheader | head -1); driver $drv; CUDA ${cuda:-?}"
if [[ -z "$cuda" || "$cuda" -lt 130 ]]; then
  log "driver supports CUDA ${cuda:-?} < 13.0: torch 2.15.0 (CUDA 13.2 wheels) will not run. Ask for a host with driver >= 580."
  exit 3
fi

# ---- 1. system packages (container disk: re-checked after every start)
if step apt "$MARK_CTR"; then
  need=()
  for c in git curl rsync tmux jq; do command -v "$c" >/dev/null || need+=("$c"); done
  ldconfig -p | grep -q libgomp.so.1 || need+=(libgomp1)
  if ((${#need[@]})); then
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -q && apt-get install -y -q --no-install-recommends ca-certificates "${need[@]}"
  fi
  touch "$MARK_CTR/apt"
fi

# ---- 2. network: Python's socket.create_connection tries IPv6 first; on hosts with a black-holed
# IPv6 route huggingface_hub hangs (seen on the dev server). Prefer IPv4 if the default fails.
if step net "$MARK_CTR"; then
  if ! timeout 15 python3 -c "import socket;socket.create_connection(('huggingface.co',443),timeout=10)" 2>/dev/null; then
    grep -q '^precedence ::ffff:0:0/96 100' /etc/gai.conf 2>/dev/null || echo 'precedence ::ffff:0:0/96 100' >>/etc/gai.conf
    timeout 15 python3 -c "import socket;socket.create_connection(('huggingface.co',443),timeout=10)" \
      || { log "cannot reach huggingface.co"; exit 1; }
  fi
  touch "$MARK_CTR/net"
fi

# ---- 3. uv + Python 3.12 on the volume
if step uv "$MARK_VOL" || ! command -v uv >/dev/null; then
  curl -LsSf https://astral.sh/uv/install.sh | env UV_INSTALL_DIR="$UV_INSTALL_DIR" INSTALLER_NO_MODIFY_PATH=1 sh
  uv python install 3.12
  touch "$MARK_VOL/uv"
fi

# ---- 4. llama.cpp b12503: source (convert_hf_to_gguf.py, gguf-py), CPU build, CUDA 12.8 build + cudart
if step llama "$MARK_VOL"; then
  mkdir -p "$W/llama/cpu/llama-$LLAMA_TAG" "$W/llama/cuda/llama-$LLAMA_TAG"
  base="https://github.com/ggml-org/llama.cpp"
  curl -fsSL "$base/archive/refs/tags/$LLAMA_TAG.tar.gz" | tar xz -C "$W/llama"
  for pair in "cpu:llama-$LLAMA_TAG-bin-ubuntu-x64.tar.gz" "cuda:llama-$LLAMA_TAG-bin-ubuntu-cuda-12.8-x64.tar.gz" \
              "cuda:cudart-llama-$LLAMA_TAG-bin-ubuntu-cuda-12.8-x64.tar.gz"; do
    # every release tarball has exactly one top-level dir (llama-<tag>/ or cudart-.../): strip it so
    # binaries, libs AND their .so symlinks (libllama-common.so.1 -> ...) land together in one dir
    curl -fsSL "$base/releases/download/$LLAMA_TAG/${pair#*:}" \
      | tar xz -C "$W/llama/${pair%%:*}/llama-$LLAMA_TAG" --strip-components=1
  done
  LD_LIBRARY_PATH="$LLAMA_BIN_CPU" "$LLAMA_BIN_CPU/llama-server" --version 2>&1 | tail -1
  LD_LIBRARY_PATH="$LLAMA_BIN_CUDA" "$LLAMA_BIN_CUDA/llama-server" --version 2>&1 | tail -2
  touch "$MARK_VOL/llama"
fi
# (whether the CUDA build really computes on this GPU is decided by the kernel smoke test, step 6b)
LD_LIBRARY_PATH="$W/llama/cuda/llama-$LLAMA_TAG" "$W/llama/cuda/llama-$LLAMA_TAG/llama-server" --list-devices 2>&1 \
  | grep CUDA0 || log "WARNING: the CUDA 12.8 llama.cpp build lists no CUDA device (step 6b tries the fallbacks)"

# ---- 5. training venv: torch 2.15.0 (CUDA 13.2, PyPI default) + pinned stack + GPU kernels
if step venv-train "$MARK_VOL"; then
  uv venv -q -p 3.12 "$VENV_TRAIN"
  uv pip install -q -p "$VENV_TRAIN/bin/python" -r ml/train/requirements.txt -r ml/train/requirements-gpu.txt
  uv pip install -q -p "$VENV_TRAIN/bin/python" -e "$LLAMA_SRC/gguf-py"
  uv cache clean -q || true   # ~wheel-sized duplicate on the volume otherwise
  touch "$MARK_VOL/venv-train"
fi
rm -f "$MARK_VOL/fla-missing"
"$VENV_TRAIN/bin/python" - "$MARK_VOL/fla-missing" <<'EOF'
import sys, torch, transformers, peft
assert torch.cuda.is_available(), "torch sees no GPU"
x = torch.randn(1024, 1024, device="cuda", dtype=torch.bfloat16); (x @ x).sum().item()
print("torch", torch.__version__, "cuda", torch.version.cuda, torch.cuda.get_device_name(0),
      "cap", torch.cuda.get_device_capability(0), "transformers", transformers.__version__, "peft", peft.__version__)
try:
    import fla  # Gated DeltaNet kernels for Qwen3.5
    # the op transformers resolves for Qwen3.5 ("fla" + "ops.gated_delta_rule"); run it once forward and
    # backward so a Triton kernel that does not compile for sm_120 is caught here, not mid-matrix
    from fla.ops.gated_delta_rule import chunk_gated_delta_rule
    B, T, H, K = 1, 128, 2, 64
    q, k, v = (torch.randn(B, T, H, K, device="cuda", dtype=torch.bfloat16, requires_grad=True) for _ in range(3))
    g = torch.nn.functional.logsigmoid(torch.randn(B, T, H, device="cuda", dtype=torch.float32))
    beta = torch.rand(B, T, H, device="cuda", dtype=torch.bfloat16)
    o, _ = chunk_gated_delta_rule(q, k, v, g=g, beta=beta, use_qk_l2norm_in_kernel=True)
    o.float().sum().backward()
    assert torch.isfinite(q.grad).all(), "fla backward produced non-finite grads"
    print("flash-linear-attention", fla.__version__, "chunk_gated_delta_rule fwd+bwd OK on", torch.cuda.get_device_name(0))
except Exception as e:
    print("WARNING flash-linear-attention unavailable, Qwen3.5 trains on the slow PyTorch path:", e)
    open(sys.argv[1], "w").write(repr(e) + "\n")  # train_matrix.sh preflight refuses Qwen runs on this marker
EOF

# ---- 6. self-test (unit tests: metrics, gates, rendering parity, judge/rescore plumbing via the stub)
if step selftest "$MARK_VOL"; then
  "$VENV_TRAIN/bin/python" -m pytest -q ml/eval/tests ml/train/tests
  touch "$MARK_VOL/selftest"
fi

# ---- 6b. llama.cpp GPU KERNEL SMOKE TEST, on every start (a restarted pod can land on another host/driver).
# `--list-devices` runs no kernel: a build without sm_120 device code, a failing or slow PTX JIT or a wrong kernel
# would only show at the anchor's parity/eval, after its training time. ml/train/llama_smoke.py converts real base
# weights (pinned revision; downloaded here, step 7 then skips them) to Q4_0 once, decodes 4 fixed prompts with
# every layer on the GPU and on the CPU build, and compares (PASS: all agree, all layers offloaded, < 120 s).
# Measured on the dev server CPU: GGUF build 16.5 s for Qwen3.5-0.8B, decode 2.5 s per build [M]; on the pod
# ~1-3 min the first time (two models), ~30 s on a restart [A]. Candidates, first PASS wins:
#   1. the release CUDA 12.8 build (its default arch list includes 120a-real, i.e. sm_120 device code [V source])
#   2. the release CUDA 13.5 build of the same tag (~670 MB, ~1 min to fetch) [A: needs the driver's 13.x minor-
#      version compatibility]
#   3. a source build of the same tag for sm_120 only (needs nvcc >= 12.8 in the image; ~10-15 min [A];
#      LLAMA_SRC_BUILD=0 skips it)
#   4. none: marker llama-cpu-fallback -> train_matrix.sh preflight aborts (rc 6) unless ALLOW_CPU_EVAL=1
#      (CPU evals: ~10-40 min per ~420-item dev eval, ~10 evals per session: over the budget; runbook section 3)
# LLAMA_SMOKE_MODELS="" skips the test (legacy --list-devices verdict only).
LLAMA_SMOKE_MODELS="${LLAMA_SMOKE_MODELS-Qwen/Qwen3.5-0.8B LiquidAI/LFM3-1.7B}"
smoke() {  # $1 label  $2 llama.cpp bin dir under test; rc 0 = PASS on every smoke model
  local m rc=0; [[ -x "$2/llama-server" ]] || return 1
  for m in $LLAMA_SMOKE_MODELS; do
    log "llama.cpp kernel smoke: $1 build on $m"
    timeout 1800 "$VENV_TRAIN/bin/python" -m ml.train.llama_smoke --hf "$m" --work "$W/llama/smoke" --gpu-bin "$2" \
      --cpu-bin "$LLAMA_BIN_CPU" --require-gpu --timeout "${LLAMA_SMOKE_TIMEOUT:-120}" \
      --json "$W/llama/smoke/$1-$(basename "$m").json" >>"$MURMUR_LOGS/llama-smoke.log" 2>&1 || rc=1
    jq -c '{status, reason: (.reason // "" | .[0:300]), gpu}' "$W/llama/smoke/$1-$(basename "$m").json" 2>/dev/null >&2 || true
    (( rc == 0 )) || return 1
  done
}
fetch_cuda135() {
  local d="$W/llama/cuda13.5/llama-$LLAMA_TAG" base="https://github.com/ggml-org/llama.cpp/releases/download/$LLAMA_TAG"
  [[ -x "$d/llama-server" ]] && { echo "$d"; return; }
  mkdir -p "$d"
  for f in "llama-$LLAMA_TAG-bin-ubuntu-cuda-13.5-x64.tar.gz" "cudart-llama-$LLAMA_TAG-bin-ubuntu-cuda-13.5-x64.tar.gz"; do
    curl -fsSL "$base/$f" | tar xz -C "$d" --strip-components=1 || return 1
  done
  echo "$d"
}
build_cuda_src() {  # sm_120 only, server + quantize; the CPU backend is built in as well
  local b="$W/llama/build-cuda" t0; t0=$(date +%s)
  [[ "${LLAMA_SRC_BUILD:-1}" != 0 ]] && command -v nvcc >/dev/null && command -v cmake >/dev/null \
    || { log "llama.cpp source build skipped (LLAMA_SRC_BUILD=0, or no nvcc/cmake in the image)"; return 1; }
  log "llama.cpp source build for sm_120 (~10-15 min, log $MURMUR_LOGS/llama-build.log)"
  [[ -x "$b/bin/llama-server" ]] || {
    cmake -S "$LLAMA_SRC" -B "$b" -DCMAKE_BUILD_TYPE=Release -DGGML_CUDA=ON -DCMAKE_CUDA_ARCHITECTURES=120a-real \
      -DGGML_NATIVE=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_OPENSSL=OFF >>"$MURMUR_LOGS/llama-build.log" 2>&1 &&
    cmake --build "$b" --config Release -j "$(nproc)" --target llama-server llama-quantize >>"$MURMUR_LOGS/llama-build.log" 2>&1 \
      || return 1
    log "llama.cpp source build: $(( $(date +%s) - t0 ))s"; }
  echo "$b/bin"
}
if [[ -n "$LLAMA_SMOKE_MODELS" ]]; then
  mkdir -p "$W/llama/smoke"; pick=""
  prev=$(cat "$MARK_VOL/llama-cuda-bin" 2>/dev/null || true)
  if [[ -n "$prev" ]] && smoke previous "$prev"; then pick=$prev
  elif smoke cuda12.8 "$W/llama/cuda/llama-$LLAMA_TAG"; then pick="$W/llama/cuda/llama-$LLAMA_TAG"
  elif d=$(fetch_cuda135) && smoke cuda13.5 "$d"; then pick=$d
  elif d=$(build_cuda_src) && smoke source "$d"; then pick=$d
  fi
  if [[ -n "$pick" ]]; then
    echo "$pick" >"$MARK_VOL/llama-cuda-bin"; rm -f "$MARK_VOL/llama-cpu-fallback"
    log "llama.cpp kernel smoke PASS: pod evals use $pick"
  else
    rm -f "$MARK_VOL/llama-cuda-bin"; touch "$MARK_VOL/llama-cpu-fallback"
    log "WARNING: no llama.cpp CUDA build passed the kernel smoke test ($MURMUR_LOGS/llama-smoke.log, $W/llama/smoke/*.json):"
    log "  train_matrix.sh will refuse to start (rc 6); see TRAINING_RUNBOOK 'llama.cpp CUDA fallback'"
  fi
elif LD_LIBRARY_PATH="$LLAMA_BIN_CUDA" "$LLAMA_BIN_CUDA/llama-server" --list-devices 2>&1 | grep -q CUDA0; then
  rm -f "$MARK_VOL/llama-cpu-fallback"
else
  touch "$MARK_VOL/llama-cpu-fallback"
fi

# ---- 7. base weights (public repos; ~9.8 GB for all three) into $HF_HOME on the volume, each at the commit
# pinned in ml/train/configs/base.yaml (base_revisions; TRAINING_RUNBOOK 0.1). sft/merge load the same commit.
MODELS="${MODELS-LiquidAI/LFM3-1.7B Qwen/Qwen3.5-0.8B Qwen/Qwen3.5-2B}"   # MODELS="" (probe session): none
for m in $MODELS; do
  rev=$("$VENV_TRAIN/bin/python" -m ml.train.config revision "$m")   # refuses a repo without a pinned commit
  key="weights-$(echo "$m" | tr '/' '_')-${rev:0:12}"
  if step "$key" "$MARK_VOL"; then
    "$VENV_TRAIN/bin/python" -c "from huggingface_hub import snapshot_download as s; print(s('$m', revision='$rev'))"
    touch "$MARK_VOL/$key"
  fi
done

# ---- 8. ROUND-TRIP PROBE ENVIRONMENT (owned by ml/roundtrip, NOT implemented here)
# The probe (TTS + ASR on GPU, docs/ml/ROUNDTRIP.md) brings its own setup. If the roundtrip team
# ships ml/roundtrip/pod_setup.sh it is run here, otherwise this is a no-op.
if [[ -x ml/roundtrip/pod_setup.sh ]] && step roundtrip-setup "$MARK_VOL"; then
  bash ml/roundtrip/pod_setup.sh && touch "$MARK_VOL/roundtrip-setup"
fi

df -h "$W" | tail -1
log "bootstrap complete"
