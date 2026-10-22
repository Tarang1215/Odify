# Odify Native Preview — standalone Android MVP

**Status:** first independent Kotlin/Jetpack Compose + Media3 implementation.
**Not based on Mornye or SpotiFLAC.** Existing Odify mainline is untouched.

## Visual decisions

- White home/search/library, black-and-white Odify logo/icon and centered brand splash.
- 126dp x 167dp Featured cards, 83dp recently-played square cards.
- Lightweight translucent charcoal mini player, white bottom navigation.
- Hide bottom navigation when playing and scrolling content downward, or 4.7s after idle; show when scrolling upward.
- Immersive dark now-playing with album art and waveform seek; drag downward to dismiss.
- Low-glow/NO outer glow play button, thin outline only.
- No heavy backdrop blur in feed cells.

## Playback

- Android Media3 ExoPlayer and MediaSessionService, foreground media playback.
- Back/next, pause/play, shuffle/repeat, lockscreen notification and background audio session.
- Android DocumentsUI `OpenMultipleDocuments(audio/*)` for Google Drive test files; no Google OAuth setup or storage permission prompt.
- Import up to 20 files per batch, max 100MB each; selected cloud file is COPIED to private local audio store before playback.
- SHA256 duplicate detection, ID3/MP4 metadata & embedded artwork extraction when present.
- Local search / all songs / albums / artists / liked songs, simple recently played.
- Source catalog and original Drive files never deleted when an import is removed.

## Build

Run on a machine with Android SDK API35, JDK17, Gradle8.9:

    gradle -p native-android :app:assembleDebug

Or open GitHub Actions workflow `Odify Independent Native Android Preview` for a signed debug APK artifact.
Android package **com.odify.music.nativepreview.debug** is independent of existing Odify package and keeps the original app installed.

## Galaxy S25 Ultra acceptance test

1. Install the debug APK artifact.
2. Open Profile -> Google Drive에서 음악 선택 (or Home shortcut).
3. Use system file picker to choose 2-5 user-owned MP3 files from the Google Drive provider.
4. Play tracks, seek via waveform, press next/back, return Home and lock screen.
5. Scroll downward: navigation bar hides but mini player stays. Scroll up: navigation returns.
6. Stay idle for >4.7s during playback: navigation hides.
7. Open full player, swipe downward: returns to previous screen.
8. Verify cover art, liking, local search, playback after restarting app.

## Known limits

- This is a **preview**, not verified on a physical S25 Ultra.
- Drive import copies audio locally: no direct streaming or Drive folder sync yet.
- Featured playlists are local sample mixes, not ML-ranked recommendations.
- Sync lyrics, cross-device bookmarks, playlist editing and drive remote range streaming not yet integrated.
- Android APK build status must be verified in CI before sharing it as installable.
- Do not delete your existing Odify app during preview evaluation.
