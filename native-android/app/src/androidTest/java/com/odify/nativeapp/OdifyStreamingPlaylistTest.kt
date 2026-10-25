package com.odify.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OdifyStreamingPlaylistTest {
    @Test fun youtubeSongCanBeSavedResolvedAndRemovedWithoutChangingExistingPlaylists() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val playlists=OdifyPlaylists(context)
        val before=playlists.all()
        val prefs=context.getSharedPreferences("odify_streaming_v1",0)
        val id="T--6HBX2K4g";val old=prefs.getString(id,null)
        var created:String?=null
        try {
            OdifyStreamingLibrary(context).remember(YoutubeVideo(id,"Hype Boy","NewJeans",""),180000)
            val list=playlists.create("검수용 · "+java.util.UUID.randomUUID().toString());created=list.id
            assertTrue(playlists.addSong(list.id,"youtube:$id"))
            assertFalse(playlists.addSong(list.id,"youtube:$id"))
            val restored=OdifyPlaylists(context)
            val saved=restored.all().first {it.id==list.id}
            val resolved=restored.resolved(saved,emptyList())
            assertEquals(listOf("youtube:$id"),resolved.map {it.id})
            assertEquals("Hype Boy",resolved.single().title)
        } finally {
            created?.let {playlists.delete(it)}
            prefs.edit().apply {if(old==null)remove(id) else putString(id,old)}.commit()
        }
        assertEquals(before,playlists.all())
    }
    @Test fun bundledDownloadEngineRunsOnThisPhone() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        com.yausername.youtubedl_android.YoutubeDL.init(context)
        com.yausername.ffmpeg.FFmpeg.init(context)
        val request=com.yausername.youtubedl_android.YoutubeDLRequest(emptyList<String>()).addOption("--version")
        val output=com.yausername.youtubedl_android.YoutubeDL.execute(request,redirectErrorStream=true).out
        assertTrue(output,Regex("[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}").containsMatchIn(output))
        java.io.File(context.filesDir,"phone_download_engine_test.txt").writeText(output)
    }
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
