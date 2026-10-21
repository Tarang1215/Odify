# Odify Android streaming diagnostics

The former `playableCdnProbe: true` only proved that the extractor returned a Googlevideo URL and an HTTP GET could read at least one byte from the first 1 KiB range. It **did not** establish that MediaPlayer could read metadata, demux, or decode this audio stream.

The Discover diagnosis now also includes:

- `audioCodec` and `audioContainer`, sanitized yt-dlp format tags.
- `androidPrepare: READY` if a disposable Android MediaPlayer can prepare the same signed CDN source with extractor-supplied headers.
- `androidPrepare: MEDIA_ERROR` and numeric `androidErrorWhat`/`androidErrorExtra` when Android raises an asynchronous media error.
- `androidPrepare: TIMEOUT`, `EXCEPTION` (class name only), or `SESSION_UNAVAILABLE` for other conditions.

The media probe **never starts audio**, does not replace the user's music player, and cannot prove uninterrupted playback or seeking. It uses a 7-second timeout and always releases its own MediaPlayer. A successful READY result with playback still failing points to an issue after prepare (resume, range/seek, player lifecycle or delivery path) and should be followed by sanitized Android playback logs.

Never log or share signed media URLs, per-format HTTP headers, cookies, query parameters, account tokens, or raw extractor stderr.
