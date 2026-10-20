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
assert "playAll(" in files['lib/widgets/odify_youtube_explore.dart'].read_text()
assert "initialIndex: index" in files['lib/widgets/odify_youtube_explore.dart'].read_text()

# v0.5: safe queue identities and no stale extracted URLs in session state.
source = files['lib/services/odify_youtube.dart'].read_text()
assert 'source: sourceForId(video.id)' in source
assert "return '$sourceScheme://video/$videoId';" in source
assert 'return uri.pathSegments.single;' in source
assert 'source: url,' not in source
assert 'OdifyYouTube.resolveAudioSource(media.source)' in app
assert "'odify-youtube'" in app
assert 'OdifyYouTube.playable(item)' in files['lib/widgets/odify_youtube_explore.dart'].read_text()
phone_browser = files['lib/widgets/odify_device_music_browser.dart'].read_text()
assert '.playAll(' in phone_browser
assert 'initialIndex: initialIndex' in phone_browser
assert 'playSingle(track.toPlayable())' not in phone_browser

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


# No-APK v0.9 implementation checks: portable sync ledger and Drive adapter.
sync_required = {
    'lib/services/odify_sync/sync_clock.dart',
    'lib/services/odify_sync/sync_repository.dart',
    'lib/services/odify_sync/sync_coordinator.dart',
    'lib/services/odify_sync/drive_sync_transport.dart',
    'test/odify_sync_coordinator_test.dart',
    'test/odify_drive_transport_test.dart',
}
assert sync_required <= files.keys(), sorted(sync_required - files.keys())
repository = files['lib/services/odify_sync/sync_repository.dart'].read_text()
coordinator = files['lib/services/odify_sync/sync_coordinator.dart'].read_text()
drive = files['lib/services/odify_sync/drive_sync_transport.dart'].read_text()
model = files['lib/services/odify_sync/sync_merge.dart'].read_text()
assert 'Future<OdifySyncEvent> recordLocalChange' in repository
assert 'CREATE TABLE sync_events' in repository
assert 'Future<void> importRemote(' in repository
assert 'local_pending' in repository and 'batch_id' in repository
assert 'Future<OdifyPendingBatch?> pendingBatch' in repository
assert 'transport.putIfAbsent(batch)' in coordinator
assert 'await ledger.markUploaded(batch)' in coordinator
assert 'appDataFolder' in drive
assert 'multipart/related' in drive
assert "'spaces': 'appDataFolder'" in drive
assert "changes/startPageToken" in drive
assert 'OdifySyncEvent.fromJson(event.toJson())' in drive
assert "Unsupported or unsafe sync operation" in model
print('Odify v0.9 player queue + offline sync persistence + Drive adapter static assertions passed')


# v1.0: Android-only user-initiated Google OAuth + per-account sync settings.
oauth_required = {
    'lib/services/odify_sync/google_drive_auth.dart',
    'lib/widgets/odify_sync_settings.dart',
    'test/odify_sync_settings_test.dart',
}
assert oauth_required <= files.keys(), sorted(oauth_required - files.keys())
auth = files['lib/services/odify_sync/google_drive_auth.dart'].read_text()
screen = files['lib/widgets/odify_sync_settings.dart'].read_text()
quick = files['lib/widgets/odify_quick_access.dart'].read_text()
assert 'google_sign_in: ^7.2.0' in app
assert 'ODIFY_GOOGLE_SERVER_CLIENT_ID' in auth
assert 'authorizationClient.authorizeScopes(_scopes)' in auth
assert 'authorizationForScopes(_scopes)' in auth
assert 'OdifyDriveSyncTransport.scope' in auth
assert 'OdifySyncRepository(accountId: account.id)' in screen
assert 'OdifySyncCoordinator(ledger: repository, transport: drive)' in screen
assert "op: 'playlist.create'" in screen
assert "op: 'playlist.rename'" in screen
assert "op: 'playlist.delete'" in screen
profile = files['lib/screens/settings/profile_settings_page.dart'].read_text()
settings_tab = files['lib/screens/settings/settings_tab.dart'].read_text()
assert 'OdifySyncSettingsScreen' in profile
assert 'odify-profile-drive-sync' in profile
assert 'OdifySyncSettingsScreen' not in quick
assert 'DonatePage' not in settings_tab
assert 'settingsDonate' not in settings_tab
assert 'fetchConfigSnapshot' not in settings_tab
assert '_DonationGoalProgress' not in settings_tab
assert "storageNameFor(String accountId)" in repository
print('Odify v1.0 opt-in Drive auth, per-account partition and playlist UI static assertions passed')


