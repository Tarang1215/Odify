# Native audio configuration and Drive library fix

Version 1.1.0 build 7 fixes an invalid AVAudioSession combination: playback
was configured with explicit allowAirPlay, which Apple only permits with
playAndRecord. Playback now uses default mode, longFormAudio and empty options,
activating before inserting the current item. Background audio and supported
AirPlay routing remain enabled without recording or microphone access.

System errors now show source and safe NSError domain/code chains rather than
only Unknown error. The diagnostic logger emits category-activation and error
codes; no track titles, file IDs, account identifiers, media URLs or tokens.

Drive can discover the same explicit Odify library roots used by Android,
index nested audio transactionally, guard pagination/folder loops and merge
fresh file binding while preserving unchanged embedded metadata. Custom named
folders remain available through manual browsing. Only Odify music roots are
recursively scanned; private tags do not become external discovery queries.

Source: 11207183cb1d7584953bc212365b2b6d7f13c8d0.
CI: https://github.com/Tarang1215/Odify/actions/runs/40990554034
Six packaging tests and 32 XCTest cases passed, with zero failures in 29.239
seconds. Coverage includes generated MP3/M4A playback, automatic transitions,
Drive discovery/pagination/merge, safe error codes and playback category options.
These are simulator checks, not proof of physical background playback.

Native arm64 iPhone/iPad Release, minimum OS 26.0, version 1.1.0 (7).
IPA 1,734,309 bytes; native executable 6,683,176 bytes. Independent executable,
licenses, OAuth callback, background-mode and no-extension checks passed.
Public download matched the independently downloaded artifact and checksum:
06db62d27b61f661016b5a7e8d3a583a4c6ceb4aa6f326bf70a9b1b5af2e4b18.

Release: https://github.com/Tarang1215/Odify/releases/tag/ios-native-1.1.0-b7-1120718
Direct IPA: https://github.com/Tarang1215/Odify/releases/download/ios-native-1.1.0-b7-1120718/Odify-iOS-1.1.0.ipa

The cloud YouTube probe still failed with a sign-in/bot confirmation response;
its failed JSON is included in the release. Actual guest-runtime playback and
long screen-off survival remain unverified. Do not infer these from CI success.

No device identifiers, model/version inventory, personal signing identifiers,
pairing records, USB session details or raw device logs are published here.

References:
- https://developer.apple.com/documentation/avfaudio/avaudiosession/categoryoptions-swift.struct/allowairplay
- https://developer.apple.com/documentation/avfoundation/supporting-airplay-in-your-app
