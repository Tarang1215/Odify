# iOS queue preparation cancellation

## Problem and behavior

Queue edits, recommendation appends and current-item updates rebuild the prepared
successors. That cancels the old preparation task. URLSession can complete that
request with `URLError.cancelled` (`NSURLErrorDomain -999`) rather than Swift's
`CancellationError`. The old generic catch showed a next-song failure and added
the still-valid entry to `failedIDs`, including when a replacement request had
already prepared that song.

Build 9 ignores canceled tasks, obsolete preparation generations and explicit
network cancellation (including wrapped underlying errors). Neither the banner
nor the failed-entry set is changed by those results. Cancellation while opening
a media source also closes its owned HTTP session. Source resolution preserves
cancellation instead of wrapping it as a user-visible connection error.

Real failures from the active preparation still show the source and error code.
They are recorded in the existing bounded, device-local trace as
`next-prepare-failed`, without titles, URLs, account identifiers or tokens. The
ready-item trace now uses that item's source rather than the currently playing
item's source, so mixed-source queues are attributed correctly.

## Regression coverage

`YouTubeStreamingTests` holds one successor request until after the queue is
rebuilt, then releases it with either a cancellation or a real HTTP failure.
Both cases must leave the replacement queue and current audio item intact, keep
pause respected and retain the successor through another rebuild. Advancing to
that successor must still reach a ready AVPlayerItem.

A separate test verifies that a real HTTP 403 from the active successor request
is still reported and traced without replacing the current song. Error
classification covers plain and wrapped cancellation, timeout and HTTP denial.

These use generated silent audio and controlled responses, not live YouTube or
private Drive data. Cloud compilation and automated results are recorded after
the build completes; physical-device screen-off and long-duration playback are
separate checks.

Build 9 validation: [CI 41001525710](https://github.com/Tarang1215/Odify/actions/runs/41001525710)
passed all 41 XCTest cases and six packaging tests. The separate live cloud
YouTube probe failed a service restriction; that is not a passing live-stream test.

Build 10 also avoids rebuilding successors on the deferred current-item callback
for the initially selected item: `startCurrent` has already scheduled that work.
This removes an unnecessary cancellation/restart of next-song network requests.
Actual automatic transitions still refill the prepared queue. Fixed `playing`
and `queue-advanced` stages are added to the existing local trace to distinguish
ready media from playback that actually started. Remaining network-dependent
buffering must be assessed separately on the physical device.

Apple's documented cancellation meaning:
https://developer.apple.com/documentation/foundation/urlerror/cancelled
