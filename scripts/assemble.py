#!/usr/bin/env python3
"""Assemble Odify from the audited upstream SpotiFLAC commit + local overlay.

Does not invoke GitHub Actions, does not require an Android SDK and never
modifies the source SpotiFLAC repository. Requires ordinary git network access.
"""
from pathlib import Path
import shutil
import subprocess
import sys

# Runtime is built exclusively from an Odify-owned frozen source snapshot.
# Original upstream commit remains documented in config/upstream_lock.json.
UPSTREAM = 'https://github.com/Tarang1215/Odify.git'
COMMIT = '191fd35e1d9cf147a54312453c39e717408d0454'  # own immutable vendor commit
ORIGINAL_UPSTREAM_COMMIT = '8e4504e72ddcda6bdf6df5e1275396f46e9dfb9f'
ROOT = Path(__file__).resolve().parents[1]
TARGET = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else ROOT / 'build' / 'Odify'


def replace_one(path, old, new):
    old = old.replace('package:spotiflac_android/', 'package:mornye/')
    new = new.replace('package:spotiflac_android/', 'package:mornye/')
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
    # A vendor receipt binds the Odify snapshot to the reviewed upstream SHA.
    # No automatic vendor upgrade can silently change the audio core.
    vendor_receipt = TARGET / 'ODIFY_VENDOR_BASE.txt'
    if not vendor_receipt.is_file() or ORIGINAL_UPSTREAM_COMMIT not in vendor_receipt.read_text(encoding='utf-8'):
        raise RuntimeError('Reviewed vendor source provenance file missing/mismatched')
    # The 5.2.1 Flutter package and Kotlin namespace were renamed. Carry
    # Odify's independent modules across, but preserve the newest upstream
    # framework/initialization implementations and patch them later by anchors.
    framework_sources = {
        # Never replace freshly pinned v5.2.1 core identity or lifecycle files
        # with an older 5.2.0 overlay; reviewed port_521 adjusts their seams.
        'android/app/src/main/kotlin/com/zarz/spotiflac/SpotiFlacApplication.kt',
        'android/app/src/main/kotlin/com/zarz/spotiflac/ImpellerCompatibility.kt',
        'lib/providers/theme_provider.dart',
        'lib/providers/runtime_profile_provider.dart',
        'lib/screens/mornye_library_screen.dart',
        'lib/screens/settings/profile_settings_page.dart',
        'lib/screens/settings/settings_tab.dart',
        'lib/widgets/local_library_artwork_builder.dart',
        'lib/widgets/mini_player.dart',
    }
    for source in (ROOT / 'overlay').rglob('*'):
        if not source.is_file():
            continue
        relative = source.relative_to(ROOT / 'overlay').as_posix()
        if relative in framework_sources:
            continue
        if relative.startswith('android/app/src/main/kotlin/com/zarz/spotiflac/'):
            relative = relative.replace('/com/zarz/spotiflac/', '/com/zarz/mornye/')
        destination = TARGET / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        if source.suffix == '.dart':
            destination.write_text(
                source.read_text(encoding='utf-8').replace(
                    'package:spotiflac_android/', 'package:mornye/'
                ), encoding='utf-8',
            )
        elif source.suffix == '.kt':
            destination.write_text(
                source.read_text(encoding='utf-8').replace(
                    'package com.zarz.spotiflac', 'package com.zarz.mornye'
                ), encoding='utf-8',
            )
        else:
            shutil.copy2(source, destination)

    # Google OAuth is an opt-in client-only SDK. No Firebase backend or
    # Trove/Supabase dependency is introduced. A separate public Web OAuth
    # server client ID will be supplied using --dart-define at a later build.
    replace_one(TARGET / 'pubspec.yaml',
                'version: 5.2.1+163', 'version: 0.9.2+172')
    replace_one(TARGET / 'pubspec.yaml',
                '  shared_preferences: ^2.5.6',
                '  shared_preferences: ^2.5.6\n'
                '  google_sign_in: ^7.2.0')
    replace_one(TARGET / 'android/app/build.gradle.kts',
                'applicationId = "com.zarz.mornye"', 'applicationId = "com.odify.music"')
    replace_one(TARGET / 'android/app/src/main/AndroidManifest.xml',
                'android:label="Mornye"', 'android:label="Odify"')
    # Share Odify's LRCLIB fallback with track detail and player screens.
    replace_one(TARGET / 'lib/screens/track_metadata_screen.dart',
                "import 'package:spotiflac_android/services/platform_bridge.dart';",
                "import 'package:spotiflac_android/services/platform_bridge.dart';\n"
                "import 'package:spotiflac_android/services/odify_lyrics_fallback.dart';")
    replace_one(TARGET / 'lib/services/music_player_service.dart',
                "show ListeningTrack;", "show ListeningTrack, ListeningStatisticsStore;")
    replace_one(TARGET / 'lib/services/music_player_service.dart',
                "import 'package:spotiflac_android/services/music_player_runtime.dart';",
                "import 'package:spotiflac_android/services/music_player_runtime.dart';\n"
                "import 'package:spotiflac_android/services/odify_likes.dart';")
    # Samsung/MediaStore content URIs require on-demand embedded art lookup.
    # Mornye's SAF artwork resolver remains the fallback for other sources.
    replace_one(TARGET / 'lib/services/music_player_service.dart',
                "import 'package:spotiflac_android/services/music_player_runtime.dart';",
                "import 'package:spotiflac_android/services/music_player_runtime.dart';\n"
                "import 'package:spotiflac_android/services/odify_device_artwork.dart';")
    replace_one(TARGET / 'lib/services/music_player_service.dart',
                "      extract: (source) =>\n"
                "          DownloadedEmbeddedCoverResolver.resolveOrExtract(source),",
                "      extract: (source) async =>\n"
                "          await OdifyDeviceArtwork.forSource(source) ??\n"
                "          await DownloadedEmbeddedCoverResolver.resolveOrExtract(source),")
    # Odify's transient CDN handle and persistent YouTube ID are network
    # identities. Never ask ReplayGain's local-file analyzer to open them;
    # otherwise every Play/Next can incur failed filesystem probing.
    replace_one(TARGET / 'lib/services/music_player_service.dart',
                "          'https',\n        }.contains(Uri.tryParse(path)?.scheme) ||",
                "          'https',\n          'odify-cdn',\n"
                "          'odify-youtube',\n        }.contains(Uri.tryParse(path)?.scheme) ||")
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
    # The initial synchronous Android prepare AND resume errors retry once.
    replace_one(TARGET / 'lib/services/music_player_service.dart',
                "    if (media.isNetwork) {\n      await _player.setNetworkSource(path);\n",
                "    if (media.source.startsWith('odify-youtube://')) {\n      // Prepare AND resume inside the retry boundary. Android can report\n      // MEDIA_ERROR_UNKNOWN only after asynchronous MediaPlayer preparation.\n      await OdifyYouTube.prepareAudioSource(\n        source: media.source,\n        resolved: path,\n        isCurrent: () => _isCurrentPlayRequest(generation, media),\n        prepare: (source) async {\n          await _player.setNetworkSource(source);\n          if (!_isCurrentPlayRequest(generation, media)) return;\n          if (position != null) {\n            await _player.seek(position);\n            if (!_isCurrentPlayRequest(generation, media)) return;\n          }\n          await _player.resume();\n          if (!_isCurrentPlayRequest(generation, media)) {\n            await _player.pause();\n          }\n        },\n      );\n      return;\n    }\n    if (media.isNetwork) {\n      await _player.setNetworkSource(path);\n")
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
    replace_one(TARGET / 'android/app/src/main/kotlin/com/zarz/mornye/MainActivity.kt',
                "            flutterEngine.plugins.add(MusicHapticsPlugin())\n        }",
                "            flutterEngine.plugins.add(MusicHapticsPlugin())\n        }\n"
                "        if (!flutterEngine.plugins.has(OdifyMediaStorePlugin::class.java)) {\n"
                "            flutterEngine.plugins.add(OdifyMediaStorePlugin())\n        }")
    replace_one(TARGET / 'lib/services/music_player_service.dart',
                "  int _playRequestGeneration = 0;",
                "  int _playRequestGeneration = 0;\n  int? _odifyAsyncRecoveryAttempted;\n  int? _odifyAsyncRecoveryActive;")
    replace_one(TARGET / 'lib/services/music_player_service.dart',
                "    _sourceReady = false;\n    _playbackError = error;",
                "    if (_odifyAsyncRecoveryActive == _playRequestGeneration) return;\n    // MediaPlayer may emit MEDIA_ERROR_UNKNOWN via the event stream AFTER\n    // setSource/resume have returned. The synchronous retry cannot see it.\n    // Recover once per play generation; never retry local files or older songs.\n    if (_sourceReady &&\n        _switchingGeneration == 0 &&\n        _index >= 0 &&\n        _index < _media.length &&\n        _media[_index].source.startsWith('odify-youtube://') &&\n        !_userPaused &&\n        _odifyAsyncRecoveryAttempted != _playRequestGeneration) {\n      final generation = _playRequestGeneration;\n      final track = _media[_index];\n      _odifyAsyncRecoveryAttempted = generation;\n      _odifyAsyncRecoveryActive = generation;\n      _sourceReady = false;\n      _broadcastState(playerState: PlayerState.playing, loading: true);\n      unawaited(_serializeSourceChange(() async {\n        try {\n          if (!_isCurrentPlayRequest(generation, track)) return;\n          await OdifyYouTube.recoverAfterMediaError(\n            source: track.source,\n            isCurrent: () =>\n                _isCurrentPlayRequest(generation, track) && !_userPaused,\n            prepare: (relay) async {\n              if (!_isCurrentPlayRequest(generation, track) || _userPaused) return;\n              await player.stop();\n              if (!_isCurrentPlayRequest(generation, track) || _userPaused) return;\n              await player.setNetworkSource(relay);\n              if (!_isCurrentPlayRequest(generation, track) || _userPaused) return;\n              await player.setVolume(_normalizationVolume);\n              if (!_isCurrentPlayRequest(generation, track) || _userPaused) return;\n              await player.resume();\n              if (!_isCurrentPlayRequest(generation, track)) {\n                await player.pause();\n              }\n            },\n          );\n          if (!_isCurrentPlayRequest(generation, track)) return;\n          if (_userPaused) {\n            _sourceReady = false;\n            _broadcastState(playerState: PlayerState.paused);\n            return;\n          }\n          _sourceReady = true;\n          _broadcastState();\n        } catch (recoverError, recoverStack) {\n          if (_isCurrentPlayRequest(generation, track)) {\n            _odifyAsyncRecoveryActive = null;\n            _reportPlaybackError(player, recoverError, recoverStack);\n          }\n        } finally {\n          if (_odifyAsyncRecoveryActive == generation) {\n            _odifyAsyncRecoveryActive = null;\n          }\n        }\n      }).catchError((Object recoverError, StackTrace recoverStack) {\n        if (_isCurrentPlayRequest(generation, track)) {\n          _odifyAsyncRecoveryActive = null;\n          _reportPlaybackError(player, recoverError, recoverStack);\n        }\n      }));\n      return;\n    }\n    _sourceReady = false;\n    _playbackError = error;")
    # If Android rejects a signed CDN URL, forget its short-lived handle.
    # The next user playback attempt must re-extract instead of replaying
    # the same known-bad URL cached by the native extractor.
    replace_one(TARGET / 'lib/services/music_player_service.dart',
                "    _log.e('Playback failed for ${mediaItem.value?.title}', error, stackTrace);",
                "    _log.e('Playback failed for ${mediaItem.value?.title}', error, stackTrace);\n"
                "    if (_index >= 0 && _index < _media.length &&\n"
                "        _media[_index].source.startsWith('odify-youtube://')) {\n"
                "      unawaited(OdifyYouTube.invalidateAudioSource(_media[_index].source));\n"
                "    }")
    # Best-effort prefetch: one next YouTube ID when a track starts.
    # Prefetch runs after a 3-second delay; local and SAF playback unchanged.
    replace_one(TARGET / 'lib/services/music_player_service.dart',
                "      _sourceReady = true;\n"
                "      if (media.source.startsWith('network://')) {",
                "      _sourceReady = true;\n"
                "      if (media.source.startsWith('odify-youtube://') &&\n"
                "          _index + 1 < _media.length) {\n"
                "        final following = _media[_index + 1];\n"
                "        if (following.source.startsWith('odify-youtube://')) {\n"
                "          unawaited(Future<void>.delayed(const Duration(seconds: 3), () async {\n"
                "            if (_isCurrentPlayRequest(generation, media)) {\n"
                "              await OdifyYouTube.prefetchAudioSource(following.source);\n"
                "            }\n"
                "          }));\n"
                "        }\n"
                "      }\n"
                "      if (media.source.startsWith('network://')) {")
    # R8 must preserve Commons Compress ZIP extra-field implementations.
    # The initial Python bundle unzip uses ExtraFieldUtils' reflective class
    # registration (Class.newInstance). If R8 removes a nullary constructor
    # or changes a concrete implementation, the app crashes in <clinit> with
    # "class ... is not a concrete class" (Galaxy release 2026-10-21).
    # Keep this narrow to ZIP archivers: preserve unrelated release shrinking.
    proguard = TARGET / 'android/app/proguard-rules.pro'
    proguard_text = proguard.read_text(encoding='utf-8')
    zip_guard = (
        '\n# Odify: Apache Commons Compress ZIP registry uses reflection.\n'
        '-keep class org.apache.commons.compress.archivers.zip.** { *; }\n'
    )
    if 'org.apache.commons.compress.archivers.zip.**' in proguard_text:
        raise RuntimeError('Vendor ZIP optimizer rules changed; review before update')
    proguard.write_text(proguard_text + zip_guard, encoding='utf-8')

    # Keep this one routed backend name stable across R8 so release APK
    # inspection can detect a missing streaming engine before distribution.
    proguard.write_text(
        proguard.read_text(encoding='utf-8') +
        '\n-keep class xyz.luan.audioplayers.player.OdifyExoPlayerWrapper { *; }\n',
        encoding='utf-8',
    )

    # yt-dlp is an optional GPL-3.0 native dependency; retain attribution.
    gradle = TARGET / 'android/app/build.gradle.kts'
    gradle_text = gradle.read_text(encoding='utf-8')
    dependency_anchor = 'dependencies {\n'
    if gradle_text.count(dependency_anchor) != 1:
        raise RuntimeError('Expected exactly one Gradle dependencies block')
    replace_one(gradle, dependency_anchor,
                dependency_anchor +
                '    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")\n'
                '    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")\n')
    # yt-dlp bundles ZIP archives as pseudo .so assets. They are not ELF
    # binaries and must be excluded from native llvm-strip processing.
    replace_one(gradle,
                '        if (discordSdkEnabled) jniLibs.pickFirsts += "**/libdiscord_partner_sdk.so"',
                '        if (discordSdkEnabled) jniLibs.pickFirsts += "**/libdiscord_partner_sdk.so"\n'
                '        jniLibs.keepDebugSymbols += setOf("**/libffmpeg.zip.so", "**/libpython.zip.so")\n'
                '        jniLibs.useLegacyPackaging = true')
    replace_one(TARGET / 'android/app/src/main/kotlin/com/zarz/mornye/MainActivity.kt',
                '            flutterEngine.plugins.add(OdifyMediaStorePlugin())\n        }',
                '            flutterEngine.plugins.add(OdifyMediaStorePlugin())\n        }\n'
                '        if (!flutterEngine.plugins.has(OdifyYtDlpPlugin::class.java)) {\n'
                '            flutterEngine.plugins.add(OdifyYtDlpPlugin())\n        }')
    # Select Media3 only for Odify's opaque online CDN/relay sources.
    # All ordinary streams, SAF, local audio and USB DAC retain upstream path.
    plugin_dir = TARGET / 'third_party/audioplayers_android/android'
    wrapped = plugin_dir / 'src/main/kotlin/xyz/luan/audioplayers/player/WrappedPlayer.kt'
    replace_one(
        wrapped,
        '                    val player = getOrCreatePlayer()\n',
        '                    val player = getOrCreatePlayer(value)\n',
    )
    replace_one(
        wrapped,
        '    private fun getOrCreatePlayer(): PlayerWrapper {\n'
        '        val currentPlayer = player\n'
        '        return if (released || currentPlayer == null) {\n'
        '            createPlayer().also {',
        '    private fun getOrCreatePlayer(nextSource: Source? = source): PlayerWrapper {\n'
        '        var currentPlayer = player\n'
        '        if (playerMode == MEDIA_PLAYER && currentPlayer != null &&\n'
        '            (currentPlayer is OdifyExoPlayerWrapper) != OdifyExoPlayerWrapper.accepts(nextSource)) {\n'
        '            currentPlayer.release()\n'
        '            player = null\n'
        '            currentPlayer = null\n'
        '            released = true\n'
        '            prepared = false\n'
        '            playing = false\n'
        '        }\n'
        '        return if (released || currentPlayer == null) {\n'
        '            createPlayer(nextSource).also {',
    )
    replace_one(
        wrapped,
        '    private fun createPlayer(): PlayerWrapper {\n'
        '        return when (playerMode) {\n'
        '            MEDIA_PLAYER -> MediaPlayerWrapper(this)',
        '    private fun createPlayer(nextSource: Source? = source): PlayerWrapper {\n'
        '        return when (playerMode) {\n'
        '            MEDIA_PLAYER -> if (OdifyExoPlayerWrapper.accepts(nextSource))\n'
        '                OdifyExoPlayerWrapper(this) else MediaPlayerWrapper(this)',
    )
    replace_one(
        wrapped,
        '    fun onError(what: Int, extra: Int): Boolean {',
        '    fun onExoError(code: String, safeCause: String) {\n'
        '        prepared = false\n'
        '        playing = false\n'
        '        focusManager.handleStop()\n'
        '        handleError("AndroidAudioError", "Odify Media3 streaming failed",\n'
        '            "EXO_" + code + " / " + safeCause)\n'
        '    }\n\n'
        '    fun onError(what: Int, extra: Int): Boolean {',
    )
    plugin_gradle = plugin_dir / 'build.gradle'
    replace_one(plugin_gradle, '        minSdkVersion 19', '        minSdkVersion 23')
    replace_one(
        plugin_gradle,
        '    implementation "androidx.core:core-ktx:1.19.1"',
        '    implementation "androidx.core:core-ktx:1.19.1"\n'
        '    implementation "androidx.media3:media3-exoplayer:1.9.4"\n'
        '    implementation "androidx.media3:media3-datasource-okhttp:1.9.4"\n'
        '    implementation "com.squareup.okhttp3:okhttp:4.12.0"',
    )
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
    subprocess.run([sys.executable, str(ROOT / 'scripts/port_521.py'), str(TARGET)], check=True)
    print(f'Odify assembled at {TARGET}')
    print('Next: install the pinned Flutter/Rust/Android toolchain from upstream CI; flutter test.')


if __name__ == '__main__':
    assemble()

