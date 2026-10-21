# Source provenance / distribution notice review — Odify 0.9.5

This change is **independently authored** from general published designs.
The implementation does **not** copy Musify, SimpMusic, ViMusic, RiMusic or
InnerTune source code. Architectural inspiration alone is different from
incorporating a project's licensed code.

**This does NOT waive existing distribution obligations**. GPL compliance
cannot be bypassed by renaming, isolating, or lightly rewriting GPL-derived
code; any included GPL component or derivative work must be examined under
its own license. All third-party files, SDKs, Flutter plugins, native binaries,
codecs and multimedia tools in the shipped APK require their own license
inventory and appropriate notices. No automated license compliance review
has been completed in this change.

- Frozen Mornye upstream: MIT source; keep the required copyright/license.
- yt-dlp Python extractor, youtubedl_android wrapper, embedded FFmpeg / other
  runtime binaries: **audit each actual packaged component and build flags**
  before public distribution; do not assume the wrapper and engine share a
  license or that FFmpeg is always built as LGPL-only.
- Media3 and OkHttp: review redistributed package notices.
- App's original Odify streaming policy: this change is authored inside
  Odify and uses no source copied from the four GPL applications studied.

Evidence and actual binary inventory should accompany any release review.
This is developer documentation, not a legal opinion.
