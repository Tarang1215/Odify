package com.odify.nativeapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Private, bounded recovery state. An intentional pause/stop invalidates
 * in-flight writes, so a system restart can never undo the user's pause. */
internal class OdifyYoutubeCheckpoint(context:Context,name:String="odify_youtube_resume_v1") {
    private val prefs=context.getSharedPreferences(name,0)
    private val epoch=epochs.computeIfAbsent(context.packageName+":"+name){AtomicInteger()}
    private var encodedState:YoutubeSessionState?=null
    private var encoded=""
    fun token()=epoch.get()
    fun inactive()=synchronized(epoch) {epoch.incrementAndGet();prefs.edit().putBoolean("active",false).apply();Unit}
    fun clear()=synchronized(epoch) {epoch.incrementAndGet();prefs.edit().clear().apply();Unit}
    fun write(value:YoutubeSessionState,token:Int,now:Long=System.currentTimeMillis(),active:Boolean=true):Boolean {
        val video=value.video ?: return false
        if(!video.videoId.matches(videoId))return false
        fun songs(items:List<OdifySong>,limit:Int)=JSONArray().apply {items.take(limit).forEach {put(song(it))}}
        val signature=value.copy(playback=YoutubeWebState())
        val raw=synchronized(this) {
            if(encodedState!=signature) {
                encoded=JSONObject().put("video",JSONObject().put("id",video.videoId).put("title",video.title).put("channel",video.channel))
                    .put("presentation",value.presentation?.let(::song)).put("entry",value.entryKey)
                    .put("following",songs(value.following,5000)).put("keys",JSONArray(value.followingKeys.take(5000)))
                    .put("previous",songs(value.previous.takeLast(100),100)).put("repeat",value.repeat).put("shuffle",value.shuffle).toString()
                encodedState=signature
            }
            encoded
        }
        return synchronized(epoch) {
            if(token!=epoch.get())false else {
                prefs.edit().putString("session",raw).putLong("position",value.playback.position).putLong("duration",value.playback.duration)
                    .putLong("at",now).putBoolean("active",active).apply();true
            }
        }
    }
    fun restore(now:Long=System.currentTimeMillis(),requireActive:Boolean=true):YoutubeSessionState?=runCatching {
        val at=prefs.getLong("at",0)
        val maxAge=if(requireActive)30*60_000L else 7*24*60*60_000L
        if(requireActive && !prefs.getBoolean("active",false) || at<=0 || now<at || now-at>maxAge)return@runCatching null
        val raw=JSONObject(prefs.getString("session",null) ?: return@runCatching null)
        val item=raw.getJSONObject("video");val id=item.getString("id")
        if(!videoId.matches(id))return@runCatching null
        fun songs(key:String,limit:Int):List<OdifySong> {
            val rows=raw.optJSONArray(key) ?: return emptyList()
            return (0 until minOf(rows.length(),limit)).mapNotNull {i -> rows.optJSONObject(i)?.let(::readSong)}
        }
        val following=songs("following",5000)
        val savedKeys=raw.optJSONArray("keys")?.let {rows ->(0 until rows.length()).map {rows.optString(it)}}.orEmpty()
        val keys=if(savedKeys.size==following.size && savedKeys.all {it.isNotBlank()} && savedKeys.distinct().size==savedKeys.size)savedKeys
            else following.map {UUID.randomUUID().toString()}
        YoutubeSessionState(YoutubeVideo(id,item.optString("title"),item.optString("channel"),""),
            playback=YoutubeWebState(position=prefs.getLong("position",0).coerceAtLeast(0),duration=prefs.getLong("duration",0).coerceAtLeast(0),buffering=true),
            following=following,repeat=raw.optBoolean("repeat"),shuffle=raw.optBoolean("shuffle"),
            presentation=raw.optJSONObject("presentation")?.let(::readSong)?.forYoutubePlayback(id),
            entryKey=raw.optString("entry").ifBlank {UUID.randomUUID().toString()},followingKeys=keys,previous=songs("previous",100))
    }.getOrNull()
    fun restorePaused(owner:String,id:String,now:Long=System.currentTimeMillis()):YoutubeSessionState? {
        if(owner!="youtube")return null
        val saved=restore(now,requireActive=false) ?: return null
        if((saved.presentation?.id ?: "youtube:${saved.video?.videoId}")!=id)return null
        return saved.copy(playIntent=false,playback=saved.playback.copy(
            playing=false,buffering=false,ready=true,ended=false,error=""))
    }
    private fun song(value:OdifySong)=JSONObject().put("id",value.id).put("title",value.title).put("artist",value.artist)
        .put("album",value.album).put("file",value.filePath).put("art",value.artworkPath).put("duration",value.durationMs)
        .put("source",value.sourceUri).put("genre",value.genre).put("md5",value.contentMd5).put("video",value.playbackVideoId)
    private fun readSong(value:JSONObject):OdifySong? {
        val id=value.optString("id");if(id.isBlank())return null
        return OdifySong(id,value.optString("title"),value.optString("artist"),value.optString("album"),value.optString("file"),
            artworkPath=value.optString("art").takeIf {it.isNotBlank() && it!="null"},durationMs=value.optLong("duration"),
            sourceUri=value.optString("source").takeIf {it.isNotBlank() && it!="null"},genre=value.optString("genre"),
            contentMd5=value.optString("md5"),playbackVideoId=value.optString("video"))
    }
    companion object {
        private val epochs=ConcurrentHashMap<String,AtomicInteger>()
        private val videoId=Regex("[A-Za-z0-9_-]{11}")
    }
}
