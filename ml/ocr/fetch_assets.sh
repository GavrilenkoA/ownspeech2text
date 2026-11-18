#!/usr/bin/env bash
# Fetch the OCR models and the open-licence fonts the renderer uses into .cache/ml/ (skips files that are
# present with the right hash; never downloads twice). Public URLs only, no token.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OCR="${MURMUR_OCR_MODELS:-$ROOT/.cache/ml/ocr}"
FONTS="${MURMUR_SCREENS_FONTS:-$ROOT/.cache/ml/screens/fonts}"
HF=https://huggingface.co/PaddlePaddle
GF=https://github.com/google/fonts/raw/main
get() {  # dest sha256 url
  if [ -f "$1" ] && echo "$2  $1" | sha256sum -c --status; then echo "ok   $1"; return; fi
  mkdir -p "$(dirname "$1")"; curl -fsSL -o "$1.part" "$3"; mv "$1.part" "$1"
  echo "$2  $1" | sha256sum -c --status || { echo "HASH MISMATCH $1" >&2; exit 1; }; echo "got  $1"
}
get "$OCR/PP-OCRv6_tiny_det_onnx/inference.onnx" 193bab7a04fca699a6c82e6abb5b81bdb28177f0abd4062552b04908dafb19f8 "$HF/PP-OCRv6_tiny_det_onnx/resolve/main/inference.onnx"
get "$OCR/cyrillic_PP-OCRv5_mobile_rec_onnx/inference.onnx" 5371ee1ddaa7983cc62d0818d99e982b6804638c85e4f960d59a574094e172e5 "$HF/cyrillic_PP-OCRv5_mobile_rec_onnx/resolve/main/inference.onnx"
[ -f "$OCR/cyrillic_PP-OCRv5_mobile_rec_onnx/inference.yml" ] || curl -fsSL -o "$OCR/cyrillic_PP-OCRv5_mobile_rec_onnx/inference.yml" "$HF/cyrillic_PP-OCRv5_mobile_rec_onnx/resolve/main/inference.yml"
get "$OCR/Roboto.ttf" 56a45233d29f11b4dfb86d248e921939d115778f87325e7ae8cc108383d6664d "$GF/apache/roboto/static/Roboto-Regular.ttf" || true
get "$FONTS/Roboto[wdth,wght].ttf" d7598e12c5dbef095ff8272cfc55da0250bd07fbdecbac8a530b9b277872a134 "$GF/ofl/roboto/Roboto%5Bwdth,wght%5D.ttf"
get "$FONTS/NotoSans[wdth,wght].ttf" bfb7bb691513f12e734dc346c03a03f784912432d7e3fa8e56efcf906fe86b3d "$GF/ofl/notosans/NotoSans%5Bwdth,wght%5D.ttf"
get "$FONTS/RobotoMono[wght].ttf" 66a80e79d17e4c7cabd162e2916578a4cc08fd19eef6e2a643305eae9c567b2b "$GF/ofl/robotomono/RobotoMono%5Bwght%5D.ttf"
get "$FONTS/JetBrainsMono[wght].ttf" 48715a42ec242c21e9f02692891e147d022299a52e48d5e413e1a942193ffeda "$GF/ofl/jetbrainsmono/JetBrainsMono%5Bwght%5D.ttf"
get "$FONTS/NotoColorEmoji-Regular.ttf" 4d82a18d8d95f60ba883ce242bbadbf84a576e987745dd7ba38d71c67cff2d73 "$GF/ofl/notocoloremoji/NotoColorEmoji-Regular.ttf"
