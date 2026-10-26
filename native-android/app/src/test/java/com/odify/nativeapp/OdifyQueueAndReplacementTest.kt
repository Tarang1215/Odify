package com.odify.nativeapp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.*
import org.junit.Test

class OdifyQueueAndReplacementTest {
    private fun song(id:String)=OdifySong(id,id,"Artist","Album","/audio/$id.mp3")

    @Test fun rapidMovesUseCurrentQueueAndDistinguishDuplicateSongs() {
        val duplicate=song("same")
        val state=MutableStateFlow(YoutubeSessionState(video=YoutubeVideo("abcdefghijk","Now","Artist",""),
            following=listOf(duplicate,song("b"),duplicate,song("c")),
            followingKeys=listOf("same-first","b","same-second","c"),entryKey="current",shuffle=true))
        state.update {it.moved("c","same-first")}
        state.update {it.moved("same-second","same-first")}
        assertEquals(listOf("c","same-second","same-first","b"),state.value.followingKeys)
        assertEquals(listOf("c","same","same","b"),state.value.following.map {it.id})
        assertFalse(state.value.shuffle)
        assertEquals("current",state.value.entryKey)
        assertEquals("abcdefghijk",state.value.video?.videoId)
    }

    @Test fun staleOrCurrentEntryCannotMoveOrReplaceTheUpcomingQueue() {
        val value=YoutubeSessionState(following=listOf(song("a"),song("b")),followingKeys=listOf("a","b"),entryKey="current")
        assertSame(value,value.moved("current","b"))
        assertSame(value,value.moved("removed","b"))
        assertSame(value,value.moved("a","missing"))
        assertEquals(listOf("a","b"),value.followingKeys)
    }

    @Test fun replacementKeepsTheOriginalCoverMetadataLyricsAndPlaylistIdentity() {
        val original=song("gdrive:original").copy(title="원래 곡",artist="원래 가수",album="원래 앨범",
            artworkPath="/covers/album.jpg",lyrics="원래 가사",chartId="chart",durationMs=240000)
        val video=song("youtube:abcdefghijk").copy(title="Video upload",artworkPath="/covers/thumbnail.jpg",
            filePath="/audio/youtube_queue_marker.wav",durationMs=238000)
        val replaced=odifyReplacementPresentation(original,video,"abcdefghijk")
        assertEquals(original.id,replaced.id)
        assertEquals(original.title,replaced.title)
        assertEquals(original.artist,replaced.artist)
        assertEquals(original.album,replaced.album)
        assertEquals(original.artworkPath,replaced.artworkPath)
        assertEquals(original.lyrics,replaced.lyrics)
        assertEquals(original.chartId,replaced.chartId)
        assertEquals(video.filePath,replaced.filePath)
        assertEquals("abcdefghijk",replaced.playbackVideoId)
        assertTrue(replaced.isYoutubeStream)
        assertFalse(odifyReplacementPresentation(original,video.copy(filePath="/downloads/audio.m4a"),"abcdefghijk").isYoutubeStream)
    }
}
