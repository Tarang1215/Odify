# Build 18: Android home baseline, fluid presentation and keyboard

Android OdifyPersonalHome, OdifyRecommendations and OdifyPalette are the concrete
reference. The original launcher path and dot are rendered as a SwiftUI Shape;
the waveform is no longer used as the app logo. Neutral surfaces replace the
generic mint/purple home. Artwork-backed daily hero, recent moments, artists,
personal mixes, mood browsing and beyond-library discoveries follow the APK's
section hierarchy. The finite home retains its small section views instead of
recreating nested lazy carousels on vertical reversal.

The old Made for you cards were static collection/favorites/chart links. Local
60-day listening signals and likes now produce explainable daily, repeat, liked,
unheard, rediscovery and offline mixes. Actual observed playing time must reach
30 seconds; seek positions, paused time and sampling gaps do not count. Each
playback occurrence is recorded at most once. The app retains at most 1,000
events locally. Likes are reconciled by recording identity across catalog/owned
IDs. Daily artist contributions are capped at three, and daily discovery order
is stable. Existing APK history is not imported; the iOS model starts from its
own likes/history. No private history or recommendation profile is uploaded.

The mini player now floats above scrolling content. Shared scroll-content bottom
margins keep the last item clear. Native Clear Liquid Glass, a low-opacity album
tint and a thin specular border preserve the underlying content. The inspector
fills its column without the detached rounded-card/trailing black gap. Launch
bounds are captured for the mini player, inspector cover and individual queue
rows. An animatable frame expansion plus matched artwork connects that source
to the full player instead of always sliding up from the screen edge.

Artist rows use a native virtualized List with unique catalog/recording IDs and
fixed minimum row geometry. Unranked numbers are removed. The play control is a
46pt circle with contrasting dark glyph. Preview-only long artist fixtures make
scroll-reversal regression reproducible without live catalog requests.

A UIKit responder supplies discoverable hardware key commands for Space,
left/right seek, Shift seeking, Command track navigation, up/down app volume,
Escape, search and section navigation. It yields while text input or a presented
dialog is active, and preserves Control-Option accessibility chords. The YouTube
player's documented volume API receives the same app-volume changes. XCUITest
uses hardware key events for seek/pause/resume and confirms spaces survive text
entry. Its finite silent WAV is generated solely for opt-in preview/testing;
normal playback never uses it or an audio keepalive.

The build 17 lyrics/source/autoplay/US-chart work remains included. Targeted unit
regressions and device Release compilation are complemented by UI tests for
collapse, drag, overlays, inspector presentation, scroll clearance, artist rows
and keyboard behavior, with retained screenshots for review. Real device input,
service autoplay and prolonged screen-off playback remain physical checks.
