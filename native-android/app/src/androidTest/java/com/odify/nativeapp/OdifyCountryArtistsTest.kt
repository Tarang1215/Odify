package com.odify.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Metadata-only fixtures. No production key, audio or real ranking is fabricated. */
class OdifyCountryArtistsTest {
    @Test fun keepsProviderOrderRatherThanInventingCountryRanksFromListenerCounts() {
        val raw="""{"topartists":{"artist":[{"name":"First","listeners":"1","url":"https://www.last.fm/music/First"},{"name":"Second","listeners":"999999"},{"name":"FIRST"},{"name":""}]}}"""
        val result=OdifyPopularArtists.parse(raw,"jp",123,true)
        assertEquals(listOf("First","Second"),result.artists.map {it.name})
        assertEquals(listOf(1,2),result.artists.map {it.rank});assertEquals("jp",result.country)
        assertEquals(123L,result.fetchedAt);assertTrue(result.cached)
        assertEquals("South Korea",OdifyPopularArtists.countryName("kr"))
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
