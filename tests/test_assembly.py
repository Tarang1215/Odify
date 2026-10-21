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
    'pubspec.yaml': 'name: mornye\nversion: 5.2.1+163\n  shared_preferences: ^2.5.6\n',
    'android/app/build.gradle.kts': 'applicationId = "com.zarz.mornye"\n        if (discordSdkEnabled) jniLibs.pickFirsts += "**/libdiscord_partner_sdk.so"\ndependencies {\n}\n',
    'android/app/src/main/AndroidManifest.xml': '<application android:name=".SpotiFlacApplication" android:label="Mornye" />\n',
    'android/app/proguard-rules.pro': '# Existing vendor configuration stays intact.\n',
    'lib/services/music_player_service.dart': (
        "import 'package:mornye/services/music_player_runtime.dart';\n"
        "    show ListeningTrack;\n"
        "part 'music_player_autoplay.dart';\n"
        "    _log.e('Playback failed for ${mediaItem.value?.title}', error, stackTrace);\n"
        "      _sourceReady = true;\n      if (media.source.startsWith('network://')) {\n"
        "  Future<String?> _resolveSource(PlayableMedia media) async {\n"
        "    if (media.isNetwork) {\n"
        "      await _player.setNetworkSource(path);\n"
        "          'https',\n        }.contains(Uri.tryParse(path)?.scheme) ||\n"
        "      extract: (source) =>\n"
        "          DownloadedEmbeddedCoverResolver.resolveOrExtract(source),\n"
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
    'third_party/audioplayers_android/android/src/main/kotlin/xyz/luan/audioplayers/source/UrlSource.kt': (
        "        } else {\n"
        "            mediaPlayer.setDataSource(url)\n"
        "        }\n"
    ),
    'android/app/src/main/kotlin/com/zarz/mornye/MainActivity.kt': (
        "            flutterEngine.plugins.add(MusicHapticsPlugin())\n        }\n"
    ),
    'lib/screens/track_metadata_screen.dart': (
        "import 'package:mornye/services/platform_bridge.dart';\\n"
    ),
    'lib/screens/home_tab.dart': (
        "import 'package:mornye/widgets/app_snack_bar.dart';\n"
        "slivers: [\n"
        "              _buildHeaderSliver(greeting: greeting),\n"
    ),
    'lib/providers/local_library_provider_sources.dart': (
        "  Future<bool?> _probePathAvailability(String path, {String? bookmark}) async {\n"
        "  Future<LocalLibrarySource?> _sourceForScan(String sourceId) async {\n"
    ),
    'lib/screens/home_tab_intro.dart': '    final sections = <Widget>[\n',
}

