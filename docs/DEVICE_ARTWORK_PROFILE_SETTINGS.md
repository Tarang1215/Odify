# Odify v1.1 — Samsung Music embedded covers, profile Google sync, no donor UI

Updated 2026-10-20 — **GitHub source only, not shipped as an APK**.

## User-reported problems

1. Samsung Music shows album covers for existing MP3 files; Odify's `내 휴대폰 음악` browser showed only a generic note and the player lacked art.
2. Google login/Drive synchronization belongs under **Settings → tap profile → Google account & Drive**, not as a large button on Home.
3. The inherited SpotiFLAC/Mornye **developer donation menu and fundraising goal** should not be part of Odify.

## Covers: what changed

- Android `OdifyMediaStorePlugin` already returned MediaStore `_ID`, album ID and modification time, but **did not extract** embedded `APIC` artwork. It now offers a restricted `getArtwork(id)` call.
- Native `MediaMetadataRetriever.setDataSource(context, contentUri)` retrieves the **file's own embedded picture** from the public MediaStore audio URI; no direct filesystem/Samsung private database access. The heavy native work is on one worker, never the main Android UI thread.
- Bound potentially huge input images (12MiB cap), sample and scale to at most **512 pixels**, and encode JPEG (~82 quality) to Odify-private files.
- Cache filename includes source MediaStore ID + `DATE_MODIFIED` for invalidation after tags or file updates. Cap native cache to approximately 480–600 files; old art can be extracted again on demand.
- `OdifyDeviceTrack` keeps native `cachedCoverPath`, `modifiedSeconds`, and `albumId`. Its `PlayableMedia.artUri` uses a local `file://` URL when available; **never** a cloud/remote artwork URL.
- `OdifyDeviceArtworkTile` asks for covers lazily for visible tracks in **내 휴대폰 음악**. A bounded Dart lookup deduplicates concurrent extraction and updates the `library.cover_path` of matching imported MediaStore rows.
- The existing shared Library artwork builder uses this same resolver for MediaStore content URIs. Normal Mornye SAF/direct-file artwork paths use the original `DownloadedEmbeddedCoverResolver`, unchanged.
- The audio service's `_recoverDocumentArtwork` now checks Odify's MediaStore extractor first and publishes the recovered local `MediaItem.artUri` without blocking audio; original resolver remains a fallback.
- Importing thousands of songs does **not** synchronously extract thousands of images. Initial Library rows acquire covers when browsed/played. Previously browsed covers remain cached and are picked up in a later MediaStore sync.

### Limits

- The native code is in the overlay but has not yet been Android-compiled or tested on the actual Galaxy S25 Ultra; Flutter/Python tests do **not** prove native support for every MP3 or album art tag.
- Without embedded art, an item keeps the placeholder. No artwork is fabricated, and Samsung Music's private artwork DB is not accessed.
- If Android revokes READ_MEDIA_AUDIO or a media item becomes unavailable, cover retrieval returns null; playback remains independent.
- Odify cache uses app-private storage; uninstalling the app removes its thumbnails. It never changes the original MP3 tags or duplicates MP3 bytes.

## Google account settings

- Original `SettingsTab` user-profile card still opens `ProfileSettingsPage`, which now has the **Google 계정 · Drive 동기화** entry next to local name/photo editing. It opens `OdifySyncSettingsScreen` to connect/disconnect Google and view Drive sync.
- Removed the standalone Home `동기화 및 백업 · Google Drive` button.
- Still requires separate Google Cloud OAuth client configuration and stable Android signing SHA (no external account sign-in completed yet). Supabase/Trove is never used.

## Donation removal and license

- The custom `SettingsTab` removes the inherited `DonatePage` entry, automatic fundraiser remote config fetch, donation progress bar, and donation search keywords.
- The upstream **About**, author credits, open-source licenses and related attribution remain intact, including required attribution for derivative works.
- Removing fundraising UI does **not** erase legal copyright notices or change MIT/GPL redistribution conditions.

## Regression checks

- Python assembly fixtures and static overlay assertions assert profile entry, no donor route/requests, album resolver/player bridge, source-ID restrictions and local cache.
- Dart-only Flutter analyzer and tests cover MediaStore parsed IDs and native cached artwork flowing into `PlayableMedia.artUri`.
- Native Kotlin compile and Galaxy screenshot/audio/artwork checks will be included with the **next grouped APK release**, not a new APK for this small iteration.

## Next bundled release QA

1. From Profile Settings open Google Drive screen; if OAuth is unconfigured, explain it rather than crashing.
2. Confirm **no developer donation entry** and no fundraiser network request from Settings.
3. Play a Samsung Music MP3 with embedded cover from `내 휴대폰 음악`; thumbnail appears in list and player, including next/previous queue changes.
4. View same item in unified Library, album view, mini player, lock screen notification; artwork should be consistent.
5. Test 1,000+ tracks without full synchronous artwork extraction; scroll fast and measure memory/frames.
6. Corrupt/oversized MP3 artwork falls back to placeholder without crashing.
