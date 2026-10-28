# Company device diagnostics

## Muted playback and command checks

Read [the latest continuity handoff](../../docs/COMPACT_WIDGET_CONTINUITY_20261027.md) for installed-versus-prepared versions. The user now authorizes actual playback only at media volume zero. The paused scroll harness below remains separate.

After Gradle unit tests export the exact generated YouTube pages, run `node --test tools/company-debug/youtube-command-state.test.mjs`. These seven VM tests use a fake player and no network/audio.

`OdifyMutedPlaybackTest` is a separate explicit real-playback instrumentation class; it must not be swept into the safe UI test run. It asserts volume zero and tests actual YouTube/system/widget controls, renderer recovery and single-song continuation. The safe `OdifyCompanyUiTest` never starts playback.

For observation without instrumentation keeping the app alive, first start Odify playback at volume zero, then return to the launcher and run:

```sh
python tools/company-debug/monitor-muted-playback.py --seconds 180
```

The monitor reads only Odify session metadata/process evidence, does not start playback or change volume, and stops if music volume is not zero. Its default output is ignored `.local/company-debug/background/<timestamp>/`; keep it private. An interruption at the end is a failed observation, even if under the recovery timeout. The monitor does not stop playback on completion; stop only Odify manually afterwards.

`--simulate-process-death` is an explicit destructive QA option for the **background Odify YouTube host only**. It checks package/PID identity, refuses a foreground app, terminates once, and observes sticky recovery. Do not use it during ordinary user listening or native-only playback. `--self-test` checks the session parser without touching a device. Physical observation has not yet been run for preview12.60.

## Paused-only scroll measurement

Read [the handoff](../../docs/HANDOFF_20261026_COMPANY_TO_HOME.md) first. These scripts preserve the company measurement sequence; they are **device-specific diagnostic tools**, not a general UI test runner. The portable path/cleanup changes were syntax-checked only at handoff, not rerun on a phone.

Requirements: Python 3.8+, adb on PATH or ANDROID_HOME/ANDROID_SDK_ROOT, exactly one authorized unlocked device, matching signed app + AndroidTest installed. The real library must contain a saved streaming presentation and the Home artist `리센느` with online results. Screen coordinates target the company's Galaxy S25 Ultra at **1440 × 3120**; inspect and adapt them for another resolution, font scale, layout or content before running. Never substitute coordinates that hit playback buttons. Labels are Korean. Country swipes use y=2100, below the independently scrolling genre row.

Run from any directory:

```sh
python tools/company-debug/paired-scroll.py preview12.54-home
```

Use the actual path to the script if outside the repo. Output defaults to `~/odify-scroll-measurements/<name>/`; override the parent with `ODIFY_BENCHMARK_OUTPUT`. Each phase saves raw gfxinfo and summary JSON. Do **not** commit personal data, raw artwork, credentials or library dumps.

Set `ODIFY_BENCHMARK_ARTIST` to the exact artist label currently visible in the Home row (default `리센느`; the 2026-10-27 run used `아이유`). The same target must be used on both sides of a comparison. `last-ui.xml` and `measurement-log.txt` help diagnose missing labels; these can contain private library information and must stay local.

The wrapper stops the existing app, runs only `OdifyCompanyScrollHarness`, waits for `cache/company-scroll-ready`, then drives whitelisted non-playback controls. It sets music volume to zero and temporarily keeps the plugged-in screen awake. In finally it writes `cache/company-scroll-stop`, waits for harness cleanup, and restores the original awake setting. Volume remains zero. Keep USB attached until it exits. If interrupted/disconnected, the harness has a 150-second deadline and restores its state in finally; reconnect and check the screen setting manually. A killed Android process cannot run finally, so avoid force-stopping during the harness.

Do not run `measure-scroll.py` by itself: it expects the wrapper's paused harness and cleanup. Do not run all instrumentation classes: some other classes test real playback. This harness uses a paused presentation and asserts no actual YouTube playback service starts; it does not call play, prepare or start audio decoding.

For manual reproduction: reset `adb shell dumpsys gfxinfo com.odify.music.nativepreview.v11.debug reset`, perform only one gesture group while the paused harness is ready, then collect `adb shell dumpsys gfxinfo com.odify.music.nativepreview.v11.debug framestats`. Signal stop through `adb shell run-as com.odify.music.nativepreview.v11.debug touch cache/company-scroll-stop`. Compare cold/warm runs separately. Maximum frame duration is not a constant FPS estimate. Perfetto/atrace and sampling can identify main-thread stalls; on .52 the decisive stack was HomeArtistPortrait → sameArtist → norm → ICU Regex Pattern.compileImpl.
