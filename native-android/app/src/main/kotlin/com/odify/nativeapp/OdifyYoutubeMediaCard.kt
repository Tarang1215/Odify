package com.odify.nativeapp

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import kotlinx.coroutines.*

/** One presentation and command bridge for Android's media card and lock screen. */
internal class OdifyYoutubeMediaCard(context:Context,private val notificationId:Int=8002,
    private val dispatch:(String,Long)->Unit) {
    private val app=context.applicationContext
    private val manager=app.getSystemService(NotificationManager::class.java)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    val session=MediaSession(app,"Odify").apply {
        setCallback(object:MediaSession.Callback() {
            override fun onPlay()=dispatch("play",0)
            override fun onPause()=dispatch("pause",0)
            override fun onStop()=dispatch("stop",0)
            override fun onSeekTo(pos:Long)=dispatch("seek",pos.coerceAtLeast(0))
            override fun onSkipToPrevious()=dispatch("previous",0)
            override fun onSkipToNext()=dispatch("next",0)
        })
    }
    private var value=YoutubeSessionState()
    private var metadataKey=""
    private var notificationKey=""
    private var artwork:Bitmap?=null
    private var artworkJob:Job?=null
    private var closed=false
    init {
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"Odify 재생",NotificationManager.IMPORTANCE_LOW))
    }
    private fun metadata(cover:Bitmap?=null):MediaMetadata {
        val song=value.presentation;val video=value.video
        return MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,song?.title ?: video?.title ?: "Odify")
            .putString(MediaMetadata.METADATA_KEY_ARTIST,song?.artist ?: video?.channel.orEmpty())
            .putString(MediaMetadata.METADATA_KEY_ALBUM,song?.album.orEmpty())
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID,song?.id ?: video?.videoId.orEmpty())
            .putLong(MediaMetadata.METADATA_KEY_DURATION,value.playback.duration.takeIf {it>0} ?: song?.durationMs ?: 0)
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART,cover).putBitmap(MediaMetadata.METADATA_KEY_ART,cover).build()
    }
    fun update(current:YoutubeSessionState) {
        if(closed || current.video==null)return
        value=current;session.isActive=true
        val state=current.playback
        val actions=PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP or
            (if(current.following.isNotEmpty())PlaybackState.ACTION_SKIP_TO_NEXT else 0L)
        session.setPlaybackState(PlaybackState.Builder().setActions(actions)
            .setState(if(state.error.isNotBlank())PlaybackState.STATE_ERROR else if(state.playing)PlaybackState.STATE_PLAYING
                else if(!state.ready)PlaybackState.STATE_BUFFERING else PlaybackState.STATE_PAUSED,
                state.position,if(state.playing)1f else 0f,SystemClock.elapsedRealtime()).build())
        val song=current.presentation
        val path=song?.artworkPath ?: if(song==null)java.io.File(app.cacheDir,"youtube_search_${current.video.videoId}.jpg").absolutePath else null
        val key=listOf(current.video.videoId,song?.id,song?.title,song?.artist,song?.album,path,state.duration,song?.durationMs).joinToString("\u0000")
        if(key!=metadataKey) {
            metadataKey=key;artworkJob?.cancel();artwork=null
            session.setMetadata(metadata())
            if(!path.isNullOrBlank())artworkJob=scope.launch {
                val loaded=withContext(Dispatchers.IO) {decodeCover(path)}
                if(!closed && metadataKey==key) {
                    artwork=loaded;session.setMetadata(metadata(loaded));manager.notify(notificationId,notification())
                }
            }
        }
        val cardKey="$key:${state.playing}:${state.ready}:${state.error}:${current.following.isNotEmpty()}"
        if(cardKey!=notificationKey){notificationKey=cardKey;manager.notify(notificationId,notification())}
    }
    private fun action(name:String,code:Int)=PendingIntent.getService(app,notificationId*10+code,
        Intent(app,OdifyYoutubePlaybackService::class.java).setAction(name),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun notification():Notification {
        val current=value;val song=current.presentation
        val open=PendingIntent.getActivity(app,notificationId,Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder=Notification.Builder(app,CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(song?.title ?: current.video?.title ?: "Odify")
            .setContentText(current.playback.error.ifBlank {song?.artist ?: current.video?.channel ?: "음악 연결 중"})
            .setContentIntent(open).setDeleteIntent(action("stop",4)).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true).setOngoing(current.playback.playing).setLargeIcon(artwork)
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0,1,2))
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous,"이전 곡",action("previous",0)).build())
            .addAction(Notification.Action.Builder(if(current.playback.playing)android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if(current.playback.playing)"일시정지" else "재생",action(if(current.playback.playing)"pause" else "play",1)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next,"다음 곡",if(current.following.isEmpty())null else action("next",2)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,"재생 종료",action("stop",3)).build())
        return builder.build()
    }
    fun dismiss() {
        if(closed)return
        artworkJob?.cancel();metadataKey="";notificationKey="";value=YoutubeSessionState();artwork=null
        session.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED,0,0f).setActions(0).build())
        session.setMetadata(null);session.isActive=false;manager.cancel(notificationId)
    }
    fun close() {
        if(closed)return
        dismiss();closed=true;scope.cancel();session.release()
    }
    companion object {
        private const val CHANNEL="odify_youtube_audio"
        private fun decodeCover(path:String):Bitmap?=runCatching {
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeFile(path,bounds)
            if(bounds.outWidth !in 1..8192 || bounds.outHeight !in 1..8192)return@runCatching null
            var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>512)sample*=2
            BitmapFactory.decodeFile(path,BitmapFactory.Options().apply {inSampleSize=sample})
        }.getOrNull()
    }
}
