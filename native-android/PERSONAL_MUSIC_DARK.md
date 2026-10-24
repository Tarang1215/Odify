# Dark personal music experience — preview12.10

Requested navigation: **Home → Explore → Search → Library**, with icon-only tabs,
a 48dp touch target and accessible names. Profile and account/source management
are utilities reached from the page headers. The latest direction uses a shallow,
rounded glass bar without an opaque rectangular backing. Finger movement
up hides it; movement down brings it back on the same spring as the mini-player.
Horizontal gestures in unclaimed page areas change neighboring primary tabs.
Album carousels and sliders retain their own gestures; account pages are excluded.
Navigation and mini-player sample the actual page through a bounded GPU blur,
including artwork and text. The sample is opaque before blurring so original sharp
glyphs cannot show through. Live coordinate conversion and draw-only frame updates
follow scrolling and ancestor translations without per-frame recomposition or bitmap
readback. A 24dp blur with sampling bleed prevents a thin transparent edge.
The centered compact player follows measured title width within 168–216dp,
contains the cover, title and a circular pause control, and reveals the full
mini-player on tap or upward swipe. Long titles wait
2.2 seconds before scrolling; the full mini-player also scrolls overflowing titles.
The cover's dominant chromatic hue supplies a subdued inner glow and soft edge
shadow. A maximum 64px local decode runs off the main thread and caches 64 colors;
neutral/very dark artwork falls back to muted gray. Track changes blend the color
over 700ms. Text and playback controls remain untinted and sharp.

All main pages, search, settings, sheets, splash and system bars use the same dark
palette. Artwork supplies color; the UI uses restrained charcoal surfaces and thin
boundaries. The immersive player uses one cached blurred album cover, follows a
downward drag and blends back toward the measured mini-player origin. Closing
keeps portrait proportions and smoothly fades the final portion, avoiding the
opaque flattened frame that flashed against the mini-player. Artwork needs no
remote-audio decoding.

Home contains an actual artwork hero, personal mix cards, recent music and saved
playlists. The device records a qualified listen after 30 seconds of actual playing
time, including background playback. Seeking or a brief tap does not fabricate a
listen. The bounded event history covers 60 days and is kept only on the device.
Likes, listened artists and genres produce explainable mixes with an artist-diversity
limit. A new listener sees an honestly labeled own-music starting mix. Saving a mix
persists that exact list atomically in the existing playlist store; later ranking
changes never rewrite saved playlists.
Recent cards show artwork and title only. Home, Search and Explore share the same
pill typography, visual height, touch target and corner radius.

Explore reads public Top 100 charts for Korea, Japan and the US. Korean public
playlist HTML supplies all 100 ranked entries when the marketing feed fails; Japan
and the US use 100-entry feeds with public-playlist fallback. Provider metadata is
retained without a prominent provider banner. Genre filters operate **within the
country's Top 100**, not an independent full genre chart or all-services popularity metric.
Six-hour metadata caching and saved-chart fallback avoid blocking the screen when
offline. Selecting an owned chart song plays directly; unavailable songs show a
preparation message. Sequential play, shuffle and saving all 100 references are
supported. Verified append-only Drive mapping manifests resolve reused files and
new CHART files to chart IDs; later sync can resolve previously pending references.
No unreviewed match is substituted. Matching prefers byte-identical offline copies.

Search covers the connected music server by title, artist, album and genre, with
an explicit separate lyrics scope. Chart selections always use music metadata,
so a coincidental lyric substring cannot substitute an unrelated song in results.
Library defaults to saved playlists, with Offline and Liked tabs;
it does not dump the whole Drive server into the personal collection. Imported local
files and supported local document URIs count as offline. Google Drive document
links and Media3's temporary streaming cache do not. Completed exports to a local
document are registered as offline copies without modifying their source.

The seek bar uses **12 decorative 96-bar patterns**, with tapered ends, separated
phrases and a tall climax. Thin rounded bars distinguish elapsed white from muted
unplayed bars; a seek handle appears only during interaction. A randomly selected pattern is
stable for the currently displayed song. These are stylized artwork, not an audio
measurement. Tapping/dragging and accessibility progress actions still seek actual
audio position. Embedded lyrics, queue editing, source audio, playlists, OAuth
scopes and the permanent package/signing identity are retained.

Local validation: 28 JVM tests and Android-test Kotlin compilation passed. The
fixed-signature GitHub build, private app-data backup and real Galaxy acceptance
results are tracked in `docs/V46_PROGRESS.md`. Never uninstall the user's existing
app to bypass a signature mismatch.
