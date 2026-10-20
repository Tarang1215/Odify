#!/usr/bin/env python3
"""Assemble Odify from a pinned upstream SpotiFLAC Mobile commit."""
from pathlib import Path
import shutil
import subprocess
import sys

UPSTREAM = "https://github.com/spotiflacapp/SpotiFLAC-Mobile.git"
REVISION = "21ee30c46d405aa72dd650d0cc6e3da0df831c12"
ROOT = Path(__file__).resolve().parents[1]
TARGET = Path(sys.argv[1]).resolve() if len(sys.argv)>1 else ROOT/"build"/"Odify"

def exact_replace(path, before, after):
    text = path.read_text(encoding="utf-8")
    count = text.count(before)
    if count != 1:
        raise RuntimeError(f"Upstream drift: {path}: anchor appeared {count} times")
    path.write_text(text.replace(before, after), encoding="utf-8")

def main():
    if TARGET.exists():
        raise RuntimeError(f"Target must not exist: {TARGET}")
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(["git", "clone", "--filter=blob:none", "--no-checkout", UPSTREAM, str(TARGET)], check=True)
    subprocess.run(["git", "-C", str(TARGET), "checkout", "--detach", REVISION], check=True)
    for source in (ROOT/"overlay").rglob("*"):
        if source.is_file():
            output = TARGET/source.relative_to(ROOT/"overlay")
            output.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, output)

    exact_replace(TARGET/"android/app/build.gradle.kts",
        'applicationId = "com.zarz.spotiflac"', 'applicationId = "com.odify.music"')
    exact_replace(TARGET/"android/app/src/main/AndroidManifest.xml",
        'android:label="SpotiFLAC Mobile"', 'android:label="Odify"')
    service=TARGET/"lib/services/music_player_service.dart"
    exact_replace(service, "    show ListeningTrack;", "    show ListeningTrack, ListeningStatisticsStore;")
    exact_replace(service, "part 'music_player_autoplay.dart';",
        "part 'music_player_autoplay.dart';\npart 'odify_taste.dart';")

    autoplay=TARGET/"lib/services/music_player_autoplay.dart"
    exact_replace(autoplay,
        "      final model = handler._runtime.personalization.rankingSnapshot;",
        "      final model = handler._runtime.personalization.rankingSnapshot;\n"
        "      final odifyTaste = await OdifyTasteProfile.fromLocalHistory();")
    exact_replace(autoplay,
        "        limit: 6 - upcoming.length,\n      );",
        "        limit: 6 - upcoming.length,\n"
        "        preferenceAdjustment: odifyTaste.bonus,\n      );")
    exact_replace(autoplay,
        "            6 - upcoming.length,\n          ), debugLabel: 'autoplay-ranking');",
        "            6 - upcoming.length,\n"
        "            odifyTaste,\n          ), debugLabel: 'autoplay-ranking');")
    exact_replace(autoplay,
        "  int,\n);\n\n/// Pass metadata and a frozen 64 KiB model only, never the player/runtime.",
        "  int,\n  OdifyTasteProfile,\n);\n\n/// Pass metadata and a frozen 64 KiB model only, never the player/runtime.")
    exact_replace(autoplay, "    limit,\n  ) = input;", "    limit,\n    taste,\n  ) = input;")
    exact_replace(autoplay,
        "    preferenceAdjustment: (track) => model.rankAdjustment(",
        "    preferenceAdjustment: (track) => taste.bonus(track) + model.rankAdjustment(")

    exact_replace(TARGET/"lib/screens/home_tab.dart",
        "import 'package:spotiflac_android/widgets/app_snack_bar.dart';",
        "import 'package:spotiflac_android/widgets/app_snack_bar.dart';\n"
        "import 'package:spotiflac_android/widgets/odify_device_library_card.dart';")
    exact_replace(TARGET/"lib/screens/home_tab_intro.dart",
        "    final sections = <Widget>[",
        "    final sections = <Widget>[\n"
        "      _HomeSection(title: 'Your phone music', inset: inset,\n"
        "          child: const OdifyDeviceLibraryCard()),")
    print(f"Assembled at {TARGET}")
    print("No APK has been built. Verify with pinned Flutter/Rust/Android tooling.")
if __name__ == "__main__":
    main()
