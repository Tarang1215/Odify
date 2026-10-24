package com.odify.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Regression for YouTube presentation suffixes observed on actual playlists. */
class OdifyYouTubeMatcherTest {
    @Test
    fun matchesExactOwnedOfficialAudioInKoreanAndJapanese() {
        val korean = OdifySong("localfolder:k", "밤편지", "아이유", "", "")
        val japanese = OdifySong("localfolder:j", "Lemon", "米津玄師", "", "")
        assertEquals(
            korean,
            OdifyYouTubeMatcher.find(
                YoutubeVideo("abcdefghijk", "아이유 - 밤편지 (Official Audio)", "", ""),
                listOf(korean, japanese), emptyList()
            )
        )
        assertEquals(
            japanese,
            OdifyYouTubeMatcher.find(
                YoutubeVideo("abcdefghijl", "米津玄師 - Lemon [Official Music Video]", "", ""),
                listOf(korean, japanese), emptyList()
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