class OdifyAssemblyTests(unittest.TestCase):
    def test_full_overlay_applies_to_offline_fixture(self):
        mod = script()
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / 'Odify'
            mod.ROOT, mod.TARGET = ROOT, target
            calls = []
            def fake_git(argv, check=False):
                calls.append(argv)
                if 'clone' in argv:
                    # The canonical assembler now clones our *own* frozen
                    # source snapshot, not a live upstream checkout.
                    receipt = target / 'ODIFY_VENDOR_BASE.txt'
                    receipt.parent.mkdir(parents=True, exist_ok=True)
                    receipt.write_text(
                        'Immutable upstream commit: '
                        '8e4504e72ddcda6bdf6df5e1275396f46e9dfb9f\\n'
                    )
                    for relative, text in FIXTURES.items():
                        file = target / relative
                        file.parent.mkdir(parents=True, exist_ok=True)
                        file.write_text(text, encoding='utf-8')
                # port_521 is verified by a separate parser/contract check and
                # actual pinned-source CI, not the tiny synthetic fixture.
            with patch.object(mod.subprocess, 'run', side_effect=fake_git):
                mod.assemble()
            player = (target / 'lib/services/music_player_service.dart').read_text()
            self.assertIn('OdifyYouTube.resolveAudioSource(media.source)', player)
            self.assertIn('OdifyYouTube.invalidateAudioSource', player)
            self.assertIn('OdifyYouTube.prefetchAudioSource', player)
            self.assertIn('OdifyYouTube.prepareAudioSource(', player)
            self.assertIn('isCurrent: () => _isCurrentPlayRequest(generation, media)', player)
            self.assertIn("'odify-cdn'", player)
            self.assertIn("'odify-youtube'", player)
            self.assertIn('OdifyDeviceArtwork.forSource(source)', player)
            port = (ROOT / 'scripts/port_521.py').read_text()
            self.assertIn('odify-profile-drive-sync', port)
            self.assertIn('OdifyDeviceArtwork.resolve(', port)
            self.assertIn("if (!const bool.fromEnvironment('ODIFY_ENABLE_UPDATER'", port)
            self.assertTrue(any('port_521.py' in ' '.join(str(x) for x in args)
                                for args in calls))
            self.assertTrue(
                (target / 'android/app/src/main/kotlin/com/zarz/mornye/OdifyMediaStorePlugin.kt').is_file()
            )
            self.assertIn("'odify-youtube'", (target / 'lib/services/playable_media.dart').read_text())
            native = (target / 'third_party/audioplayers_android/android/src/main/kotlin/xyz/luan/audioplayers/source/UrlSource.kt').read_text()
            self.assertIn('mediaPlayer.setDataSource(odify.context, android.net.Uri.parse(odify.url), odify.headers)', native)
            self.assertTrue((target / 'third_party/audioplayers_android/android/src/main/kotlin/xyz/luan/audioplayers/source/OdifyStreamRegistry.kt').is_file())
            gradle = (target / 'android/app/build.gradle.kts').read_text()
            manifest = (target / 'android/app/src/main/AndroidManifest.xml').read_text()
            self.assertIn('com.odify.music', gradle)
            self.assertIn('package:mornye/services/playable_media.dart',
                          (target / 'lib/services/odify_youtube.dart').read_text())
            self.assertIn('google_sign_in: ^7.2.0', (target / 'pubspec.yaml').read_text())
            self.assertIn('odify_lyrics_fallback.dart',
                          (target / 'lib/screens/track_metadata_screen.dart').read_text())
            self.assertIn('jniLibs.useLegacyPackaging = true', gradle)
            zip_rules = (target / 'android/app/proguard-rules.pro').read_text()
            self.assertIn('# Existing vendor configuration stays intact.', zip_rules)
            self.assertIn('-keep class org.apache.commons.compress.archivers.zip.** { *; }', zip_rules)
            self.assertEqual(zip_rules.count('-keep class org.apache.commons.compress.archivers.zip.**'), 1)

            self.assertNotIn('android:extractNativeLibs', manifest)
            self.assertTrue((target / 'lib/services/odify_youtube.dart').is_file())
            home = (target / 'lib/screens/home_tab.dart').read_text()
            self.assertIn('OdifyQuickAccess(showDeviceLibrary: _showsHome)', home)
            self.assertIn('SliverToBoxAdapter(', home)
            self.assertLess(
                home.index('OdifyQuickAccess(showDeviceLibrary: _showsHome)'),
                home.index('_buildHomeIntroSliver') if '_buildHomeIntroSliver' in home else len(home),
            )
            self.assertNotIn('OdifyYouTubeExploreCard', (target / 'lib/screens/home_tab_intro.dart').read_text())
            self.assertTrue((target / 'lib/widgets/odify_quick_access.dart').is_file())
            sources = (target / 'lib/providers/local_library_provider_sources.dart').read_text()
            self.assertIn("if (path == 'legacy://local-library') return true;", sources)
            self.assertIn('sourceId == LocalLibraryItem.legacySourceId', sources)
            self.assertIn('item.path == \'legacy://local-library\'', sources)
            library_card = (target / 'lib/widgets/odify_device_library_card.dart').read_text()
            self.assertIn('setLocalLibraryEnabled(true)', library_card)
            self.assertIn('ref.invalidate(libraryBrowseProvider)', library_card)
            self.assertIn('reloadFromStorage()', library_card)

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

