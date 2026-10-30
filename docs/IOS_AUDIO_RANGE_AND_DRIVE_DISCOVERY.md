# Native audio ranges and Drive music discovery

Build 8 adds a verified HTTP byte source between public YouTube resolution and
AVFoundation. A resolved URL alone is not proof that the audio can be fetched.
The app first reads bytes 0–1023, validates HTTP 206, Content-Range, content type
and total size, then serves AVAssetResourceLoader requests with that same
ephemeral HTTP client, User-Agent and bounded Range requests. The signed URL is
kept only in memory; the player sees a local custom-scheme asset URL. Requests
do not use cookies, Drive tokens, signature rewriting or third-party proxies.

Explicit 401/403/429 responses are terminal access errors, not reasons to change
clients/endpoints or silently skip the selected song. AVPlayer error-log status
codes preserve a mid-playback denial. Cancellation, rejected preparation and
item removal release both resource requests and owned URLSession instances.
The existing native audio session and two-successor queue remain in place.

Drive listings now explicitly select spaces=drive, corpora=user and bypass
local response caches. The Whole music view queries audio MIME types without
depending on a particular parent folder or name. Named Odify root discovery
remains; if it finds no audio, library sync falls back to the audio-only query.
This does not fetch images/videos or recursively traverse unrelated folders.
Files with incorrect non-audio MIME types remain available by manual folder
browsing, which retains filename-extension handling.

Before library sync, Drive about.get confirms its current account against the
SDK account. Comparison stays inside the app; emails, tokens and account IDs
are not included in logs or library snapshots. A mismatch is reported rather
than treating a different account's empty library as a successful sync.

A bounded device-local diagnostic file contains only fixed playback stages,
source names, uptime and sanitized numeric error/domain chains (maximum 80
events). It excludes track titles, file names/IDs, URLs, credentials and error
userInfo. App info exposes a ShareLink for user-directed export. This avoids
requiring a live USB capture to coincide exactly with each playback attempt.
No device log or private account information is published in this document.

Native tests exercise generated AAC audio through the actual queue engine and
the new HTTP/custom-resource path: start, seek and automatic next-song playback;
matching public HTTP headers; rejection of a 403 after exactly one request;
and trace size/privacy limits. Drive tests add music outside named roots and
rejection of an unexpected API account. Existing MP3/Drive/queue tests remain.
These generated/mock checks do not establish actual streaming availability,
guest-runtime success or physical screen-off survival. Cloud YouTube probe JSON
must be inspected separately from the workflow's success status.

Sources:
- https://developers.google.com/workspace/drive/api/reference/rest/v3/files/list
- https://developers.google.com/workspace/drive/api/reference/rest/v3/about/get
- https://developer.apple.com/documentation/avfoundation/avassetresourceloader


## Verified build 8

Source: b947e9ff4fdd9205635955b123e9a989fd0bbceb.
CI: https://github.com/Tarang1215/Odify/actions/runs/40994621043 — success.
Six packaging checks and **37 XCTest cases passed**, with zero failures in
29.718 seconds. The verified range-source queue start/seek/advance test passed
using generated AAC audio and a mock HTTP server; the denial test stopped after
one request. These are generated/mock checks, not live YouTube playback.

Native arm64 iPhone/iPad Release, iOS/iPadOS 26+, version 1.1.0 (8).
IPA 1,764,395 bytes; main executable 6,799,632 bytes. Independent checks cover
actual device Mach-O, licenses, Google OAuth callback, background audio and
absence of guest extensions. Public download matched the separate artifact and
release digest: 048dda5826b08d7f0d2c8a66ff1652a3549233d7f9ad3ea8fc1b3fe15db3ef8a.

Release: https://github.com/Tarang1215/Odify/releases/tag/ios-native-1.1.0-b8-b947e9f
Direct IPA: https://github.com/Tarang1215/Odify/releases/download/ios-native-1.1.0-b8-b947e9f/Odify-iOS-1.1.0.ipa

The final cloud probe still returned a sign-in/bot confirmation restriction.
Its failed JSON is included in the release. Physical guest-runtime streaming,
Drive account lookup and long screen-off continuity remain pending and must
not be described as verified by the passing synthetic queue tests.
