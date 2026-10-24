# Dark personal music experience — preview12.4

Requested navigation: **Home → Explore → Search → Library**, with icon-only tabs,
a 48dp touch target and accessible names. Profile and account/source management
are utilities reached from the page headers. The 56dp charcoal navigation capsule
stays visible on primary screens; fullscreen playback covers it.

All main pages, search, settings, sheets, splash and system bars use the same dark
palette. Artwork supplies color; the UI uses restrained charcoal surfaces and thin
boundaries. The immersive player uses one cached blurred album cover, springs out
of the actual mini-player bounds, follows a downward drag and collapses to the
same origin. There is no full-player opacity fade or remote-audio decoding for art.

Home contains an actual artwork hero, personal mix cards, recent music and saved
playlists. The device records a qualified listen after 30 seconds of actual playing
time, including background playback. Seeking or a brief tap does not fabricate a
listen. The bounded event history covers 60 days and is kept only on the device.
Likes, listened artists and genres produce explainable mixes with an artist-diversity
limit. A new listener sees an honestly labeled own-music starting mix. Saving a mix
persists that exact list atomically in the existing playlist store; later ranking
changes never rewrite saved playlists.

Explore reads public Apple Music Top 50 feeds for Korea, Japan and the US. Charts
show source and provider update time. Genre filters operate **within the country's
Top 50**, not an independent full genre chart or all-services popularity metric.
Six-hour metadata caching and saved-chart fallback avoid blocking the screen when
offline. Selecting a chart song searches the user's owned catalog; no unowned audio
or unreviewed YouTube stream is silently substituted.

Search covers the full connected music server by title, artist, album, genre and
available lyrics. Library defaults to saved playlists, with Offline and Liked tabs;
it does not dump the whole Drive server into the personal collection. Imported local
files and supported local document URIs count as offline. Google Drive document
links and Media3's temporary streaming cache do not. Completed exports to a local
document are registered as offline copies without modifying their source.

The seek bar uses **12 decorative 96-bar patterns**. A randomly selected pattern is
stable for the currently displayed song. These are stylized artwork, not an audio
measurement. Tapping/dragging and accessibility progress actions still seek actual
audio position. Embedded lyrics, queue editing, source audio, playlists, OAuth
scopes and the permanent package/signing identity are retained.

Local validation: 24 JVM tests and Android-test Kotlin compilation passed. The
fixed-signature GitHub build, private app-data backup and real Galaxy acceptance
results are tracked in `docs/V46_PROGRESS.md`. Never uninstall the user's existing
app to bypass a signature mismatch.
