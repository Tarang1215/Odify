#!/usr/bin/env python3
"""Verify immutable Mornye 5.2.1 lock, then run the canonical Odify assembler.

This gate deliberately uses scripts/assemble.py so CI cannot accidentally
validate one implementation while release artifacts use another.
"""
from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
LOCK = json.loads((ROOT / "config/upstream_lock.json").read_text())
TARGET = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else ROOT / "build/Odify"


def replace_method(path: str, signature: str, replacement: str):
    """Small balanced Dart method patch helper for existing offline unit tests."""
    dest = TARGET / path
    text = dest.read_text(encoding="utf-8")
    if text.count(signature) != 1:
        raise RuntimeError("Expected one Dart method signature: " + signature)
    start = text.index(signature)
    brace = text.index("{", start + len(signature))
    depth = 0
    end = -1
    for at in range(brace, len(text)):
        if text[at] == "{":
            depth += 1
        elif text[at] == "}":
            depth -= 1
            if depth == 0:
                end = at + 1
                break
    if end == -1:
        raise RuntimeError("Unbalanced Dart method: " + signature)
    dest.write_text(text[:start] + replacement + text[end:], encoding="utf-8")


def main():
    base = LOCK["base"]
    app = LOCK["app"]
    expected_sha = "8e4504e72ddcda6bdf6df5e1275396f46e9dfb9f"
    if (base["repository"] != "mornyeapp/Mornye" or
        base["version"] != "5.2.1" or
        base["commit"] != expected_sha or
        base["allowMovingTags"] or
        base["updatePolicy"] != "manual-review-only"):
        raise RuntimeError("Unreviewed upstream lock. Refusing to assemble.")
    spec = importlib.util.spec_from_file_location(
        "odify_canonical_assembler", ROOT / "scripts/assemble.py"
    )
    assembly = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(assembly)
    if (assembly.COMMIT != expected_sha or
        assembly.UPSTREAM != "https://github.com/mornyeapp/Mornye.git"):
        raise RuntimeError("Odify default assembler has diverged from source lock")
    assembly.TARGET = TARGET
    assembly.assemble()
    identity = (TARGET / "lib/constants/app_info.dart").read_text()
    version, build = app["ownVersion"].split("+")
    if (f"static const String version = '{version}';" not in identity or
        f"static const String buildNumber = '{build}';" not in identity or
        f"static const String githubRepo = '{app['updateRepo']}';" not in identity):
        raise RuntimeError("Odify app identity differs from version lock")
    updater = (TARGET / "lib/services/update_checker.dart").read_text()
    if "ODIFY_ENABLE_UPDATER" not in updater:
        raise RuntimeError("Upstream forced updater is not isolated")
    print("Validated immutable Mornye 5.2.1 source and Odify-only version", app["ownVersion"])


if __name__ == "__main__":
    main()
