#!/usr/bin/env python3
"""Pinned FLEURS dev clips (CC-BY-4.0, google/fleurs, public, no token) for the real end-to-end test and the DC8 dry run.

  tools/e2e/fleurs_clip.py <out_dir> [ru|en]   -> <out_dir>/fleurs-<lang>.wav (PCM16 mono 16 kHz) + fleurs-<lang>.txt
  tools/e2e/fleurs_clip.py <out_dir> mixed60   -> <out_dir>/fleurs-mixed60.wav + .txt: 60 s of distinct ru and en
                                                  utterances, alternating, 0.5 s apart (DC8's mixed case)

The dev tarball (187 MB) and its TSV are downloaded once into <main checkout>/.cache/e2e/fleurs/ (shared by every
worktree; MURMUR_FLEURS_DIR overrides), pinned to a dataset commit and checked against their sha256. The reference is
the TSV's normalised transcription (lowercase, no punctuation), the same text WordErrorRate normalises to.
Python stdlib only.
"""
import array, hashlib, os, pathlib, socket, struct, subprocess, sys, tarfile, urllib.request

# This server's IPv6 route to the HF CDN hangs (same workaround as tools/models/fetch).
_gai = socket.getaddrinfo
socket.getaddrinfo = lambda host, *a, **k: [r for r in _gai(host, *a, **k) if r[0] == socket.AF_INET] or _gai(host, *a, **k)

REVISION = "70bb2e84b976b7e960aa89f1c648e09c59f894dd"
BASE = f"https://huggingface.co/datasets/google/fleurs/resolve/{REVISION}/data"
FILES = {
    "ru_dev.tar.gz": (f"{BASE}/ru_ru/audio/dev.tar.gz", 187377526, "4dc533cd0f312f749bd6d0fe968f3183288d938e07ed97dc141d9f10570457f9"),
    "ru_dev.tsv": (f"{BASE}/ru_ru/dev.tsv", 316328, "25d9dc88a7533fc0f303354a8e6d3c8186c557229c9a0024487090a11226171e"),
    "en_dev.tar.gz": (f"{BASE}/en_us/audio/dev.tar.gz", 171250900, "2658fda72f199e12676ecac9415094667a4e14e149b146e568ea00b2a2f0954c"),
    "en_dev.tsv": (f"{BASE}/en_us/dev.tsv", 213065, "9d57ee7e91e9d4c92edb39f6bbea668ef8dc2a3ff96eb510d5580b2ad05d17ec"),
}
# ~9 s each, plain vocabulary (no names or numerals that ASR may spell differently).
CLIPS = {"ru": "8292209001184880742.wav", "en": "2606692427476446963.wav"}


def cache_dir() -> pathlib.Path:
    if os.environ.get("MURMUR_FLEURS_DIR"):
        return pathlib.Path(os.environ["MURMUR_FLEURS_DIR"])
    here = pathlib.Path(__file__).resolve().parent
    common = subprocess.run(["git", "-C", str(here), "rev-parse", "--path-format=absolute", "--git-common-dir"],
                            capture_output=True, text=True, check=True).stdout.strip()
    return pathlib.Path(common).parent / ".cache" / "e2e" / "fleurs"


