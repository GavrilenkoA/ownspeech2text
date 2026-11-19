# Murmur prompt eval. Run from the repo root:  make -C prompts <target>
# Inference targets go through tools/heavy (own memory scope) and use 6 threads.
ROOT     := $(abspath ..)
PY       ?= python3
MODELS   ?= q2b lfm3 q08
PROFILES ?= default.v1
SET      ?= dev.v2
RUNS     := $(ROOT)/.cache/ml/prompt/runs

.PHONY: test sets sets-fixture goldens eval sweep report raw

test:            ## unit tests: renderer goldens + chat templates, scorer, guard contract
	cd tests && $(PY) -m unittest discover -s . -p "test_*.py"

OCRPY    ?= $(ROOT)/.cache/ml/ocr/venv/bin/python

sets:            ## rebuild v1 sets + v2 sets/shots/smoke with real render+OCR (ml/screens; heavy, ~4 min) and the dense set
	cd $(ROOT) && $(PY) -m prompts.eval.cases.build
	cd $(ROOT) && tools/heavy $(OCRPY) -m prompts.eval.cases.build_v2 --workers 3 --threads 2
	cd $(ROOT) && tools/heavy $(OCRPY) -m prompts.eval.cases.build_v2 --dense --workers 3 --threads 2

sets-fixture:    ## v2 sets from the OCR fixture (no render/OCR): sets/*.v2fx.jsonl, shots/*.fx.jsonl
	cd $(ROOT) && $(PY) -m prompts.eval.cases.build_v2 --fixture

goldens:         ## regenerate examples/golden_*.txt (v1, must not change) and examples/ctxv2_*.txt
	$(PY) render.py --regen-goldens

eval:            ## one run: make eval M=q2b PROFILE=default.v1 SET=dev.v2
	cd $(ROOT) && tools/heavy $(PY) prompts/eval/run_eval.py --m $(M) --profile $(PROFILE) --set $(SET)

sweep:           ## all MODELS x PROFILES on SET, sequentially
	cd $(ROOT) && prompts/eval/sweep.sh "$(MODELS)" "$(PROFILES)" "$(SET)"

report:          ## table of every stored run for SET
	cd $(ROOT) && $(PY) prompts/eval/report.py $(RUNS)/*.$(SET).jsonl --by-category --guard-detail

raw:             ## identity baseline (raw transcript as output)
	cd $(ROOT) && $(PY) prompts/eval/score.py --set prompts/eval/sets/$(if $(findstring .,$(SET)),$(SET),$(SET).v1).jsonl --raw
