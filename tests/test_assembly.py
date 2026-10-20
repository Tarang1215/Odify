"""Regression checks for Odify's overlay assembly. No Android SDK required."""
from __future__ import annotations
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]

def script():
    spec = importlib.util.spec_from_file_location('odify_assemble', ROOT / 'scripts/assemble.py')
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result

FIXTURES = {
    'android/app/build.gradle.kts': 'applicationId = "com.zarz.spotiflac"\ndependencies {\n}\n',
    'android/app/src/main/AndroidManifest.xml': '<application android:name=".SpotiFlacApplication" android:label="SpotiFLAC Mobile" />\n',
    'lib/services/music_player_service.dart': (
        "import 'package:spotiflac_android/services/music_player_runtime.dart';\n"
        "    show ListeningTrack;\n"
        "part 'music_player_autoplay.dart';\n"
        "  Future<String?> _resolveSource(PlayableMedia media) async {\n"
    ),
    'lib/services/playable_media.dart': (
        "const {'network', 'http', 'https'}.contains(Uri.tryParse(source)?.scheme);\n"
    ),
    'lib/services/music_player_autoplay.dart': (
        "      final model = handler._runtime.personalization.rankingSnapshot;\n"
        "        limit: 6 - upcoming.length,\n      );\n"
        "            6 - upcoming.length,\n          ), debugLabel: 'autoplay-ranking');\n"
        "  int,\n);\n\n/// Pass metadata and a frozen 64 KiB model only, never the player/runtime.\n"
        "    limit,\n  ) = input;\n"
        "    preferenceAdjustment: (track) => model.rankAdjustment(\n"
    ),
    'android/app/src/main/kotlin/com/zarz/spotiflac/MainActivity.kt': (
        "            flutterEngine.plugins.add(MusicHapticsPlugin())\n        }\n"
    ),
    'lib/screens/home_tab.dart': "import 'package:spotiflac_android/widgets/app_snack_bar.dart';\n",
    'lib/screens/home_tab_intro.dart': '    final sections = <Widget>[\n',
}

class OdifyAssemblyTests(unittest.TestCase):
    def test_full_overlay_applies_to_offline_fixture(self):
        mod = script()
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / 'Odify'
            mod.ROOT, mod.TARGET = ROOT, target
            def fake_git(argv, check=False):
                if 'clone' in argv:
                    for relative, text in FIXTURES.items():
                        file = target / relative
                        file.parent.mkdir(parents=True, exist_ok=True)
                        file.write_text(text, encoding='utf-8')
            with patch.object(mod.subprocess, 'run', side_effect=fake_git):
                mod.assemble()
            player = (target / 'lib/services/music_player_service.dart').read_text()
            self.assertIn('OdifyYouTube.resolveAudioSource(media.source)', player)
            self.assertIn("'odify-youtube'", (target / 'lib/services/playable_media.dart').read_text())
            self.assertIn('com.odify.music', (target / 'android/app/build.gradle.kts').read_text())
            self.assertTrue((target / 'lib/services/odify_youtube.dart').is_file())

    def test_no_signed_audio_url_persisted_in_queue(self):
        source = (ROOT / 'overlay/lib/services/odify_youtube.dart').read_text()
        self.assertIn("return '$sourceScheme://video/$videoId';", source)
        self.assertIn('return uri.pathSegments.single;', source)
        self.assertIn('source: sourceForId(video.id)', source)
        self.assertNotIn('source: url,', source)

    def test_patch_fails_on_duplicate_anchor(self):
        mod = script()
        with tempfile.TemporaryDirectory() as temporary:
            mod.TARGET = Path(temporary)
            file = Path(temporary) / 'f.txt'
            file.write_text('x\nx\n', encoding='utf-8')
            with self.assertRaisesRegex(RuntimeError, 'expected one anchor'):
                mod.replace_one(file, 'x', 'y')

if __name__ == '__main__':
    unittest.main()
