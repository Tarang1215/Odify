# Terminal media responses and public chart transport

An initial successful media prefix does not establish that later ranges are
authorized. The prior source forgot a 401/403/429 after each failed block,
allowing later AVFoundation requests to contact the denied source again. Typed
HTTP errors could also be lost behind the player's eventual decoder error.

Build 14 latches the first media denial for that source and cancels pending
reads. Subsequent reads fail locally, including reads covered by the cached
prefix. The resource-loader callback reports that typed failure directly to
the engine. A denied current source pauses and releases its player items,
cancels preparation/stall work, exits loading and retains the selected queue
occurrence. Obsolete callbacks cannot restore playing/buffering state.

A terminal successor failure stops preparation instead of resolving every
later recording. Existing valid current audio may continue. Already prepared
valid items can finish naturally, but no cold automatic requests are started
after a terminal response. Source UUIDs distinguish current and future player
items when repeat-one shares the same logical queue occurrence. An explicit
user selection/play action may start a new request; there is no automatic
alternate endpoint/client, signature rewriting, cookie import or proxy.

The error UI offers a user-directed public YouTube link: the exact video when
its ID is known, otherwise a search using the public YouTube track metadata.
Private Drive/local tracks never receive this external-search action. External
playback is owned by the selected app/browser, not Odify's queue.

Public Apple chart JSON now has a dedicated default-session connection pool,
an application/json Accept header and an honest Odify User-Agent. It shares
neither cookies nor credentials with browser/search/artwork traffic. Cached
home discovery, inline errors and manual refresh from build 13 are retained.
The public Korean chart endpoint returned HTTP 200 and 50 entries in an
independent unauthenticated PC check. That does not establish availability in
every guest-runtime/network environment.

Regression coverage includes source-level remembered denial, a prefix-only
server that denies subsequent media ranges, current-item loading termination,
no automatic queue scan after a denied successor, and chart request identity /
JSON / credential boundaries. These use controlled responses and generated
audio; actual YouTube availability and physical screen-off playback remain
separate validation requirements.