def sha256(p: pathlib.Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def fetch(name: str, d: pathlib.Path) -> pathlib.Path:
    url, size, digest = FILES[name]
    dst = d / name
    if dst.is_file() and dst.stat().st_size == size and sha256(dst) == digest:
        return dst
    part = d / (name + ".part")
    print(f"downloading {url} ({size} bytes)", file=sys.stderr)
    with urllib.request.urlopen(url, timeout=60) as r, open(part, "wb") as out:
        while chunk := r.read(1 << 20):
            out.write(chunk)
    if part.stat().st_size != size or sha256(part) != digest:
        part.unlink()
        sys.exit(f"{name}: size or sha256 mismatch, deleted the download")
    part.rename(dst)
    return dst


def to_pcm16(wav: bytes) -> bytes:
    """RIFF WAVE (PCM16 or float32, mono, 16 kHz) -> PCM16 samples."""
    if wav[:4] != b"RIFF" or wav[8:12] != b"WAVE":
        sys.exit("not a WAVE file")
    pos, fmt, data = 12, None, None
    while pos + 8 <= len(wav):
        cid, n = wav[pos:pos + 4], struct.unpack("<I", wav[pos + 4:pos + 8])[0]
        body = wav[pos + 8:pos + 8 + n]
        if cid == b"fmt ":
            fmt = struct.unpack("<HHIIHH", body[:16])
        elif cid == b"data":
            data = body
        pos += 8 + n + (n & 1)
    tag, channels, rate, _, _, bits = fmt
    if channels != 1 or rate != 16000:
        sys.exit(f"unexpected format: {channels} ch, {rate} Hz")
    if tag == 1 and bits == 16:
        return data
    if tag == 3 and bits == 32:
        floats = array.array("f", data)
        return array.array("h", (max(-32768, min(32767, int(round(x * 32767)))) for x in floats)).tobytes()
    sys.exit(f"unsupported WAVE format tag {tag} / {bits} bits")


def wav16(pcm: bytes) -> bytes:
    return (b"RIFF" + struct.pack("<I", 36 + len(pcm)) + b"WAVE" + b"fmt " + struct.pack("<IHHIIHH", 16, 1, 1, 16000, 32000, 2, 16)
            + b"data" + struct.pack("<I", len(pcm)) + pcm)


def rows(tsv: pathlib.Path):
    """(file, transcription, samples) per distinct sentence id, in TSV order."""
    seen = set()
    for line in tsv.read_text(encoding="utf-8").splitlines():
        cols = line.split("\t")
        if len(cols) > 5 and cols[0] not in seen:
            seen.add(cols[0])
            yield cols[1], cols[3].strip(), int(cols[5])


def mixed60(out: pathlib.Path, d: pathlib.Path) -> None:
    """Greedy, deterministic for the pinned revision: ru and en in turn, 5-12 s sentences without digits."""
    total, gap = 60 * 16000, 8000
    pools = {l: [r for r in rows(fetch(f"{l}_dev.tsv", d)) if r[0] != CLIPS[l] and 80000 <= r[2] <= 192000
                 and not any(ch.isdigit() for ch in r[1])] for l in ("ru", "en")}
    chosen, used, turn = [], 0, 0
    while True:
        lang = ("ru", "en")[turn % 2]
        pick = next((r for r in pools[lang] if used + r[2] + gap <= total), None)
        if pick is None:
            other = ("ru", "en")[(turn + 1) % 2]
            if next((r for r in pools[other] if used + r[2] + gap <= total), None) is None:
                break
            turn += 1
            continue
        pools[lang].remove(pick)
        chosen.append((lang, pick))
        used += pick[2] + gap
        turn += 1
    audio = {}
    for lang in ("ru", "en"):
        want = {r[0] for l, r in chosen if l == lang}
        with tarfile.open(fetch(f"{lang}_dev.tar.gz", d)) as t:
            for m in t:
                name = m.name.removeprefix("dev/")
                if name in want:
                    want.discard(name)
                    audio[name] = to_pcm16(t.extractfile(m).read())
    pcm = bytearray()
    for _, r in chosen:
        pcm += audio[r[0]] + b"\0\0" * gap
    pcm = bytes(pcm[: total * 2]).ljust(total * 2, b"\0")
    (out / "fleurs-mixed60.wav").write_bytes(wav16(pcm))
    (out / "fleurs-mixed60.txt").write_text(" ".join(r[1] for _, r in chosen) + "\n", encoding="utf-8")
    langs = "".join(l[0] for l, _ in chosen)
    print(f"{out / 'fleurs-mixed60.wav'}: {len(pcm) // 32} ms; {len(chosen)} utterances ({langs})")


def main() -> None:
    out = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    lang = sys.argv[2] if len(sys.argv) > 2 else "ru"
    if lang == "mixed60":
        out.mkdir(parents=True, exist_ok=True)
        d = cache_dir()
        d.mkdir(parents=True, exist_ok=True)
        mixed60(out, d)
        return
    if lang not in CLIPS:
        sys.exit(f"language {lang}: one of {sorted(CLIPS)}")
    clip = CLIPS[lang]
    out.mkdir(parents=True, exist_ok=True)
    d = cache_dir()
    d.mkdir(parents=True, exist_ok=True)
    tsv = fetch(f"{lang}_dev.tsv", d)
    ref = None
    for line in tsv.read_text(encoding="utf-8").splitlines():
        cols = line.split("\t")
        if len(cols) > 3 and cols[1] == clip:
            ref = cols[3].strip()
            break
    if not ref:
        sys.exit(f"{clip} not in the TSV")
    tar = fetch(f"{lang}_dev.tar.gz", d)
    with tarfile.open(tar) as t:
        wav = t.extractfile(t.getmember(f"dev/{clip}")).read()
    pcm = to_pcm16(wav)
    (out / f"fleurs-{lang}.wav").write_bytes(wav16(pcm))
    (out / f"fleurs-{lang}.txt").write_text(ref + "\n", encoding="utf-8")
    print(f"{out / f'fleurs-{lang}.wav'}: {len(pcm) // 32} ms; reference {len(ref.split())} words")


if __name__ == "__main__":
    main()
