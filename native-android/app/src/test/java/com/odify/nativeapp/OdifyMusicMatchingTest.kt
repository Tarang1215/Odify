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
    @Test fun lyricsUploadLinksToVerifiedRecordingAndKeepsProducerCredit() {
        val video=YoutubeVideo("lNNPWLC8sTo","임재현 - 사랑에 연습이 있었다면 (Prod. 2soo) // 가사","Lyrics uploader","")
        val verified=messy.copy(id="1684467058",title="If there was practice in love",artist="Jaehyun Lim",artistId="1352299660")
        val cover=verified.copy(id="2",artist="Another artist")
        val instrumental=verified.copy(id="3",title="If there was practice in love (Instrumental)")
        assertEquals(verified,OdifyMusicMatching.choose(video,listOf(cover,instrumental,verified)))
        val expected=OdifyMusicMatching.Credits("사랑에 연습이 있었다면","임재현 (Prod. 2soo)")
        assertEquals(expected,OdifyMusicMatching.credits(video))
        assertEquals(expected,OdifyMusicMatching.credits(video,verified.title,verified.artist))
        assertEquals(expected,OdifyMusicMatching.credits(video.copy(title="사랑에 연습이 있었다면 (Prod. 2soo)",channel="Lim Jae Hyun - Topic")))
    }
    @Test fun localizedKisekiTitleMatchesOnlyTheSameArtistAndRecording() {
        val video=YoutubeVideo("L483dymSxB0","커다란 세계에서 너를 만난 것: GReeeeN - 키세키(キセキ, Kiseki) [가사/발음/한글 자막/해석]","Uploader","")
        val recording=messy.copy(title="キセキ",artist="GReeeeN")
        assertEquals("GReeeeN キセキ",OdifyMusicMatching.query(video))
        assertEquals(recording,OdifyMusicMatching.choose(video,listOf(recording.copy(artist="グリーンボーイズ"),recording)))
        assertNull(OdifyMusicMatching.choose(video.copy(title=video.title+" (Live)"),listOf(recording)))
    }
    @Test fun homeAndSearchShareAliasesAndTrustedCatalogArtistIds() {
        fun song(id:String,name:String,artistId:String="")=OdifySong(id,id,name,"","",catalogArtistId=artistId)
        val songs=listOf(song("1","제니"),song("2","Gennie"),song("3","G-Dragon"),song("4","지드래곤"),
            song("5","Yuuri"),song("6","優里"),song("7","Unknown local spelling","42"),song("8","別表記","42"),
            song("9","別表記","99"))
        val groups=OdifyDiscovery.artistGroups(songs)
        assertEquals(listOf(listOf("1","2"),listOf("3","4"),listOf("5","6"),listOf("7","8"),listOf("9")),
            groups.map {(_,members)->members.map {it.id}})
        assertTrue(OdifySearchMatching.matches(songs[0],"JENNIE",OdifySearchScope.Music))
        assertTrue(OdifySearchMatching.matches(songs[5],"Yuuri",OdifySearchScope.Music))
        val tracks=songs.take(3).map {ExploreTrack(it.id,it.title,it.artist,"",emptyList(),0)}
        assertEquals(listOf("3","1","2"),OdifyDiscovery.prioritize(tracks,listOf(OdifyPopularArtist("지드래곤",1,""))).map {it.id})
    }
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
