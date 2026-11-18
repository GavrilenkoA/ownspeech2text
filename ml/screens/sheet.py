"""Contact sheet of PNGs for quick visual review: python -m ml.screens.sheet OUT.png IMG... [--h 900]"""
from __future__ import annotations

import sys

from PIL import Image


def sheet(paths, out, h=900, cols=4):
    ims = []
    for p in paths:
        im = Image.open(p).convert("RGB")
        ims.append(im.resize((max(1, im.width * h // im.height), h)))
    rows = [ims[i:i + cols] for i in range(0, len(ims), cols)]
    W = max(sum(i.width for i in r) + 10 * (len(r) - 1) for r in rows)
    S = Image.new("RGB", (W, len(rows) * (h + 10)), "#777")
    y = 0
    for r in rows:
        x = 0
        for im in r:
            S.paste(im, (x, y))
            x += im.width + 10
        y += h + 10
    S.save(out)


if __name__ == "__main__":
    args = sys.argv[1:]
    h = 900
    if "--h" in args:
        i = args.index("--h")
        h = int(args[i + 1])
        del args[i:i + 2]
    sheet(args[1:], args[0], h)
