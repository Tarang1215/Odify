package com.odify.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class OdifyLibraryContinuityTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun mixedImportedPlaylistRetainsEveryPlayableReferenceAfterRecreation() {
        val name="test_mixed_"+java.util.UUID.randomUUID()
        val streaming=OdifyStreamingLibrary(context,name+"_streams")
        val playlists=OdifyPlaylists(context,name,name+"_streams")
        val video=YoutubeVideo("abcdefghijk","Not owned yet","Artist","")
        val held=OdifySong("gdrive:fixture","Owned song","Artist","Album","")
        try {
            streaming.rememberAll(listOf(video))
            val list=playlists.saveMix("Imported",listOf(held.id,"youtube:"+video.videoId))
            val recreated=OdifyPlaylists(context,name,name+"_streams")
            val tracks=recreated.resolved(recreated.all().single(),listOf(held))
            assertEquals(list.songIds,tracks.map {it.id})
            assertTrue(tracks.last().isYoutubeStream)
        } finally {context.getSharedPreferences(name,0).edit().clear().commit();context.getSharedPreferences(name+"_streams",0).edit().clear().commit()}
    }
    @Test fun retryIsExplicitAndDoesNotResetAlreadySavedOrAgeRestrictedRequests() {
        val fileName="test_requests_"+java.util.UUID.randomUUID()+".json"
        val requests=OdifyStreamingRequests(context,fileName)
        try {
            val videos=listOf("aaaaaaaaaaa","bbbbbbbbbbb","ccccccccccc").map {YoutubeVideo(it,"Title","Artist","")}
            videos.forEach {requests.enqueue(it,0)}
            requests.update(videos[0].videoId){it.put("status","HELD_PHONE").put("error","old engine")}
            requests.update(videos[1].videoId){it.put("status","SAVED_PHONE").put("sha256","verified")}
            requests.update(videos[2].videoId){it.put("status","SKIPPED_AGE")}
            requests.enqueue(videos[0],123000)
            assertEquals("HELD_PHONE",requests.read().getJSONObject(0).getString("status"))
            requests.retryFailed()
            val restored=OdifyStreamingRequests(context,fileName).read()
            assertEquals("QUEUED_PHONE",restored.getJSONObject(0).getString("status"))
            assertEquals(123000,restored.getJSONObject(0).getLong("duration_ms"))
            assertEquals("verified",restored.getJSONObject(1).getString("sha256"))
            assertEquals("SKIPPED_AGE",restored.getJSONObject(2).getString("status"))
        } finally {File(context.filesDir,fileName).delete()}
    }
    @Test fun replacementIsReversibleAndNeverChangesTheOriginalTrack() {
        val name="test_override_"+java.util.UUID.randomUUID()
        val song=OdifySong("gdrive:fixture","Original","Artist","Album","original.mp3",contentMd5="original-md5")
        try {
            val overrides=OdifyAudioOverrides(context,name,name+"_streams")
            overrides.choose(song,YoutubeVideo("abcdefghijk","Chosen audio","Channel",""))
            assertEquals("youtube:abcdefghijk",OdifyAudioOverrides(context,name,name+"_streams").resolve(song).id)
            assertEquals("original.mp3",song.filePath);assertEquals("original-md5",song.contentMd5)
            overrides.clear(song.id);assertEquals(song,overrides.resolve(song))
        } finally {context.getSharedPreferences(name,0).edit().clear().commit();context.getSharedPreferences(name+"_streams",0).edit().clear().commit()}
    }
    @Test fun chartDiffQueuesOnlyUnownedNewEntriesAndRetainsResolution() {
        val name="test_chart_"+java.util.UUID.randomUUID()+".json"
        try {
            val requests=OdifyChartRequests(context,name)
            val owned=OdifySong("gdrive:fixture","Held","Artist","","",chartId="1")
            val chart=ExploreChart("kr","now",0,listOf(ExploreTrack("1","Held","Artist","",emptyList(),1),ExploreTrack("2","New","Artist","",emptyList(),2)))
            val index=OdifyChartMatching.buildIndex(listOf(owned))
            requests.observe(chart,index);requests.resolved("2","abcdefghijk");requests.observe(chart,index)
            val rows=OdifyChartRequests(context,name).read()
            assertEquals(1,rows.length());assertEquals("2",rows.getJSONObject(0).getString("chart_id"))
            assertEquals("QUEUED_PHONE",rows.getJSONObject(0).getString("status"))
        } finally {File(context.filesDir,name).delete();File(context.filesDir,name+".bak").delete()}
    }
    @Test fun chapterOrWebpageErrorsAreNotMisclassifiedAsAgeRestrictions() {
        assertFalse(OdifyPhoneDownloadPolicy.ageRestricted("Unable to download webpage: HTTP Error 403"))
        assertTrue(OdifyPhoneDownloadPolicy.gated("Unable to download webpage: HTTP Error 403"))
        assertTrue(OdifyPhoneDownloadPolicy.ageRestricted("Sign in to confirm your age"))
    }
}
