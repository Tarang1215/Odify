# Odify native iPad / iPhone port — 2026-10-29

User request: replace the earlier common Flutter UI with an iPad-adaptive native
Odify interface, native Liquid Glass, internal audio streaming and continuous
background playback. Deliver an unsigned device IPA for LiveContainer. Public
repository/cloud Mac build/artifact upload were expressly authorized in chat.

## Architecture

`native-ios/` is a new SwiftUI + AVFoundation target, not a reskin of the common
Flutter app. Android is untouched. The previous common assembly and its legacy
workflow remain reproducible, but the active iOS branch runs
`odify-native-ios.yml` and produces **1.1.0 (3)**, minimum **iOS/iPadOS 26**.

- Window width < 760 pt: compact navigation and full player.
- 760–1099 pt: navigation sidebar + browsing pane + horizontal mini player.
- >= 1100 pt: sidebar + browsing pane + now-playing/up-next inspector.
- Native `glassEffect`, `GlassEffectContainer`, system menus, artwork zoom
  navigation, draggable expanding lyric/queue panels. No full-screen live blur
  on every scrolling row. Thumbnail decoding/cache is isolated from the UI.
- Home: artists, Made for you collection/favorites/discovery, mood searches.
- Search: official metadata, YouTube video results, imported collection.
- Explore: independent cached KR/JP/US Apple Music song charts in a finger-driven
  pager. This is **not** the requested independent national artist ranking.
- Artist: local + official catalog songs; normalized artist/title deduplication;
  official artist portraits where the catalog supplies an Apple Music page.
  Unavailable portraits use a person placeholder, not album artwork.
- Playlists: detail screen, cover/photo picker, rename/create/delete, track menu.
- Lyrics: user-supplied text and editing; no automatic lyric scraping.

## Playback

`AVQueuePlayer` plays audio directly inside Odify; no playback through YTKACE or
ReVanced. The Android native preview itself uses an owned YouTube WebView, while
ReVanced/Morphe are only external-open targets.

YouTubeKit is pinned to `89854c93207ad0d2b3d78d6f1aea24595ca66552` (MIT; original
license bundled). Only its **local** extraction mode is enabled. No third-party
remote-extraction proxy, user cookies or credentials are used. Audio-only native
M4A sources go to AVQueuePlayer, not a hidden WKWebView. The primary resolver is
now a shared public-video iOS client (`native-ios/Streaming`), after the original
extractor failed the cloud probe. A Windows-side soundless request received
HTTP 206 and 1024 bytes for both AAC itag 139 and 140 on 2026-10-29. The exact same
Swift resolver is compiled into the app and Mac probe. Service login/age/private
restrictions are surfaced, not retried through a different client. There is no claim of
permanent YouTube compatibility, guaranteed ad-free operation, or reliable
restricted/paid/account-required playback.

Official catalog title/artist/artwork stays attached to the queue entry; video
matching requires title+artist and excludes explicit live/cover/remix variants.
Raw video selection keeps video metadata; cover/live uses the video thumbnail.

Queue occurrence UUIDs survive reorder/restore; moving upcoming entries rebuilds
only prepared successors and retains the current AVPlayerItem. Two successors
are resolved ahead of time. Automatic recommendation playback (toggle in the
current-track menu) adds unseen songs before a short queue ends. Discovery
failure leaves the existing prepared queue intact. Repeat, seek, pause, skips, interruption/headphone
handling and MPNowPlayingInfo/MPRemoteCommandCenter are wired. Queue/library and
position persist using container-relative file paths and lock-compatible file
protection. Force-quitting an iOS app necessarily ends its playback; the app does
not promise to survive the user force-closing LiveContainer.

## Import / limits

- Files import uses Apple's document picker and copies selected audio into
  Odify storage, preserving embedded tags/artwork where present.
- Direct Google Drive uses the user's newly created iOS OAuth public client ID,
  GoogleSignIn 9.1.0 pinned to `913b4005ea26aebe1c97d54e35ad82a515924c71`, and
  `drive.readonly` only. The SDK owns Keychain credentials. Root folders, nested
  folders, shared-with-me items, pagination, account-bound identities and token
  refresh are connected. AVAssetResourceLoader reads authenticated byte ranges
  and supports seeking; tokens are never embedded in playback URLs. Redirects
  strip authorization on a host change. No private Drive media is in CI.
- Embedded Drive tags/artwork are read locally. Private Drive/local tags are
  **not automatically sent to Apple/iTunes/YouTube for cover/discovery lookup**.
  Auto-review rejected automatic private-metadata enrichment; that code was not
  applied, and recommendations for private sources use the on-device library.
- **Existing Android/common-app library migration, independent national artist
  rankings, and home WidgetKit extensions are not included in this milestone.**
- A LiveContainer guest cannot independently register a WidgetKit extension;
  the native in-app glass mini player is included instead. Standalone signing
  and WidgetKit sharing require a separate delivery design.
- YTKACE is a separately SideStore-installed YouTube app/tweak. It is not an SDK
  exposing its player/queue to this app; no cross-app audio ownership is claimed.

## Validation gates

1. Python packaging tests keep old Flutter validation and native iPad/device checks.
2. Cloud Xcode Release arm64 build, no signing credentials.
3. Simulator XCTest: queue reorder/repeats, catalog identity, parser, path safety,
   **actual muted native AVQueue playback and automatic transition after reorder**.
