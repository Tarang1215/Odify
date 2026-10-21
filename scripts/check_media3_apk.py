#!/usr/bin/env python3
"""Check that Odify's dedicated Media3 engine survives APK packaging.

This is a static DEX sanity check, not Android hardware playback validation.
R8 keep rules preserve the Odify bridge class name while allowing Media3 to
shrink and optimize normally.
"""
from pathlib import Path
import sys
import zipfile

REQUIRED = (
    b"Lxyz/luan/audioplayers/player/OdifyExoPlayerWrapper;",
    b"OdifyMedia3",
)


def verify(apk: Path) -> None:
    if not apk.is_file():
        raise FileNotFoundError(apk)
    with zipfile.ZipFile(apk) as archive:
        names = [n for n in archive.namelist() if n.startswith("classes") and n.endswith(".dex")]
        if not names:
            raise RuntimeError("Android APK has no DEX files")
        payload = b"".join(archive.read(n) for n in names)
    missing = [term.decode("ascii") for term in REQUIRED if term not in payload]
    if missing:
        raise RuntimeError("Odify Media3 streaming bridge not packaged: " + ", ".join(missing))
    print("Odify Media3 bridge found in " + apk.name +
          "; Galaxy first-audio playback still requires physical device test.")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: check_media3_apk.py <apk>")
    verify(Path(sys.argv[1]))
