# Native rendering validation — version 46

Version 46 (`1.0.0-preview12.34`, source `6ce9dd7`) was measured on the
connected SM-S938N at its existing 1440 × 3120 resolution and 1.15 font scale.
The display reported 120.00001 Hz during both completed transition runs.
The app requests up to 120 Hz for its own window; it does not change the user's
global refresh-rate, resolution or power settings.

## Changes

- Settled liquid outlines use exact native rounded rectangles. Only the changing
  neck samples the distance field, with precomputed constants and primitive arrays.
- The real page is still sampled through a blurred body and sharp refracted rim.
  Captures are reused until source content, location or capture size changes.
  Scroll/gesture/lifecycle observers keep moving content current without an
  unconditional, permanently running page-redraw loop.
- Shader pixels outside the surface and transparent sharp-rim centers return
  early. Joined capsule normals are analytic; changing necks retain their distance
  field normals. Unused compact-state pulse values no longer rebuild controls.
- Playing pulses update render layers rather than recomposing song text and
  buttons. Clip, shadow, border and optical draw share one cached pulse contour;
  a device regression case verifies that the stable shape still changes visibly.
- Removed the background decoding of eight full songs for a PCM waveform cache.
  Now Playing already uses the user's chosen decorative waveform patterns, so no
  displayed feature consumed that analysis.

## Completed same-device measurements

`OdifyGlassFrameTest` uses the production mini-player and nav over a fixed
background, warms up for 2.5 seconds, then runs twelve toggles one second apart.
The baseline is the original version-42 renderer, with only test visibility
changes. Both versions used the same signed test implementation. Screen recording
was kept separate from performance measurement.

| Transition metric | Original renderer | Version 46 |
| --- | ---: | ---: |
| FrameMetrics total duration, median | 28.015 ms | 13.389 ms |
| FrameMetrics total duration, p95 | 48.972 ms | 32.266 ms |
| Samples over 16.67 ms | 77.90% | 31.02% |
| Frames emitted in 1.8-second compact idle phase | 215 | 0 |

Median total duration fell 52.2%, and p95 fell 34.1% in these completed runs.
Zero idle frames means no render was needed; it does **not** mean a rendered
frame took zero milliseconds. FrameMetrics TOTAL_DURATION includes pipeline work
and is not a presented-FPS counter. Do not infer missed-vsync percentages or
claim fixed 120 fps from its duration thresholds or raw frame counts.

The separate real Home/Explore test performs three equal 600 ms swipes in each
direction after opening each page. The following compares version 44 with the
final version 46 while the existing Drive track "Lemon Tang" is playing and the
mini-player is present. The final run explicitly checks PLAYING/no-error session
state and the track's UI before measuring. These are Android `gfxinfo` results
over each approximately 6.5-second script interval, including brief pauses:

| Playing scroll metric | Version 44 | Version 46 |
| --- | ---: | ---: |
| Home median / p95 / p99 | 13 / 25 / 31 ms | 12 / 17 / 21 ms |
| Home Android-reported janky frames | 34/753 (4.52%) | 2/786 (0.25%) |
| Explore median / p95 / p99 | 13 / 26 / 32 ms | 12 / 17 / 23 ms |
| Explore Android-reported janky frames | 42/739 (5.68%) | 8/775 (1.03%) |

These are single before/after device runs, not a statistically controlled
benchmark. Network/image-cache state, Android scheduling and thermal conditions
can affect them. Earlier scripted runs without a controlled playing precondition
gave Home/Explore medians of 9/10 ms on the original renderer and 7/9 ms on version
44; they are not substituted for the more demanding playing comparison above.
An intermediate version-43 Explore run had worse tail duration while the phone
reported thermal throttling. The first draw-only pulse change in version 45
improved playing scroll but regressed transition duration (median 15.4 ms);
sharing its contour recovered that to 13.4 ms. Version 44's synthetic transition
was faster still (11.8 ms), so version 46 is not claimed fastest in every metric.
Long frames remain, especially on Explore;
this is an improvement, not proof of uninterrupted 120 fps throughout the app.

## Validation and evidence

The version-46 frame test and all thirteen device regression cases passed:
five contour/pulse/seam/shader cases, three streaming-playlist/runtime cases, and five
playback/storage cases. CI compile, JVM validation and signed APK assembly passed
in [run 40200152492](https://github.com/Tarang1215/Odify/actions/runs/40200152492).

Private local evidence is under `.local/v46/`: `glass43_baseline.json`,
`glass43_final46.json`, the corresponding completed instrumentation logs,
`scroll43_final44_playing_*.txt`, `scroll43_final46_playing_*.txt`,
`playing46_state.json`, and `player46_device.log`.
Historical filenames retain the original benchmark's version-43 prefix; the
`final46` suffix identifies the final APK. These are not inferred from recordings.

The fixed-signature update, test APK and hash-pinned installer are archived under
`D:\Odify\Android\Odify-v46`. No app uninstall or data reset was used. All 1,716
baseline catalog IDs remain (1,723 currently), both baseline playlists remain
unchanged (three playlists currently exist), and both protected MASTER hashes
match their prior values. Version-44 optical/transition recordings remain labeled
as version 44; the final version's manual playing-scroll test is recorded in the
metrics and playback precondition evidence above.
