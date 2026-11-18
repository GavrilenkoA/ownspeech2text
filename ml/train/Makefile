# Murmur training / export / eval entry points (run from anywhere: paths are repo-relative).
#   make -C ml/train venv test          # local CPU venv + unit tests
#   make -C ml/train dryrun             # full CPU chain on the smoke cases (see docs/ml/TRAINING_RUNBOOK.md)
#   make -C ml/train eval MODEL=x.gguf DATA=set.jsonl OUT=dir SYSTEM=default
REPO   := $(abspath ../..)
C      := $(REPO)/.cache/ml/train
VENV   := $(C)/venv
PY     := $(VENV)/bin/python
HEAVY  := $(REPO)/tools/heavy
LLAMA_TAG ?= b12503
export MURMUR_LLAMA_SRC ?= $(C)/src/llama.cpp-$(LLAMA_TAG)
export MURMUR_LLAMA_BIN ?= $(REPO)/.cache/ml/llm/bin/llama-$(LLAMA_TAG)
export PYTHONPATH := $(REPO)
export OMP_NUM_THREADS ?= 6
SYSTEM ?= finetuned
TIME   ?= /usr/bin/time -f "TIME %e s, maxrss %M KB:"
BASE   ?= $(C)/models/Qwen3.5-0.8B
# BF16-vs-Q4_0 quantisation loss in the dry run on the first N smoke cases (BF16 is slow on CPU)
QLIMIT ?= 8
# optional cap on the Q4_0 dry-run eval (default: all 24 smoke cases)
EVAL_LIMIT ?=

.PHONY: venv llama-src test smoke-data dryrun eval tiny
# dryrun-% (lora|dora|full) must NOT be .PHONY: make never applies pattern rules to phony targets

venv:
	cd $(REPO) && uv venv -q -p 3.12 $(VENV)
	cd $(REPO) && $(HEAVY) uv pip install -q -p $(PY) --index-strategy unsafe-best-match \
	  --extra-index-url https://download.pytorch.org/whl/cpu -r ml/train/requirements.txt
	$(MAKE) llama-src
	uv pip install -q -p $(PY) -e $(MURMUR_LLAMA_SRC)/gguf-py

llama-src:
	test -d $(MURMUR_LLAMA_SRC) || (mkdir -p $(C)/src && curl -fsSL \
	  https://github.com/ggml-org/llama.cpp/archive/refs/tags/$(LLAMA_TAG).tar.gz | tar xz -C $(C)/src)

test:
	cd $(REPO) && $(PY) -m pytest -q ml/eval/tests ml/train/tests

smoke-data:
	mkdir -p $(C)/data
	cd $(REPO) && $(PY) -m ml.eval.smoke_to_contract --split train --out $(C)/data/smoke.train.jsonl
	cd $(REPO) && $(PY) -m ml.eval.smoke_to_contract --split dev --out $(C)/data/smoke.dev.jsonl
	sed -i 's/"id": "smoke-/"id": "smoke-dev-/' $(C)/data/smoke.dev.jsonl
	cd $(REPO) && $(PY) -m ml.eval.smoke_to_contract --split test --out $(C)/data/smoke.test.jsonl

# One method end to end: train -> merge -> GGUF BF16 -> Q4_0 -> eval both -> quant-loss table -> card
dryrun-%: smoke-data
	# finished steps are skipped (train_info.json / merged / manifest.json present); evals resume per example
	test -f $(C)/runs/dryrun-$*/train_info.json || (cd $(REPO) && MURMUR_METHOD=$* MURMUR_BASE=$(BASE) MURMUR_MEM=24G \
	  $(HEAVY) $(TIME) $(PY) -m ml.train.sft --config ml/train/configs/dryrun-cpu.yaml $(SET))
	test "$*" = full || test -f $(C)/runs/dryrun-$*/merged/config.json || test -f $(C)/runs/dryrun-$*/gguf/manifest.json || \
	  (cd $(REPO) && $(HEAVY) $(TIME) $(PY) -m ml.train.merge --run $(C)/runs/dryrun-$*)
	test -f $(C)/runs/dryrun-$*/gguf/manifest.json || (cd $(REPO) && $(HEAVY) $(TIME) $(PY) -m ml.train.export \
	  --run $(C)/runs/dryrun-$* --name murmur-dryrun-$* --keep-bf16)
	cd $(REPO) && $(HEAVY) $(TIME) $(PY) -m ml.eval.run_eval --model $(C)/runs/dryrun-$*/gguf/murmur-dryrun-$*-Q4_0.gguf \
	  --data $(C)/data/smoke.test.jsonl --out $(C)/runs/dryrun-$*/eval/Q4_0 --label "dryrun-$* Q4_0" $(if $(EVAL_LIMIT),--limit $(EVAL_LIMIT))
	cd $(REPO) && $(HEAVY) $(TIME) $(PY) -m ml.eval.run_eval --model $(C)/runs/dryrun-$*/gguf/murmur-dryrun-$*-BF16.gguf \
	  --data $(C)/data/smoke.test.jsonl --out $(C)/runs/dryrun-$*/eval/BF16 --label "dryrun-$* BF16" --limit $(QLIMIT)
	rm -rf $(C)/runs/dryrun-$*/eval/Q4_0-first$(QLIMIT) && cp -r $(C)/runs/dryrun-$*/eval/Q4_0 $(C)/runs/dryrun-$*/eval/Q4_0-first$(QLIMIT)
	cd $(REPO) && $(PY) -m ml.eval.run_eval --rescore $(C)/runs/dryrun-$*/eval/Q4_0-first$(QLIMIT) --limit $(QLIMIT) >/dev/null
	cd $(REPO) && $(PY) -m ml.eval.compare $(C)/runs/dryrun-$*/eval/BF16/report.json \
	  $(C)/runs/dryrun-$*/eval/Q4_0-first$(QLIMIT)/report.json --json $(C)/runs/dryrun-$*/eval/quant_loss.json \
	  --md $(C)/runs/dryrun-$*/eval/quant_loss.md
	cd $(REPO) && $(PY) -m ml.train.card --run $(C)/runs/dryrun-$* --name murmur-dryrun-$* --repo OWNER/murmur-dryrun-$* \
	  --eval $(C)/runs/dryrun-$*/eval/Q4_0/report.json --qloss-json $(C)/runs/dryrun-$*/eval/quant_loss.json
	cd $(REPO) && $(PY) -m ml.train.upload_hf --run $(C)/runs/dryrun-$* --repo OWNER/murmur-dryrun-$* --dry-run

dryrun: dryrun-lora
	$(MAKE) dryrun-full SET="--set train.save_steps=1000" EVAL_LIMIT=8 QLIMIT=4

eval:
	cd $(REPO) && $(HEAVY) $(PY) -m ml.eval.run_eval --model $(MODEL) --data $(DATA) --out $(OUT) --system $(SYSTEM)

# Whole chain on a tiny random-weight model of a family (code paths for LFM3 / Qwen3.5-2B without their weights)
FAMILY ?= lfm3
TOK    ?= $(C)/models/LFM3-1.7B-tok
METHOD ?= lora
tiny: smoke-data
	cd $(REPO) && $(HEAVY) $(TIME) $(PY) -m ml.train.tiny_chain --family $(FAMILY) --tok-dir $(TOK) --method $(METHOD) \
	  --work $(C)/tiny
