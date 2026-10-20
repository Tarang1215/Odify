#!/usr/bin/env python3
"""Lightweight static checks without invoking Flutter, Android, or Actions.

These checks are not equivalent to a successful Android build or device test.
"""
from pathlib import Path
import ast

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'overlay'
assembly = ROOT / 'scripts' / 'assemble.py'
ast.parse(assembly.read_text(encoding='utf-8'))
files = {p.relative_to(SOURCE).as_posix(): p for p in SOURCE.rglob('*') if p.is_file()}
required = {
    'lib/services/odify_likes.dart',
    'lib/services/odify_taste.dart',
    'lib/services/odify_device_catalog.dart',
    'lib/widgets/odify_device_library_card.dart',
    'lib/widgets/odify_device_music_browser.dart',
    'lib/widgets/odify_quick_access.dart',
    'android/app/src/main/kotlin/com/zarz/spotiflac/OdifyMediaStorePlugin.kt',
}
assert required <= files.keys(), sorted(required - files.keys())
for name, path in files.items():
    source = path.read_text(encoding='utf-8')
    assert '\x00' not in source, name
    assert source.endswith('\n'), name
    assert '<<<<<<<' not in source and '>>>>>>>' not in source, name
kt = files['android/app/src/main/kotlin/com/zarz/spotiflac/OdifyMediaStorePlugin.kt'].read_text()
assert 'MediaStore.Audio.Media.EXTERNAL_CONTENT_URI' in kt
assert 'READ_MEDIA_AUDIO' in kt
assert 'READ_EXTERNAL_STORAGE' in kt
assert 'com.odify.music/media_store' in kt
assert 'MediaStore query returned null' in kt
taste = files['lib/services/odify_taste.dart'].read_text()
assert 'likedArtistCounts' in taste and '(liked ? 7.0 : 0.0)' in taste
assert 'OdifyLikeStore.read()' in taste
app = assembly.read_text()
assert 'OdifyMediaStorePlugin' in app and 'odify_likes.dart' in app
assert 'git clone' not in app  # subprocess call uses argv list
assert 'subprocess.run' in app
print(f'Odify overlay static checks passed: {len(files)} files')

# v0.4: yt-dlp is a native plug-in, invoked from Flutter and reused by Home.
assert {
    'lib/services/odify_youtube.dart',
    'lib/widgets/odify_youtube_explore.dart',
    'android/app/src/main/kotlin/com/zarz/spotiflac/OdifyYtDlpPlugin.kt',
} <= files.keys()
assert 'OdifyYtDlpPlugin' in app
assert 'OdifyQuickAccess(showDeviceLibrary: _showsHome)' in app
assert 'SliverToBoxAdapter(' in app
assert 'home_tab_intro.dart' not in app  # Not hidden in extension-dependent section
assert 'odify_quick_access.dart' in app
assert 'OdifyYouTubeExploreCard' in files['lib/widgets/odify_quick_access.dart'].read_text()
assert 'OdifyDeviceLibraryCard' in files['lib/widgets/odify_quick_access.dart'].read_text()
assert '0.16.0' in app and 'jniLibs.useLegacyPackaging = true' in app
assert 'android:extractNativeLibs' not in app
assert 'YoutubeDL.getInstance().getInfo(request)' in files['android/app/src/main/kotlin/com/zarz/spotiflac/OdifyYtDlpPlugin.kt'].read_text()
assert "playSingle(media)" in files['lib/widgets/odify_youtube_explore.dart'].read_text()

# v0.5: safe queue identities and no stale extracted URLs in session state.
source = files['lib/services/odify_youtube.dart'].read_text()
assert 'source: sourceForId(video.id)' in source
assert "return '$sourceScheme://video/$videoId';" in source
assert 'return uri.pathSegments.single;' in source
assert 'source: url,' not in source
assert 'OdifyYouTube.resolveAudioSource(media.source)' in app
assert "'odify-youtube'" in app
assert 'OdifyYouTube.playable(video)' in files['lib/widgets/odify_youtube_explore.dart'].read_text()

# v0.6 personalized Discover: bounded search, source-level dedup, likes feedback.
assert {
    'lib/services/odify_discovery.dart',
    'test/odify_discovery_test.dart',
} <= files.keys()
discover = files['lib/services/odify_discovery.dart'].read_text(encoding='utf-8')
assert 'OdifyTasteProfile.fromLocalHistory()' in discover
assert 'OdifyDiscoveryRanker.rank' in discover
assert 'knownSongs' in discover and 'byChannel' in discover
assert 'OdifyYouTube.search(query.term, limit: 12)' in discover
ui = files['lib/widgets/odify_youtube_explore.dart'].read_text(encoding='utf-8')
assert 'OdifyDiscover.recommendations' in ui and 'OdifyLikeStore.setLiked' in ui
assert "'For You'" in ui and '추천 새로고침' in ui
kt = files['android/app/src/main/kotlin/com/zarz/spotiflac/OdifyYtDlpPlugin.kt'].read_text(encoding='utf-8')
assert 'ytsearch$limit:' in kt and 'coerceIn(1, 30)' in kt
assert 'int limit = 30' in files['lib/services/odify_youtube.dart'].read_text(encoding='utf-8')
print('Odify v0.6 personalized Discover static assertions passed')


# v0.8 library visibility and portable multi-device event domain.
library_card = files['lib/widgets/odify_device_library_card.dart'].read_text()
assert 'setLocalLibraryEnabled(true)' in library_card
assert 'reloadFromStorage()' in library_card
assert 'ref.invalidate(libraryBrowseProvider)' in library_card
assert "odify_media_sync_last_success_v1" in library_card
assert "legacy://local-library" in app
assert "local_library_provider_sources.dart" in app
assert 'lib/services/odify_sync/sync_merge.dart' in files
assert 'test/odify_sync_merge_test.dart' in files
assert 'class OdifySyncState' in files['lib/services/odify_sync/sync_merge.dart'].read_text()
print('Odify v0.8 library visibility + shared sync-merge static assertions passed')
