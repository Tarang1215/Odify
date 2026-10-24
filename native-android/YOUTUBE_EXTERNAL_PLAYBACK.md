# YouTube search and external playback

Search → YouTube shows up to 20 music-category video results, fetched using the
existing user-approved YouTube Data API connection. Each row shows its public
thumbnail, title and channel. Search waits for 900ms of idle typing and caches
20 queries in memory for 30 minutes. It does not scrape another app's account.

Tapping a result opens its exact video ID in an installed YouTube app. The
device's `app.morphe.android.youtube` is tried first, followed by known ReVanced,
YouTube and YouTube Music packages, then the Android URL handler. Odify pauses
its own Media3 session after a successful handoff to avoid overlapping audio.

“가사 영상 바로 재생” searches the typed artist/title plus `lyrics`, chooses the
first result not labelled live/concert/cover/karaoke/remix/sped/slowed, and opens
it externally. Missing Explore audio uses artist + title + lyrics too. Without
a connected API account, the same query opens in the installed YouTube app for
manual selection. This is a quick fallback, not a verified MASTER source; it
does not silently rewrite links or claim the top result is always correct.

The external app owns playback, background availability, queue and restrictions.
Odify does not embed ReVanced, extract its audio stream, read its storage/tokens,
or display the previous Drive mini-player as if it controlled YouTube. A unified
Odify queue/seek bar requires an independent supported playback integration and
has not been implemented by this intent handoff.

Public app-link search was verified on the USB device with Hype Boy. No private
account export, YouTube cookie reuse, source-gate bypass or provider patching is
used. Official references: [search API](https://developers.google.com/youtube/v3/docs/search/list),
[Android intents](https://developer.android.com/guide/components/intents-common).
