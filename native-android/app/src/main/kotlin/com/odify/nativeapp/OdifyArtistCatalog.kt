package com.odify.nativeapp

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Public song metadata only. No previews, audio URLs, or playback on lookup. */
internal class OdifyArtistCatalog(context:Context) {
    private val directory=File(context.cacheDir,"artist_catalog").apply {mkdirs()}
    suspend fun tracks(artist:String):List<ExploreTrack> = withContext(Dispatchers.IO) {
        val name=artist.trim().take(120)
        require(name.isNotBlank())
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(name.toByteArray())
            .joinToString("") {"%02x".format(it)}
        val file=File(directory,"$hash.json")
        val cached=runCatching {decode(file.readText(),name)}.getOrDefault(emptyList())
        if(cached.isNotEmpty() && System.currentTimeMillis()-file.lastModified()<24*60*60_000L)return@withContext cached
        val countries=when {
            name.any {it in '\u3040'..'\u30ff' || it in '\u4e00'..'\u9fff'} -> listOf("jp","us")
            name.any {it in '\uac00'..'\ud7a3'} -> listOf("kr","us")
            else -> listOf("us","jp","kr")
        }
        var lastFailure:Exception?=null
        for(country in countries) {
            try {
                val raw=request("https://itunes.apple.com/search?term=${URLEncoder.encode(name,"UTF-8")}&country=$country&media=music&entity=song&attribute=artistTerm&limit=200")
                val result=decode(raw,name)
                if(result.isNotEmpty()) {
                    val atomic=AtomicFile(file);val stream=atomic.startWrite()
                    try {stream.write(raw.toByteArray());atomic.finishWrite(stream)}
                    catch(error:Exception) {atomic.failWrite(stream);throw error}
                    return@withContext result
                }
            } catch(cancel:CancellationException) {throw cancel}
            catch(error:Exception) {lastFailure=error}
        }
        if(cached.isNotEmpty())return@withContext cached
        if(lastFailure!=null)throw lastFailure
        emptyList()
    }
    internal fun decode(raw:String,artist:String):List<ExploreTrack> {
        val rows=JSONObject(raw).getJSONArray("results")
        return (0 until minOf(rows.length(),200)).mapNotNull {index ->
            val row=rows.optJSONObject(index) ?: return@mapNotNull null
            val id=row.optString("trackId");val title=row.optString("trackName");val name=row.optString("artistName")
            if(row.optString("kind")!="song" || !id.matches(Regex("[0-9]+")) || title.isBlank() ||
                !OdifyDiscovery.sameArtist(name,artist))return@mapNotNull null
            // Keep discovery rows text-first. Owned tracks retain their own covers.
            ExploreTrack(id,title,name,"",listOf(row.optString("primaryGenreName")).filter(String::isNotBlank),index+1)
        }.distinctBy {OdifyYouTubeMatcher.norm(it.artist) to OdifyYouTubeMatcher.norm(it.title)}
    }
    private fun request(address:String):String {
        val connection=URL(address).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout=8_000;connection.readTimeout=12_000;connection.instanceFollowRedirects=false
            check(connection.responseCode==200) {"아티스트 목록에 연결하지 못했어요 (${connection.responseCode})"}
            connection.inputStream.use {input ->
                val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true) {val count=input.read(buffer);if(count<0)break;check(output.size()+count<=2_000_000);output.write(buffer,0,count)}
                output.toString("UTF-8")
            }
        } finally {connection.disconnect()}
    }
}

internal data class OdifyMood(val key:String,val name:String,val description:String,val genres:List<String>)

internal object OdifyDiscovery {
    val moods=listOf(
        OdifyMood("unwind","쉬어가는 시간","재즈 · 어쿠스틱 · 잔잔한 장르",listOf("jazz","acoustic","ambient","재즈","어쿠스틱","뉴에이지","new age")),
        OdifyMood("focus","몰입이 필요할 때","클래식 · 연주 · 앰비언트",listOf("classical","instrumental","ambient","lo-fi","클래식","연주")),
        OdifyMood("energy","기분을 올리고 싶을 때","팝 · 댄스 · 록의 에너지",listOf("pop","dance","electronic","rock","hip-hop","팝","댄스","록","힙합")),
        OdifyMood("night","늦은 밤의 리듬","R&B · 소울 · 얼터너티브",listOf("r&b","soul","alternative","알앤비","소울","얼터너티브")))
    fun sameArtist(first:String,second:String):Boolean = OdifyYouTubeMatcher.norm(first).let {it.isNotBlank() && it==OdifyYouTubeMatcher.norm(second)}
    fun matches(mood:OdifyMood,genres:List<String>):Boolean = genres.any {genre ->mood.genres.any {genre.contains(it,true)}}
    fun newTracks(tracks:List<ExploreTrack>,owned:List<OdifySong>):List<ExploreTrack> {
        val index=OdifyChartMatching.buildIndex(owned)
        return tracks.distinctBy {OdifyYouTubeMatcher.norm(it.artist) to OdifyYouTubeMatcher.norm(it.title)}
            .filter {index.resolve(it)==null}
    }
}
