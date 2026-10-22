package com.odify.nativeapp

import org.junit.Assert.*
import org.junit.Test

class KoreanOriginalLyricsTest {
    private val song = OdifySong(
        id = "gdrive:sample", title = "밤편지", artist = "아이유",
        album = "", filePath = "", genre = "K-pop", durationMs = 202_000L
    )
    private val romanized = """
        [00:01.00]I bam geu nal bam pyeonji reul sseoyo
        [00:03.00]Neol saenggak hamyeon naega miso jisneun bam
        [00:05.00]I bam geu nal bam pyeonji reul sseoyo
    """.trimIndent()

    @Test fun flagsLatinOnlyLyricsForKoreanTrack() {
        assertTrue(KoreanOriginalLyrics.needsOriginal(song, romanized))
        assertFalse(KoreanOriginalLyrics.needsOriginal(
            song, "[00:01.00]오늘도 너를 생각해요 그 밤"))
        assertFalse(KoreanOriginalLyrics.needsOriginal(
            song.copy(title = "Hello", artist = "Example", genre = "Pop"), romanized))
    }

    @Test fun onlyAcceptsExactKoreanTitleArtistAndDuration() {
        val korean = "[00:01.00]이 밤 그날 그대에게 전하는 이 밤의 편지야\n" +
            "[00:04.00]그리웠던 마음을 담아 한 글자씩 적어요"
        val matching = LyricCandidate(
            1, "밤편지", "아이유", "", 201, korean, ""
        )
        assertEquals(matching,
            KoreanOriginalLyrics.verifiedOriginal(song, listOf(matching)))
        assertNull(KoreanOriginalLyrics.verifiedOriginal(
            song, listOf(matching.copy(durationSec = 260))))
        assertNull(KoreanOriginalLyrics.verifiedOriginal(
            song, listOf(matching.copy(artist = "다른 아티스트"))))
        assertNull(KoreanOriginalLyrics.verifiedOriginal(
            song, listOf(matching, matching.copy(id = 2))))
    }

    @Test fun actualOriginalIsNotReplaced() {
        val korean = "[00:01.00]이 밤을 그대에게 보내요 언젠가 우리 다시 만나"
        assertFalse(KoreanOriginalLyrics.needsOriginal(song, korean))
    }
}
