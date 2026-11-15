package com.odify.nativeapp

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

internal class OdifyRelatedTracks(private val context:Context) {
    private val cache=File(context.cacheDir,"related_tracks")

    suspend fun load(seeds:List<Pair<String,String>>):OdifyRelatedMusic = withContext(requests) {
        if(seeds.isEmpty())return@withContext OdifyRelatedMusic()
        val apiKey=OdifyLastFmKeyStore(context).read() ?: return@withContext OdifyRelatedMusic()
        val similar=seeds.take(4).map { (title,artist) ->
            async {
                val direct=tracks(cached("track.getsimilar",artist,apiKey,12,title),"similartracks")
                val extra=if(direct.any { !OdifyDiscovery.sameArtist(artist,it.artist) })emptyList()
                else similarArtists(cached("artist.getsimilar",artist,apiKey,4),artist).take(2).flatMap {name->
                    tracks(cached("artist.gettoptracks",name,apiKey,4),"toptracks").take(2)
                }
                OdifyRecommendations.key(title,artist) to (extra+direct).distinctBy {it.id}
            }
        }.awaitAll().toMap()
        val nearby=(0 until 3).flatMap {index->similar.values.mapNotNull {it.getOrNull(index)}}.take(6)
        val tagSeeds=(seeds.take(4)+nearby.map {it.title to it.artist}).distinctBy { (title,artist)->
            OdifyRecommendations.key(title,artist)
        }
        val tags=tagSeeds.map { (title,artist)->
            async {
                val trackTags=tags(cached("track.gettoptags",artist,apiKey,12,title))
                val descriptors=if(trackTags.isEmpty() && (title to artist) in seeds)
                    tags(cached("artist.gettoptags",artist,apiKey,12)) else trackTags
                OdifyRecommendations.key(title,artist) to descriptors
            }
        }.awaitAll().toMap()
        OdifyRelatedMusic(similar,tags)
    }

    private suspend fun cached(method:String,artist:String,apiKey:String,limit:Int,title:String?=null):JSONObject {
        val key=OdifyMusicMatching.cacheKey(method+"|"+title+"|"+artist)
        val file=File(cache,"$key.json")
        val saved=runCatching {JSONObject(file.readText())}.getOrNull()
        if(saved!=null && System.currentTimeMillis()-file.lastModified() in 0 until 72*60*60_000L)return saved
        return try {
            fun encode(value:String)=URLEncoder.encode(value.take(160),"UTF-8")
            val parameters="&autocorrect=1&artist="+encode(artist)+(title?.let {"&track="+encode(it)} ?: "")
            val raw=OdifyPopularArtists.request(method,parameters,apiKey,limit)
            val result=JSONObject(raw)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if(cache.isDirectory || cache.mkdirs())runCatching {
                val atomic=AtomicFile(file)
                val output=atomic.startWrite()
                try {output.write(raw.toByteArray());atomic.finishWrite(output)}
                catch(error:Exception) {atomic.failWrite(output);throw error}
            }
            result
        } catch(cancel:CancellationException) {throw cancel}
        catch(_:Exception) {saved ?: JSONObject()}
    }

    companion object {
        private val requests=Dispatchers.IO.limitedParallelism(3)

        fun tracks(data:JSONObject,collection:String):List<ExploreTrack> {
            val rows=data.optJSONObject(collection)?.optJSONArray("track") ?: return emptyList()
            return (0 until minOf(rows.length(),12)).mapNotNull {index->
                val row=rows.optJSONObject(index) ?: return@mapNotNull null
                val title=row.optString("name").trim()
                val artist=OdifyMusicMatching.artistName(row.optJSONObject("artist")?.optString("name").orEmpty())
                if(title.isBlank() || artist.isBlank())return@mapNotNull null
                val key=OdifyRecommendations.key(title,artist)
                ExploreTrack("lastfm-"+OdifyMusicMatching.cacheKey(key).take(24),title,artist,"",emptyList(),index+1)
            }.distinctBy {it.id}
        }

        fun similarArtists(data:JSONObject,artist:String):List<String> {
            val rows=data.optJSONObject("similarartists")?.optJSONArray("artist") ?: return emptyList()
            return (0 until minOf(rows.length(),4)).mapNotNull {index->
                rows.optJSONObject(index)?.optString("name")?.trim()?.takeIf {it.isNotBlank() && !OdifyDiscovery.sameArtist(it,artist)}
            }.distinctBy(OdifyMusicMatching::artistKey)
        }

        fun tags(data:JSONObject):List<String> {
            val rows=data.optJSONObject("toptags")?.optJSONArray("tag") ?: return emptyList()
            return (0 until minOf(rows.length(),12)).mapNotNull {index->
                rows.optJSONObject(index)?.optString("name")?.trim()?.takeIf {it.isNotEmpty()}
            }
        }
    }
}
