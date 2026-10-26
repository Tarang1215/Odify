package com.odify.nativeapp

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Public song metadata only. No previews, audio URLs, or playback on lookup. */
internal class OdifyArtistCatalog(private val context:Context) {
    private val directory=File(context.cacheDir,"artist_catalog").apply {mkdirs()}
    private val portraitRequests=Semaphore(2)
    suspend fun portrait(artist:String,knownTitles:List<String> = emptyList()):String? = portraitRequests.withPermit {
        withContext(Dispatchers.IO) {
            val key="artist-"+OdifyMusicMatching.cacheKey(artist)
            val file=File(directory,"$key-photo.txt")
            val saved=runCatching {file.readText().takeIf {File(it).isFile}}.getOrNull()
            if(saved!=null && System.currentTimeMillis()-file.lastModified()<7*24*60*60_000L)return@withContext saved
            try {
                val identity=tracks(artist,knownTitles).firstNotNullOfOrNull {it.music} ?: return@withContext saved
                val url=URL(identity.artistUrl)
                if(url.protocol!="https" || url.host!="music.apple.com" || !identity.artistId.matches(Regex("[0-9]+")))return@withContext saved
                val address=OdifyArtistPortrait.address(OdifyCatalogHttp.get(identity.artistUrl,4_000_000)) ?: return@withContext saved
                OdifyCatalogArt.download(context,address,"$key-${System.currentTimeMillis()/(7*24*60*60_000L)}",400)
                    ?.also {file.writeText(it)} ?: saved
            } catch(cancel:CancellationException){throw cancel} catch(_:Exception){saved}
        }
    }
    suspend fun moodTracks(key:String):List<ExploreTrack> = withContext(Dispatchers.IO) {
        val mood=OdifyDiscovery.moods.first {it.key==key}
        val query=when(key) {"focus"->"classical";"unwind"->"jazz";"night"->"R&B soul";else->"dance"}
        val file=File(directory,"mood-$key.json")
        fun decodeMood(raw:String):List<ExploreTrack> {
            val rows=JSONObject(raw).getJSONArray("results")
            return (0 until minOf(rows.length(),100)).mapNotNull {index ->
                val row=rows.optJSONObject(index) ?: return@mapNotNull null
                val genre=row.optString("primaryGenreName")
                if(row.optString("kind")!="song" || !OdifyDiscovery.matches(mood,listOf(genre)))return@mapNotNull null
                val id=row.optString("trackId");val title=row.optString("trackName");val artist=row.optString("artistName")
                if(!id.matches(Regex("[0-9]+")) || title.isBlank() || artist.isBlank())null
                else OdifyCatalogHttp.parse(row)?.track(index+1)
            }.distinctBy {OdifyYouTubeMatcher.norm(it.artist) to OdifyYouTubeMatcher.norm(it.title)}
        }
        val cached=runCatching {decodeMood(file.readText())}.getOrDefault(emptyList())
        if(cached.isNotEmpty() && System.currentTimeMillis()-file.lastModified()<24*60*60_000L)return@withContext cached
        try {
            val raw=request("https://itunes.apple.com/search?term=${URLEncoder.encode(query,"UTF-8")}&country=us&media=music&entity=song&limit=100")
            val result=decodeMood(raw)
            if(result.isNotEmpty()) {
                val atomic=AtomicFile(file);val stream=atomic.startWrite()
                try {stream.write(raw.toByteArray());atomic.finishWrite(stream)}
                catch(error:Exception) {atomic.failWrite(stream);throw error}
            }
            result.ifEmpty {cached}
        } catch(cancel:CancellationException) {throw cancel}
        catch(failure:Exception) {cached.ifEmpty {throw failure}}
    }
    suspend fun tracks(artist:String,knownTitles:List<String> = emptyList()):List<ExploreTrack> = withContext(Dispatchers.IO) {
        val name=artist.trim().take(120)
        require(name.isNotBlank())
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(name.toByteArray())
            .joinToString("") {"%02x".format(it)}
        val file=File(directory,"$hash.json")
        val cached=runCatching {decode(file.readText(),name,knownTitles)}.getOrDefault(emptyList())
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
                val result=decode(raw,name,knownTitles)
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
        // Some localized/newer artist names are absent from this catalog.
        // The user's existing connected YouTube music search still recognizes
        // them; keep its video identities and label these as search results.
        if(OdifyYoutubeAuth.connected) {
            try {
                val videos=OdifyYoutubeLibrary(context).search(name,50)
                val canonical=OdifyMusicMatching.artistAlias(name,videos)
                if(canonical!=null) {
                    val raw=request("https://itunes.apple.com/search?term=${URLEncoder.encode(canonical,"UTF-8")}&country=us&media=music&entity=song&attribute=artistTerm&limit=200")
                    val resolved=JSONObject(raw).put("odifyCanonicalArtist",canonical).toString()
                    val result=decode(resolved,name,knownTitles)
                    if(result.isNotEmpty()) {
                        val atomic=AtomicFile(file);val stream=atomic.startWrite()
                        try {stream.write(resolved.toByteArray());atomic.finishWrite(stream)}
                        catch(error:Exception){atomic.failWrite(stream);throw error}
                        return@withContext result
                    }
                }
                if(videos.isNotEmpty())return@withContext videos.mapIndexed {index,video ->
                    ExploreTrack("youtube:${video.videoId}",video.title,video.channel,"",emptyList(),index+1)
                }
            } catch(cancel:CancellationException) {throw cancel}
            catch(failure:Exception) {lastFailure=failure}
        }
        if(lastFailure!=null)throw lastFailure
        emptyList()
    }
    internal fun decode(raw:String,artist:String,knownTitles:List<String> = emptyList()):List<ExploreTrack> {
        val root=JSONObject(raw)
        val rows=root.getJSONArray("results")
        val canonical=root.optString("odifyCanonicalArtist").ifBlank {artist}
        val canonicalIds=(0 until rows.length()).mapNotNull {rows.optJSONObject(it)}
            .filter {OdifyDiscovery.sameArtist(it.optString("artistName"),canonical)}
            .map {it.optString("artistId")}.filter {it.matches(Regex("[0-9]+"))}.toSet()
        // A localized tag (아이유) may be indexed under a canonical name (IU).
        // Resolve that alias only with an unambiguous artist ID supported by
        // the user's known song titles, never by accepting a fuzzy artist name.
        val known=knownTitles.map(OdifyYouTubeMatcher::norm).filter(String::isNotBlank).toSet()
        val evidence=(0 until rows.length()).mapNotNull {rows.optJSONObject(it)}
            .filter {it.optString("kind")=="song" && it.optString("artistId").matches(Regex("[0-9]+")) &&
                OdifyYouTubeMatcher.norm(it.optString("trackName")) in known}
            .groupBy {it.optString("artistId")}.mapValues {(_,songs)->songs.map {OdifyYouTubeMatcher.norm(it.optString("trackName"))}.distinct().size}
            .entries.sortedByDescending {it.value}
        val hasExact=(0 until rows.length()).any {OdifyDiscovery.sameArtist(rows.optJSONObject(it)?.optString("artistName").orEmpty(),artist)}
        val alias=if(hasExact)null else evidence.firstOrNull()?.takeIf {it.value>(evidence.getOrNull(1)?.value ?: 0)}?.key
        return (0 until minOf(rows.length(),200)).mapNotNull {index ->
            val row=rows.optJSONObject(index) ?: return@mapNotNull null
            val id=row.optString("trackId");val title=row.optString("trackName");val name=row.optString("artistName")
            if(row.optString("kind")!="song" || !id.matches(Regex("[0-9]+")) || title.isBlank() ||
                !(OdifyDiscovery.sameArtist(name,canonical) || row.optString("artistId") in canonicalIds || alias!=null && row.optString("artistId")==alias))return@mapNotNull null
            OdifyCatalogHttp.parse(row)?.track(index+1)
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
    fun sameArtist(first:String,second:String):Boolean = OdifyMusicMatching.norm(first).let {it.isNotBlank() && it==OdifyMusicMatching.norm(second)}
    fun matches(mood:OdifyMood,genres:List<String>):Boolean = genres.any {genre ->mood.genres.any {genre.contains(it,true)}}
    fun newTracks(tracks:List<ExploreTrack>,owned:List<OdifySong>):List<ExploreTrack> {
        val index=OdifyChartMatching.buildIndex(owned)
        return tracks.distinctBy {OdifyYouTubeMatcher.norm(it.artist) to OdifyYouTubeMatcher.norm(it.title)}
            .filter {index.resolve(it)==null}
    }
}
