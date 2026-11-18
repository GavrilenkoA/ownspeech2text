"""The completion-only loss in ml/train/sft.py equals transformers' stock shifted cross-entropy, on tiny
random models built from the real LFM3 and Qwen3.5 configs (needs the local config/tokenizer dirs)."""
from __future__ import annotations

import os
from pathlib import Path

import pytest
import torch

from ml.eval import contract
from ml.train.render_data import IGNORE

DIRS = {"lfm3": Path(os.environ.get("MURMUR_TOK_LFM3", contract.REPO / ".cache/ml/train/models/LFM3-1.7B-tok")),
        "qwen3.5": Path(os.environ.get("MURMUR_TOK_QWEN", contract.REPO / ".cache/ml/train/models/Qwen3.5-0.8B"))}


@pytest.mark.parametrize("family", ["lfm3", "qwen3.5"])
def test_completion_loss_matches_stock_loss(family, tmp_path):
    if not (DIRS[family] / "config.json").exists():
        pytest.skip(f"no config at {DIRS[family]}")
    from transformers import AutoModelForCausalLM
    from ml.train.sft import Collator, completion_loss
    from ml.train.tiny_chain import tiny_model
    torch.manual_seed(0)
    m = AutoModelForCausalLM.from_pretrained(tiny_model(family, DIRS[family], tmp_path / "m"), dtype=torch.float32).eval()
    vocab = m.config.vocab_size
    g = torch.Generator().manual_seed(1)
    feats = []
    for n_p, n_c in ((12, 5), (20, 3), (7, 9)):   # different prompt/completion lengths -> right padding
        ids = torch.randint(10, vocab - 10, (n_p + n_c,), generator=g).tolist()
        feats.append({"input_ids": ids, "labels": [IGNORE] * n_p + ids[n_p:]})
    batch = Collator(pad_id=0)(feats)
    with torch.no_grad():
        ref = m(**batch).loss                                   # stock: full logits, shifted CE, mean over labels
        ours, out, scaled = completion_loss(m, batch)
        n_items = (batch["labels"][:, 1:] != IGNORE).sum()
        ours_n, _, scaled_n = completion_loss(m, batch, num_items_in_batch=n_items)
    assert not scaled and scaled_n
    assert out.logits.shape[0] == int(n_items)                  # only completion positions were projected
    assert torch.allclose(ours, ref, atol=1e-5), (float(ours), float(ref))
    assert torch.allclose(ours_n, ref, atol=1e-5)
