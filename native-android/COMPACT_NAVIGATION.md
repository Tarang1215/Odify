# Music navigation, home and immersive player

This UI update builds on `feature/odify-v12-master-db` at
`8304e79cb7e2986ceba87f160f0611791b130e03`.

The bottom navigation has three destinations, in this order: Home, Library,
Search. Profile/Settings is available from the account button on each primary
screen. Settings has an explicit Back button and retains system Back history.

The existing white, monochrome, rounded design and dark floating mini-player
remain. The navigation uses a 56dp floating capsule, capped at 312dp wide, with
23dp icons. The selected icon has a 48-by-36dp pale background, rather than a
large grey tab. Every tab has a 48dp-high touch area, a localized accessible
name, and selected-tab semantics. System navigation insets and the shared
mini-player animation use the same navigation height.

Library categories are Playlists, Songs, Albums, Artists, and Liked Songs.
The existing `Saved` category is labeled accurately as likes. YouTube playlists
are reachable directly from Library, while authorization and source management
remain in Settings. Search input, filters, and Library category use saveable
page state when moving among screens.

Home now opens with a real-cover hero and working playback action, followed by
recent listening/additions, actual genre/favorite mixes, artists from the local
catalog and the user's own playlists. Empty categories are omitted. There are
no fabricated tracks, playlist counts, or recommendations from an external AI.

The full player reuses the cached current cover for a single blurred background
under a contrast scrim and the existing glass controls. Its surface expands
from the measured mini-player bounds with spring geometry, with no opacity fade
on the player surface. Dragging down changes that same geometry interactively;
release either dismisses to the mini-player or springs back. A nested-scroll
connection gives collapse priority only when the content is already at its top,
so scrolling a compact display, horizontal cover swipes, and seeking remain
separate gestures. Android versions below 31 do not apply Compose's blur effect.

No audio files, MASTER links, OAuth identity, signing certificate, catalog,
playlist persistence format, or playback service are modified. Version code is
15, version name `1.0.0-preview12.3`; the existing signed application identity
is retained.

## Validation

The change is submitted to the existing Kotlin compile/JVM regression CI.
The native preview workflow also handles the dedicated UI branch, retaining
all existing signing checks. A missing fixed signing secret must still fail
instead of generating a different identity.

Manual device checks remain required: three tab hit targets and TalkBack,
small-screen/large-font layout, profile Back returning to the prior screen,
search/filter retention, playlist access, gesture insets, and mini-player
show/hide behavior with an active queue. USB and `D:\Odify` were not visible
to the cloud execution environment during this change; no device installation
or interaction is claimed.
