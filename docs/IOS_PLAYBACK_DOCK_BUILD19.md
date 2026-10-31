# Build 19: owned playback, native presentation and a compact iPad dock

File references now determine routing before source labels. A local file wins
before a Drive reference, then Drive wins before catalog YouTube. This applies
to direct selections, enqueueing, restored selections and successors. A missing
owned reference fails with a repair message instead of searching YouTube. Valid
owned candidates can match untagged artist/title filenames without title-only
fuzzy replacement. Matching still rejects other accounts, differing durations
and ambiguous editions.

Native intent remains authoritative through late web pause snapshots. The
embedded player requests playback in its ready callback and retains explicit
pause and provider autoplay-block behavior. Background/foreground transitions
no longer turn an expected web suspension into a connection timeout. The public
Audio Session API declares music playback. Native AVQueuePlayer explicitly uses
continuesIfPossible. None of these changes demonstrate continuous YouTube web
playback on a locked physical iPad. No visibility spoofing, private entitlement,
silent keepalive or access-denial bypass is added.

The former overlay was constrained to the root safe-area geometry and scaled
its horizontal and vertical dimensions independently. A fullScreenCover with
the system zoom navigation transition now owns the full window and tracks the
selected dock/inspector source. Its background extends to all edges. Hardware
commands are hosted in the presented player and yield to nested dialogs.

The iPad dock is centered at up to 1,000pt. Track information and transport use
native GlassEffectContainer identities and merge at rest; while playing, their
32pt separation exceeds the blending distance. Reduced Motion removes the
optional spacing animation. No unbounded empty center or manual glass bitmap
is introduced. The note remains and the sidebar footer is removed.

References:
- Apple shared-source zoom: https://developer.apple.com/videos/play/wwdc2024/10145/
- Apple glass composition: https://developer.apple.com/documentation/swiftui/applying-liquid-glass-to-custom-views
- Spotify Design on continuity and motion: https://medium.com/spotify-design/designing-a-new-foundation-spotify-for-desktop-58305f16ce72
- Public web audio session: https://www.w3.org/TR/audio-session/

Validation covers stale file labels, mixed local/Drive references, missing owned
references, late paused web states, explicit pause, full window/inspector bounds,
dock split/join, real silent native playback while backgrounded, and keyboard
handoff across full-screen presentation. Physical YouTube lock-screen playback
remains an explicit unverified acceptance criterion.

The Android comparison also exposed a missing ownership link: APK reads the
user's Odify 음악 마스터 Google Sheet and resolves ODF filenames/exact Drive
IDs into canonical names. iOS now discovers the same named sheet through the
existing read-only Drive account, validates its first-sheet CSV headers, and
applies only exact unambiguous ID/code mappings. No private document ID is
embedded, no file is modified, and no MASTER data is sent outside Google.
Lookup is paginated/bounded, CSV is limited to 9MB, and cache is account-scoped.
Other accounts, ODF prefix collisions, conflicting records and explicit user
corrections are rejected. Original filenames survive tag reads, which cannot
overwrite MASTER names. Startup repairs connected libraries; explicit music
reconnect also refreshes the table and reports the result.

Master parser/export/mapping regressions cover quoting, Unicode, account
isolation, read-only requests, ambiguity and owned-source preference afterward.
Drive API: https://developers.google.com/workspace/drive/api/reference/rest/v3/files/export
