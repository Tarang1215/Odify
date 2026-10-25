# Latest device and archive verification — 2026-10-25

Native version 42 (`1.0.0-preview12.30`) is installed with the permanent package
and signing certificate. Its APK SHA-256 is
`3eacbc6deee5aef791748a89ef8e4e5c4169fca372569b0a49a4a31959519fe2`.
The APK, signed test APK and hash-pinned update installer are archived under
`D:\Odify\Android\Odify-v42`.

Twelve real-device instrumentation cases passed: four liquid-surface/optical
cases, three streaming-playlist/download-runtime cases, and five existing
playback/storage cases. CI compilation/JVM validation passed. The V4.6 Python
suite passed 58 cases, including cooperative pause/resume without rewriting
completed evidence. These checks do not establish that the visual result matches
the user's taste. An attempted controlled visual capture was interrupted by
changing phone state; the final version-42 capture remains pending an unlocked,
stable phone. No successful final visual review is claimed.

After installation, all 1,716 baseline Drive track IDs remain (1,723 currently),
both baseline playlists are unchanged, and both original MASTER SHA-256 values
match their earlier protected values. Databases/WAL and playlist snapshots were
copied privately for this check; no user files were replaced.

The Windows manager is available at `D:\Odify\음악 다운로드 관리.lnk`, backed
by `D:\Odify\Manage-Odify-Downloads.ps1`. A real pause held the archive receipt
unchanged, and Resume continued the same worker PID. The archive was later left
paused through its control file. At that snapshot, album audio had 423 new
verified downloads and 2,202 verified reuses (2,625 ready out of 11,513 queued
recordings). These overlap the chart archive and must not be added as a unique
combined-library total. Chart audio remains 2,558 ready of 2,759. Read live JSON
receipts for later counts. Further Drive uploading remains deferred.

Phone collection is intentionally an open issue: a real 20-second listen created
a durable request, but its download entered `HELD_PHONE`. The bundled engine's
version command ran, which does not prove successful downloading. The user
explicitly deferred this repair while prioritizing liquid glass. Earlier YouTube
background playback followed by Drive handoff passed in an isolated version-38
test; an initial autoplay timeout remains recorded. That network test was not
rerun as part of version 42's twelve cases.

Implementation/reference scope is documented in
`native-android/YOUTUBE_EXTERNAL_PLAYBACK.md`; archive scope and controls are in
`docs/V46_MONTHLY_HISTORY.md`. The Liquid Glass Studio MIT notice is bundled in
the APK. Interactive browser use was disabled, so reference source review is
distinguished from an interactive demo review.
