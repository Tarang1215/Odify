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

## Verified project binding and device results (2026-10-25)

The native package `com.odify.music.nativepreview.v11.debug` and permanent
signing SHA-1 `65:6D:46:5E:5C:72:9B:DF:21:A4:02:6C:76:4D:FB:63:6F:18:99:E5`
are registered as Android client 2 in project `sage-passkey-512404-q5`
(number `745737586961`, console name My First Project). The separate
`odify-512404` project (number `816869113913`) contains the desktop rclone client;
enabling its YouTube API did not enable the native consumer's API.

After explicit user approval, YouTube Data API v3 was enabled in the native
project. The console showed enabled status. The existing installed build then
returned Oasis Don't Look Back In Anger results, including OasisVEVO, lyrics
videos and Oasis - Topic. No API key, package, signing certificate or OAuth
client was replaced. Error diagnostic preferences retain the last failure;
their old value alone does not establish that the latest request failed.

The diagnostic `OdifyExternalBrowserProbe` connected to the installed Morphe
app's exported Android MediaBrowser service. Its advertised actions included
PLAY_FROM_URI, but sending the verified Hype Boy HTTPS watch URL left the
session STOPPED while Odify remained RESUMED. A later run retained an older
Oasis title instead of switching to the requested Hype Boy track. Successful
connection/test completion therefore does not mean playback integration works.
This result covers the tested public interface and URI, not every possible
provider integration. No production remote-control feature is claimed.

The device currently uses `com.google.android.webview`; Brave is not installed.
Android WebView and browser-provided Custom Tabs are distinct. Installing Brave
does not make ordinary WebView inherit Brave features. A Custom Tab uses the
browser's own surface and is not an arbitrary embedded Odify player. A visible
WebView/IFrame player is a separate implementation option; no hidden WebView,
Brave engine bundle or new browser dependency has been added.

References: [Android WebView versus Custom Tabs](https://developer.android.com/develop/ui/views/layout/webapps),
[WebView provider requirements](https://chromium.googlesource.com/chromium/src.git/+/master/android_webview/docs/webview-providers.md),
[YouTube IFrame control API](https://developers.google.com/youtube/iframe_api_reference).
