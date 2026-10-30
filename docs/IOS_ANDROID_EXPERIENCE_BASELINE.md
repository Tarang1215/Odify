# iOS target: the existing Android music-app experience

The existing Android APK is the reference for behavior and interaction. The
iOS port should preserve its music-app experience: native artwork and metadata,
transport/scrubbing controls, source/next-song pill, lyrics, editable queue,
library/playlists, smooth glass transitions and continuous mixed-source audio.
It should not substitute an ordinary video-player screen or an external-app
handoff for normal playback.

The Android source attaches a service-owned 240dp WebView underneath OdifyRoot.
It uses YT.Player internally while the native music interface remains in front.
Its service/queue/media-session behavior is the implementation reference; the
exact currently installed APK binary has not been independently matched here.

## Build 15 integration

Production LibraryStore supplies a WKWebView-backed YouTube driver. Native
PlaybackEngine remains the shared queue/control/metadata layer; Drive/local
audio remains AVQueuePlayer-based. A scene-window media renderer remains under
the opaque native music UI when full-player presentations open or close. No
video screen is automatically presented. Normal YouTube playback no longer
uses the experimental direct-media URL resolver.

Known video IDs load the embedded player. Catalog-only selections use the
ordinary public YouTube search page inside the app's WKWebView to read public
result metadata and match artist/title, excluding cover/live variants. No
cookies, tokens, HTML, user profiles or media URLs are exported. Consent,
sign-in, unavailable results and player restrictions fail explicitly; they are
not solved by automated interactions or alternate private endpoints.

The driver uses the app bundle identity for its embed origin/base URL, handles
play/pause/seek through the documented player API, and reuses a ready player
with loadVideoById. Generation IDs reject stale callbacks from prior queue
occurrences. A finite preparation lease, renderer-start/buffering watchdog,
typed player errors and autoplay-blocked handling prevent indefinite spinners.
Only fixed stages and codes go into the existing safe local trace.

## Acceptance and evidence boundaries

- Preserve the native music UI and library while changing the internal engine.
- Verify seek, pause/resume, reorder, duplicate/repeat occurrences, completion,
  and Web-to-native-to-Web transitions without losing current selection.
- Verify displayed artwork/title/artist and system controls on the device.
- Verify screen-off continuation and next-track transitions physically at zero
  device volume, followed by long-duration stability testing.
- Keep platform/provider failures visible; no private APIs, fake page-visibility
  signals, ad modifications, imported credentials or silent-audio keepalives.

Fake-driver/native-audio integration tests and JavaScriptCore tests exercise
the control and handoff logic. They cannot establish YouTube network access,
WebKit media behavior under physical suspension or Android feature parity.
Those remain release-blocking evidence gaps for calling this a completed port;
the IPA is a preview for that validation.

## Verified build 15 preview

Source: `017a4c51cc64c0a0ca18af9e46f8770a5498b087`.
[Cloud validation](https://github.com/Tarang1215/Odify/actions/runs/41018706108)
passed six packaging checks and 61 XCTest cases with zero failures (37.462
seconds of test execution). All seven new web-driver/JavaScript regression
cases passed, including pause during asynchronous video lookup and mixed
Web/native/Web queue transitions with a generated silent local MP3.

The downloaded CI artifact and separately downloaded public IPA were checked
independently. The preview is native arm64 device Release, version 1.1.0 (15),
iPad/iPhone, minimum OS 26, with the required background-audio declaration,
OAuth callback and original dependency notices. It contains no guest extensions
or embedded provisioning profile. Public IPA: 1,864,067 bytes; SHA256
`94a3b3e06a534b0ae79e1acc358428e300157f425ab29ca284f3d96503129031`.

[LiveContainer IPA](https://github.com/Tarang1215/Odify/releases/download/ios-native-1.1.0-b15-017a4c5/Odify-iOS-1.1.0.ipa).
The historical direct-audio probe still returned a service sign-in/bot denial;
it does not test the new production WebKit route. Simulator presentation
fixtures and controlled tests do not verify live web playback, actual screen-off
continuation, remote controls, frame pacing or complete Android feature parity.
Those physical acceptance checks remain pending.

Primary references:
- https://developers.google.com/youtube/iframe_api_reference
- https://developers.google.com/youtube/terms/required-minimum-functionality
- https://developer.apple.com/documentation/webkit/wkwebviewconfiguration/allowsinlinemediaplayback
