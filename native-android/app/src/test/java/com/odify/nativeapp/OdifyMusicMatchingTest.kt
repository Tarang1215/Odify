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
    @Test fun heavenResolvesToJenniesOfficialEpForAudioAndLyricUploads() {
        val official=YoutubeVideo("dkMPft1y21Y","JENNIE - HEAVEN (Official Audio)","JennieRubyJaneVEVO","")
        val lyrics=YoutubeVideo("ENH3P5HF_og","제니의 지독한 정병연애썰 : JENNIE - HEAVEN [가사/해석/lyrics]","때잉","")
        val album=messy.copy(id="6804046964",artist="JENNIE",title="HEAVEN",album="Fallen Angel - EP",artistId="913944",durationMs=186721)
        val different=album.copy(artist="Jennie Vee",artistId="1099439451")
        val single=album.copy(id="single",album="HEAVEN - Single")
        val live=album.copy(id="live",album="Fallen Angel (Live)")
        for(video in listOf(official,lyrics)) {
            assertEquals(album,OdifyMusicMatching.choose(video,listOf(different,live,single,album)))
            assertNull(OdifyMusicMatching.choose(video,listOf(different,live)))
            assertNull(OdifyMusicMatching.choose(video,listOf(album,album.copy(artistId="other"))))
        }
        val cover=lyrics.copy(title="JENNIE - HEAVEN (Cover)")
        assertTrue(OdifyMusicMatching.usesVideoArtwork(cover))
        assertNull(OdifyMusicMatching.choose(cover,listOf(album)))
        assertFalse(OdifyMusicMatching.isVideoVersion("Artist - Live Forever (Official Audio)"))
        assertFalse(OdifyMusicMatching.isVideoVersion("Artist - Cover Me"))
        assertNull(OdifyMusicMatching.recording("JENNIE","HEAVEN",listOf(album,album.copy(durationMs=220000))))
    }
    @Test fun localizedYoasobiDriveOriginalCanBeSavedWithoutAMasterVideoLink() {
        val video=YoutubeVideo("bVNoTmK46tc","Lagu Jepang | YOASOBI - Ano Yume wo Nazotte (lirik dan terjemahan indonesia)","Simaing Marzuky","")
        val recording=messy.copy(id="1542182544",title="Ano yume wo nazotte",artist="YOASOBI",album="THE BOOK",
            artistId="1490256993",durationMs=240747,genre="J-Pop")
        val drive=OdifySong("gdrive:fixture","あの夢をなぞって","","","")
        fun resolve(items:List<OdifySong>,upload:YoutubeVideo=video,duration:Long=242000)=
            OdifyPhoneDownloadPolicy.recordingSource(upload,duration,recording,items)
        assertEquals(recording,OdifyMusicMatching.choose(video,listOf(recording)))
        assertEquals(drive,resolve(listOf(drive)))
        assertNull(resolve(listOf(drive,drive.copy(id="gdrive:duplicate"))))
        assertNull(resolve(listOf(drive.copy(artist="Someone else"))))
        assertNull(resolve(listOf(drive),video.copy(title=video.title+" (Cover)")))
        assertNull(resolve(listOf(drive),duration=180000))
        val original=OdifyMusicMatching.credits(video,recording.title,recording.artist)
        assertEquals(OdifyMusicMatching.Credits("あの夢をなぞって","YOASOBI"),original)
        assertTrue(OdifySearchMatching.matches(drive.copy(artist="YOASOBI"),"Ano yume wo nazotte",OdifySearchScope.Music))
    }

    @Test fun originalTitlesRequireTheSameCatalogRecordingAndNativeWriting() {
        val romanized=messy.copy(title="Sarange yeonseubi isseotdamyeon",genre="K-Pop")
        val korean=romanized.copy(title="사랑에 연습이 있었다면")
        assertEquals("kr",OdifyTitlePolicy.storefront(romanized))
        assertEquals(korean.title,OdifyTitlePolicy.storefrontTitle(romanized,korean,"kr"))
        assertNull(OdifyTitlePolicy.storefrontTitle(romanized,korean.copy(id="2"),"kr"))
        assertNull(OdifyTitlePolicy.storefrontTitle(romanized,korean.copy(artistId="different"),"kr"))
        assertNull(OdifyTitlePolicy.storefrontTitle(romanized,romanized,"kr"))
        val japanese=romanized.copy(genre="J-Pop")
        assertEquals("jp",OdifyTitlePolicy.storefront(japanese))
        assertEquals("ハルカ",OdifyTitlePolicy.storefrontTitle(japanese,japanese.copy(title="ハルカ"),"jp"))
        assertEquals("한글 제목",OdifyTitlePolicy.title("Romanized (한글 제목)","Artist"))
    }
    @Test fun normalizesFeaturedCreditsAndPrefersTheOriginalAlbumOverCompilations() {
        val upload=YoutubeVideo("DtCphp8M5Dg","DJ Khaled - I'm the One ft. Justin Bieber, Chance the Rapper, Lil Wayne (Lyrics)","7clouds Rap","")
        val original=messy.copy(id="1244229849",title="I'm the One (feat. Justin Bieber, Quavo, Chance the Rapper & Lil Wayne)",
            album="Grateful",artist="DJ Khaled",artistId="157749142")
        val compilation=original.copy(id="1409917803",album="NOW Party Anthems",compilation=true)
        val differentArtist=original.copy(id="1485036623",title="I'm the One",artist="Descendents",artistId="2152")
        assertEquals("DJ Khaled I'm the One",OdifyMusicMatching.query(upload))
        assertEquals(original,OdifyMusicMatching.choose(upload,listOf(compilation,differentArtist,original)))
        assertEquals(OdifyMusicMatching.Credits("I'm the One","DJ Khaled"),OdifyMusicMatching.credits(upload,original.title,original.artist))
    }

    @Test fun readsThePerformerAndTitleInAQuotedVideoBeforeTheStudioCredit() {
        val upload=YoutubeVideo("xB5Ia_Nnlvg","ILLIT(아일릿) '체포하겠어 (You're Under Arrest)' (4K) | STUDIO CHOOM ORIGINAL",
            "STUDIO CHOOM [스튜디오 춤]","")
        val song=messy.copy(id="6818511627",title="You’re Under Arrest",artist="ILLIT",artistId="1734551937",album="BREAK EVEN - EP")
        val remix=song.copy(id="6830467634",album="You're Under Arrest (Remixes)")
        assertEquals(OdifyMusicMatching.Credits("체포하겠어","ILLIT"),OdifyMusicMatching.credits(upload))
        assertEquals("ILLIT You're Under Arrest",OdifyMusicMatching.query(upload))
        assertEquals(song,OdifyMusicMatching.choose(upload,listOf(remix,song)))
        assertEquals(song,OdifyMusicMatching.choose(upload.copy(title="아일릿(ILLIT) - 체포하겠어 [가사]"),listOf(song)))
        assertNull(OdifyMusicMatching.choose(upload.copy(title="Another Artist - 체포하겠어"),listOf(song)))
    }
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
