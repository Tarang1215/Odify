package com.odify.nativeapp

import android.content.Context
import org.json.JSONObject

/** Reversible playback mapping; original Drive bytes/MASTER identity stay intact. */
internal class OdifyAudioOverrides(context:Context,name:String="odify_audio_overrides_v1",streamingName:String="odify_streaming_v1") {
    private val prefs=context.getSharedPreferences(name,0)
    private val library=OdifyStreamingLibrary(context.applicationContext,streamingName)
    fun choose(song:OdifySong,video:YoutubeVideo) {
        library.remember(video)
        check(prefs.edit().putString(song.id,JSONObject().put("video_id",video.videoId)
            .put("selected_at",java.time.Instant.now().toString()).toString()).commit())
    }
    fun resolve(song:OdifySong):OdifySong = prefs.getString(song.id,null)?.let {raw ->
        runCatching {library.find("youtube:"+JSONObject(raw).getString("video_id"))}.getOrNull()
    } ?: song
    fun clear(id:String){prefs.edit().remove(id).apply()}
}
