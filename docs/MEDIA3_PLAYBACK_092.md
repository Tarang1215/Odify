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

## 0.9.4 403 fix: bounded byte-range parity with the successful CDN probe
Android 16 reported `EXO_ERROR_CODE_IO_BAD_HTTP_STATUS / CDN_HTTP_403` while the extractor's `validateAudioRange` succeeds. The extractor uses `HttpURLConnection` with `Range: bytes=0-1023` and `Accept-Encoding: identity`, while Media3's original OkHttpDataSource initially requested an unbounded stream and can negotiate differently. This difference is now addressed with `OdifyBoundedRangeDataSource`, a Media3 `DataSource` that uses `DefaultHttpDataSource` (also `HttpURLConnection`) to issue the exact bounded first byte-range and continuous 256 KiB subsequent ranges, preserving ExoPlayer's contiguous-read abstraction, seek offset, and yt-dlp's allowed headers. It verifies Content-Range bounds, detects truncated chunks, and never logs signed addresses. HTTP 416 is only treated as EOF when the returned complete resource length proves EOF. External CDN HTTP 403 may still occur due to authentication/token restrictions; device playback, seeking and expiry must be validated before claiming success.

## 0.9.5 — independent client-bound HTTP + extractor fallback policy + TTL-aware ephemeral cache
Research context: Musify, SimpMusic, ViMusic and RiMusic published architectures were inspected, but **no GPL source or methods were pasted** into this project. This Odify-specific policy is an original implementation.

1. **Matching request identity**: `OdifyPlaybackProtocol.canonicalHeaders` accepts only a narrowly allowlisted User-Agent, Referer, Origin, Accept and Accept-Language from the current yt-dlp extraction, rejects missing User-Agent (rather than silently substituting a generic browser UA), and uses the resulting same immutable header map for two CDN preflight requests and in-process Media3 handoff. The selected client identifier is attached to the session, yielding sanitized errors such as `CDN_HTTP_403_CLIENT_visionos`.
2. **Music-oriented client selection and failover**: the independent `OdifyPlaybackProtocol` prioritizes `visionos`, then `android_vr`, `android`, and `web_embedded`, while honoring the last successful client and time-limited per-client failure suppression. Extractors continue to be powered by yt-dlp; a separate non-yt-dlp extractor has **not** been introduced. The separation of client-selection policy and transport is intended to allow a future independently designed extractor backend.
3. **Revalidation and cache safety**: sequential byte-range CDN GETs 0-1023 and 1024-2047 are checked with the **exact playback headers**. Reject clients that cannot fetch the follow-up seek segment, rather than promoting a first-byte-only success. URL session cache deadline is min(15 minutes, signed `expire` parameter minus 90 seconds). On explicit playback failure, evict both the cached extraction and its native registry handle before re-resolving. No shared disk cache persists protected URLs or credentials.

This approach tests a plausible HTTP 403 cause but can still fail if YouTube restricts access for other reasons. Android build and tests are not a substitute for a Samsung device audio/seek/background test. All preexisting third-party licenses must still be obeyed.

### Extractor module boundary (0.9.5)
The original `OdifyAudioExtractor` interface and `OdifyYtDlpExtractor` implementation live in an independent Kotlin source file. Native playback, client/HTTP policy, format selection and UI no longer own the yt-dlp process invocation directly. This first refactor still ships only the existing pinned yt-dlp backend with client-profile fallbacks; **it does not imply a separately functioning InnerTube implementation**. A second wholly original engine may be attached after direct CDN behavior is validated on Galaxy. Do not copy code from GPL apps to implement it.
