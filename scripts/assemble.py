#!/usr/bin/env python3
"""Assemble Odify from the audited upstream SpotiFLAC commit + local overlay.

Does not invoke GitHub Actions, does not require an Android SDK and never
modifies the source SpotiFLAC repository. Requires ordinary git network access.
"""
from pathlib import Path
import shutil
import subprocess
import sys

UPSTREAM = 'https://github.com/spotiflacapp/SpotiFLAC-Mobile.git'
COMMIT = '21ee30c46d405aa72dd650d0cc6e3da0df831c12'  # Oct 19, 2026
ROOT = Path(__file__).resolve().parents[1]
TARGET = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else ROOT / 'build' / 'Odify'


def replace_one(path, old, new):
    text = path.read_text(encoding='utf-8')
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f'{path.relative_to(TARGET)}: expected one anchor; got {count}')
    path.write_text(text.replace(old, new), encoding='utf-8')


def assemble():
    if TARGET.exists():
        raise RuntimeError(f'Target already exists: {TARGET}. Choose another path.')
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(['git', 'clone', '--filter=blob:none', '--no-checkout', UPSTREAM, str(TARGET)], check=True)
    subprocess.run(['git', '-C', str(TARGET), 'checkout', '--detach', COMMIT], check=True)
    for source in (ROOT / 'overlay').rglob('*'):
        if source.is_file():
            destination = TARGET / source.relative_to(ROOT / 'overlay')
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)

    replace_one(TARGET / 'android/app/build.gradle.kts',
                'applicationId = "com.zarz.spotiflac"', 'applicationId = "com.odify.music"')
    replace_one(TARGET / 'android/app/src/main/AndroidManifest.xml',
                'android:label="SpotiFLAC Mobile"', 'android:label="Odify"')
    replace_one(TARGET / 'lib/services/music_player_service.dart',
                "    show ListeningTrack;", "    show ListeningTrack, ListeningStatisticsStore;")
    replace_one(TARGET / 'lib/services/music_player_service.dart',
                "import 'package:spotiflac_android/services/music_player_runtime.dart';",
                "import 'package:spotiflac_android/services/music_player_runtime.dart';\n"
                "import 'package:spotiflac_android/services/odify_likes.dart';")
    # Re-resolve YouTube stream URLs when playback starts (IDs survive restarts).
    replace_one(TARGET / 'lib/services/music_player_service.dart',
                "import 'package:spotiflac_android/services/music_player_runtime.dart';",
                "import 'package:spotiflac_android/services/music_player_runtime.dart';\n"
                "import 'package:spotiflac_android/services/odify_youtube.dart';")
    replace_one(TARGET / 'lib/services/music_player_service.dart',
                "  Future<String?> _resolveSource(PlayableMedia media) async {",
                "  Future<String?> _resolveSource(PlayableMedia media) async {\n"
                "    if (media.source.startsWith('odify-youtube://')) {\n"
                "      try {\n"
                "        return await OdifyYouTube.resolveAudioSource(media.source);\n"
                "      } catch (error) {\n"
                "        _log.w('Odify stream resolution failed: $error');\n"
                "        return null;\n"
                "      }\n"
                "    }")
    replace_one(TARGET / 'lib/services/playable_media.dart',
                "const {'network', 'http', 'https'}.contains(Uri.tryParse(source)?.scheme);",
                "const {'network', 'http', 'https', 'odify-youtube'}"
                ".contains(Uri.tryParse(source)?.scheme);")
    replace_one(TARGET / 'lib/services/music_player_service.dart',
                "part 'music_player_autoplay.dart';",
                "part 'music_player_autoplay.dart';\npart 'odify_taste.dart';")
    autoplay = TARGET / 'lib/services/music_player_autoplay.dart'
    replace_one(autoplay,
                "      final model = handler._runtime.personalization.rankingSnapshot;",
                "      final model = handler._runtime.personalization.rankingSnapshot;\n"
                "      final odifyTaste = await OdifyTasteProfile.fromLocalHistory();")
    replace_one(autoplay,
                "        limit: 6 - upcoming.length,\n      );",
                "        limit: 6 - upcoming.length,\n"
                "        preferenceAdjustment: odifyTaste.bonus,\n      );")
    replace_one(autoplay,
                "            6 - upcoming.length,\n          ), debugLabel: 'autoplay-ranking');",
                "            6 - upcoming.length,\n"
                "            odifyTaste,\n          ), debugLabel: 'autoplay-ranking');")
    replace_one(autoplay,
                "  int,\n);\n\n/// Pass metadata and a frozen 64 KiB model only, never the player/runtime.",
                "  int,\n  OdifyTasteProfile,\n);\n\n/// Pass metadata and a frozen 64 KiB model only, never the player/runtime.")
    replace_one(autoplay,
                "    limit,\n  ) = input;",
                "    limit,\n    taste,\n  ) = input;")
    replace_one(autoplay,
                "    preferenceAdjustment: (track) => model.rankAdjustment(",
                "    preferenceAdjustment: (track) => taste.bonus(track) + model.rankAdjustment(")
    replace_one(TARGET / 'android/app/src/main/kotlin/com/zarz/spotiflac/MainActivity.kt',
                "            flutterEngine.plugins.add(MusicHapticsPlugin())\n        }",
                "            flutterEngine.plugins.add(MusicHapticsPlugin())\n        }\n"
                "        if (!flutterEngine.plugins.has(OdifyMediaStorePlugin::class.java)) {\n"
                "            flutterEngine.plugins.add(OdifyMediaStorePlugin())\n        }")
    # yt-dlp is an optional GPL-3.0 native dependency; retain attribution.
    gradle = TARGET / 'android/app/build.gradle.kts'
    gradle_text = gradle.read_text(encoding='utf-8')
    dependency_anchor = 'dependencies {\n'
    if gradle_text.count(dependency_anchor) != 1:
        raise RuntimeError('Expected exactly one Gradle dependencies block')
    replace_one(gradle, dependency_anchor,
                dependency_anchor +
                '    implementation("io.github.junkfood02.youtubedl-android:library:0.16.0")\n'
                '    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.16.0")\n')
    # yt-dlp bundles ZIP archives as pseudo .so assets. They are not ELF
    # binaries and must be excluded from native llvm-strip processing.
    replace_one(gradle,
                '        if (discordSdkEnabled) jniLibs.pickFirsts += "**/libdiscord_partner_sdk.so"',
                '        if (discordSdkEnabled) jniLibs.pickFirsts += "**/libdiscord_partner_sdk.so"\n'
                '        jniLibs.keepDebugSymbols += setOf("**/libffmpeg.zip.so", "**/libpython.zip.so")\n'
                '        jniLibs.useLegacyPackaging = true')
    replace_one(TARGET / 'android/app/src/main/kotlin/com/zarz/spotiflac/MainActivity.kt',
                '            flutterEngine.plugins.add(OdifyMediaStorePlugin())\n        }',
                '            flutterEngine.plugins.add(OdifyMediaStorePlugin())\n        }\n'
                '        if (!flutterEngine.plugins.has(OdifyYtDlpPlugin::class.java)) {\n'
                '            flutterEngine.plugins.add(OdifyYtDlpPlugin())\n        }')
    # MediaStore tracks use the upstream legacy source, whose virtual URI is
    # not a real filesystem directory. Preserve visibility of this source and
    # never attempt a native folder scan against the virtual URI.
    local_sources = TARGET / 'lib/providers/local_library_provider_sources.dart'
    replace_one(local_sources,
                "  Future<bool?> _probePathAvailability(String path, {String? bookmark}) async {",
                "  Future<bool?> _probePathAvailability(String path, {String? bookmark}) async {\n"
                "    if (path == 'legacy://local-library') return true;")
    replace_one(local_sources,
                "  Future<LocalLibrarySource?> _sourceForScan(String sourceId) async {",
                "  Future<LocalLibrarySource?> _sourceForScan(String sourceId) async {\n"
                "    if (sourceId == LocalLibraryItem.legacySourceId) {\n"
                "      final source = await _db.getSources();\n"
                "      if (source.any((item) => item.id == sourceId &&\n"
                "          item.path == 'legacy://local-library')) return null;\n"
                "    }")
    home = TARGET / 'lib/screens/home_tab.dart'
    replace_one(home,
                "import 'package:spotiflac_android/widgets/app_snack_bar.dart';",
                "import 'package:spotiflac_android/widgets/app_snack_bar.dart';\n"
                "import 'package:spotiflac_android/widgets/odify_quick_access.dart';")
    # This sliver is outside SpotiFLAC's extension-dependent home sections.
    # It stays visible in both Home/Browse and Search even when no provider is
    # installed; the Search tab avoids duplicating the MediaStore sync card.
    replace_one(home,
                '              _buildHeaderSliver(greeting: greeting),',
                '              _buildHeaderSliver(greeting: greeting),\n'
                '              SliverToBoxAdapter(\n'
                '                child: OdifyQuickAccess(showDeviceLibrary: _showsHome),\n'
                '              ),')
    print(f'Odify assembled at {TARGET}')
    print('Next: install the pinned Flutter/Rust/Android toolchain from upstream CI; flutter test.')


if __name__ == '__main__':
    assemble()
