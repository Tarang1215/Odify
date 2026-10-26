# Odify motion and playlist editing

The product's core visual principle is gentle, connected motion. Screen destinations, menus, country changes and queue edits should preserve visual continuity when opening and closing. Avoid abrupt replacement, row flashes and opaque charcoal blocks over album artwork. Retain outgoing content until its transition completes. Respect the system animator duration scale.

- Playlist destinations fade, lift and expand over 520 ms; outgoing content survives the 420 ms return transition.
- Player controls use 50 dp glass circles with accessible icon descriptions. Queue, editing and options panels grow from their control position over 520 ms and return over 400 ms. Modal refraction samples the background once per opening and retains the phase-one transparency (.76) and rounded-rectangle refraction. Material provides safe window geometry.
- The footer center is a 50 dp glass capsule, exactly aligned with the circles. Its horizontal pager shows only `Drive`, `YouTube` or `Local` on the first page and only the next track title on the second (current track for repeat-one). No thumbnail, secondary label, format, or page dots.
- Tapping lyrics when present switches directly to the existing immersive lyrics area. If absent, show only the compact add/edit menu. Long-press opens add/edit for an existing lyric too. Avoid blank lyric panels or an extra menu step before readable lyrics.
- Playlist names can be changed from library card options and the full destination. Existing identity, order and songs are preserved; empty or conflicting names are rejected.
- Covers can be chosen from the destination image button or options, and restored to track artwork. Images are copied privately, bounded to 20 MB input and 1024 px output, with unique files; failed imports preserve the previous image. Original track art is never modified. Names and cover references survive restart and legacy catalogs remain readable.
- Queue occurrence keys remain stable. Drag placement fades are disabled and the lifted item settles into its destination before its layer is released. Only one controller reorder is submitted on release.
- Home artist and mix collections use a glass surface with bounded titles, equal-width compact actions, transparent song rows and truncated metadata.

## Motion performance

Static artwork and modal snapshots must not run the live-scroll backdrop refresh loop. Keep the glass shader's rounded-rectangle geometry stable during morphs; transform and clip the outer GPU layer instead of rebuilding blur/refraction effects every frame. Animation progress used only for rendering belongs in draw/graphicsLayer callbacks. Derive endpoint visibility to avoid recomposing the entire player on each lyric/player transition frame. Queue lift and placement offsets likewise stay in render callbacks.

Measure on the physical device with the same office-safe gestures and `dumpsys gfxinfo ... framestats`; keep startup/cache effects and changing window counts in mind when comparing. Do not infer a constant frame rate from a single run.

## Company verification constraint

Never play music or run playback tests in the office. Keep STREAM_MUSIC at zero. Only `OdifyCompanyUiTest` is authorized on the attached phone: it uses UI fixtures, an idle unprepared player, and isolated preferences/images. Do not run the other instrumentation classes here. Preserve the installed application ID, permanent signing certificate and user data when updating.