4. Opt-in simulator presentation fixture screenshot, no user library uploaded.
5. Separate soundless live stream probe. A failure is retained and must not be
   reported as a streaming success. GitHub runner networking differs from iPad.
6. Independent downloaded IPA checksum, Mach-O device platform, extension/license
   inspection before delivery.

Pending real hardware checks: LiveContainer launch, native glass rendering and
frame pacing, file-provider access, YouTube region/network compatibility,
Google account consent/refresh in LiveContainer, private Drive playback,
lock-screen controls, and 60+ minute screen-off transitions. Build success or a
simulator queue test does not establish these hardware outcomes.

## Work in progress validation, 2026-10-29

- `f4bfd57`: device Release build passed. The soundless YouTube probe failed
  (`YouTubeKitError.extractError`); the continued-on-error step's apparent success
  was initially misread. The downloaded JSON established failure, and the user
  was explicitly corrected. Never infer probe success from step conclusion.
  Twelve XCTest cases ran: eleven passed; the corrupt prepared-file case failed
  (two assertions). Edited-queue automatic transition now passes and reuses the
  buffered AVPlayerItem. Simulator portrait/landscape/player captures produced.
- The corrupt-file case is being fixed by validating asset playability before
  insertion into AVQueuePlayer, so a bad successor cannot poison the player.
- Direct Drive code adds fake-account authenticated range/seek tests plus
  parsing, pagination safety, redirect token stripping and account partition tests.
- A directly downloadable GitHub prerelease is produced only after the checks
  pass. User expressly requested a direct IPA link for LiveContainer. This does
  not publish the user's personal credentials, Drive library or media files.

## Primary sources consulted

- Apple Liquid Glass: https://developer.apple.com/documentation/swiftui/view/glasseffect(_:in:)
- Apple background audio: https://developer.apple.com/documentation/avfaudio/avaudiosession
- YouTubeKit: https://github.com/alexeichhorn/YouTubeKit
- YTKACE: https://github.com/itzzace/ytkace
- LiveContainer limitations: https://livecontainer.github.io/docs/faq
- Embedded-video advertisements: https://support.google.com/youtube/answer/132596

## Verified delivery — 2026-10-29

- App source: `37338059b8dc9856425fc20ebf10bc9ce7e4907e`.
- Version: **1.1.0 (3)**. Native arm64 iPhone/iPad device app, minimum OS 26.0.
- CI: https://github.com/Tarang1215/Odify/actions/runs/40835881312 — **success**.
- Six Python packaging tests and **18 XCTest cases passed**. Drive's fake-account
  authenticated range loader successfully parsed a native audio asset and
  sought within it; real muted AVQueue tests verified reorder/advance and
  corrupt-successor recovery. No private Google account/media was used by CI.
- IPA: 1,683,497 bytes, main native Mach-O 6,463,824 bytes. Original SDK licenses,
  Google callback scheme, bundle ID, background-audio declaration, device CPU/
  platform, execution permission and zero guest extensions independently checked.
- SHA256: `21902d3c4be90b0984a2d2816e8eb2185b09b44ccea66135c9cb5a7171a514fc`.
- Release: https://github.com/Tarang1215/Odify/releases/tag/ios-native-1.1.0-b3-3733805
- Direct IPA: https://github.com/Tarang1215/Odify/releases/download/ios-native-1.1.0-b3-3733805/Odify-iOS-1.1.0.ipa
- The public direct link was fetched without authentication and matched the
  independently verified artifact and GitHub release asset digest.
- Exact resolved dependencies: `IOS_NATIVE_DEPENDENCIES_3733805.json` beside this
  document. Downloaded verification JSON and IPA are in ignored
  `dist/ios/native-1.1.0/` in the active worktree.

The final shared-client cloud YouTube probe **failed** with
`Sign in to confirm you’re not a bot`. A separate company-network soundless
request for the same public video returned HTTP 206/audio-mp4 for itag 139 and
140 (1024 bytes each). This establishes a working path on that network, not
iPad playback or universal stream availability. The release explicitly includes
the failed cloud-probe JSON and result notice. Do not report this probe passed.

The captured simulator screenshots remained **portrait** (2048×2732), despite
the landscape preference. Portrait presentation was visually inspected. Wide-
window three-pane code is present, but the landscape capture and actual device
layout/rotation remain unverified. Preview captures use fictional fixture music,
not the user's collection.

## iPad installation

1. Open the direct IPA link in iPad Safari and download to Files.
2. Open the existing LiveContainer, tap +, and select the IPA in Files/Downloads.
3. Launch Odify → Library → Google Drive → connect the Google account registered
   as an OAuth test user → open a folder and choose a song.
4. Odify does not need an additional standalone SideStore installation when used
   as a LiveContainer guest. Keep the existing container/signing setup intact.

Still pending: actual LiveContainer Google login/return/refresh, the user's Drive
audio, actual YouTube playback, system controls, landscape/resize behavior, frame
pacing, and 60+ minute screen-off continuity. Android is untouched. PR 22 stays draft.


## 2026-10-30 playback follow-up

Build 6 supersedes build 3 for playback startup/recovery and square artist portraits.
All 28 native tests and six packaging checks passed, including Drive MP3 start,
seek and automatic next-song playback. Full evidence, direct IPA/checksum and
remaining hardware/YouTube limitations are in `IOS_PLAYBACK_RECOVERY_20261030.md`.
