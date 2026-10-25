package com.odify.nativeapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Stable video IDs belong to Odify playlists; they never require a YouTube playlist write scope. */
internal class OdifyStreamingLibrary(private val context:Context, name:String="odify_streaming_v1") {
    companion object {val version=kotlinx.coroutines.flow.MutableStateFlow(0L)}
    private val prefs=context.getSharedPreferences(name,0)
    @Synchronized fun remember(video:YoutubeVideo,duration:Long=0) {
        require(Regex("[A-Za-z0-9_-]{11}").matches(video.videoId))
        val old=prefs.getString(video.videoId,null)?.let {JSONObject(it)}
        if(old!=null && old.optString("title")==video.title && old.optString("artist")==video.channel && duration<=old.optLong("duration"))return
        prefs.edit().putString(video.videoId,(old ?: JSONObject()).put("id",video.videoId).put("title",video.title)
            .put("artist",video.channel).put("duration",maxOf(duration,old?.optLong("duration") ?: 0)).toString()).apply()
        version.value++
    }
    @Synchronized fun rememberAll(videos:List<YoutubeVideo>) {
        val editor=prefs.edit()
        var changed=false
        videos.filter {Regex("[A-Za-z0-9_-]{11}").matches(it.videoId)}.forEach {video ->
            val old=prefs.getString(video.videoId,null)?.let {JSONObject(it)}
            if(old!=null && old.optString("title")==video.title && old.optString("artist")==video.channel)return@forEach
            changed=true
            editor.putString(video.videoId,(old ?: JSONObject()).put("id",video.videoId).put("title",video.title)
                .put("artist",video.channel).put("duration",old?.optLong("duration") ?: 0).toString())
        }
        if(changed){editor.apply();version.value++}
    }
    private fun savedRows():Map<String,JSONObject> {
        val rows=OdifyStreamingRequests(context).read()
        return (0 until rows.length()).map {rows.getJSONObject(it)}.filter {it.optString("status")=="SAVED_PHONE"}.associateBy {it.optString("video_id")}
    }
    private fun decode(videoId:String,raw:String,record:JSONObject?):OdifySong?=runCatching {
        val data=JSONObject(raw)
        val local=record?.optString("local_path")?.takeIf {it.isNotBlank()}?.let {File(it)}?.takeIf {it.isFile}
        val charts=data.optJSONArray("charts")
        OdifySong("youtube:"+videoId,data.getString("title"),data.optString("artist"),"YouTube",(local ?: placeholder()).absolutePath,
            File(context.cacheDir,"youtube_search_$videoId.jpg").takeIf {it.isFile}?.absolutePath,
            durationMs=maxOf(data.optLong("duration"),record?.optLong("duration_ms") ?: 0),
            chartAliases=(0 until (charts?.length() ?: 0)).map {charts!!.getString(it)})
    }.getOrNull()
    fun find(id:String):OdifySong? {
        if(!id.startsWith("youtube:"))return null
        val videoId=id.removePrefix("youtube:")
        return prefs.getString(videoId,null)?.let {decode(videoId,it,savedRows()[videoId])}
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
    @Synchronized fun associateChart(videoId:String,chartId:String) {
        val data=prefs.getString(videoId,null)?.let {JSONObject(it)} ?: return
        val charts=data.optJSONArray("charts") ?: JSONArray()
        if((0 until charts.length()).any {charts.getString(it)==chartId})return
        charts.put(chartId)
        prefs.edit().putString(videoId,data.put("charts",charts).toString()).apply();version.value++
    }
    fun downloads():List<OdifySong> = prefs.all.keys.mapNotNull {find("youtube:"+it)}.filterNot {it.isYoutubeStream}
    fun all():List<OdifySong> {
        val saved=savedRows()
        return prefs.all.entries.filter {Regex("[A-Za-z0-9_-]{11}").matches(it.key)}
            .mapNotNull {(id,raw)->(raw as? String)?.let {decode(id,it,saved[id])}}
    }
}

/** A portable PC collection outbox. Uploads remain deferred until explicitly resumed. */
internal class OdifyStreamingRequests(private val context:Context,fileName:String="youtube_collection_requests.json") {
    companion object {private val lock=Any()}
    private val file=File(context.filesDir,fileName)
    fun enqueue(video:YoutubeVideo,duration:Long,reason:String="listened_20s",retry:Boolean=false)=synchronized(lock) {
        require(Regex("[A-Za-z0-9_-]{11}").matches(video.videoId))
        val rows=read()
        val old=(0 until rows.length()).map {rows.getJSONObject(it)}.firstOrNull {it.optString("video_id")==video.videoId}
        if(old!=null) {
            if(duration>0) old.put("duration_ms",duration)
            if(retry && old.optString("status") !in setOf("SAVED_PHONE","DOWNLOADING_PHONE","SKIPPED_AGE"))
                old.put("status","QUEUED_PHONE").put("error","").put("progress",0)
            save(rows);return@synchronized
        }
        rows.put(JSONObject().put("video_id",video.videoId).put("title",video.title).put("artist",video.channel)
            .put("video_url","https://www.youtube.com/watch?v=${video.videoId}").put("duration_ms",duration)
            .put("requested_at",java.time.Instant.now().toString()).put("status","QUEUED_PHONE")
            .put("drive_upload",false).put("reason",reason).put("progress",0))
        save(rows)
    }
    fun retryFailed()=synchronized(lock) {
        val rows=read()
        for(i in 0 until rows.length()) {
            val row=rows.getJSONObject(i)
            if(row.optString("status") in setOf("HELD_PHONE","HELD_SOURCE","HELD_SPACE"))
                row.put("status","QUEUED_PHONE").put("error","").put("progress",0)
        }
        save(rows)
    }
    private fun save(rows:JSONArray) {
        val temp=File(file.parentFile,file.name+".tmp")
        temp.writeText(JSONObject().put("version",1).put("requests",rows).toString(2))
        check(temp.renameTo(file)) {"보관 요청을 저장하지 못했습니다."}
    }
    fun update(id:String,edit:(JSONObject)->Unit)=synchronized(lock) {
        val rows=read()
        for(i in 0 until rows.length()) if(rows.getJSONObject(i).optString("video_id")==id) edit(rows.getJSONObject(i))
        save(rows)
    }
    fun read():JSONArray=synchronized(lock) {if(file.isFile) JSONObject(file.readText()).getJSONArray("requests") else JSONArray()}
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
