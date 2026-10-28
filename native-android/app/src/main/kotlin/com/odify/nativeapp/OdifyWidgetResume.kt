package com.odify.nativeapp

import android.content.ComponentName
import android.content.Context
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

/** Only an explicit widget play may revive a saved engine. A delayed button
 * from a previous track/owner must never replace newer playback. */
internal object OdifyWidgetResume {
    suspend fun play(context:Context,owner:String,id:String):Boolean {
        if(owner !in setOf("youtube","native") || id.isBlank())return false
        fun current()=OdifyWidgetPlayback.read(context).let {it.owner==owner && it.id==id}
        if(!current())return false
        if(owner=="youtube") {
            val live=OdifyYoutubeRuntime.session.value
            if(live.video!=null) {
                if((live.presentation?.id ?: "youtube:${live.video.videoId}")!=id)return false
                OdifyYoutubeRuntime.command("odifyPlay()",context);return true
            }
            val saved=withContext(Dispatchers.IO) {OdifyYoutubeCheckpoint(context).restorePaused(owner,id)} ?: return false
            if(!current() || OdifyYoutubeRuntime.session.value.video!=null)return false
            OdifyYoutubeRuntime.session.value=saved
            OdifyYoutubeRuntime.command("odifyPlay()",context)
            return true
        }
        val saved=withContext(Dispatchers.IO) {OdifyNativeCheckpoint(context).restore(id)} ?: return false
        if(!current() || OdifyYoutubeRuntime.session.value.video!=null)return false
        val future=MediaController.Builder(context,SessionToken(context,ComponentName(context,OdifyPlaybackService::class.java))).buildAsync()
        val player=try {withContext(Dispatchers.IO){future.get(7,TimeUnit.SECONDS)}}
            catch(error:Exception){MediaController.releaseFuture(future);throw error}
        try {
            if(!current() || OdifyYoutubeRuntime.session.value.video!=null || player.mediaItemCount!=0)return false
            player.pause();player.shuffleModeEnabled=saved.shuffle;player.repeatMode=saved.repeat
            player.setMediaItems(saved.songs.queuedMediaItems(context),saved.index,saved.position)
            player.prepare();player.play()
            // Media3 owns its foreground notification; keep the initiating
            // controller until the play command has reached the service.
            delay(1000)
            return true
        } finally {player.release()}
    }
}
