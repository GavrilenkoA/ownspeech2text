# Reference OCR pipeline. Run from the repo root: make -C ml/ocr <target>
ROOT := $(abspath ../..)
PY := cd $(ROOT) && $(ROOT)/.cache/ml/ocr/venv/bin/python
HEAVY := $(ROOT)/tools/heavy
.PHONY: venv assets test golden golden-check measure
venv:
	cd $(ROOT) && uv venv .cache/ml/ocr/venv -p 3.13 && uv pip install -p .cache/ml/ocr/venv -r ml/ocr/requirements.txt
	cd $(ROOT)/.cache/ml/screens && cp $(ROOT)/ml/screens/package.json . && PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 npm install --no-audit --no-fund
assets:
	$(ROOT)/ml/ocr/fetch_assets.sh
test:
	$(PY) -m pytest -q ml/ocr/tests ml/screens/tests
golden:          ## regenerate ml/ocr/golden (after changing a pipeline constant)
	$(HEAVY) $(PY) -m ml.ocr.golden make
golden-check:    ## the full parity suite against the Python reference
	$(HEAVY) $(PY) -m ml.ocr.golden check
measure:         ## detector input limit / tiling sweep (writes .cache/ml/ocr/measure/)
	$(HEAVY) $(PY) -m ml.ocr.measure detlimit