# v1.0 Home feed UX/performance: separate hot Home UI from heavy yt-dlp work.
home_service = files['lib/services/odify_home_feed.dart'].read_text()
home_ui = files['lib/widgets/odify_home_feed.dart'].read_text()
quick = files['lib/widgets/odify_quick_access.dart'].read_text()
explore = files['lib/widgets/odify_youtube_explore.dart'].read_text()
media_card = files['lib/widgets/odify_device_library_card.dart'].read_text()
assert 'const OdifyHomeFeed()' in quick
assert 'OdifyGlassPill' in quick
assert 'if (showDeviceLibrary)' in quick
assert 'Timer(const Duration(milliseconds: 900)' in home_ui
assert 'OdifyHomeFeedService.shared' in home_ui
assert 'cacheWidth: (size * MediaQuery.devicePixelRatioOf(context))' in home_ui
assert 'OdifyLikeStore.setLiked' in home_ui
assert 'Duration(seconds: 3)' in home_ui
assert '오늘의 음악 탐색' in home_ui
assert '나를 위한 추천' in home_ui
assert '공식 차트 순위 아님' in home_service
assert '_queuedSearch' in home_service and 'queries.first' in home_service
assert 'initialQuery' in explore and 'initialCategory' in explore
assert '_usingStarter' in explore
assert 's.totalCount' in media_card and 's.isLoading' in media_card
assert 'Duration(seconds: 12)' in media_card
assert 'Duration(hours: 24)' in media_card
assert 'test/odify_home_feed_test.dart' in files
assert 'test/odify_home_widget_test.dart' in files
print('Odify v1.0 Home feed and deferred-indexing static assertions passed')


# v1.1: embedded MediaStore album art, profile OAuth entry, no donation UI.
artwork = files['lib/services/odify_device_artwork.dart'].read_text()
catalog = files['lib/services/odify_device_catalog.dart'].read_text()
browser = files['lib/widgets/odify_device_music_browser.dart'].read_text()
art_widget = files['lib/widgets/local_library_artwork_builder.dart'].read_text()
native = files['android/app/src/main/kotlin/com/zarz/spotiflac/OdifyMediaStorePlugin.kt'].read_text()
assert 'OdifyDeviceArtwork.forSource(source)' in app
assert 'OdifyDeviceArtwork.resolve(' in art_widget
assert 'OdifyDeviceArtworkTile' in browser
assert "'coverPath': OdifyDeviceArtwork.peek(" in catalog
assert 'artUri: _artworkUri()' in catalog
assert 'MediaMetadataRetriever()' in native
assert 'embeddedPicture' in native
assert 'BitmapFactory.Options()' in native
assert 'File(context.filesDir, "odify_media_artwork")' in native
assert 'worker.execute' in native and 'getArtwork' in native
assert "source URI" not in native or 'getArtwork' in native
assert 'LocalLibraryArtworkBuilder' in art_widget
assert 'test/odify_device_artwork_test.dart' in files
print('Odify v1.1 embedded cover, profile login, donor UI removal assertions passed')


# v1.2 actual Flutter glass redesign: no animated backdrop per scrolling tile.
glass = files['lib/widgets/odify_glass.dart'].read_text()
library = files['lib/screens/mornye_library_screen.dart'].read_text()
shortcuts = files['lib/widgets/odify_library_shortcuts.dart'].read_text()
theme = files['lib/providers/theme_provider.dart'].read_text()
runtime = files['lib/providers/runtime_profile_provider.dart'].read_text()
player = files['lib/widgets/mini_player.dart'].read_text()
home_ui = files['lib/widgets/odify_home_feed.dart'].read_text()
quick = files['lib/widgets/odify_quick_access.dart'].read_text()
assert 'class OdifyGlassSurface' in glass
assert 'BackdropFilter' not in glass.replace('BackdropFilter or saveLayer', '')
assert 'ImageFilter.blur' not in glass
assert 'OdifyLibraryShortcuts(' in library
assert 'onPlaylists: () => widget.onOpenSection' in library
assert 'OdifyGlassSurface(' in shortcuts
assert '_featuredMix(' in home_ui
assert 'ListView.separated(' in home_ui
assert 'cacheWidth: (size * MediaQuery.devicePixelRatioOf(context))' in home_ui
assert 'radius: 24' in player and 'highlightBorder: true' in player
assert 'MornyeGlassLevel.frosted' in runtime
assert 'MornyeAccent.green' in theme and "?? 'mornye'" in theme
assert "?? 'dark'" in theme and 'prefs.getString(kThemeModeKey)' in theme
assert 'OdifyGlassSurface(' in files['lib/widgets/odify_device_library_card.dart'].read_text()
assert 'OdifyGlassSurface(' in files['lib/widgets/odify_youtube_explore.dart'].read_text()
assert "test/odify_glass_design_test.dart" in files
print('Odify v1.2 native Flutter glass + performance regression assertions passed')
