package com.odify.nativeapp

import org.junit.Assert.*
import org.junit.Test

class OdifyYoutubeQuickPlayTest {
    @Test fun usesLyricsAndFirstNonLiveResultWithoutOfficialRequirement() {
        assertEquals("NewJeans Hype Boy lyrics", OdifyYoutubeQuickPlay.query("NewJeans", "Hype Boy"))
        val live=YoutubeVideo("abcdefghijk", "Artist Song Live Concert", "Artist", "")
        val cover=live.copy(videoId="12345678901", title="Song cover")
        val lyric=live.copy(videoId="lmnopqrstuv", title="Song Lyrics", channel="Fan")
        assertEquals(lyric, OdifyYoutubeQuickPlay.first(listOf(live,cover,lyric)))
        assertNull(OdifyYoutubeQuickPlay.first(listOf(live,cover)))
    }
}
