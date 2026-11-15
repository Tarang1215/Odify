package com.odify.nativeapp

import org.junit.Assert.*
import org.junit.Test

class OdifyHybridRecommendationsTest {
    private val now=1_800_000_000_000L
    private fun song(id:String,title:String,artist:String,genre:String="",album:String="")=
        OdifySong(id,title,artist,album,"/music/$id",genre=genre)
    private fun online(id:String,title:String,artist:String,vararg genres:String)=
        ExploreTrack(id,title,artist,"",genres.toList(),0)

    @Test fun mixesOwnedAndOnlineAndMatchesMoodAcrossGenres() {
        val seed=song("local:seed","Late night","Artist One","Acoustic")
        val sameGenre=song("gdrive:owned","Quiet Hour","Artist Two","Acoustic")
        val mood=online("lastfm-one","Evening","Artist Three","Jazz","mellow")
        val unrelated=online("chart-two","Fanfare","Artist Four","Metal","energetic")
        val similar=online("lastfm-two","Moonrise","Artist Five")
        val songs=listOf(seed,sameGenre)
        val signals=mapOf(seed.id to ListeningSignal(3,now))
        val seeds=OdifyRecommendations.seeds(songs,setOf(seed.id),signals,now)
        val key=OdifyRecommendations.key(seed.title,seed.artist)
        val related=OdifyRelatedMusic(mapOf(key to listOf(similar)),mapOf(key to listOf("chill")))
        val ranked=OdifyRecommendations.recommend(songs,seeds,signals,listOf(mood,unrelated),related,now)
        assertTrue(ranked.any {it.owned?.id==sameGenre.id})
        assertTrue(ranked.any {it.track.id==mood.id && it.owned==null})
        assertEquals(similar.id,ranked.first().track.id)
        assertFalse(ranked.any {it.track.id==unrelated.id})
    }

    @Test fun usesRecentStreamsButDoesNotTreatAutoSavedVideosOrWholeAlbumsAsStrongVotes() {
        val album=(1..8).map {number->song("local:$number","Track $number","One artist","Pop","One album")}
        val downloaded=song("youtube:abcdefghijk","Auto download","Ignored artist")
        val recent=song("youtube:bcdefghijkl","Watched","Recent artist").forYoutubePlayback("bcdefghijkl")
        val signals=mapOf(recent.id to ListeningSignal(5,now))
        val seeds=OdifyRecommendations.seeds(album+downloaded+recent,emptySet(),signals,now)
        assertEquals(recent.id,seeds.first().song.id)
        assertEquals(1,seeds.count {it.song.album=="One album"})
        assertFalse(seeds.any {it.song.id==downloaded.id})
    }

    @Test fun prefersOwnedDuplicateAcrossArtistAliasesAndDownRanksRecentRepeat() {
        val recent=song("local:recent","Recent","제니","Pop")
        val older=song("local:older","Older","JENNIE","Pop")
        val songs=listOf(recent,older)
        val duplicate=online("chart","Recent","Gennie","Pop")
        val signals=mapOf(recent.id to ListeningSignal(2,now))
        val seeds=OdifyRecommendations.seeds(songs,setOf(recent.id),signals,now)
        val ranked=OdifyRecommendations.recommend(songs,seeds,signals,listOf(duplicate),OdifyRelatedMusic(),now)
        assertEquals(listOf(older.id,recent.id),ranked.map {it.owned?.id})
    }

    @Test fun recommendationPlaybackRequiresTheSameRecordingInsteadOfTheFirstVideo() {
        val other=YoutubeVideo("abcdefghijk","Different artist - Evening","Uploader","")
        val live=YoutubeVideo("bcdefghijkl","Artist Three - Evening (Live)","Uploader","")
        val expected=YoutubeVideo("cdefghijklm","Artist Three - Evening (Lyrics)","Uploader","")
        assertEquals(expected,OdifyYoutubeQuickPlay.matching(listOf(other,live,expected),"Artist Three","Evening"))
        assertNull(OdifyYoutubeQuickPlay.matching(listOf(other,live),"Artist Three","Evening"))
    }
    @Test fun excludesReducedSuggestionsAcrossSources() {
        val owned=song("local:song","Evening","제니","Pop")
        val source=online("lastfm-one","Evening","JENNIE","Pop")
        val other=song("local:other","Morning","Another Artist","Pop")
        val songs=listOf(owned,other)
        val seeds=OdifyRecommendations.seeds(songs,setOf(other.id),emptyMap(),now)
        val excluded=setOf(OdifyRecommendations.key(owned.title,owned.artist))
        val ranked=OdifyRecommendations.recommend(songs,seeds,emptyMap(),listOf(source),OdifyRelatedMusic(),now,excluded)
        assertEquals(listOf(other.id),ranked.map {it.owned?.id})
    }
}
