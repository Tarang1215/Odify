# Latest device and archive verification — 2026-10-25

Native version 46 (`1.0.0-preview12.34`) is installed with the permanent package
and signing certificate. Its APK SHA-256 is
`d995d9ebb6870c3ab7c6297cede310f7545efbc94f8dea1bfcb1c4d3161031c5`.
The APK, signed test APK and hash-pinned update installer are archived under
`D:\Odify\Android\Odify-v46`.

Fourteen real-device instrumentation cases passed: a production-glass frame
benchmark, five liquid-surface/pulse/optical cases, three streaming-playlist/download-runtime
cases, and five existing playback/storage cases. CI compilation/JVM validation passed. The V4.6 Python
suite passed 58 cases, including cooperative pause/resume without rewriting
completed evidence. These checks do not establish that the visual result matches
the user's taste. Version-44 screenshots and a separate real-device recording
show the compact/expanded player, aligned controls and live artwork/text behind
the glass while scrolling. Version 46 additionally passed a real playing-scroll
touch check with a confirmed Drive track. Earlier recordings remain labeled with
their actual build. Same-device transition median total duration fell from 28.0
to 13.4 ms. Playing-scroll janky frames fell from 4.52% to 0.25% on Home and from
5.68% to 1.03% on Explore, comparing version 44 with 46 in single scripted runs.
Long frames remain; fixed 120 fps is not claimed. Method, tails and limits are in
`docs/V46_RENDERING_PERFORMANCE.md`.

After installation, all 1,716 baseline Drive track IDs remain (1,723 currently),
both baseline playlists are unchanged (three playlists currently exist), and both original MASTER SHA-256 values
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
rerun as part of version 46's fourteen cases.

Implementation/reference scope is documented in
`native-android/YOUTUBE_EXTERNAL_PLAYBACK.md`; archive scope and controls are in
`docs/V46_MONTHLY_HISTORY.md`. The Liquid Glass Studio MIT notice is bundled in
the APK. Interactive browser use was disabled, so reference source review is
distinguished from an interactive demo review.
