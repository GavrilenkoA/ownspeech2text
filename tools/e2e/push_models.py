#!/usr/bin/env python3
"""Install catalog artifacts into the app on an emulator/phone without the network (real end-to-end test, DC8 dry run).

  tools/e2e/push_models.py <serial> <artifactId> [<artifactId> ...] [--prepared] [--remove]

For each artifact: fetch it into the shared cache (tools/models/fetch: verified sha256), adb push the files to
/data/local/tmp/murmur-e2e/models/<id>/, copy them with `run-as app.murmur` into the app's
noBackupFilesDir/models/<id>/ (ARCHITECTURE 8.3) and write installed.json last, the same document WP-13's installer
writes (artifactId, catalogVersion, files{name: sha256}, bytesOnDisk, installedAtMs, prepared). --prepared marks the
artifact as already proven by Preparing (skips the install-time load); the default lets the app prepare it.
--remove deletes the artifacts from the app instead. The temporary copy in /data/local/tmp is deleted afterwards.
Needs a debuggable app (run-as). Python stdlib only.
"""
import json, os, pathlib, subprocess, sys, time

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
CATALOG = ROOT / "core/catalog/src/main/resources/catalog/catalog.json"
PKG = "app.murmur"
TMP = "/data/local/tmp/murmur-e2e/models"


def run(*cmd: str, check: bool = True) -> str:
    r = subprocess.run(cmd, capture_output=True, text=True)
    if check and r.returncode != 0:
        sys.exit(f"failed ({r.returncode}): {' '.join(cmd)}\n{r.stdout}{r.stderr}")
    return r.stdout


def models_cache() -> pathlib.Path:
    common = run("git", "-C", str(ROOT), "rev-parse", "--path-format=absolute", "--git-common-dir").strip()
    return pathlib.Path(common).parent / ".cache" / "models"


def main() -> None:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    prepared = "--prepared" in sys.argv
    remove = "--remove" in sys.argv
    if len(args) < 2:
        sys.exit(__doc__)
    serial, ids = args[0], args[1:]
    sdk = pathlib.Path(os.environ.get("ANDROID_HOME", pathlib.Path.home() / ".local/share/android-sdk"))
    adb = [str(sdk / "platform-tools/adb"), "-s", serial]
    catalog = json.loads(CATALOG.read_text())
    artifacts = {a["id"]: a for m in catalog["models"] for a in m["artifacts"]}
    for aid in ids:
        if aid not in artifacts:
            sys.exit(f"unknown artifact {aid}")
        if any(f.get("archive") for f in artifacts[aid]["files"]):
            sys.exit(f"{aid}: archives are not supported here (the app extracts them; use the in-app download)")

    if remove:
        for aid in ids:
            run(*adb, "shell", "run-as", PKG, "rm", "-rf", f"no_backup/models/{aid}")
            print(f"removed {aid}")
        return

    cache = models_cache()
    missing = [aid for aid in ids if not (cache / aid / "fetch.ok").is_file()]
    if missing:
        subprocess.run([str(ROOT / "tools/models/fetch"), *missing], check=True)

    for aid in ids:
        art, src = artifacts[aid], cache / aid
        files = art["files"]
        tmp = f"{TMP}/{aid}"
        run(*adb, "shell", "rm", "-rf", tmp)
        run(*adb, "shell", "mkdir", "-p", tmp)
        for f in files:
            run(*adb, "push", str(src / f["name"]), f"{tmp}/{f['name']}")
        run(*adb, "shell", "chmod", "-R", "a+rX", TMP)
        dst = f"no_backup/models/{aid}"
        run(*adb, "shell", "run-as", PKG, "rm", "-rf", dst)
        run(*adb, "shell", "run-as", PKG, "mkdir", "-p", dst)
        for f in files:
            run(*adb, "shell", "run-as", PKG, "cp", f"{tmp}/{f['name']}", f"{dst}/{f['name']}")
        marker = {
            "artifactId": aid,
            "catalogVersion": catalog["catalogVersion"],
            "files": {f["name"]: f["sha256"] for f in files},
            "bytesOnDisk": sum(f["bytes"] for f in files),
            "installedAtMs": int(time.time() * 1000),
            "prepared": prepared,
        }
        subprocess.run([*adb, "shell", "run-as", PKG, "sh", "-c", f"'cat > {dst}/installed.json'"],
                       input=json.dumps(marker), text=True, check=True, capture_output=True)
        listing = run(*adb, "shell", "run-as", PKG, "ls", "-l", dst)
        sizes = {line.split()[-1]: int(line.split()[4]) for line in listing.splitlines() if len(line.split()) >= 8}
        for f in files:
            if sizes.get(f["name"]) != f["bytes"]:
                sys.exit(f"{aid}/{f['name']}: {sizes.get(f['name'])} bytes on the device, catalog says {f['bytes']}")
        run(*adb, "shell", "rm", "-rf", tmp)
        print(f"installed {aid} ({marker['bytesOnDisk']} bytes, prepared={prepared})")


if __name__ == "__main__":
    main()
