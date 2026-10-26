# Odify motion and playlist editing

The product's core visual principle is gentle, connected motion. Screen destinations, menus, country changes and queue edits should preserve visual continuity when opening and closing. Avoid abrupt replacement, row flashes and opaque charcoal blocks over album artwork. Retain outgoing content until its transition completes. Respect the system animator duration scale.

- Playlist destinations fade, lift and expand over 520 ms; outgoing content survives the 420 ms return transition.
- Player controls use 50 dp glass circles with accessible icon descriptions. Lyrics, queue and options grow from their measured control position over 520 ms and return over 400 ms. Modal refraction samples the background once per opening.
- The footer center is a smooth, text-only horizontal pager: transport source first, next scheduled track second (current track for repeat-one). Two subtle dots show position. Tapping source opens track information; tapping next opens the queue. No album thumbnail occupies the footer.
- Playlist names can be changed from library card options and the full destination. Existing identity, order and songs are preserved; empty or conflicting names are rejected.
- Covers can be chosen from the destination image button or options, and restored to track artwork. Images are copied privately, bounded to 20 MB input and 1024 px output, with unique files; failed imports preserve the previous image. Original track art is never modified. Names and cover references survive restart and legacy catalogs remain readable.
- Queue occurrence keys remain stable. Drag placement fades are disabled and the lifted item settles into its destination before its layer is released. Only one controller reorder is submitted on release.
- Home artist and mix collections use a glass surface with bounded titles, equal-width compact actions, transparent song rows and truncated metadata.

## Company verification constraint

Never play music or run playback tests in the office. Keep STREAM_MUSIC at zero. Only `OdifyCompanyUiTest` is authorized on the attached phone: it uses UI fixtures, an idle unprepared player, and isolated preferences/images. Do not run the other instrumentation classes here. Preserve the installed application ID, permanent signing certificate and user data when updating.
