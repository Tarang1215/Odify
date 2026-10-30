# iPad playback recovery — 2026-10-30

The user tested build 3 in LiveContainer: Google Drive connected, but Drive and
YouTube playback did not start. The app displayed “재생할 수 없는 음원입니다”
and subsequently a request timeout. Home artist images looked soft and did not
fill their circular frames. No actual iPad logs were obtained. The user confirmed their Drive files are MP3.

## Changes in builds 4–6

- Keep `AVAsset.isPlayable` preflight for local files, but let remote and custom
  Drive assets enter AVPlayerItem and report their actual readiness/failure.
  The generic preflight rejection was confirmed by the reported message;
  the underlying device/network failure is not yet established.
- Apply the same public HTTP User-Agent to YouTube resolution and AVFoundation
  media playback using Apple's documented AVURLAssetHTTPUserAgentKey.
- Honor signed stream expiration with a two-minute margin and a 30-minute cap;
  honor cancellation before an extraction fallback. Transient player API errors
  get one short delayed retry; explicit access restrictions never retry through
  another client. A network timeout does not start a slow extraction fallback.
- Recover a stalled current item after 20 seconds without progress, bounded by
  the existing per-occurrence retry/skip budget. Never resume from a failure
  notification while the user has paused.
- Retry/skip an initially missing or failed selected item; scan past broken
  successors until two future items have actually been inserted. Remove a
  failed buffered successor and release its observation/resource loader.
- Recreate a terminally failed AVQueuePlayer before retrying, reinstall its
  observations and time observer, and preserve the user's volume. Handle a
  failed current item even when the native queue has already removed it.
- Release preparation work and finite iOS background assertions on completion,
  cancellation, and expiration. Prepare two successors and delayed autoplay
  additions while the current background-audio lifetime is available. There
  is no silent-audio keepalive or indefinite background entitlement claim.
- Preserve playback intent when the only song finishes before recommendations
  arrive; continue when additions arrive, or settle when discovery finishes.
  A user pause cancels this intent. Private Drive/local metadata still does not
  become automatic external recommendation or artwork queries.
- Show source-specific failure details and retry controls on Home and in Drive;
  AVFoundation error chains include domain/code only, excluding signed URLs,
  tokens, private file IDs and headers.
- Replace the Apple Music OpenGraph social-card transform with a verified
  600x600 original artist portrait. Fill and clip its exact square before
  circular clipping; decode for the current display scale.

## Validation and limits

First passing source: ef173307f625a0e4efed4246e57bc56ca4399fdb.
CI: https://github.com/Tarang1215/Odify/actions/runs/40979214598 — 26 XCTest
cases passed in 24.326 seconds, including both real AVFoundation Drive audio
tests and all 12 silent local transitions.

Final source with terminal-player recovery: b63a3c124127de3857db18cd466591ff9544a555.
CI: https://github.com/Tarang1215/Odify/actions/runs/40980535107 did not produce
a test-case/summary result before its eight-minute test-step limit. Device and
simulator compilation completed. The downloaded full test log ends after the
simulator build; test execution was not verified. This is not a passing run.
The test window was increased to 14 minutes for the next run.

MP3 validation source: 7620dda0db21f1a785415f45705bad91402e8a05.
CI: https://github.com/Tarang1215/Odify/actions/runs/40982215141 (result below).
A generated 320 kbit/s, 44.1 kHz stereo MP3 with ID3 and Xing/LAME headers is
used for actual Drive-loader + PlaybackEngine start, precise seek and automatic
next-song transition. The fixture is a test-bundle resource, not user music and
not part of the application target. YouTube resolution errors now surface
without multiplying the transport layer's own bounded retry/timeout budget.

Six local IPA packaging tests passed. Native XCTest additions cover actual
AAC/M4A audio through the fake-account Drive range loader and PlaybackEngine,
12 silent queue transitions with at most three prepared items, a missing
selected file followed by more failures, pause during a failure notification,
late recommendations after a one-song queue, remote current-item failure
recovery with volume preservation, portrait URL transformation,
signed URL lifetime and transient-versus-restricted YouTube responses.

