# Odify UI v1.2 — functional Flutter glass redesign

Updated 2026-10-20. **Source committed; NO APK build, signing, deployment, or on-device usability claim.**

## User design decision

Use the seven supplied music-app reference images as *design inspiration*, not copied trade dress:
- Dark first, with a subdued emerald/mint accent and restrained optical glass.
- Heavy emphasis on real artwork, large featured recommendations, floating mini-player, rounded category tiles, clean now-playing screen.
- Preserve light-mode choice and accessibility.
- More prominent glass than old SpotiFLAC without increasing GPU/frame costs.

**Platform priorities remain Galaxy → Windows → iPad → Mac.**

## Screen-level implementation

### Home (Flutter)

- `lib/widgets/odify_quick_access.dart`: new app wordmark block, rounded icon tile and functional search button. One-row All/For You/Trending/Covers pills navigate to native Discover with the correct category and sensible initial query. No nested vertical scrolling.
- `lib/widgets/odify_home_feed.dart`: replace generic list with **오늘의 추천** large feature card (first personalized result) and horizontal album-art carousel (`ListView.separated` with bounded width, max six tracks). Hearts update the existing local taste/likes model with 3s debounced refresh. Search-based **오늘의 음악 탐색** lives under the feature: KR/JP/Global pills, up to four high-contrast tracks in one glass-tinted panel.
- `lib/widgets/odify_youtube_explore.dart`: extension-free search entry is now a tinted feature tile with better iconography, preserving the existing Discover search/playback.
- `lib/widgets/odify_device_library_card.dart`: same optical tint/radius as home cards. Native MediaStore sync behavior unchanged.

All card data is the real result of the existing YouTube/discovery services; no mocked assets are bundled into production. **Trending stays clearly labeled as search-based exploration, not a verified official music chart.** Empty taste falls back to those starter results.

### Library (Flutter)

- `lib/widgets/odify_library_shortcuts.dart`: responsive 2-column grid replacing four large Mornye list rows; preserves four real callbacks: Playlists, Artists, Albums and Songs. Keeps `MornyeLibraryScreen`'s existing paginated local artwork grid and navigation.
- `lib/screens/mornye_library_screen.dart`: pinned upstream copied into overlay to replace the corresponding category section only; list paging, search, filters, local album and listening-history state remain intact.

### Player, navigation and theme

- `lib/widgets/mini_player.dart`: update the **existing** Mornye glass navigation surface with 24dp corners and a subtle optical rim; do not add another backdrop pass. Keeps play/pause/next, Hero transitions and progress bar.
- `lib/providers/theme_provider.dart`: fresh installs default to `ThemeMode.dark`, `AppThemeStyle.mornye`, green accent, glass clarity 0.82, and disabled dynamic wallpaper color. **Existing persisted user appearance preferences are preserved** (including light/Material mode, accent and glass slider). User can change appearance in Settings.
- `lib/providers/runtime_profile_provider.dart`: default Mornye level on capable devices is **frosted**, using a single grouped backdrop over floating chrome; high-end refraction shader is available only with the explicit force blur preference on capable devices. Low-end defaults to flat.
- Upstream `MornyeBottomBar` already batches mini-player and tab bar under `BackdropGroup` / `LiquidGlassBatch`. Reuse it rather than building an extra custom bar. Existing `MornyePlayerLayout` and album-derived background remain the full-screen playback implementation, now reached by default style.

### Shared low-cost glass surface

`lib/widgets/odify_glass.dart` provides `OdifyGlassSurface`, `OdifyGlassPill`, `OdifySectionTitle`:
- static two-tone gradient and one-pixel border, no blur shaders, no `saveLayer`, no additional `BackdropFilter`
- rounded touch areas and clear titles; theme-aware dark/light tint and foreground
- no decorative animated shimmer, no continuous ticker per album card

## Performance guardrails

1. Already existing home search delay (900 ms), 30 minute memoized search cache, one queued native search at a time, and 24h local MediaStore background sync remain untouched.
2. Artwork image decode uses `cacheWidth` based on actual rendering size and device pixel ratio capped to 512 pixels; displayed artwork is isolated in `RepaintBoundary`. This prevents decoding poster/full resolution for tiny cards.
3. Horizontal carousel is `ListView.separated` and bounded to six data items, not unlimited children; visible song rows max four. No network fetch starts during Flutter `build()`.
4. Scrollable card surfaces **do not** create `BackdropFilter`. The floating shell continues to have its existing grouped glass pass. Low-end uses flat fallback; users can opt into refraction in Appearance on high-end phones.
5. Theme change is a user choice; never silently override saved theme preferences.
6. All touch targets remain functional and image failures fall back to icon tiles.

### Known limitations / verification gates

- Dart analyzer and widget tests ensure the code compiles and static visuals have no scroll-time blur. **They are not substitutes for profile/release performance measurements** on Galaxy S25 Ultra. Verify jank with Flutter DevTools frame charts after the next batched APK distribution.
- No new Spotify or YouTube rights, chart APIs or official rankings were introduced. YouTube search/extractor reliability is subject to platform/provider constraints.
- No Google OAuth configuration, Drive backend modification, or unrelated Trove/Supabase changes.
- The active installed Galaxy APK continues to show the prior UI until the user approves grouped packaging.

## QA checklist for next grouped APK

1. First-run theme is dark/frosted; a previously saved light theme stays light after upgrade.
2. Home scroll: feature mix, personalized art carousel, Trending tabs, Discover card, Samsung local library all visible; no header overflows at 1.3x font scale.
3. Search button and All/For You/Trending/Covers pills open the correct functional screen.
4. Play from a featured or carousel card starts the right selected queue index; next/previous work.
5. Album art from embedded Samsung MP3 remains available in both Library and Now Playing.
6. Library four shortcuts open the existing playlist/artist/album/song screens and paginated album grid still scrolls.
7. Mini-player/toolbar transparency does not produce black flashes or unreadable contrast.
8. Profile → Google sync screen remains accessible, and donor menu remains removed.
9. Under Flutter DevTools/profile APK, measure first frame, 90/99th frame times, scroll jank, MediaStore/resolver CPU and memory on Galaxy S25 Ultra.

## Code validation

`.github/workflows/odify-dart-check.yml` is the **only** workflow run for this iteration, consisting of the Python assembly smoke tests, Flutter analyzer and widget tests (including `odify_glass_design_test.dart`); it contains NO Android/Kotlin/Gradle/Rust/APK packaging.
