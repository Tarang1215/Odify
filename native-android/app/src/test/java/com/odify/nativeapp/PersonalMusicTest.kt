package com.odify.nativeapp

import org.junit.Assert.*
import org.junit.Test

class PersonalMusicTest {
    @Test fun twelveSeekArtPatternsAreDistinctAndNeverFormSolidBlocks() {
        val patterns=(0 until OdifyWaveformPatterns.count).map(OdifyWaveformPatterns::samples)
        assertEquals(12,patterns.distinct().size)
        patterns.forEach { bars ->
            assertEquals(96,bars.size)
            assertTrue(bars.all {it in .025f.. .98f})
            assertTrue(bars.max()-bars.min() > .8f)
            assertTrue(bars.count {it<.25f}>15)
            assertTrue(bars.first()<.1f && bars.last()<.1f)
            val mean = bars.average()
            val variance = bars.sumOf { (it-mean)*(it-mean) }
            val neighbors = bars.zipWithNext().sumOf { (a,b) -> (a-mean)*(b-mean) }
            assertTrue("The whole waveform must read as one coherent silhouette", neighbors/variance > .75)
        }
    }
    private fun song(id:String,artist:String="Artist",genre:String="Pop") =
        OdifySong(id,"Song $id",artist,"Album","",genre=genre)

    @Test fun navigationSeparatesDiscoveryFromPersonalCollection() {
        assertEquals(listOf(OdifyTab.Home,OdifyTab.Explore,OdifyTab.Search,OdifyTab.Library),odifyPrimaryTabs)
        assertFalse(odifyPrimaryTabs.contains(OdifyTab.Profile))
    }

    @Test fun noListeningHistoryDoesNotInventPersonalization() {
        val songs=listOf(song("gdrive:one"),song("gdrive:two"))
        val mixes=OdifyRecommendations.mixes(songs,emptySet(),emptyMap(),1000)
        assertEquals(listOf("start"),mixes.map {it.key})
        assertTrue(mixes.flatMap {it.songs}.all {it in songs})
        assertTrue(OdifyRecommendations.mixes(emptyList(),emptySet(),emptyMap()).isEmpty())
    }

    @Test fun listeningAndLikesRecommendOwnedRelatedSongsWithArtistDiversity() {
        val songs=(1..8).map {song("gdrive:$it")}+song("gdrive:other","Another")+song("gdrive:far","Unrelated","Classical")
        val mixes=OdifyRecommendations.mixes(songs,setOf("gdrive:1"),mapOf("gdrive:2" to ListeningSignal(7,1000)),1000)
        val daily=mixes.single {it.key=="daily"}.songs
        assertTrue(daily.count {it.artist=="Artist"}<=3)
        assertTrue(daily.any {it.artist=="Another"})
        assertFalse(daily.any {it.artist=="Unrelated"})
        assertEquals("gdrive:2",mixes.single {it.key=="repeat"}.songs.first().id)
    }

    @Test fun providerLinksAndDriveCacheAreNotOfflineDownloads() {
        assertFalse(song("gdrive:one").copy(sourceUri="https://www.googleapis.com/drive/v3/files/one?alt=media").isOfflineSaved)
        assertFalse(song("drive-provider").copy(sourceUri="content://com.google.android.apps.docs.storage/document/one").isOfflineSaved)
        assertTrue(song("localfolder:one").copy(sourceUri="content://com.android.externalstorage.documents/tree/primary/document/one").isOfflineSaved)
        assertTrue(song("local:one").copy(filePath="/app/owned.mp3").isOfflineSaved)
        assertFalse(song("local:empty").isOfflineSaved)
    }
}
