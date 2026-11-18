# Dataset factory (PLAN_V2 stage C, CONTEXT_V2). Run from anywhere: paths are relative to the repo root.
REPO := $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../..)
# the real screen path (ml.screens + ml.ocr) needs the OCR venv; the generator itself is stdlib-only
PY    ?= $(REPO)/.cache/ml/ocr/venv/bin/python
PYPUB ?= $(REPO)/.cache/ml/data/venv/bin/python
OUT   ?= $(REPO)/.cache/ml/data/synth_v2
N     ?= 3000
DEV   ?= 300
TEST  ?= 300
WORKERS ?= 5
MINED ?= $(REPO)/ml/roundtrip/samples/mined/real
MINED_ARG := $(if $(MINED),--mined $(MINED),)

.PHONY: venv test stub proof synth recheck sample public validate

venv:   # secondary venv for the public converters (pyarrow) and tests
	uv venv -q -p 3.13 $(REPO)/.cache/ml/data/venv && uv pip install -q -p $(PYPUB) -r $(REPO)/ml/data/requirements.txt

test:
	cd $(REPO) && $(PY) -m pytest -q -p no:cacheprovider ml/data/tests

# offline plumbing check (no network, no OCR): stub teacher, fixture screens, toy scale
stub:
	cd $(REPO) && $(PY) -m ml.data.generate --teacher stub --screens fixture --out $(REPO)/.cache/ml/data/synth_runs/stub \
	    --n 300 --dev 30 --test 30 --workers 2 $(MINED_ARG)

# 216 teacher scenarios, real teacher + real render/OCR (38 requests, measured ~7 min teacher + ~6 min build)
proof:
	cd $(REPO) && tools/with-teacher tools/heavy $(PY) -m ml.data.generate --out $(REPO)/.cache/ml/data/synth_runs/proof \
	    --limit 216 --max-requests 45 --workers 3 --screens real $(MINED_ARG)

# THE full set: ~633 teacher requests (see docs/ml/DATA_CARD.md for the measured wall time)
synth:
	cd $(REPO) && tools/with-teacher tools/heavy $(PY) -m ml.data.generate --out $(OUT) --n $(N) --dev $(DEV) --test $(TEST) \
	    --workers $(WORKERS) --screens real $(MINED_ARG)

# after any prompts/render.py change: re-apply the CONTEXT_V2 rules to the stored set (no OCR, no teacher)
recheck:
	cd $(REPO) && $(PY) -m ml.data.recheck --run $(OUT)

# committed sample for reviewers (<= 200 lines) from a finished run directory RUN=...
sample:
	cd $(REPO) && $(PY) -m ml.data.make_sample --run $(RUN) --out ml/data/samples/synth_v2.sample.jsonl --n 200

public:
	cd $(REPO) && tools/heavy $(PYPUB) -m ml.data.public.convert --out $(REPO)/.cache/ml/data/public_out

validate:
	cd $(REPO) && for f in $(OUT)/train.jsonl $(OUT)/dev.jsonl $(OUT)/test.jsonl; do $(PY) -m ml.common.validate --strict $$f || exit 1; done
