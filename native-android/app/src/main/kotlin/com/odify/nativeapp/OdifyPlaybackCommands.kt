package com.odify.nativeapp

import androidx.media3.common.Player

/** Keep explicit playback intent consistent across app and widget controls. */
internal object OdifyPlaybackCommands {
    fun hasPlayIntent(playWhenReady:Boolean,state:Int,hasError:Boolean=false)=
        playWhenReady && !hasError && state!=Player.STATE_IDLE && state!=Player.STATE_ENDED
    fun hasPlayIntent(player:Player)=hasPlayIntent(player.playWhenReady,player.playbackState,player.playerError!=null)
    fun toggle(player:Player) {
        if(hasPlayIntent(player))player.pause() else resume(player)
    }
    fun resume(player:Player) {
        if(player.currentMediaItem==null)return
        if(player.playbackState==Player.STATE_ENDED)player.seekToDefaultPosition()
        if(player.playerError!=null || player.playbackState==Player.STATE_IDLE)player.prepare()
        player.play()
    }
}

/** Catalog enrichment must never silently change a chosen streaming transport. */
internal fun OdifySong.forYoutubePlayback(videoId:String)=copy(
    filePath="",sourceUri=null,playbackVideoId=videoId)

/** A mixed native queue needs a valid silent marker until its YouTube handoff. */
internal fun List<OdifySong>.queuedMediaItems(context:android.content.Context):List<androidx.media3.common.MediaItem> {
    val marker by lazy {OdifyStreamingLibrary(context).placeholder().absolutePath}
    return map {song ->
        (if(song.isYoutubeStream)song.copy(filePath=marker,sourceUri=null) else song).mediaItem()
    }
}
