# YouTube search and internal playback

Search → YouTube shows up to 20 music-category video results, fetched using the
existing user-approved YouTube Data API connection. Each row shows its public
thumbnail, title and channel. Search waits for 900ms of idle typing and caches
20 queries in memory for 30 minutes. It does not scrape another app's account.

VersionCode 30 introduced an internal WebView/IFrame player. VersionCode 31
replaced its separate dialog with the existing Odify mini-player, full-screen
artwork player and queue. The selected public video title/channel and cached
thumbnail form a temporary song; they are not inserted into the Drive catalog
or declared verified artist/album metadata. Native controls call the official
IFrame API for play, pause and seek. The package identifier is the HTTPS
origin/Referer. No extracted stream URL or ReVanced account is used.

The service owns the WebView across Activity recreation; the renderer sits
behind the native artwork UI. A foreground mediaPlayback service, Android
MediaSession, media notification, audio focus and bounded playback wake lock
are implemented to support background playback. The service is not restarted
after process death or force-stop. Actual background/screen-off playback still
requires device verification; a foreground service alone does not prove that
the WebView/provider will keep producing audio.

Starting a YouTube song pauses the native Media3 player and builds a following
queue of up to 20 existing Drive songs from listening-based mixes, falling back
to available Drive songs. End-of-video/Next hands that queue to Media3; Repeat
restarts the selected video. Shuffle is carried into the Drive queue. There is
no prebuffer/crossfade or claim of gapless playback. An empty Drive catalog
leaves no automatic following queue. Explicitly playing a local/Drive song
stops the web service. The YouTube item itself is not an offline file.

The row's explicit external icon still opens its exact video ID in an installed
YouTube app. The device's `app.morphe.android.youtube` is tried first, followed
by known ReVanced, YouTube and YouTube Music packages and the Android URL handler.

“가사 영상 바로 재생” searches the typed artist/title plus `lyrics`, chooses the
first result not labelled live/concert/cover/karaoke/remix/sped/slowed or a
tutorial/lesson/instrumental, and opens it inside Odify. Missing Explore audio
uses artist + title + lyrics too. Without
a connected API account, the same query opens in the installed YouTube app for
manual selection. This is a quick fallback, not a verified MASTER source; it
does not silently rewrite links or claim the top result is always correct.

For explicit external handoff, the external app owns playback, background
availability, queue and restrictions. Odify does not extract ReVanced streams or
read its storage/tokens. Unrelated search results are not silently turned into
an autoplay queue. Embedding-disabled/unavailable videos report an error;
the full-screen menu offers explicit external handoff. Downloads and audio
correction remain separate from web playback.

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
client was replaced. From versionCode 30, successful API responses clear the
previous failure diagnostic and failed searches have an explicit retry button.

The diagnostic `OdifyExternalBrowserProbe` connected to the installed Morphe
app's exported Android MediaBrowser service. Its advertised actions included
PLAY_FROM_URI. The initial null-extras probe left the session STOPPED. Inspecting
the installed service showed that it reads the extras Bundle; a non-null empty
Bundle was then tested. Cold playback remained uninitialized. After the user
explicitly approved opening ReVanced once to start Hype Boy, the public URI
command changed playback to Oasis, and another background-state test changed it
back to Hype Boy. Both changes eventually brought Morphe's Activity forward.
This proves new-video playback works through that interface, but does not meet
the requirement to keep Odify foreground. The diagnostic now waits eight seconds
to avoid treating an already-playing old title as successful new-track playback.
No app patch, credential export, privileged grant or production hidden remote
engine was added.

The device currently uses `com.google.android.webview`; Brave is not installed.
Android WebView and browser-provided Custom Tabs are distinct. Installing Brave
does not make ordinary WebView inherit Brave features. A Custom Tab uses the
browser's own surface and is not an arbitrary embedded Odify player. A visible
WebView/IFrame player was implemented in version 30, then integrated behind
the native player surface in version 31. No Brave engine bundle or new browser
dependency was added.

## Glass and mini-player motion (versionCode 32)

The native glass records the actual underlying Compose page, crops it using
live coordinates, applies blur and an original AGSL edge-refraction lens on
Android 13+, then draws sharp foreground controls. Android 12 uses blur without
the lens. Mini-player and navigation retain the same artwork-derived glow.
The full-player lower surface uses the same glass modifier.

The expanded mini-player has a title-measured information lobe and a fixed
120dp transport lobe. The information lobe has a capped title width and marquee;
the heart sits beside that text. Transport controls are centered in their own
lobe, independently of weighted text. Expansion keeps the same cover/text/play
nodes mounted. A continuous contour pinches to zero before a gap opens, with
the reverse sequence on collapse. A short touch/release tension effect starts
immediately. While playing in the foreground, the left surface (compact surface
when collapsed) morphs subtly without moving its text or controls. Idle/background
states stop that loop; disabled system animations disable the continuous effect.

References are visual/implementation research, not bundled web dependencies:
[GlassiFy](https://github.com/Saviru/GlassiFy),
[native glass examples](https://github.com/topics/liquid-glass), and the user's
[surface-tension/liquid-morph reference](https://codepen.io/Margarita-the-solid/pen/NPRPBjd).
The Compose path and AGSL code here are original implementations.

## Validation boundaries

- Version 30: actual internal Hype Boy audio and advancing playback time were
  observed. The exposed video region was black on the real device too; this was
  not claimed fixed. Version 31 uses the user-requested native artwork surface.
- Version 31: permanently signed APK installed preserving app data. The lens
  shader compiled on the real phone (one device test). The background/handoff
  test stopped before playback because it incorrectly required preloaded Drive
  duration. DriveCatalog entries commonly have unknown duration until opened;
  version 32's test now selects an existing catalog item without that condition.
- Version 32: compilation and JVM regression validation are recorded with the
  change. New device tests cover contour continuity at the split and actual
  background playback followed by Drive handoff. Their execution and visual
  review are pending the user's next device-review window. Do not describe
  unexecuted tests as successful background playback.

References: [Android WebView versus Custom Tabs](https://developer.android.com/develop/ui/views/layout/webapps),
[WebView provider requirements](https://chromium.googlesource.com/chromium/src.git/+/master/android_webview/docs/webview-providers.md),
[YouTube IFrame control API](https://developers.google.com/youtube/iframe_api_reference).
