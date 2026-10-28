package com.odify.nativeapp

import org.junit.Assert.*
import org.junit.Test

class OdifyDiscoveryTest {
    @Test fun artistIdentityDoesNotAcceptSubstringMatchesOrEmptyTags() {
        assertTrue(OdifyDiscovery.sameArtist("Ｖａｕｎｄｙ","vaundy"))
        assertFalse(OdifyDiscovery.sameArtist("Vaundy tribute band","Vaundy"))
        assertFalse(OdifyDiscovery.sameArtist("",""))
    }
    @Test fun discoveryExcludesOwnedRecordingsAndDeduplicatesAcrossCountries() {
        val owned=listOf(OdifySong("gdrive:one","Owned title","Artist","",""))
        fun track(id:String,title:String,artist:String)=ExploreTrack(id,title,artist,"",listOf("Pop"),1)
        val results=OdifyDiscovery.newTracks(listOf(track("1","Owned title","Artist"),track("2","New title","Artist"),
            track("3","NEW TITLE","Artist"),track("4","Owned title","Different artist")),owned)
        assertEquals(listOf("2","4"),results.map {it.id})
    }
    @Test fun moodGroupingRequiresGenreEvidenceInsteadOfAssigningUnknownSongs() {
        val focus=OdifyDiscovery.moods.first {it.key=="focus"}
        assertTrue(OdifyDiscovery.matches(focus,listOf("Classical")))
        assertFalse(OdifyDiscovery.matches(focus,listOf("K-Pop")))
        assertFalse(OdifyDiscovery.matches(focus,emptyList()))
        assertFalse(OdifyDiscovery.matches(focus,listOf("")))
    }
}
