package com.odify.nativeapp

import org.junit.Assert.*
import org.junit.Test

class OdifyYoutubeWebPageTest {
    @Test fun generatesIdentifiedVisiblePlayerWithoutEmbeddingUntrustedMetadata() {
        val page=OdifyYoutubeWebPage.html("cmpRLQZkTb8","https://com.odify.music.nativepreview.v11.debug")
        assertTrue(page.contains("videoId:'cmpRLQZkTb8'"))
        assertTrue(page.contains("origin:'https://com.odify.music.nativepreview.v11.debug'"))
        assertTrue(page.contains("controls:1"))
        assertTrue(page.contains("odifySnapshot"))
        assertFalse(page.contains("display:none"))
    }
    @Test fun rejectsScriptInjectionInIdAndOrigin() {
        for((id,origin) in listOf("bad');alert(1)" to "https://com.odify.music", "cmpRLQZkTb8" to "https://evil.example/'")) {
            assertThrows(IllegalArgumentException::class.java) {OdifyYoutubeWebPage.html(id,origin)}
        }
    }
    @Test fun formatsFiniteNonNegativePlaybackTimes() {
        assertEquals("4:48",OdifyYoutubeWebPage.time(288.9f))
        assertEquals("0:00",OdifyYoutubeWebPage.time(-1f))
        assertEquals("0:00",OdifyYoutubeWebPage.time(Float.NaN))
    }
}
