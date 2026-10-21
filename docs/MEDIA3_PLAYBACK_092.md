# Odify 0.9.2 — experimental Media3/OkHttp online music playback

## Background
Galaxy S25 Ultra (Android 16) reported `MEDIA_ERROR_UNKNOWN extra=-1005` with Android MediaPlayer although yt-dlp using `android_vr` retrieved a signed Googlevideo audio URL and an authenticated first-byte range returned data. This CDN probe does not prove the Android decoder can prepare, seek or play.

## Architecture
Odify's existing stable YouTube video identity, playlist database, yt-dlp source extraction, UI and audio_service queue remain unchanged. The patched audioplayers Android `WrappedPlayer` delegates only `odify-cdn://audio/<token>` and narrowly validated `127.0.0.1:/stream/<token>` relay URLs to a **separate Media3 ExoPlayer (1.9.4)**. Its HTTP source is **OkHttpDataSource** with allowlisted yt-dlp headers, redirect handling and native byte ranges. Codec/container hints are supplied by a short-lived in-memory registry without persisting signed media URLs or credentials.

All other ordinary web and local paths, MP3, FLAC, SAF, file-descriptor and USB DAC/DSD routes retain the original MediaPlayer or existing native USB backend. Flutter audioplayers and the audio_service media session keep their normal external transport interface, focus policy and background playback handling. ExoPlayer never requests audio focus independently.

The implementation intentionally does **not** introduce an unbounded disk cache for expiring signed URLs or copy InnerTune/ReVanced source. Media3/OkHttp are acquired through their own library dependencies.

## Device acceptance
Install the debug variant `com.odify.music.debug` alongside the existing app; do **not** uninstall Odify or erase app data to bypass a signing mismatch. On a Galaxy S25 Ultra Android 16, verify:
1. Search and play `A2O LITE NEON`, including audible first second.
2. Seek to 30s and backward, pause/resume, next song and repeat.
3. Background notification/lock screen transport.
4. A preexisting local MP3/FLAC and any supported USB DAC.
5. If it fails, collect only sanitized `OdifyMedia3` error-code/cause-class log lines and media diagnostics, never signed Googlevideo URLs, cookies, headers or video IDs.

CI success and a DEX-contents check confirm compilation/packaging, **not** audible playback on a physical Samsung device.

## 0.9.3 HTTP 502 investigation
The 0.9.2 Galaxy A/B test reached Media3 but reported `EXO_ERROR_CODE_IO_BAD_HTTP_STATUS / HTTP_502`. The prior Dart relay deliberately mapped any upstream non-200/206/416 status or network failure to 502, masking whether the origin was the CDN or the local relay.

Odify 0.9.3 retries a newly extracted native Media3/OkHttp CDN handle first after a failed network open. The relay remains only for extractors lacking a usable handle. Sanitary transport diagnostics now distinguish `CDN_HTTP_502` from `RELAY_HTTP_502_UPSTREAM_403`, `RELAY_HTTP_502_NETWORK` or `RELAY_HTTP_502_TIMEOUT`, without logging signed URLs, query strings, cookies or headers. No repeated automatic loop is added; generation checks remain in place. This does not by itself prove the remote CDN accepts Media3's range requests.
