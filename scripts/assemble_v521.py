#!/usr/bin/env python3
"""Build Odify from ONE audited, immutable Mornye 5.2.1 source revision.

The pinned upstream is a *build-time source input*, never an update feed.
All original upstream automatic-updater prompts are disabled in Odify.
A newer upstream release cannot silently change Odify without a lock change,
a compatibility rebase, and a new green CI run.
"""
from __future__ import annotations

import json
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
LOCK = json.loads((ROOT / "config/upstream_lock.json").read_text())
BASE = LOCK["base"]
APP = LOCK["app"]
UPSTREAM = "https://github.com/" + BASE["repository"] + ".git"
COMMIT = BASE["commit"]
TARGET = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else ROOT / "build/Odify"


def change(path: str, before: str, after: str):
    dest = TARGET / path
    content = dest.read_text(encoding="utf-8")
    n = content.count(before)
    if n != 1:
        raise RuntimeError("Upstream 5.2.1 drift: %s: %r occurred %d times" % (path, before[:70], n))
    dest.write_text(content.replace(before, after), encoding="utf-8")


def overlay():
    for source in (ROOT / "overlay").rglob("*"):
        if not source.is_file():
            continue
        rel = source.relative_to(ROOT / "overlay")
        dest = TARGET / rel
        if rel.parts[:6] == ("android", "app", "src", "main", "kotlin", "com"):
            # Mornye 5.2.1 renamed its Android namespace and Kotlin package.
            rel = Path(str(rel).replace(
                "com/zarz/spotiflac/Odify", "com/zarz/mornye/Odify"
            ))
            dest = TARGET / rel
            content = source.read_text(encoding="utf-8").replace(
                "package com.zarz.spotiflac", "package com.zarz.mornye"
            )
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_text(content, encoding="utf-8")
        else:
            dest.parent.mkdir(parents=True, exist_ok=True)
            if source.suffix == ".dart":
                # The package rename is global and mechanical. Tests and Odify
                # domain widgets continue to share the same Dart modules.
                content = source.read_text(encoding="utf-8").replace(
                    "package:spotiflac_android/", "package:mornye/"
                )
                dest.write_text(content, encoding="utf-8")
            else:
                shutil.copy2(source, dest)
    # Refuse to accidentally embed the obsolete Kotlin package. It is not
    # registered under the 5.2.1 MainActivity and would silently be dead code.
    assert not (TARGET / "android/app/src/main/kotlin/com/zarz/spotiflac/OdifyYtDlpPlugin.kt").exists()


