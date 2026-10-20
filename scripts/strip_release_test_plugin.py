#!/usr/bin/env python3
"""Remove Flutter's dev-only integration_test registrar for a CI release APK.

The upstream app declares integration_test in dev_dependencies. Flutter's
generated GeneratedPluginRegistrant.java includes this plugin even though the
release Java compilation classpath omits its debug-only Android implementation.

Preserve dev dependency, debug test compilation, all other plugin registrars,
and the real runtime code. Called AFTER compiling debug and BEFORE release.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import re

PLUGIN = "dev.flutter.plugins.integration_test.IntegrationTestPlugin"
BLOCK = re.compile(
    r"(?ms)^(?P<indent>[ \t]+)try\s*\{\s*\n"
    r"(?P<body>.*?)"
    r"^(?P=indent)\}\s*catch\s*\(Exception e\)\s*\{\s*\n"
    r"(?P<catch>.*?)"
    r"^(?P=indent)\}\s*\r?\n"
)


def without_dev_plugin(source: str) -> str:
    matches = [
        match for match in BLOCK.finditer(source)
        if PLUGIN in match.group()
    ]
    if len(matches) != 1:
        raise ValueError(
            f"Expected one integration_test registration block, got {len(matches)}"
        )
    chosen = matches[0]
    text = source[: chosen.start()] + source[chosen.end() :]
    if PLUGIN in text:
        raise ValueError("Other integration_test references remain after patch")
    if text.count("registerWith") != source.count("registerWith"):
        raise ValueError("Generated registrant method was unexpectedly changed")
    return text


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("registrant", type=Path)
    args = parser.parse_args()
    source = args.registrant.read_text(encoding="utf-8")
    args.registrant.write_text(
        without_dev_plugin(source),
        encoding="utf-8",
    )
    print("Excluded only debug integration_test from Android release registrar.")


if __name__ == "__main__":
    main()
