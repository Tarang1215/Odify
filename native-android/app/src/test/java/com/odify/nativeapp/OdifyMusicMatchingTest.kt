package com.odify.nativeapp

import org.junit.Assert.*
import org.junit.Test

class OdifyMusicMatchingTest {
    @Test fun metadataUpgradeOrdersRecentAndNeverPlayedVideosTogetherWithoutBoxingDifferentNumberTypes() {
        fun video(id:String)=YoutubeVideo(id,"Title","Artist","")
        val first=video("abcdefghijk");val recent=video("bcdefghijkl");val unknown=video("cdefghijklm")
        val ordered=OdifyMusicMatching.recent(listOf(unknown,recent,first),listOf("youtube:${first.videoId}"),
            mapOf("youtube:${recent.videoId}" to ListeningSignal(1,1234567890000L)))
        assertEquals(listOf(first,recent,unknown),ordered)
        assertEquals(listOf(unknown,recent),OdifyMusicMatching.recent(listOf(unknown,recent),emptyList(),emptyMap()))
    }
    private val messy=OdifyMusicIdentity("1","Messy","ROSÉ","F1 The Album","https://cover","1406242696","","",180000)
    @Test fun identifiesRecordingFromLyricsUploadWithoutUsingUploaderAsArtist() {
        val video=YoutubeVideo("abcdefghijk","ROSÉ - Messy (Lyrics)","Lemonade","")
        assertEquals(messy,OdifyMusicMatching.choose(video,listOf(messy,messy.copy(id="2",album="Deluxe"),messy.copy(id="3",artist="Kvn Rose"))))
        assertTrue(OdifyDiscovery.sameArtist("Rose","ROSÉ"))
    }
    @Test fun refusesCoversAlternateVersionsAndAmbiguousSongTitles() {
        for(title in listOf("ROSÉ Messy live","ROSÉ Messy cover","ROSÉ Messy (slowed)")) {
            assertTrue(OdifyMusicMatching.usesVideoArtwork(YoutubeVideo("abcdefghijk",title,"ROSÉ","")))
            assertNull(OdifyMusicMatching.choose(YoutubeVideo("abcdefghijk",title,"ROSÉ",""),listOf(messy)))
        }
        assertNull(OdifyMusicMatching.choose(YoutubeVideo("abcdefghijk","Messy","Unrelated uploader",""),listOf(messy)))
        assertNull(OdifyMusicMatching.choose(YoutubeVideo("abcdefghijk","ROSÉ Messy + Other","ROSÉ",""),listOf(messy,messy.copy(id="2",title="Other"))))
    }
    @Test fun localizedArtistAliasRequiresRepeatedTitlesAndMatchingArtistChannel() {
        val videos=listOf(YoutubeVideo("abcdefghijk","리센느(RESCENE) LOVE ATTACK","RESCENE",""),
            YoutubeVideo("bcdefghijkl","리센느 (RESCENE) Deja Vu","Music show",""))
        assertEquals("RESCENE",OdifyMusicMatching.artistAlias("리센느",videos))
        assertNull(OdifyMusicMatching.artistAlias("리센느",videos.take(1)))
        assertNull(OdifyMusicMatching.artistAlias("리센느",videos.map {it.copy(channel="Festival")}))
    }
    @Test fun artistPortraitParserRejectsAlbumAndUntrustedImages() {
        fun html(address:String)="<meta property=\"og:image\" content=\"$address\">"
        val address="https://is1-ssl.mzstatic.com/image/thumb/AMCArtistImages221/v4/portrait.png/1200x630cw.png"
        assertEquals(address,OdifyArtistPortrait.address(html(address)))
        val legacyPortrait=address.replace("AMCArtistImages221","Features211")
        assertEquals(legacyPortrait,OdifyArtistPortrait.address(html(legacyPortrait)))
        assertNull(OdifyArtistPortrait.address(html(address.replace("AMCArtistImages221","Music211"))))
        assertNull(OdifyArtistPortrait.address(html(address.replace("is1-ssl.mzstatic.com","untrusted.example"))))
    }
}
