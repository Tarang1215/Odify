package com.odify.nativeapp

import org.junit.Assert.*
import org.junit.Test

class OdifyArtistTracksTest {
    private fun music(id:String,title:String)=OdifyMusicIdentity(id,title,"ROSÉ","Album","https://art.example/$id","42","","",180000).track()
    @Test fun ownedDuplicatesChooseAvailableLocalRecordingWithoutChangingTheLibrary() {
        val cloud=OdifySong("gdrive:one","Messy","ROSÉ","Album","",sourceUri="https://drive.example/one")
        val local=cloud.copy(id="localfolder:two",title="Ｍｅｓｓｙ",sourceUri="content://media/two",artworkPath="/cover.jpg")
        val songs=listOf(cloud,local,cloud.copy(id="third",title="Messy (Live)"))
        val visible=OdifyArtistTracks.owned(songs,"ROSÉ")
        assertEquals(listOf(local,songs[2]),visible)
        assertEquals(3,songs.size)
    }
    @Test fun albumSingleAndRemasterRowsCollapseWhileDifferentVersionsRemain() {
        val original=music("1","Messy")
        val rows=listOf(music("2","Messy (2024 Remastered)"),original,music("3","Messy"),
            music("4","Messy (Live)"),music("5","Messy (Instrumental)"),music("6","Messy (Japanese Version)"))
        assertEquals(listOf("1","4","5","6"),OdifyArtistTracks.additional(rows,emptyList(),"ROSÉ").map {it.id})
    }
    @Test fun uploadBadgesArtistPrefixAndAccentsDoNotRepeatOwnedOfficialTrack() {
        val owned=listOf(OdifySong("local:one","ROSE - Messy [Official Audio]","ROSÉ","Album",""))
        assertEquals(listOf("2"),OdifyArtistTracks.additional(listOf(music("1","Messy"),music("2","Messy (Live)")),owned,"ROSÉ").map {it.id})
        assertEquals(OdifyArtistTracks.titleKey("Song","IU"),OdifyArtistTracks.titleKey("IU – Song (Official MV)","아이유",listOf("IU")))
    }
    @Test fun unverifiedLiveTakesAndDifferentSongNamesAreNeverFuzzyMerged() {
        val live=ExploreTrack("youtube:a","Messy (Live)","Performer","",emptyList(),1)
        val rows=listOf(live,live.copy(id="youtube:b"),music("3","Messy Tonight"),music("4","Messy"))
        assertEquals(4,OdifyArtistTracks.additional(rows,emptyList(),"ROSÉ").size)
        assertNotEquals(OdifyArtistTracks.titleKey("Song (Acoustic)","IU"),OdifyArtistTracks.titleKey("Song","IU"))
    }
}
