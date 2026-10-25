package com.odify.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OdifyStreamingPlaylistTest {
    @Test fun videoMetadataSurvivesRepositoryRecreationAndResolvesInMixedPlaylist() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val video=YoutubeVideo("T--6HBX2K4g","Hype Boy","NewJeans","")
        val repo=OdifyStreamingLibrary(context,"odify_streaming_test")
        try {
            repo.remember(video,180000)
            val song=OdifyStreamingLibrary(context,"odify_streaming_test").find("youtube:"+video.videoId)!!
            assertEquals(video.title,song.title);assertEquals(180000L,song.durationMs)
            assertFalse(song.isOfflineSaved)
            val marker=song.mediaItem()
            assertTrue(marker.localConfiguration!!.uri.toString().endsWith("youtube_queue_marker.wav"))
            assertEquals(video,repo.video(queueSong(marker)))
        } finally {context.getSharedPreferences("odify_streaming_test",0).edit().clear().commit()}
    }
}
