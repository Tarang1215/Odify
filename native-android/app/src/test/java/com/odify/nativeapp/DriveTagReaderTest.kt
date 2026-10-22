package com.odify.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class DriveTagReaderTest {
    private fun bigEnd(n: Int): ByteArray = byteArrayOf(
        (n ushr 24).toByte(), (n ushr 16).toByte(),
        (n ushr 8).toByte(), n.toByte()
    )
    private fun id3Frame(id: String, value: String): ByteArray {
        val data = byteArrayOf(3) + value.toByteArray(StandardCharsets.UTF_8)
        return id.toByteArray(StandardCharsets.US_ASCII) +
            bigEnd(data.size) + byteArrayOf(0,0) + data
    }
    @Test fun id3TagsTakePrecedenceOverOpaqueFileCodes() {
        val frames = id3Frame("TIT2", "Dream Song") +
            id3Frame("TPE1", "Example Artist") +
            id3Frame("TALB", "The Album") +
            id3Frame("TCON", "K-pop")
        val n = frames.size
        val header = "ID3".toByteArray(StandardCharsets.US_ASCII) +
            byteArrayOf(3,0,0,(n ushr 21 and 127).toByte(),
                (n ushr 14 and 127).toByte(),(n ushr 7 and 127).toByte(),
                (n and 127).toByte())
        val result = DriveTagReader.parse(header + frames, "mp3")
        assertEquals("Dream Song", result.title)
        assertEquals("Example Artist", result.artist)
        assertEquals("The Album", result.album)
        assertEquals("K-pop", result.genre)
    }

    private fun atom(name: ByteArray, data: ByteArray): ByteArray =
        bigEnd(8+data.size)+name+data
    @Test fun m4aTailIlstIsRecognized() {
        fun entry(name: String, value: String): ByteArray {
            val data = atom("data".toByteArray(StandardCharsets.US_ASCII),
                bigEnd(1)+bigEnd(0)+value.toByteArray(StandardCharsets.UTF_8))
            return atom(name.toByteArray(StandardCharsets.ISO_8859_1), data)
        }
        val ilst = atom("ilst".toByteArray(StandardCharsets.US_ASCII),
            entry("©nam", "Suddenly")+entry("©ART","I.O.I")+entry("©alb","Loop"))
        val result = DriveTagReader.parse(ByteArray(50) + ilst, "m4a")
        assertEquals("Suddenly", result.title)
        assertEquals("I.O.I", result.artist)
        assertEquals("Loop", result.album)
    }

    @Test fun fallbackRemovesOdifyTrackId() {
        assertEquals("Drowning" to "WOODZ",
            DriveTagReader.fallbackFromName("ODF000011 - WOODZ - Drowning.m4a"))
        assertEquals("ODF000004" to "",
            DriveTagReader.fallbackFromName("ODF000004.mp3"))
        assertEquals("", DriveTagReader.recognizableGenre("Odify_Auto_Upload"))
    }

    @Test fun noTagIsNotMistakenForMetadata() {
        val result = DriveTagReader.parse(ByteArray(1024), "mp3")
        assertTrue(!result.hasAnyTag)
        assertEquals(null, result.coverBytes)
    }

    @Test fun utf16ApicJpegWithExtraNulIsRecovered() {
        val jpeg = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(),
            0x00, 0x10, 0x4a, 0x46, 0x49, 0x46
        )
        val description = "Cover".toByteArray(StandardCharsets.UTF_16LE)
        val payload = byteArrayOf(1) +
            "image/jpeg".toByteArray(StandardCharsets.US_ASCII) +
            byteArrayOf(0,3,0xff.toByte(),0xfe.toByte()) +
            description + byteArrayOf(0,0,0) + jpeg
        val frame = "APIC".toByteArray(StandardCharsets.US_ASCII) +
            bigEnd(payload.size) + byteArrayOf(0,0) + payload
        val n = frame.size
        val header = "ID3".toByteArray(StandardCharsets.US_ASCII) +
            byteArrayOf(3,0,0,(n ushr 21 and 127).toByte(),
                (n ushr 14 and 127).toByte(),(n ushr 7 and 127).toByte(),
                (n and 127).toByte())
        val art = DriveTagReader.parse(header + frame, "mp3").coverBytes
        assertNotNull(art)
        assertTrue(art!!.contentEquals(jpeg))
    }

    @Test fun nativeKoreanAndJapaneseDisplayNamesTakePriority() {
        assertEquals("퇴사할게여 (Narr. 기안84)",
            OdifyTitlePolicy.title("I’m gonna TOESA (Narr. KIAN84)", "SOYEON"))
        assertEquals("またねがあれば",
            OdifyTitlePolicy.title("또 만나자가 있다면(またねがあれば)", "柏木凛子"))
        assertEquals("柏木凛子", OdifyTitlePolicy.artist("가시와기 린코(柏木凛子)"))
        assertEquals("REDRED", OdifyTitlePolicy.title("REDRED", "CORTIS"))
    }
}
