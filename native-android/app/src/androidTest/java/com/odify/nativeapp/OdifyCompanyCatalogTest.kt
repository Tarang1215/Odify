package com.odify.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File

/** Explicit, metadata-only live integration probe. No WebView, player, prepare or audio. */
class OdifyCompanyCatalogTest {
    @Test fun resolvesResceneAndRoseWithOfficialArtUsingMetadataOnly()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        withContext(Dispatchers.IO){OdifyYoutubeAuth.accessToken(context)}
        val catalog=OdifyArtistCatalog(context)
        val tracks=catalog.tracks("리센느",listOf("LOVE ATTACK","Deja Vu","UhUh","YoYo"))
        assertTrue("Expected official RESCENE discography",tracks.size>=10)
        assertTrue(tracks.all {it.music!=null && OdifyDiscovery.sameArtist(it.artist,"RESCENE") && it.artwork.isNotBlank()})
        val portrait=catalog.portrait("리센느",listOf("LOVE ATTACK","Deja Vu","UhUh","YoYo"))
        assertTrue("Expected a real artist portrait",portrait!=null && File(portrait).length()>1000)
        val musicCatalog=OdifyMusicCatalog(context)
        val music=musicCatalog.video(YoutubeVideo("abcdefghijk","ROSÉ Messy Lyrics","Lemonade",""))!!
        assertEquals("Messy",music.title);assertEquals("ROSÉ",music.artist)
        val cover=musicCatalog.cover(music)
        assertTrue(cover!=null && File(cover).length()>1000)
        val result=JSONObject().put("artist",tracks.first().artist).put("count",tracks.size)
            .put("titles",org.json.JSONArray(tracks.take(20).map {it.title})).put("portrait",portrait)
            .put("recording",JSONObject().put("title",music.title).put("artist",music.artist).put("album",music.album).put("cover",cover))
        withContext(Dispatchers.IO){File(context.cacheDir,"company-official-metadata.json").writeText(result.toString(2))}
    }
}
