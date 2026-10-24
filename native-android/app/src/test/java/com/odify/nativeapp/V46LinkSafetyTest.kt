package com.odify.nativeapp

import org.junit.Assert.*
import org.junit.Test

class V46LinkSafetyTest {
    private val video = YoutubeVideo("ABCDEFGHI_0", "Artist - Song (Official Audio)", "Artist", "playlist")
    private val song = OdifySong("gdrive:existing-drive-id", "Song", "Artist", "Album", "")
    private val master = MasterTrack("ODF000001", "2026", "Pop", "Artist", "Song", "Album",
        "https://www.youtube.com/watch?v=ABCDEFGHI_0", true, "완료", "existing-drive-id", "studio")

    @Test fun unreviewedDisplayTitleCannotLinkOwnedAudio() {
        assertNull(OdifyYouTubeMatcher.find(video, listOf(song), emptyList()))
        assertNull(OdifyYouTubeMatcher.find(video, listOf(song), listOf(master.copy(verified=false))))
    }

    @Test fun reviewedVideoAndExactDriveIdLinkWithoutNameGuessing() {
        assertEquals(song, OdifyYouTubeMatcher.find(video, listOf(song), listOf(master)))
        assertNull(OdifyYouTubeMatcher.find(video, listOf(song), listOf(master,
            master.copy(trackId="ODF000002", artist="Other"))))
    }

    @Test fun videoUrlsRequireHttpsExactHostPathAndSingleId() {
        assertEquals("ABCDEFGHI_0", OdifyYouTubeMatcher.videoId("https://youtu.be/ABCDEFGHI_0"))
        assertEquals("ABCDEFGHI_0", OdifyYouTubeMatcher.videoId(master.youtubeUrl))
        for (url in listOf("http://youtu.be/ABCDEFGHI_0", "https://evil.test/watch?v=ABCDEFGHI_0",
            "https://youtube.com/playlist?v=ABCDEFGHI_0", "https://youtu.be/ABCDEFGHI_0/extra",
            master.youtubeUrl + "&v=ZYXWVUTSR_0", "https://user@youtube.com/watch?v=ABCDEFGHI_0")) {
            assertNull(url, OdifyYouTubeMatcher.videoId(url))
        }
    }
}
