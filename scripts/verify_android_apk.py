#!/usr/bin/env python3
"""Static Android APK checks. Debug JIT and release AOT have different layouts."""
import argparse
from pathlib import Path
import zipfile


def check(apk: Path, *, mode: str = "release"):
    if mode not in {"debug", "release"}:
        raise ValueError("APK mode must be debug or release")
    if not apk.is_file():
        raise FileNotFoundError(apk)
    with zipfile.ZipFile(apk) as archive:
        names = set(archive.namelist())
        essentials = (
            "AndroidManifest.xml",
            "classes.dex",
            "lib/arm64-v8a/libflutter.so",
            "lib/arm64-v8a/libmornye_audio.so",
        )
        # Flutter uses JIT kernel assets in debug builds; libapp.so is an AOT
        # artifact and is only required in profile/release builds.
        dart_entry = (
            "assets/flutter_assets/kernel_blob.bin"
            if mode == "debug"
            else "lib/arm64-v8a/libapp.so"
        )
        for required in (*essentials, dart_entry):
            if required not in names:
                raise RuntimeError(f"Missing {mode} Android startup entry: {required}")
        native_libraries = (
            "lib/arm64-v8a/libflutter.so",
            "lib/arm64-v8a/libmornye_audio.so",
            *([] if mode == "debug" else ["lib/arm64-v8a/libapp.so"]),
        )
        for native in native_libraries:
            with archive.open(native) as stream:
                if stream.read(4) != b"\x7fELF":
                    raise RuntimeError("Corrupt ELF native library: " + native)
        with archive.open("classes.dex") as dex:
            if not dex.read(8).startswith(b"dex\n"):
                raise RuntimeError("Malformed Android Java/Kotlin DEX")
        with archive.open(dart_entry) as stream:
            if not stream.read(1):
                raise RuntimeError("Empty Flutter runtime artifact: " + dart_entry)
    print(
        f"Verified {mode} arm64 startup essentials in {apk.name}; "
        "Galaxy hardware launch still required."
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--mode", choices=("debug", "release"), default="release")
    args = parser.parse_args()
    check(args.apk, mode=args.mode)