The synthetic background lifecycle notification in the 12-song unit test is
**not** a real screen-lock, operating-system suspension, LiveContainer runtime,
or 60-minute test. The cloud Drive tests use generated audio and fake account
tokens; they do not access the user's Google Drive.

On this company's PC network, the public YouTube test returned player status OK
in 0.33 seconds and HTTP 206/audio-mp4 with 1024 media bytes in 0.38 seconds
total. This is an independent soundless network check, not iPad playback.
The cloud probe has its own recorded result and must be read from the JSON;
the workflow's normalized continued-on-error step is not success evidence.

Pending actual device validation: the reported Drive/YouTube start/timeout,
then screen-off transitions, long listening without termination, remote
controls, and portrait rendering. The generic rejection removal alone is not
evidence that all underlying network/decoder/guest-runtime errors are fixed.

## References

- https://developer.apple.com/documentation/avfoundation/avurlassethttpuseragentkey
- https://developer.apple.com/documentation/avfoundation/avplayeritem/playbackstallednotification
- https://developer.apple.com/documentation/avfoundation/avassetresourceloadingcontentinformationrequest
- https://developers.google.com/workspace/drive/api/guides/manage-downloads
- Public artist image transform checked against https://music.apple.com/us/artist/ros%C3%A9/1406242696


## Interim hardware check

While final MP3 validation runs, the user was given build 4, whose 26 native
tests passed, to try the startup fix and obtain concrete new error details if
needed. Public IPA download was independently checked against GitHub's digest,
version 4, iPad/iPhone device families and background audio. Its SHA256 is
`2b1d7df64d70d2165909328280384aedb8d734d53e44204b1421f5253ff5ea81`.
This does not establish actual MP3 playback on the user's iPad.


## Final verified build 6 delivery

- App source: `7620dda0db21f1a785415f45705bad91402e8a05`.
- CI https://github.com/Tarang1215/Odify/actions/runs/40982215141: **success**.
- Six Python packaging tests and **28 XCTest cases passed**, with zero failures
  in 25.554 seconds. MP3 start/seek/automatic advance passed in 1.772 seconds;
  failed-remote-current recovery passed, as did AAC/M4A streaming and 12 local
  silent transitions. These are simulator-generated/fake-account checks.
- Native arm64 iOS/iPadOS 26+ Release app, iPhone+iPad, version **1.1.0 (6)**.
  Main executable 6,614,576 bytes; IPA 1,712,989 bytes. Independent verification
  checked executable/platform/CPU, required licenses, OAuth callback and client,
  background audio and absence of guest extensions or embedded provisioning.
- IPA SHA256 `a2d0815aeaaee78ee1ff87af2b92b034f6df41fa11442141e778bf9eeae9a718`.
- Release: https://github.com/Tarang1215/Odify/releases/tag/ios-native-1.1.0-b6-7620dda
- Direct IPA: https://github.com/Tarang1215/Odify/releases/download/ios-native-1.1.0-b6-7620dda/Odify-iOS-1.1.0.ipa
- Public download without authentication matched both the separately downloaded
  CI artifact and GitHub release asset digest. Original downloaded artifacts,
  test log, dependency lock and probe JSON are ignored under `.local/ios-build6/`;
  the independently checked IPA/report is under `dist/ios/native-1.1.0-b6/`.
- Final cloud YouTube probe **failed** with `Sign in to confirm you’re not a bot`.
  The failed JSON is explicitly included in the release. The company PC public
  network test succeeded; actual iPad availability is still not established.

The user reported Drive connection and an MP3 playback attempt in build 3; no
subsequent confirmation of actual playback success has been received. Neither
these short simulator checks nor the background lifecycle notification proves
physical iPad/LiveContainer screen-off continuity, 60+ minute survival, system
controls or the resolution of the user's real request timeout. Those hardware
checks remain pending. Keep PR 22 draft and do not claim universal YouTube
availability or bypass any service sign-in/bot/access restriction.
