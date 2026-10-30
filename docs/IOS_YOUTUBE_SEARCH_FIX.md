# iPad YouTube search failure (-2), build 16

The production build 15 WebKit driver sent artist + title + `official audio`,
then read `window.ytInitialData` once at `didFinish`. Catalog.videoResults
recognized only `videoRenderer`. A public iPad search request for
`IU Love wins all lyrics` on 2026-10-31 returned twenty
`videoWithContextRenderer` cards and zero `videoRenderer` cards. Mobile cards
use `headline` and `shortBylineText`. The page also assigned ytInitialData as
an escaped JSON **string**, rather than the desktop object. Thus valid search
results were discarded before the embedded player received a video ID. The
driver reported its own generic -2 failure; this is not a documented YouTube
player error and is unrelated to missing lyric text or Drive authentication.

Build 16 supports both data representations and desktop/mobile/compact/lockup
video cards. It waits up to three seconds for hydration within the existing
twenty-second lookup deadline and rejects callbacks for obsolete queries and
queue generations. If a populated result page has no artist/title match, it
tries `artist title lyrics` once. This is a public search hint, not lyric
retrieval. Artist/title matching and variant rejection remain required.
Ads, playlist lockups and arbitrary watch endpoints are not song candidates.

The separate -2/-3/-4 descriptions distinguish an unmatched recording, an
unreadable response, and a consent/verification redirect. Pausing during lookup
preserves the lookup deadline; PlaybackEngine retains the final autoplay
intent. Known video IDs still load directly, without a search. Drive/local
playback and its authentication are unchanged.

Regression coverage includes the reduced public mobile card, JSON-string and
object snapshots through JavaScriptCore, pending hydration, duplicate desktop
cards, compact and lockup cards, rejected variants/ads/invalid identities,
query composition, and distinct error descriptions. Existing web-driver queue,
pause, stale-event and mixed-source tests continue to apply. Device Release
compilation and simulator XCTest run in the native iOS workflow. Actual
network playback and physical screen-off behavior still require the iPad.
