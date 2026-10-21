package com.zarz.mornye

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.luan.audioplayers.source.OdifyPlaybackProtocol

class OdifyPlaybackProtocolTest {
    @Test fun clientProfilesPrioritizeMusicThenFallBack() {
        val initial = OdifyPlaybackProtocol.orderedClients(null, emptyMap(), 100000L)
        assertEquals("visionos", initial.first())
        assertEquals(4, initial.size)
        val failed = mapOf("visionos" to 99500L)
        val next = OdifyPlaybackProtocol.orderedClients(null, failed, 100000L)
        assertEquals("android_vr", next.first())
        assertFalse(next.contains("visionos"))
        val retry = OdifyPlaybackProtocol.orderedClients(null, failed, 160000L)
        assertEquals("visionos", retry.first())
    }

    @Test fun canonicalHeadersPreserveClientUserAgentWithoutLeaks() {
        val headers = OdifyPlaybackProtocol.canonicalHeaders(
            mapOf("User-Agent" to "AndroidVR/42", "Referer" to "https://music.youtube.com/",
                "Cookie" to "private", "X-Bad" to "secret", "Origin" to "https://music.youtube.com")
        )
        assertEquals("AndroidVR/42", headers["user-agent"])
        assertEquals("https://music.youtube.com/", headers["referer"])
        assertFalse(headers.containsKey("cookie"))
        assertFalse(headers.containsKey("x-bad"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingUserAgentNeverUsesGenericFallback() {
        OdifyPlaybackProtocol.canonicalHeaders(mapOf("Referer" to "https://music.youtube.com"))
    }

    @Test fun expiresAtSignedUrlDeadlineOrShorter() {
        val now = 1_700_000_000_000L
        assertEquals(now + 15L * 60L * 1000L,
            OdifyPlaybackProtocol.expiryDeadlineMs("https://r.googlevideo.com/stream", now))
        val exp = (now / 1000L) + 180
        assertEquals(now + 90_000L,
            OdifyPlaybackProtocol.expiryDeadlineMs(
                "https://r.googlevideo.com/stream?expire=$exp&sig=private", now))
        val stale = (now / 1000L) + 20
        assertTrue(OdifyPlaybackProtocol.expiryDeadlineMs(
            "https://r.googlevideo.com/stream?expire=$stale", now) < now)
    }
}