def apply_patches():
    change("pubspec.yaml", "  shared_preferences: ^2.5.6",
        "  shared_preferences: ^2.5.6\n  google_sign_in: ^7.2.0")
    change("pubspec.yaml", "version: 5.2.1+163", "version: " + APP["ownVersion"])
    gradle = "android/app/build.gradle.kts"
    change(gradle, 'applicationId = "com.zarz.mornye"',
        'applicationId = "' + APP["id"] + '"')
    change("android/app/src/main/AndroidManifest.xml",
        'android:label="Mornye"', 'android:label="' + APP["displayName"] + '"')
    change("lib/constants/app_info.dart",
        "static const String version = '5.2.1';",
        "static const String version = '0.9.0';")
    change("lib/constants/app_info.dart",
        "static const String buildNumber = '163';",
        "static const String buildNumber = '170';")
    change("lib/constants/app_info.dart",
        "static const String appName = 'Mornye';",
        "static const String appName = 'Odify';")
    change("lib/constants/app_info.dart",
        "static const String githubRepo = 'spotiflacapp/SpotiFLAC-Mobile';",
        "static const String githubRepo = '" + APP["updateRepo"] + "';")
    # Avoid upstream release feeds, force-update dialogs and upstream version
    # comparisons in Odify. Future Odify releases require a separate updater
    # design and signed release metadata; never bypass this guard at runtime.
    change("lib/screens/main_shell_startup_prompts.dart",
        "  Future<bool> _checkForUpdates() async {",
        "  Future<bool> _checkForUpdates() async {\n"
        "    // Odify is an independent app. Disable Mornye's forced updates.\n"
        "    _hasCheckedUpdate = true;\n"
        "    return false;\n"
        "  }\n\n"
        "  Future<bool> _disabledUpstreamUpdateCheck() async {")
    # Disable the upstream 'new 5.2 release' mandatory product tour. Preserve
    # ordinary first-install onboarding so permissions still work.
    change("lib/screens/main_shell_startup_prompts.dart",
        "  Future<void> _checkUpgradeIntro() async {",
        "  Future<void> _checkUpgradeIntro() async {\n"
        "    return;\n"
        "  }\n\n"
        "  Future<void> _disabledUpstreamUpgradeTour() async {")

    player = "lib/services/music_player_service.dart"
    change(player, "show ListeningTrack;",
        "show ListeningTrack, ListeningStatisticsStore;")
    change(player, "import 'package:mornye/services/music_player_runtime.dart';",
        "import 'package:mornye/services/music_player_runtime.dart';\n"
        "import 'package:mornye/services/odify_likes.dart';\n"
        "import 'package:mornye/services/odify_device_artwork.dart';\n"
        "import 'package:mornye/services/odify_youtube.dart';")
    change(player, "      extract: (source) =>\n"
        "          DownloadedEmbeddedCoverResolver.resolveOrExtract(source),",
        "      extract: (source) async =>\n"
        "          await OdifyDeviceArtwork.forSource(source) ??\n"
        "          await DownloadedEmbeddedCoverResolver.resolveOrExtract(source),")
    change(player, "  Future<String?> _resolveSource(PlayableMedia media) async {",
        "  Future<String?> _resolveSource(PlayableMedia media) async {\n"
        "    if (media.source.startsWith('odify-youtube://')) {\n"
        "      try {\n"
        "        return await OdifyYouTube.resolveAudioSource(media.source);\n"
        "      } catch (error) {\n"
        "        _log.w('Odify stream resolution failed: $error');\n"
        "        return null;\n"
        "      }\n"
        "    }")
    change(player, "part 'music_player_autoplay.dart';",
        "part 'music_player_autoplay.dart';\npart 'odify_taste.dart';")
    change("lib/services/playable_media.dart",
        "const {'network', 'http', 'https'}.contains(Uri.tryParse(source)?.scheme);",
        "const {'network', 'http', 'https', 'odify-youtube'}"
        ".contains(Uri.tryParse(source)?.scheme);")

    autoplay = "lib/services/music_player_autoplay.dart"
    change(autoplay,
        "      final model = handler._runtime.personalization.rankingSnapshot;",
        "      final model = handler._runtime.personalization.rankingSnapshot;\n"
        "      final odifyTaste = await OdifyTasteProfile.fromLocalHistory();")
    change(autoplay, "        limit: 6 - upcoming.length,\n      );",
        "        limit: 6 - upcoming.length,\n"
        "        preferenceAdjustment: odifyTaste.bonus,\n      );")
    change(autoplay, "            6 - upcoming.length,\n          ), debugLabel: 'autoplay-ranking');",
        "            6 - upcoming.length,\n"
        "            odifyTaste,\n          ), debugLabel: 'autoplay-ranking');")
    change(autoplay,
        "  int,\n);\n\n/// Pass metadata and a frozen 64 KiB model only, never the player/runtime.",
        "  int,\n  OdifyTasteProfile,\n);\n\n"
        "/// Pass metadata and a frozen 64 KiB model only, never the player/runtime.")
    change(autoplay,
        "    limit,\n  ) = input;", "    limit,\n    taste,\n  ) = input;")
    change(autoplay,
        "    preferenceAdjustment: (track) => model.rankAdjustment(",
        "    preferenceAdjustment: (track) => taste.bonus(track) + model.rankAdjustment(")

    activity = "android/app/src/main/kotlin/com/zarz/mornye/MainActivity.kt"
    change(activity, "            flutterEngine.plugins.add(MusicHapticsPlugin())\n        }",
        "            flutterEngine.plugins.add(MusicHapticsPlugin())\n        }\n"
        "        if (!flutterEngine.plugins.has(OdifyMediaStorePlugin::class.java)) {\n"
        "            flutterEngine.plugins.add(OdifyMediaStorePlugin())\n"
        "        }\n"
        "        if (!flutterEngine.plugins.has(OdifyYtDlpPlugin::class.java)) {\n"
        "            flutterEngine.plugins.add(OdifyYtDlpPlugin())\n"
        "        }")
    change(gradle, "dependencies {\n",
        "dependencies {\n"
        '    implementation("io.github.junkfood02.youtubedl-android:library:0.16.0")\n'
        '    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.16.0")\n')
    change(gradle,
        '        if (discordSdkEnabled) jniLibs.pickFirsts += "**/libdiscord_partner_sdk.so"',
        '        if (discordSdkEnabled) jniLibs.pickFirsts += "**/libdiscord_partner_sdk.so"\n'
        '        jniLibs.keepDebugSymbols += setOf("**/libffmpeg.zip.so", "**/libpython.zip.so")\n'
        '        jniLibs.useLegacyPackaging = true')
    local_sources = "lib/providers/local_library_provider_sources.dart"
    change(local_sources,
        "  Future<bool?> _probePathAvailability(String path, {String? bookmark}) async {",
        "  Future<bool?> _probePathAvailability(String path, {String? bookmark}) async {\n"
        "    if (path == 'legacy://local-library') return true;")
    change(local_sources,
        "  Future<LocalLibrarySource?> _sourceForScan(String sourceId) async {",
        "  Future<LocalLibrarySource?> _sourceForScan(String sourceId) async {\n"
        "    if (sourceId == LocalLibraryItem.legacySourceId) {\n"
        "      final source = await _db.getSources();\n"
        "      if (source.any((item) => item.id == sourceId &&\n"
        "          item.path == 'legacy://local-library')) return null;\n"
        "    }")
    home = "lib/screens/home_tab.dart"
    change(home, "import 'package:mornye/widgets/app_snack_bar.dart';",
        "import 'package:mornye/widgets/app_snack_bar.dart';\n"
        "import 'package:mornye/widgets/odify_quick_access.dart';")
    change(home, "              _buildHeaderSliver(greeting: greeting),",
        "              _buildHeaderSliver(greeting: greeting),\n"
        "              SliverToBoxAdapter(\n"
        "                child: OdifyQuickAccess(showDeviceLibrary: _showsHome),\n"
        "              ),")


def main():
    if BASE["repository"] != "mornyeapp/Mornye" or BASE["version"] != "5.2.1":
        raise RuntimeError("Unexpected Odify upstream! Review lock changes.")
    if COMMIT != "8e4504e72ddcda6bdf6df5e1275396f46e9dfb9f":
        raise RuntimeError("Unreviewed base commit; must update tests first.")
    if TARGET.exists():
        raise RuntimeError("Refusing to overwrite existing build: %s" % TARGET)
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(["git", "clone", "--filter=blob:none", "--no-checkout", UPSTREAM, str(TARGET)], check=True)
    # Fetch tagged commit by immutable SHA, NOT upstream's moving main.
    subprocess.run(["git", "-C", str(TARGET), "checkout", "--detach", COMMIT], check=True)
    overlay()
    apply_patches()
    # Don't carry obsolete update metadata from the inherited app.
    assert "spotiflacapp/SpotiFLAC-Mobile" not in (
        TARGET / "lib/constants/app_info.dart"
    ).read_text()
    print("Odify assembled from immutable Mornye 5.2.1:", COMMIT)
    print("Update prompts disabled; independent Odify version:", APP["ownVersion"])


if __name__ == "__main__":
    main()
