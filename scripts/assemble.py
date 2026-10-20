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
    home = TARGET / 'lib/screens/home_tab.dart'
    replace_one(home,
                "import 'package:spotiflac_android/widgets/app_snack_bar.dart';",
                "import 'package:spotiflac_android/widgets/app_snack_bar.dart';\n"
                "import 'package:spotiflac_android/widgets/odify_device_library_card.dart';")
    replace_one(TARGET / 'lib/screens/home_tab_intro.dart',
                "    final sections = <Widget>[",
                "    final sections = <Widget>[\n"
                "      _HomeSection(title: 'Your phone music', inset: inset,\n"
                "          child: const OdifyDeviceLibraryCard()),")
    print(f'Odify assembled at {TARGET}')
    print('Next: install the pinned Flutter/Rust/Android toolchain from upstream CI; flutter test.')


if __name__ == '__main__':
    assemble()
