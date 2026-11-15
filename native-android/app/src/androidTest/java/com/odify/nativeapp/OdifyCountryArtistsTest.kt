package com.odify.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Metadata-only fixtures. No production key, audio or real ranking is fabricated. */
class OdifyCountryArtistsTest {
    @Test fun forgettingAListenRemovesTasteButAllowsFuturePlays() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=OdifyListeningStore(context)
        val songId="test:forget:"+java.util.UUID.randomUUID()
        val now=System.currentTimeMillis()
        try {
            store.record("first:"+songId,songId,now-500)
            assertEquals(1,store.signals(now)[songId]?.count)
            store.forget(setOf(songId),now)
            store.record("pending:"+songId,songId,now-250)
            assertNull(store.signals(now)[songId])
            store.record("future:"+songId,songId,now+1)
            assertEquals(1,store.signals(now+1)[songId]?.count)
        } finally {
            store.forget(setOf(songId),now+2)
            store.preferences.edit().remove("forgotten:"+songId).commit()
        }
    }
    @Test fun discoveryParsesSimilarArtistsAndTopTracksWithoutDuplicatingAliases() {
        val artists=org.json.JSONObject("""{"similarartists":{"artist":[{"name":"제니"},{"name":"優里"},{"name":"Yuuri"},{"name":"Another artist"}]}}""")
        assertEquals(listOf("優里","Another artist"),OdifyRelatedTracks.similarArtists(artists,"JENNIE"))
        val tracks=org.json.JSONObject("""{"toptracks":{"track":[{"name":"Evening","artist":{"name":"優里"}},{"name":"Evening","artist":{"name":"Yuuri"}},{"name":"Invalid"}]}}""")
        assertEquals(listOf("Yuuri" to "Evening"),OdifyRelatedTracks.tracks(tracks,"toptracks").map {it.artist to it.title})
    }
    @Test fun liveDiscoveryFindsDifferentArtistsEvenWhenTrackSimilarityIsEmpty() {
        val test=InstrumentationRegistry.getInstrumentation()
        val context=test.targetContext
        org.junit.Assume.assumeTrue(context.packageName.endsWith(".qa") && InstrumentationRegistry.getArguments().getString("liveRecommendations")=="true")
        val input=java.io.File(context.filesDir,"lastfm-test-input")
        val key=input.readText().trim()
        check(input.delete())
        val store=OdifyLastFmKeyStore(context)
        try {
            store.save(key)
            val seed="사랑에 연습이 있었다면" to "임재현"
            val related=kotlinx.coroutines.runBlocking {OdifyRelatedTracks(context).load(listOf(seed))}
            val matches=related.bySeed.getValue(OdifyRecommendations.key(seed.first,seed.second))
            assertTrue(matches.any {!OdifyDiscovery.sameArtist(it.artist,seed.second)})
        } finally {store.clear()}
    }
    @Test fun failedHttpResponsesKeepLastFmErrorCodes() {
        val failure=runCatching {OdifyPopularArtists.response("""{"error":10,"message":"Invalid API key"}""",403)}
            .exceptionOrNull() as OdifyLastFmError
        assertEquals(10,failure.code)
        assertEquals("Last.fm API 키를 확인해 주세요.",failure.message)
    }
    @Test fun keepsProviderOrderRatherThanInventingCountryRanksFromListenerCounts() {
        val raw="""{"topartists":{"artist":[{"name":"First","listeners":"1","url":"https://www.last.fm/music/First"},{"name":"Second","listeners":"999999"},{"name":"FIRST"},{"name":""}]}}"""
        val result=OdifyPopularArtists.parse(raw,"jp",123,true)
        assertEquals(listOf("First","Second"),result.artists.map {it.name})
        assertEquals(listOf(1,2),result.artists.map {it.rank});assertEquals("jp",result.country)
        assertEquals(123L,result.fetchedAt);assertTrue(result.cached)
        assertEquals("Korea, Republic of",OdifyPopularArtists.countryName("kr"))
        assertEquals("United States",OdifyPopularArtists.countryName("us"))
        assertTrue(runCatching {OdifyPopularArtists.parse(raw,"unknown",123)}.isFailure)
    }
    @Test fun providerErrorsAndEmptyResponsesAreNeverPresentedAsAValidRanking() {
        for(raw in listOf("""{"error":10,"message":"secret-must-not-leak"}""","""{"topartists":{"artist":[]}}""","invalid")) {
            val result=runCatching {OdifyPopularArtists.parse(raw,"kr",123)}
            assertTrue(result.isFailure);assertFalse(result.exceptionOrNull()?.message.orEmpty().contains("secret-must-not-leak"))
        }
    }
    @Test fun apiKeyIsEncryptedLocallyAndDisconnectRemovesIt() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="company-lastfm-key";context.deleteSharedPreferences(name)
        val store=OdifyLastFmKeyStore(context,name)
        val key="0123456789abcdef0123456789abcdef"
        try {
            assertNull(store.read());store.save(key);assertEquals(key,OdifyLastFmKeyStore(context,name).read())
            val values=context.getSharedPreferences(name,0).all.values.joinToString()
            assertFalse(values.contains(key));store.clear();assertNull(store.read())
        } finally {
            context.deleteSharedPreferences(name)
            java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null);if(containsAlias(name))deleteEntry(name)}
        }
    }
}
