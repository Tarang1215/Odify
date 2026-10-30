# Bounded audio delivery and prepared successors

Build 12 replaces per-byte Swift iteration for YouTube media with a task-specific
URLSession data delegate. It validates the status, audio type, Content-Range and
declared length before accepting body chunks, caps each transfer, and rejects
oversized or incomplete bodies. Cancellation completes its continuation once
and cancels the network task. Redirects remain HTTPS GoogleVideo-only, retain
the Range/User-Agent and discard credentials/cookies.

The source preflight retains 32KiB. Subsequent reads use 128KiB aligned blocks,
combine overlapping in-flight reads, and reuse already received bytes. At most
two blocks per source are fetched concurrently; eight cached blocks plus the
prefix retain at most 1MiB + 32KiB of completed media. Pending transfers and
caller response buffers are additional bounded memory. No media is saved to
disk. Closing the source cancels pending reads and releases cached bytes.

Each timed-out/lost/cannot-connect range request retries once at the same URL
after 250ms, with an 8-second request inactivity timeout. Explicit HTTP denials
do not retry or switch clients/endpoints. This is bounded recovery rather than
an availability guarantee; resource-time limits remain finite.

Before inserting a YouTube successor into AVQueuePlayer, prepare its first block
and last 64KiB, covering starting audio and common M4A metadata placement. The
current item keeps playing while this work runs under the existing finite
preparation lease. Upcoming sources keep their buffers until removed. Real
range failures are recorded with only stage/source/domain/code; they contain
no URLs, titles, credentials or account identifiers.

Queue refill observes actual AVPlayerItem identity, including repeat-one
transitions whose queue occurrence ID stays the same. Initial item insertion
still does not restart successor preparation. Partially satisfied loader
requests calculate their end from requestedOffset + requestedLength, avoiding
an overshoot when currentOffset has advanced.

## Regression scenarios

- Overlapping reads share one HTTP block request; cached reads keep working
  after new network requests are denied.
- Warmed beginning/tail bytes remain usable after connectivity disappears;
  scanning many blocks retains only the bounded cache, and closing clears it.
- Transient timeout recovery, single-request authorization denial, oversized
  body rejection and prompt explicit cancellation.
- Generated AAC starts and advances through two prepared successors with new
  network requests denied after a background lifecycle notification.
- Repeat-one keeps replenishing prepared items across four transitions while
  its logical queue occurrence remains unchanged.

These use generated audio and controlled URLProtocol responses. A background
lifecycle notification does not suspend the simulator or establish physical
iPad/LiveContainer lock-screen survival. Actual buffering, timeouts and long
screen-off playback need device validation. The new design does not prove that
a particular earlier device timeout was caused by per-byte processing.

Build 13 adds a separate catalog-request budget and service-specific, code-only
network errors. A home chart timeout no longer presents an app-wide alert.
The last successfully fetched public chart is stored locally and shown on
restart before refreshing. A failed refresh retains existing discovery and
library content, with an inline retry and pull-to-refresh. Cancellation is
silent, and `home-catalog-failed` is distinguished from media-range failure in
the safe local trace. New home tests cover restart/offline fallback, first-load
timeout without blocking the library, and canceled loads.

The initial build-12 validation did not pass: device Release compilation
succeeded, but a large generated-data test expression exceeded Swift's type
checker budget. The fixture generator was rewritten as a simple bounded loop;
final automated results are recorded after validation.

Primary API references:
- https://developer.apple.com/documentation/foundation/urlsessiontask/delegate
- https://developer.apple.com/documentation/foundation/urlsessiondatadelegate
- https://developer.apple.com/documentation/avfoundation/avqueueplayer
