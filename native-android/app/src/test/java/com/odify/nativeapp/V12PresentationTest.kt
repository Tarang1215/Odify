package com.odify.nativeapp

import org.junit.Assert.*
import org.junit.Test

class V12PresentationTest {
    @Test fun byteIdenticalUnknownArtistMirrorsCollapseButDifferentRecordingsRemain() {
        val first=OdifySong("gdrive:a","Hype Boy","","","",contentMd5="a".repeat(32))
        val local=first.copy(id="localfolder:b",sourceUri="content://com.android.externalstorage.documents/file/b")
        assertEquals(listOf(local),OdifySongPresentation.visible(listOf(first,local)))
        assertEquals(2,OdifySongPresentation.visible(listOf(first,local.copy(contentMd5="b".repeat(32)))).size)
    }
    private val song = OdifySong("gdrive:one", "밤편지", "아이유", "앨범", "",
        sourceUri = "https://www.googleapis.com/drive/v3/files/one?alt=media")

    @Test fun duplicatesPreferOwnedOfflineCopyWithoutMutatingSources() {
        val offline = song.copy(id = "offline:two", sourceUri = null, filePath = "/audio.m4a")
        val input = listOf(song, offline)
        assertEquals(listOf(offline), OdifySongPresentation.visible(input))
        assertEquals(input, OdifySongPresentation.visible(input, showDuplicates = true))
        assertEquals(2, input.size)
    }

    @Test fun sameTitleByDifferentArtistsAndUnknownArtistsRemainDistinct() {
        val another = song.copy(id = "gdrive:two", artist = "다른 가수")
        assertEquals(2, OdifySongPresentation.visible(listOf(song, another)).size)
        assertEquals(2, OdifySongPresentation.visible(listOf(
            song.copy(artist = ""), another.copy(artist = ""))).size)
    }

    @Test fun nativeNamesAndUnicodeNormalizationSurvivePresentation() {
        assertEquals("밤편지", OdifyTitlePolicy.title("밤편지", "아이유"))
        assertEquals("米津玄師", OdifyTitlePolicy.artist("米津玄師"))
        assertEquals(OdifyYouTubeMatcher.norm("Ｌｅｍｏｎ"), OdifyYouTubeMatcher.norm("Lemon"))
        assertEquals("日本語の歌", OdifyYouTubeMatcher.norm("日本語 の 歌"))
    }
}
