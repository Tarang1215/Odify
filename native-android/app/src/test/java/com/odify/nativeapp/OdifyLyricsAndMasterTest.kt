package com.odify.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OdifyLyricsAndMasterTest {
    @Test fun parseSynchronizedLrcAndOrdinaryText() {
        val parsed = LrcParser.parse("[ar:Vaundy]\n[00:03.14]First line\n[01:02.300]Second line")
        assertTrue(parsed.isTimed)
        assertEquals(3140L, parsed.lines[0].milliseconds)
        assertEquals(62300L, parsed.lines[1].milliseconds)
        assertEquals("First line", parsed.lines[0].text)
        assertFalse(LrcParser.parse("Plain lyrics\nAnother line").isTimed)
    }

    @Test fun duplicateChecksIdAndArtistButNotTitleOnly() {
        val row = MasterTrack(
            "ODF000003","2026","K-pop","CORTIS","REDRED","REDRED",
            "https://www.youtube.com/watch?v=5sOgJQ3N03Q",
            true,"완료","abc12345def","스튜디오 원곡"
        )
        val exact = OdifySong(id="gdrive:abc12345def",title="Unknown",
            artist="",album="",filePath="")
        assertEquals(MasterPresence.ON_DRIVE,
            MasterDuplicateDetector.status(row,listOf(exact)))
        val otherArtist = OdifySong(id="gdrive:differentId",title="REDRED",
            artist="Other Artist",album="",filePath="")
        assertEquals(MasterPresence.NOT_ON_DRIVE,
            MasterDuplicateDetector.status(row,listOf(otherArtist)))
        val matching = otherArtist.copy(artist="CORTIS")
        assertEquals(MasterPresence.POSSIBLE_DUPLICATE,
            MasterDuplicateDetector.status(row,listOf(matching)))
    }
}
