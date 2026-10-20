#!/usr/bin/env python3
"""Static release integrity checks, NEVER a substitute for on-device launch."""
import argparse
from pathlib import Path
import zipfile


def check(apk: Path):
    if not apk.is_file():
        raise FileNotFoundError(apk)
    with zipfile.ZipFile(apk) as archive:
        names = set(archive.namelist())
        for required in (
            "AndroidManifest.xml",
            "classes.dex",
            "lib/arm64-v8a/libflutter.so",
            "lib/arm64-v8a/libapp.so",
            "lib/arm64-v8a/libmornye_audio.so",
        ):
            if required not in names:
                raise RuntimeError(f"Missing vital Android startup/codec entry: {required}")
        for native in (
            "lib/arm64-v8a/libflutter.so",
            "lib/arm64-v8a/libapp.so",
            "lib/arm64-v8a/libmornye_audio.so",
        ):
            with archive.open(native) as stream:
                if stream.read(4) != b"\x7fELF":
                    raise RuntimeError("Corrupt ELF native library: " + native)
        with archive.open("classes.dex") as dex:
            if not dex.read(8).startswith(b"dex\n"):
                raise RuntimeError("Malformed Android Java/Kotlin DEX")
    print(f"Verified arm64 startup essentials in {apk.name}; hardware launch still required.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    check(args.apk)
