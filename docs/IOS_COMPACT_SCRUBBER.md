# Compact Now Playing scrubber

The full-screen player previously used the standard SwiftUI slider, whose
large system thumb dominated the progress rail. Version 1.1.0 (11) uses a
compact, Apple Music-inspired capsule rail without a separate thumb.

- Resting rail: 4pt; touching/dragging: 8pt, with a 0.18-second smooth response.
- The invisible interaction region stays 44pt high across the full rail.
- Elapsed and remaining labels use caption text with stable-width digits.
- Dragging previews the selected time locally. A completed gesture commits
  one seek, rather than issuing playback/network seeks on every movement.
- Gesture cancellation resets the preview. Changing the queue occurrence
  resets the scrubber view so a previous song's drag does not remain visible.
- VoiceOver exposes an adjustable playback position with 5-second steps.
- Unknown/invalid duration disables scrubbing. Position is clamped to the
  available duration. Reduced motion and right-to-left layout are respected.

The existing native glass playback controls and queue preparation fixes remain
included. No network, account or playback-engine changes are part of this UI
update. Simulator screenshots use the app's generated preview library, with no
private library content.

## Build validation

[CI 41005021725](https://github.com/Tarang1215/Odify/actions/runs/41005021725)
completed successfully for source `8b257d24de0fc86eb89e329ea1061885a7f811ec`.
All 41 existing XCTest cases passed with zero failures in 26.826 seconds of
test time, and the six packaging checks passed. Inspection of the generated
iPad portrait player screenshots confirmed the slim rail, absent oversized
thumb and readable elapsed/remaining labels. Both captured player fixtures
were portrait; landscape screenshot coverage is not claimed.

The [LiveContainer IPA](https://github.com/Tarang1215/Odify/releases/tag/ios-native-1.1.0-b11-8b257d2)
was independently checked as a native arm64 device Release, version 1.1.0 (11),
for iPad/iPhone with minimum OS 26, background audio, OAuth callback and licenses.
The unauthenticated public download matched the separately verified CI artifact.
IPA: 1,778,037 bytes; SHA256:
`d1bd34c311cd996b37a93dd1719abfe9bb042668af8946c2a7d556dbf95cfa91`.

Passing the existing playback tests and screenshot inspection is distinct from
physical touch/VoiceOver testing; physical iPad/LiveContainer gesture checks
remain separate. The separate live cloud YouTube probe still failed a
sign-in/bot confirmation response; its failed JSON is attached to the release.
This UI change does not establish network availability or reduced buffering.

Design references:
- [Apple slider guidance](https://developer.apple.com/design/human-interface-guidelines/sliders)
- [Music player controls on iPad](https://support.apple.com/en-sa/guide/ipad/ipad9a4ba1e8/ipados)
