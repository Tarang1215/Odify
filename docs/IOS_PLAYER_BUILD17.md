# Build 17: lyrics, player interactions and source selection

User reports after build 16 confirmed live YouTube playback, but exposed missing
lyrics search, duplicated mini-player progress, unreliable modal dismissal,
layout-pushing panels, weak artwork color treatment, US chart timeouts, YouTube
selection despite owned Drive files, and paused starts.

The full player now uses an explicit root presentation binding and an animated
overlay. Its close button updates that binding directly. Downward drags on the
header and main content use directional/distance thresholds and spring back when
cancelled. Lyrics and queue sit above the unchanged content layout. Their panel
controls and header remain available. Full-screen material samples the underlying
app instead of an opaque modal background. Reduced Motion disables optional
movement. The mini player has a single compact timeline on wide screens, album
color glass and play/pause artwork/waveform animation. Dominant artwork hue ignores
black borders; metadata updates no longer reset an unchanged artwork palette.

LRCLIB search is explicitly opened by the user, includes editable title/artist,
previews candidate metadata/text, and saves to the originally chosen recording.
No bulk/background private-library lookups are introduced. Requests use a named
client, omit cookies/auth, cache results and honor Retry-After. LRC timestamps,
multiple tags, offsets, active-line scrolling and line-to-seek are supported.
Reference: https://lrclib.net/docs.txt

Source preference runs before selection and queue preparation: one exact
normalized artist/title match, compatible duration, optional album disambiguation,
existing contained local path or a valid Drive file under the currently connected
account. Ambiguous editions and disconnected accounts remain unlinked. It uses
the already connected local library rather than scanning the remote Drive on
each tap. Known owned files never enter the YouTube web renderer.

The YouTube bridge carries initial autoplay intent before any initial snapshot.
Preparation pause events cannot cancel a start before the video actually plays;
explicit pause still overrides a late playing event. Old-video snapshots and
ended events cannot advance a freshly selected queue item. Provider autoplay
blocking still surfaces normally. Repeated equal state snapshots no longer
invalidate the complete music UI twice per second.

US marketing-feed timeouts were reproduced; Apple's US iTunes chart feed returned
85 rows during this investigation. Both feed schemas preserve catalog IDs and
country, request up to 100 rows, and do not substitute another country's chart.
Cancellation does not initiate fallback. Lyrics requests, source identity rules,
chart fallback, palette selection, bridge intent and native playback routing have
targeted regressions. XCUITest exercises repeated one-tap collapse/reopen,
downward drag dismissal and panel layout stability, retaining screenshots.

Physical iPad behavior, actual network autoplay and prolonged screen-off playback
remain device acceptance checks. User data and app identity are preserved.
