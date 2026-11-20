#!/usr/bin/env bash
# Publishes the compiled ORT QNN contexts (native/qnn-contexts/compile.sh output) to a public
# Hugging Face model repo, at the layout the catalog expects: <revision>/<artifact id with '--'>/<file>.
#   HF_TOKEN=hf_... tools/models/publish_npu_contexts.sh [repo] [ctx dir]
# Defaults: repo satoshiNakomoroReal/npu-contexts, ctx .cache/spikes/wp-21/ctx, revision qairt-2.52.0-r1
set -euo pipefail
repo="${1:-satoshiNakomoroReal/npu-contexts}"
ctx="${2:-$(cd "$(dirname "$0")/../.." && pwd)/.cache/spikes/wp-21/ctx}"
rev="${REV:-qairt-2.52.0-r1}"
[ -n "${HF_TOKEN:-}" ] || { echo "HF_TOKEN is required" >&2; exit 2; }
[ -d "$ctx" ] || { echo "missing context dir $ctx" >&2; exit 2; }

python3 - "$repo" "$ctx" "$rev" <<'PY'
import os, sys, pathlib
from huggingface_hub import HfApi
repo, ctx, rev = sys.argv[1], pathlib.Path(sys.argv[2]), sys.argv[3]
api = HfApi(token=os.environ["HF_TOKEN"])
api.create_repo(repo_id=repo, repo_type="model", exist_ok=True, private=False)

plan = [
    ("det_contexts.zip", "pp-ocrv6-tiny-det@qnn-sm8750"),
    ("rec_contexts.zip", "pp-ocrv5-cyrillic-mobile-rec@qnn-sm8750"),
    ("manifest.json", ""),
]
for name, art in plan:
    src = ctx / name
    if not src.exists():
        print("skip missing", src); continue
    path = f"{rev}/{name}" if not art else f"{rev}/{art.replace('@', '--')}/{name}"
    api.upload_file(path_or_fileobj=str(src), path_in_repo=path, repo_id=repo)
    print("uploaded", path, src.stat().st_size)

giga = ctx / "gigaam-v3-e2e-ctc@qnn-sm8750-20s"
if giga.is_dir():
    for f in sorted(giga.iterdir()):
        if f.is_file():
            path = f"{rev}/{giga.name.replace('@', '--')}/{f.name}"
            api.upload_file(path_or_fileobj=str(f), path_in_repo=path, repo_id=repo)
            print("uploaded", path, f.stat().st_size)
print("done:", f"https://huggingface.co/{repo}")
PY
