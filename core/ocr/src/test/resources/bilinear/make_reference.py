"""Writes cv2_reference.bin.gz: cv2.resize(INTER_LINEAR) on float32 BGR images, the reference for BilinearTest.

The resize is ml/ocr's (pipeline.resize_bilinear: astype(float32) then cv2.resize). Inputs are a pixel hash that both
sides compute (low 8 bits of products and xors, identical in Python ints and Kotlin 32-bit ints).
Run from the repo root:  .cache/ml/venv/bin/python -I core/ocr/src/test/resources/bilinear/make_reference.py
Format (little-endian): int32 case count; per case int32 imgW, imgH, x0, y0, w, h, dw, dh, rowCount, rows[rowCount],
then float32 [rowCount][3][dw] = the kept output rows, channels B, G, R.
"""
import gzip
import pathlib
import struct

import cv2
import numpy as np

# (image w, h), crop (x0, y0, w, h), output (dw, dh), kept output rows (None = all).
CASES = [
    ((1000, 9), (0, 0, 1000, 9), (397, 13), None),                       # x down 2.52, y up 1.44
    ((13, 720), (3, 11, 7, 700), (19, 263), list(range(0, 263, 4))),   # crop: x up 2.71, y down 2.66
    ((1080, 2520), (0, 0, 1080, 2520), (416, 960),                       # detector cover page: both down
     list(range(0, 4)) + list(range(478, 482)) + list(range(956, 960))),
    ((1968, 2184), (215, 1301, 300, 37), (390, 48), list(range(0, 48, 6))),  # recogniser crop: up in both
]


def image(w, h):
    y, x = np.mgrid[0:h, 0:w].astype(np.int64)
    ch = [((x * 73856093) ^ (y * 19349663) ^ (c * 83492791) ^ ((x * y) * 2654435761)) & 0xFF for c in range(3)]
    return np.stack(ch, axis=-1).astype(np.uint8)  # B, G, R


def main():
    out = bytearray(struct.pack("<i", len(CASES)))
    for (iw, ih), (x0, y0, w, h), (dw, dh), rows in CASES:
        img = image(iw, ih)[y0:y0 + h, x0:x0 + w]
        r = cv2.resize(img.astype(np.float32, copy=False), (dw, dh), interpolation=cv2.INTER_LINEAR)
        rows = list(range(dh)) if rows is None else rows
        out += struct.pack("<9i", iw, ih, x0, y0, w, h, dw, dh, len(rows))
        out += struct.pack(f"<{len(rows)}i", *rows)
        out += np.ascontiguousarray(r[rows].transpose(0, 2, 1)).astype("<f4").tobytes()
    path = pathlib.Path(__file__).with_name("cv2_reference.bin.gz")
    path.write_bytes(gzip.compress(bytes(out), mtime=0))
    print(path, len(out), "bytes raw,", path.stat().st_size, "gzipped, cv2", cv2.__version__)


if __name__ == "__main__":
    main()
