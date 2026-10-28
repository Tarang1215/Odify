package com.odify.nativeapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class OdifyNativeResume(val songs:List<OdifySong>,val index:Int,val position:Long,
    val shuffle:Boolean=false,val repeat:Int=0)

/** Private queue recovery, separate from the user's saved playlists. */
internal class OdifyNativeCheckpoint(context:Context,name:String="odify_native_resume_v1") {
    private val prefs=context.getSharedPreferences(name,0)
    private var cachedSongs:List<OdifySong>?=null
    private var encoded=""
    @Synchronized fun write(value:OdifyNativeResume,now:Long=System.currentTimeMillis()) {
        if(value.index !in value.songs.indices || value.songs.size>5000)return
        if(cachedSongs!=value.songs) {
            encoded=JSONArray().apply {value.songs.forEach {s ->put(JSONObject().put("id",s.id)
                .put("title",s.title).put("artist",s.artist).put("album",s.album).put("file",s.filePath)
                .put("art",s.artworkPath).put("duration",s.durationMs).put("source",s.sourceUri)
                .put("md5",s.contentMd5).put("video",s.playbackVideoId))}}.toString()
            cachedSongs=value.songs
        }
        prefs.edit().putString("queue",encoded).putInt("index",value.index).putLong("position",value.position.coerceAtLeast(0))
            .putBoolean("shuffle",value.shuffle).putInt("repeat",value.repeat).putLong("at",now).commit()
    }
    fun restore(id:String,now:Long=System.currentTimeMillis()):OdifyNativeResume?=runCatching {
        val age=now-prefs.getLong("at",0)
        if(age !in 0..7*24*60*60_000L)return@runCatching null
        val rows=JSONArray(prefs.getString("queue","[]"));if(rows.length() !in 1..5000)return@runCatching null
        val songs=(0 until rows.length()).map {i ->val s=rows.getJSONObject(i)
            fun optional(key:String)=s.optString(key).takeIf {it.isNotBlank() && it!="null"}
            OdifySong(s.getString("id"),s.optString("title"),s.optString("artist"),s.optString("album"),s.optString("file"),
                artworkPath=optional("art"),durationMs=s.optLong("duration"),sourceUri=optional("source"),
                contentMd5=s.optString("md5"),playbackVideoId=s.optString("video"))}
        val index=prefs.getInt("index",-1);val song=songs.getOrNull(index) ?: return@runCatching null
        if(song.id!=id || song.isYoutubeStream || song.sourceUri.isNullOrBlank() && song.filePath.isBlank())return@runCatching null
        OdifyNativeResume(songs,index,prefs.getLong("position",0).coerceAtLeast(0),prefs.getBoolean("shuffle",false),
            prefs.getInt("repeat",0).coerceIn(0,2))
    }.getOrNull()
}
