# Odify Home performance and discovery — implementation notes

Updated: 2026-10-20. **Code in GitHub overlay, NOT shipped to the currently installed APK.**

## Reported issues

- Galaxy S25 Ultra: debug APK feels somewhat janky.
- The Home screen does not directly show trending or personalized songs.
- YouTube Discover has unclear entry points and empty For You when there are no likes/history yet.

## Findings

1. The prior Home only displayed an `OdifyYouTubeExploreCard` navigation tile; it never mounted song lists. This was a **missing UI feature**, not a failed recommendation algorithm.
2. The prior `OdifyDiscover.recommendations` intentionally returned an empty list with no listening data. The Discover screen did not show any fallback.
3. User-installable GitHub artifacts have been **Android debug builds** with large bundled Python/FFmpeg yt-dlp dependencies. A debug build is not a representative release-performance benchmark.
4. Auto MediaStore sync could run on Home initialization (up to 20,000 indexed tracks); even though throttled to six hours, coincident playback/frame work may be expensive.
5. Rebuilding the Home music card for all `localLibraryProvider` scan/progress changes is unnecessary.

## Source changes implemented

- `lib/widgets/odify_home_feed.dart`: Home now has **오늘의 음악 탐색** (KR/JP/Global selector) and **나를 위한 추천**, displays up to four tracks in each preview and plays from the corresponding queue. Loaded content shows image thumbnails with `cacheWidth=120`. Initial fetch is deferred until after first paint (900ms).
- `lib/services/odify_home_feed.dart`: deduplicated, sequential native video searches; search cache for 30 minutes; bounded first Home load (one region query + at most one personalized query). No profile/network work in `build()`. No taste yet => fall back to discoverable tracks with clear labeling.
- `lib/widgets/odify_youtube_explore.dart`: explicit instructions, launch with initial category/query from Home, automatic nonpersonalized fallback when no history.
- `lib/widgets/odify_device_library_card.dart`: delay auto MediaStore indexing for 12 seconds, auto-index no more frequently than every 24h, rebuild card only on summary changes. Explicit manual sync remains available.
- `test/odify_home_feed_test.dart`, `test/odify_home_widget_test.dart`: check bounded search, cached cold-start fallback, dedup/serialization, first paint without a native query.

## Accuracy/limits

**오늘의 음악 탐색 is a YouTube SEARCH-based music discovery sample, NOT an independently verified streaming chart, YouTube trending leaderboard or popularity ranking.** Results depend on yt-dlp/YouTube availability; user-facing text explicitly says `공식 차트 순위 아님`. True realtime trending would require a licensed or verifiable chart source and sorting by grounded chart metrics.

Personalization is seeded locally by likes/listening history. A cold-start account does not already have a learned taste model. Discover's richer personal results can still issue multiple searches; Home deliberately uses at most one personalized search.

YouTube music playback, duration metadata, videos blocked by service, and widget smoothness have NOT been verified on the user's device. The app must not present unavailable content as playable.

## Next optimization acceptance criteria

1. With **profile or release build**, measure first-frame time, 90th/99th frame latency, cold/warm Home nav time and memory on Galaxy S25 Ultra using Flutter DevTools Performance and Android Studio profiler.
2. Measure native yt-dlp initialization, MediaStore indexing and I/O independently; do not tune based only on apparent frame drops from a debug build.
3. A signed arm64 release/test distribution with stable certificate is needed for accurate benchmarking and painless APK upgrades. Keep APK/release packaging until changes have been batched, as requested.
4. Test Home in empty-library, denied permission, no YouTube connection, large Samsung Music library, app foreground/background, and tab switching. Verify no duplicate expensive search or MediaStore scan.
5. Evaluate whether yt-dlp + FFmpeg must ship in base package, can be deferred/optional, or can reduce ABI/assets while preserving legitimate playback and licensing.
6. Validate real Home cards and next/previous queue interaction on device; check that selecting the 2nd song starts at index 1 and advances correctly.

No Trove/Supabase infrastructure added. Drive sync remains separately opt-in. This iteration deliberately does not create or deploy a new APK.
