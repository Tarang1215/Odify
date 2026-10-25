package com.odify.nativeapp

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.*
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

internal data class YoutubeSessionState(val video:YoutubeVideo?=null,
    val playback:YoutubeWebState=YoutubeWebState(),val following:List<OdifySong> = emptyList(),val repeat:Boolean=false,val shuffle:Boolean=false)

internal object OdifyYoutubeRuntime {
    val session=MutableStateFlow(YoutubeSessionState())
    val view=MutableStateFlow<WebView?>(null)
    var service:OdifyYoutubePlaybackService?=null
    fun play(context:Context,video:YoutubeVideo,following:List<OdifySong>) {
        session.value=YoutubeSessionState(video,following=following)
        ContextCompat.startForegroundService(context,Intent(context,OdifyYoutubePlaybackService::class.java).setAction("load"))
    }
    fun command(js:String) {service?.command(js)}
    fun stop(context:Context) {
        service?.command("odifyPause()")
        context.stopService(Intent(context,OdifyYoutubePlaybackService::class.java))
        session.value=YoutubeSessionState()
    }
}

/** The foreground service owns the WebView and public media controls across Activity recreation. */
class OdifyYoutubePlaybackService:Service() {
    private val handler=Handler(Looper.getMainLooper())
    private var browser:WebView?=null
    private var media:MediaSession?=null
    private var activeId:String?=null
    private var generation=0
    private var consumedEnd=false
    private var handingOff=false
    private var lastNotificationState=""
    private var nativeController:MediaController?=null
    private var listenClock=OdifyListenClock()
    private var collectionQueued=false
    private val wake by lazy {(getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Odify:YoutubeAudio").apply {setReferenceCounted(false)}}
    private val noisy=object:BroadcastReceiver(){override fun onReceive(c:Context?,i:Intent?){command("odifyPause()")}}
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onCreate() {
        super.onCreate();OdifyYoutubeRuntime.service=this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("odify_youtube_audio","YouTube 재생",NotificationManager.IMPORTANCE_LOW))
        media=MediaSession(this,"Odify YouTube").apply {
            setCallback(object:MediaSession.Callback(){
                override fun onPlay(){command("odifyPlay()")}
                override fun onPause(){command("odifyPause()")}
                override fun onStop(){OdifyYoutubeRuntime.stop(this@OdifyYoutubePlaybackService)}
                override fun onSeekTo(pos:Long){command("odifySeek(${pos.coerceAtLeast(0)/1000.0})")}
                override fun onSkipToPrevious(){command("odifySeek(0)")}
                override fun onSkipToNext(){handoff()}
            });isActive=true
        }
        // Chromium owns audio focus for its media element. A second service
        // focus request receives LOSS when Chromium starts, and pauses the very
        // same video. Let WebView handle interruptions; retain the noisy receiver.
        ContextCompat.registerReceiver(this,noisy,IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),ContextCompat.RECEIVER_NOT_EXPORTED)
        startForeground(8002,notification())
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        when(intent?.action) {
            "load" -> load()
            "pause" -> command("odifyPause()")
            "play" -> command("odifyPlay()")
            "next" -> handoff()
            "stop" -> OdifyYoutubeRuntime.stop(this)
        }
        return START_NOT_STICKY
    }
    @SuppressLint("SetJavaScriptEnabled")
    private fun load() {
        val video=OdifyYoutubeRuntime.session.value.video ?: return
        generation++;val request=generation;activeId=video.videoId;consumedEnd=false;handingOff=false
        listenClock=OdifyListenClock();collectionQueued=false
        OdifyStreamingLibrary(this).remember(video)
        val web=browser ?: object:WebView(applicationContext) {
            // Preserve the playback document while the owning foreground service
            // handles lock-screen/background audio. No Activity is retained.
            override fun onWindowVisibilityChanged(visibility:Int) {super.onWindowVisibilityChanged(View.VISIBLE)}
        }.apply {
            settings.javaScriptEnabled=true;settings.domStorageEnabled=true
            settings.allowFileAccess=false;settings.allowContentAccess=false
            settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.mediaPlaybackRequiresUserGesture=false
            webChromeClient=WebChromeClient()
            webViewClient=object:WebViewClient(){override fun shouldOverrideUrlLoading(v:WebView,r:WebResourceRequest)=r.isForMainFrame}
            browser=this;OdifyYoutubeRuntime.view.value=this
        }
        web.onResume()
        web.loadDataWithBaseURL("https://$packageName/",OdifyYoutubeWebPage.html(video.videoId,"https://$packageName"),"text/html","UTF-8",null)
        publish(YoutubeWebState())
        val began=SystemClock.elapsedRealtime()
        handler.removeCallbacksAndMessages(null)
        val poll=object:Runnable {
            override fun run() {
                if(request!=generation) return
                web.evaluateJavascript("window.odifySnapshot ? JSON.stringify(window.odifySnapshot()) : null") { raw ->
                    if(request!=generation) return@evaluateJavascript
                    runCatching {
                        if(raw=="null") {
                            if(SystemClock.elapsedRealtime()-began>20_000) publish(YoutubeWebState(error="YouTube 연결이 지연되고 있습니다."))
                            return@runCatching
                        }
                        val item=JSONObject(org.json.JSONTokener(raw).nextValue() as String)
                        val state=item.optInt("state",-1)
                        val error=when(val code=item.optInt("error")) {
                            0 -> ""
                            100 -> "삭제되었거나 비공개인 영상입니다."
                            101,150 -> "이 영상은 앱 내 재생을 허용하지 않습니다. 다른 검색 결과를 선택해 주세요."
                            153 -> "YouTube 플레이어의 앱 식별을 확인하지 못했습니다."
                            else -> "YouTube 재생 오류 ($code)"
                        }
                        publish(YoutubeWebState(state==1,(item.optDouble("position")*1000).toLong().coerceAtLeast(0),
                            (item.optDouble("duration")*1000).toLong().coerceAtLeast(0),item.optBoolean("ready"),error,state==0))
                        if(state==0 && !consumedEnd) {
                            consumedEnd=true
                            if(OdifyYoutubeRuntime.session.value.repeat) command("odifySeek(0);odifyPlay()") else handoff()
                        }
                        if(state!=0) consumedEnd=false
                    }
                }
                handler.postDelayed(this,500)
            }
        };handler.post(poll)
    }
    fun command(js:String) {
        browser?.evaluateJavascript(js,null)
    }
    private fun publish(state:YoutubeWebState) {
        val value=OdifyYoutubeRuntime.session.value
        if(value.video?.videoId!=activeId) return
        OdifyYoutubeRuntime.session.value=value.copy(playback=state)
        val listened=listenClock.observe(activeId.orEmpty(),SystemClock.elapsedRealtime(),state.playing)
        if(!collectionQueued && listened>=20_000 && state.duration>0) {
            val video=value.video
            if(video!=null) runCatching {
                OdifyStreamingLibrary(this).remember(video,state.duration)
                OdifyStreamingRequests(this).enqueue(video,state.duration)
                collectionQueued=true
            }.onFailure {android.util.Log.w("OdifyCollection","Could not save pending collection request",it)}
        }
        if(state.playing) {if(!wake.isHeld) wake.acquire(6*60*60*1000L)} else if(wake.isHeld) wake.release()
        media?.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP)
            .setState(if(state.error.isNotBlank()) PlaybackState.STATE_ERROR else if(state.playing) PlaybackState.STATE_PLAYING else if(!state.ready) PlaybackState.STATE_BUFFERING else PlaybackState.STATE_PAUSED,
                state.position,if(state.playing)1f else 0f,SystemClock.elapsedRealtime()).build())
        val key="${value.video?.videoId}:${state.playing}:${state.ready}:${state.error}"
        if(key!=lastNotificationState) {
            lastNotificationState=key
            val cover=value.video?.let {BitmapFactory.decodeFile(java.io.File(cacheDir,"youtube_search_${it.videoId}.jpg").absolutePath)}
            media?.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,value.video?.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST,value.video?.channel).putLong(MediaMetadata.METADATA_KEY_DURATION,state.duration)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART,cover).build())
            getSystemService(NotificationManager::class.java).notify(8002,notification())
        }
    }
    private fun action(name:String,code:Int)=PendingIntent.getService(this,code,
        Intent(this,OdifyYoutubePlaybackService::class.java).setAction(name),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun notification():Notification {
        val value=OdifyYoutubeRuntime.session.value
        val open=PendingIntent.getActivity(this,8002,Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this,"odify_youtube_audio").setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(value.video?.title ?: "Odify").setContentText(value.playback.error.ifBlank {value.video?.channel ?: "YouTube 연결 중"})
            .setContentIntent(open).setVisibility(Notification.VISIBILITY_PUBLIC).setOnlyAlertOnce(true)
            .setStyle(Notification.MediaStyle().setMediaSession(media?.sessionToken).setShowActionsInCompactView(0,1))
            .addAction(Notification.Action.Builder(if(value.playback.playing)android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if(value.playback.playing)"일시정지" else "재생",action(if(value.playback.playing)"pause" else "play",1)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next,"다음 곡",action("next",2)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,"재생 종료",action("stop",3)).build()).build()
    }
    private fun handoff() {
        if(handingOff) return
        val queue=OdifyYoutubeRuntime.session.value.following
        if(queue.isEmpty()) {command("odifyPause()");return}
        handingOff=true
        if(queue.first().id.startsWith("youtube:")) {
            command("odifyPause()")
            OdifyYoutubeRuntime.play(this,OdifyStreamingLibrary(this).video(queue.first()),queue.drop(1))
            return
        }
        val future=MediaController.Builder(this,SessionToken(this,ComponentName(this,OdifyPlaybackService::class.java))).buildAsync()
        future.addListener({runCatching {
            val player=future.get();nativeController=player
            command("odifyPause()")
            player.setMediaItems(queue.map {it.mediaItem()},0,0L);player.prepare();player.play()
            player.shuffleModeEnabled=OdifyYoutubeRuntime.session.value.shuffle
            OdifyYoutubeRuntime.session.value=YoutubeSessionState()
            handler.postDelayed({if(OdifyYoutubeRuntime.session.value.video==null)stopSelf()},2000)
        }.onFailure {handingOff=false;publish(OdifyYoutubeRuntime.session.value.playback.copy(error="Drive 다음 곡을 연결하지 못했습니다."))}},ContextCompat.getMainExecutor(this))
    }
    override fun onDestroy() {
        generation++;handler.removeCallbacksAndMessages(null)
        OdifyYoutubeRuntime.view.value=null
        browser?.let {(it.parent as? ViewGroup)?.removeView(it);it.stopLoading();it.loadUrl("about:blank");it.destroy()};browser=null
        nativeController?.release();nativeController=null
        media?.release();media=null
        if(wake.isHeld) wake.release()
        runCatching {unregisterReceiver(noisy)}
        OdifyYoutubeRuntime.service=null
        if(OdifyYoutubeRuntime.session.value.video?.videoId==activeId) OdifyYoutubeRuntime.session.value=YoutubeSessionState()
        super.onDestroy()
    }
}
