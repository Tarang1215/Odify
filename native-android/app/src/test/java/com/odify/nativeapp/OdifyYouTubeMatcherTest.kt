package com.odify.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Display text is insufficient to establish recording identity. */
class OdifyYouTubeMatcherTest {
    @Test
    fun matchesReviewedOwnedAudioInKoreanAndJapanese() {
        val korean = OdifySong("localfolder:k", "밤편지", "아이유", "", "")
        val japanese = OdifySong("localfolder:j", "Lemon", "米津玄師", "", "")
        val reviewed = listOf(
            MasterTrack("ODF000001", "2026", "", "아이유", "밤편지", "",
                "https://youtu.be/abcdefghijk", true, "", "", "studio"),
            MasterTrack("ODF000002", "2026", "", "米津玄師", "Lemon", "",
                "https://youtu.be/abcdefghijl", true, "", "", "studio")
        )
        assertEquals(
            korean,
            OdifyYouTubeMatcher.find(
                YoutubeVideo("abcdefghijk", "아이유 - 밤편지 (Official Audio)", "", ""),
                listOf(korean, japanese), reviewed
            )
        )
        assertEquals(
            japanese,
            OdifyYouTubeMatcher.find(
                YoutubeVideo("abcdefghijl", "米津玄師 - Lemon [Official Music Video]", "", ""),
                listOf(korean, japanese), reviewed
            )
        )
    }

    @Test
    fun refusesLiveCoversAndAmbiguousDuplicateAudio() {
        val original = OdifySong("localfolder:a", "밤편지", "아이유", "", "")
        val copy = original.copy(id = "gdrive:b")
        assertNull(OdifyYouTubeMatcher.find(
            YoutubeVideo("abcdefghijk", "아이유 - 밤편지 (Live)", "", ""),
            listOf(original), emptyList()
        ))
        assertNull(OdifyYouTubeMatcher.find(
            YoutubeVideo("abcdefghijk", "아이유 - 밤편지 (Cover)", "", ""),
            listOf(original), emptyList()
        ))
        assertNull(OdifyYouTubeMatcher.find(
            YoutubeVideo("abcdefghijk", "아이유 - 밤편지 (Official Audio)", "", ""),
            listOf(original, copy), emptyList()
        ))
    }
}
