package com.odify.nativeapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Stable video IDs belong to Odify playlists; they never require a YouTube playlist write scope. */
internal class OdifyStreamingLibrary(private val context:Context, name:String="odify_streaming_v1") {
    private val prefs=context.getSharedPreferences(name,0)
    @Synchronized fun remember(video:YoutubeVideo,duration:Long=0) {
        require(Regex("[A-Za-z0-9_-]{11}").matches(video.videoId))
        val old=prefs.getString(video.videoId,null)?.let {JSONObject(it)}
        prefs.edit().putString(video.videoId,JSONObject().put("id",video.videoId).put("title",video.title)
            .put("artist",video.channel).put("duration",maxOf(duration,old?.optLong("duration") ?: 0)).toString()).apply()
    }
    fun find(id:String):OdifySong? {
        if(!id.startsWith("youtube:")) return null
        val videoId=id.removePrefix("youtube:")
        return prefs.getString(videoId,null)?.let {raw -> runCatching {
            val data=JSONObject(raw)
            OdifySong(id,data.getString("title"),data.optString("artist"),"YouTube",placeholder().absolutePath,
                File(context.cacheDir,"youtube_search_$videoId.jpg").takeIf {it.isFile}?.absolutePath,
                durationMs=data.optLong("duration"))
        }.getOrNull()}
    }
    // Media3 can prepare a mixed queue without treating a YouTube page as an audio URL.
    // PlaybackService intercepts this marker at the transition, before it can play.
    private fun placeholder():File=File(context.filesDir,"youtube_queue_marker.wav").also {file ->
        if(!file.isFile) synchronized(OdifyStreamingLibrary::class.java) {
            if(!file.isFile) file.writeBytes(java.nio.ByteBuffer.allocate(1644).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray()).putInt(1636).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(1600).array())
        }
    }
    fun video(song:OdifySong)=YoutubeVideo(song.id.removePrefix("youtube:"),song.title,song.artist,"")
}

/** A portable PC collection outbox. Uploads remain deferred until explicitly resumed. */
internal class OdifyStreamingRequests(private val context:Context) {
    private val file=File(context.filesDir,"youtube_collection_requests.json")
    @Synchronized fun enqueue(video:YoutubeVideo,duration:Long) {
        val rows=read()
        if((0 until rows.length()).any {rows.getJSONObject(it).optString("video_id")==video.videoId}) return
        rows.put(JSONObject().put("video_id",video.videoId).put("title",video.title).put("artist",video.channel)
            .put("video_url","https://www.youtube.com/watch?v=${video.videoId}").put("duration_ms",duration)
            .put("requested_at",java.time.Instant.now().toString()).put("status","QUEUED_PC")
            .put("drive_upload",false))
        val temp=File(file.parentFile,file.name+".tmp")
        temp.writeText(JSONObject().put("version",1).put("requests",rows).toString(2))
        check(temp.renameTo(file)) {"보관 요청을 저장하지 못했습니다."}
    }
    fun read():JSONArray=if(file.isFile) JSONObject(file.readText()).getJSONArray("requests") else JSONArray()
    fun export():String=JSONObject().put("version",1).put("requests",read()).toString(2)
}

/** Count observed playback time, not seek position; gaps, pauses and track changes cannot inflate it. */
internal class OdifyListenClock {
    private var id="";private var at=0L;private var playing=false;private var elapsed=0L
    fun observe(videoId:String,now:Long,isPlaying:Boolean):Long {
        if(videoId!=id) {id=videoId;elapsed=0;playing=false;at=now}
        if(playing && isPlaying) elapsed+=(now-at).coerceIn(0,1500)
        at=now;playing=isPlaying
        return elapsed
    }
}

internal fun queueSong(item:androidx.media3.common.MediaItem):OdifySong {
    val metadata=item.mediaMetadata
    return OdifySong(item.mediaId,metadata.title?.toString().orEmpty(),metadata.artist?.toString().orEmpty(),
        metadata.albumTitle?.toString().orEmpty(),"",artworkPath=metadata.artworkUri?.path,
        sourceUri=item.localConfiguration?.uri?.toString(),durationMs=metadata.extras?.getLong("odify.duration") ?: 0L,
        contentMd5=metadata.extras?.getString("odify.contentMd5").orEmpty())
}
