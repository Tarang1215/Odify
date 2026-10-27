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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

internal data class YoutubeSessionState(val video:YoutubeVideo?=null,
    val playback:YoutubeWebState=YoutubeWebState(),val following:List<OdifySong> = emptyList(),val repeat:Boolean=false,val shuffle:Boolean=false,
    val presentation:OdifySong?=null,val entryKey:String=java.util.UUID.randomUUID().toString(),
    val followingKeys:List<String> = following.map {java.util.UUID.randomUUID().toString()},
    val previous:List<OdifySong> = emptyList()) {
    fun moved(fromKey:String,toKey:String):YoutubeSessionState {
        val from=followingKeys.indexOf(fromKey);val to=followingKeys.indexOf(toKey)
        if(from !in following.indices || to !in following.indices || from==to)return this
        return copy(following=following.moved(from,to),followingKeys=followingKeys.moved(from,to),shuffle=false)
    }
}

internal fun <T> List<T>.moved(from:Int,to:Int):List<T> =
    if(from !in indices || to !in indices || from==to)this
    else toMutableList().apply {add(to,removeAt(from))}

internal object OdifyYoutubeRuntime {
    val session=MutableStateFlow(YoutubeSessionState())
    val view=MutableStateFlow<WebView?>(null)
    var service:OdifyYoutubePlaybackService?=null
    fun play(context:Context,video:YoutubeVideo,following:List<OdifySong>,presentation:OdifySong?=null,
        followingKeys:List<String> = following.map {java.util.UUID.randomUUID().toString()},
        shuffle:Boolean=false,repeat:Boolean=false,previous:List<OdifySong> = emptyList()) {
        session.value=YoutubeSessionState(video,following=following,presentation=presentation ?: OdifyStreamingLibrary(context).find("youtube:${video.videoId}"),
            followingKeys=followingKeys,shuffle=shuffle,repeat=repeat,previous=previous)
        ContextCompat.startForegroundService(context,Intent(context,OdifyYoutubePlaybackService::class.java).setAction("load"))
    }
    fun move(fromKey:String,toKey:String) {session.update {it.moved(fromKey,toKey)}}
    fun command(js:String) {service?.command(js)}
    fun stop(context:Context) {
        OdifyYoutubeCheckpoint(context).inactive()
        service?.command("odifyPause()")
        context.stopService(Intent(context,OdifyYoutubePlaybackService::class.java))
        session.value=YoutubeSessionState()
    }
}

/** The foreground service owns the WebView and public media controls across Activity recreation. */
class OdifyYoutubePlaybackService:Service() {
    private val handler=Handler(Looper.getMainLooper())
    private var browser:WebView?=null
    private var card:OdifyYoutubeMediaCard?=null
    private val metadataScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var metadataJob:Job?=null
    private var autoplayJob:Job?=null
    private var plannedEntry=""
    private var waitingForAutoplay:String?=null
    private var pausedByCommand=false
    private val rendererRecovery=OdifyRecoveryBudget()
    private val checkpoint by lazy {OdifyYoutubeCheckpoint(this)}
    private var checkpointAt=0L
    private var checkpointEntry=""
    private var progressAt=0L
    private var progressPosition=0L
    private var foreground=true
    private var activeId:String?=null
    private var generation=0
    private var commandVersion=0
    private var consumedEnd=false
    private var handingOff=false
    private var nativeController:MediaController?=null
    private var listenClock=OdifyListenClock()
    private var collectionQueued=false
    private val wake by lazy {(getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Odify:YoutubeAudio").apply {setReferenceCounted(false)}}
    private val noisy=object:BroadcastReceiver(){override fun onReceive(c:Context?,i:Intent?){command("odifyPause()")}}
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onCreate() {
        super.onCreate();OdifyYoutubeRuntime.service=this
        card=OdifyYoutubeMediaCard(this) {action,position ->
            when(action) {
                "play" -> command("odifyPlay()")
                "pause" -> command("odifyPause()")
                "stop" -> OdifyYoutubeRuntime.stop(this)
                "seek" -> command("odifySeek(${position/1000.0})")
                "previous" -> previous()
                "next" -> handoff()
            }
        }
        OdifyWidgetPlayback.bind(this,"youtube",available={browser!=null && OdifyYoutubeRuntime.session.value.video!=null}) {action ->
            when(action) {
                "toggle" -> command(if(OdifyYoutubeRuntime.session.value.playback.let {it.playing || it.buffering})"odifyPause()" else "odifyPlay()")
                "previous" -> previous()
                "next" -> handoff()
            }
        }
        // Chromium owns audio focus for its media element. A second service
        // focus request receives LOSS when Chromium starts, and pauses the very
        // same video. Let WebView handle interruptions; retain the noisy receiver.
        ContextCompat.registerReceiver(this,noisy,IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),ContextCompat.RECEIVER_NOT_EXPORTED)
        // The first foreground notification must describe the actual song too;
        // constructing Chromium can take time before the first playback poll.
        card?.update(OdifyYoutubeRuntime.session.value)
        startForeground(8002,notification())
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(OdifyYoutubeRuntime.session.value.video==null) {
            val restored=if(intent==null || intent.action=="play")checkpoint.restore(requireActive=intent==null) else null
            if(restored!=null) {
                OdifyYoutubeRuntime.session.value=restored
                load(restored.playback.position)
                return START_STICKY
            }
            stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY
        }
        when(intent?.action) {
            "load" -> load()
            "pause" -> command("odifyPause()")
            "play" -> command("odifyPlay()")
            "next" -> handoff()
            "previous" -> previous()
            "stop" -> OdifyYoutubeRuntime.stop(this)
        }
        return START_STICKY
    }
    @SuppressLint("SetJavaScriptEnabled")
    private fun load(resumeMillis:Long=0,autoStart:Boolean=true,recovering:Boolean=false) {
        val video=OdifyYoutubeRuntime.session.value.video ?: return
        autoplayJob?.cancel();autoplayJob=null;waitingForAutoplay=null;pausedByCommand=!autoStart
        progressAt=SystemClock.elapsedRealtime();progressPosition=resumeMillis
        generation++;val request=generation;activeId=video.videoId;consumedEnd=false;handingOff=false
        if(!recovering){listenClock=OdifyListenClock();collectionQueued=false}
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
            webViewClient=object:WebViewClient(){
                override fun shouldOverrideUrlLoading(v:WebView,r:WebResourceRequest)=r.isForMainFrame
                override fun onRenderProcessGone(v:WebView,detail:RenderProcessGoneDetail)=recoverRenderer(v)
            }
            browser=this;OdifyYoutubeRuntime.view.value=this
        }
        web.onResume()
        web.loadDataWithBaseURL("https://$packageName/",OdifyYoutubeWebPage.html(video.videoId,"https://$packageName",resumeMillis,autoStart),"text/html","UTF-8",null)
        publish(YoutubeWebState())
        val entry=OdifyYoutubeRuntime.session.value.entryKey
        metadataJob?.cancel()
        metadataJob=metadataScope.launch {
            try {
                val store=OdifyStreamingLibrary(applicationContext)
                try {if(!store.verified(video.videoId))OdifyMusicCatalog(applicationContext).enrich(store,video)}
                catch(cancel:CancellationException){throw cancel}
                catch(error:Exception){android.util.Log.w("OdifyMetadata","Official metadata unavailable",error)}
                if(store.identity(video.videoId)==null)youtubeArtwork(applicationContext,video.videoId)
                OdifyYoutubeRuntime.session.update {current ->
                    if(current.entryKey==entry && (current.presentation==null || current.presentation.id=="youtube:${video.videoId}"))
                        current.copy(presentation=store.find("youtube:${video.videoId}")) else current
                }
                if(OdifyYoutubeRuntime.session.value.entryKey==entry)publish(OdifyYoutubeRuntime.session.value.playback)
            } catch(cancel:CancellationException){throw cancel}
            catch(error:Exception){android.util.Log.w("OdifyMetadata","Music metadata unavailable",error)}
        }
        val began=SystemClock.elapsedRealtime()
        handler.removeCallbacksAndMessages(null)
        val poll=object:Runnable {
            override fun run() {
                if(request!=generation) return
                val commandAtRequest=commandVersion
                web.evaluateJavascript("window.odifySnapshot ? JSON.stringify(window.odifySnapshot()) : null") { raw ->
                    if(request!=generation || commandAtRequest!=commandVersion) return@evaluateJavascript
                    runCatching {
                        if(raw=="null") {
                            if(SystemClock.elapsedRealtime()-began>20_000) publish(YoutubeWebState(error="YouTube 연결이 지연되고 있습니다."))
                            return@runCatching
                        }
                        val item=JSONObject(org.json.JSONTokener(raw).nextValue() as String)
                        // loadData is asynchronous: the old document can answer
                        // one final poll while a new video is being installed.
                        if(item.optString("videoId")!=video.videoId)return@runCatching
                        val state=item.optInt("state",-1)
                        val error=when(val code=item.optInt("error")) {
                            0 -> ""
                            100 -> "삭제되었거나 비공개인 영상입니다."
                            101,150 -> "이 영상은 앱 내 재생을 허용하지 않습니다. 다른 검색 결과를 선택해 주세요."
                            153 -> "YouTube 플레이어의 앱 식별을 확인하지 못했습니다."
                            else -> "YouTube 재생 오류 ($code)"
                        }
                        val wantsPlayback=item.optBoolean("wantsPlayback",true)
                        publish(YoutubeWebState(item.optBoolean("playing",state==1 && wantsPlayback),(item.optDouble("position")*1000).toLong().coerceAtLeast(0),
                            (item.optDouble("duration")*1000).toLong().coerceAtLeast(0),item.optBoolean("ready"),error,state==0 && wantsPlayback,
                            item.optBoolean("buffering",state==3)))
                        if(state==0 && wantsPlayback && !pausedByCommand && !consumedEnd) {
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
        if(js.contains("odifyPause()") || js.contains("odifyPlay()"))commandVersion++
        if(js.contains("odifyPause()")){pausedByCommand=true;waitingForAutoplay=null;checkpoint.inactive();checkpointAt=0L}
        if(js.contains("odifyPlay()"))pausedByCommand=false
        if(browser==null && js.contains("odifyPlay()") && OdifyYoutubeRuntime.session.value.video!=null) {
            load(OdifyYoutubeRuntime.session.value.playback.position);return
        }
        browser?.evaluateJavascript(js,null)
    }
    private fun recoverRenderer(web:WebView):Boolean {
        if(web!==browser)return true
        val current=OdifyYoutubeRuntime.session.value
        val playIntent=current.playback.playing || current.playback.buffering || !current.playback.ready
        generation++;handler.removeCallbacksAndMessages(null)
        OdifyYoutubeRuntime.view.value=null;browser=null
        (web.parent as? ViewGroup)?.removeView(web);web.destroy()
        if(current.video==null)return true
        if(!playIntent) {
            publish(current.playback.copy(playing=false,buffering=false,ready=true))
            return true
        }
        android.util.Log.w("OdifyPlayback","Playback renderer lost; attempting bounded recovery")
        if(rendererRecovery.allow(SystemClock.elapsedRealtime())) {
            publish(current.playback.copy(playing=false,buffering=true,ready=false,error=""))
            metadataScope.launch {
                delay(250)
                if(OdifyYoutubeRuntime.session.value.entryKey==current.entryKey && browser==null)try {
                    load(current.playback.position,playIntent,recovering=true)
                } catch(cancel:CancellationException){throw cancel}
                catch(error:Exception){publish(current.playback.copy(playing=false,buffering=false,error="재생 연결을 다시 확인해 주세요."))}
            }
        } else publish(current.playback.copy(playing=false,buffering=false,error="재생 연결을 복구하지 못했어요. 재생을 다시 눌러 주세요."))
        return true
    }
    private fun prepareFollowing() {
        val current=OdifyYoutubeRuntime.session.value
        if(current.video==null || current.entryKey==plannedEntry || autoplayJob?.isActive==true ||
            !OdifyAutoplayPolicy.shouldExtend(true,current.repeat,current.following.isNotEmpty()))return
        val song=current.presentation ?: OdifyStreamingLibrary(this).find("youtube:${current.video.videoId}") ?: return
        plannedEntry=current.entryKey
        autoplayJob=metadataScope.launch {
            try {
                val next=OdifyAutoplay.following(applicationContext,song,current.previous+song)
                OdifyYoutubeRuntime.session.update {latest ->
                    if(latest.entryKey==current.entryKey && latest.following.isEmpty() && !latest.repeat && next.isNotEmpty())
                        latest.copy(following=next,followingKeys=next.map {java.util.UUID.randomUUID().toString()}) else latest
                }
                val latest=OdifyYoutubeRuntime.session.value
                if(latest.entryKey==current.entryKey) {
                    card?.update(latest)
                    if((latest.playback.ended || waitingForAutoplay==latest.entryKey) && !pausedByCommand) {
                        if(latest.following.isNotEmpty())handoff()
                        else {waitingForAutoplay=null;command("odifyPause()");publish(latest.playback.copy(buffering=false,ended=false,ready=true))}
                    }
                }
            } catch(cancel:CancellationException){throw cancel}
            catch(error:Exception){android.util.Log.w("OdifyAutoplay","Following queue unavailable",error)}
        }
    }
    private fun publish(state:YoutubeWebState) {
        val value=OdifyYoutubeRuntime.session.value
        if(value.video?.videoId!=activeId) return
        OdifyYoutubeRuntime.session.value=value.copy(playback=state)
        if(state.playing)pausedByCommand=false
        if(state.ready && !state.playing && !state.buffering && !state.ended){
            if(!pausedByCommand)checkpoint.inactive()
            pausedByCommand=true;waitingForAutoplay=null;checkpointAt=0L
        }
        if(state.error.isNotBlank() && checkpointAt!=0L){checkpoint.inactive();checkpointAt=0L}
        prepareFollowing()
        val listened=listenClock.observe(activeId.orEmpty(),SystemClock.elapsedRealtime(),state.playing)
        if(!collectionQueued && listened>=20_000 && state.duration>0) {
            val video=value.video
            if(video!=null) runCatching {
                OdifyStreamingLibrary(this).remember(video,state.duration)
                OdifyStreamingRequests(this).enqueue(video,state.duration)
                OdifyPhoneDownloads.schedule(this)
                collectionQueued=true
            }.onFailure {android.util.Log.w("OdifyCollection","Could not save pending collection request",it)}
        }
        val needsForeground=OdifyPlaybackContinuity.foreground(state) || state.ended &&
            (value.following.isNotEmpty() || autoplayJob?.isActive==true)
        if(needsForeground) {if(!wake.isHeld) wake.acquire(6*60*60*1000L)} else if(wake.isHeld) wake.release()
        card?.update(OdifyYoutubeRuntime.session.value)
        val current=OdifyYoutubeRuntime.session.value
        val song=current.presentation
        OdifyWidgetPlayback.publish(this,OdifyWidgetTrack("youtube",song?.id ?: "youtube:${current.video?.videoId}",
            song?.title ?: current.video?.title ?: "Odify",song?.artist ?: current.video?.channel.orEmpty(),
            song?.artworkPath.orEmpty(),state.playing || state.buffering,previous=true,next=current.following.isNotEmpty()))
        if(needsForeground && !foreground){startForeground(8002,notification());foreground=true}
        else if(!needsForeground && foreground){stopForeground(STOP_FOREGROUND_DETACH);foreground=false}
        if(needsForeground && !pausedByCommand) {
            val now=SystemClock.elapsedRealtime()
            if(current.entryKey!=checkpointEntry || now-checkpointAt>=5000) {
                checkpointAt=now;checkpointEntry=current.entryKey
                val token=checkpoint.token();val snapshot=OdifyYoutubeRuntime.session.value
                metadataScope.launch(Dispatchers.IO) {runCatching {checkpoint.write(snapshot,token)}
                    .onFailure {android.util.Log.w("OdifyPlayback","Resume checkpoint unavailable",it)}}
            }
        }
        val now=SystemClock.elapsedRealtime()
        if(state.position>progressPosition+200 || state.position<progressPosition ||
            state.ready && !state.playing && !state.buffering && !state.ended) {
            progressAt=now;progressPosition=state.position
        }
        if(OdifyPlaybackContinuity.stalled(state,pausedByCommand,now-progressAt)) {
            val network=getSystemService(android.net.ConnectivityManager::class.java)
            val online=network.getNetworkCapabilities(network.activeNetwork)
                ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true
            if(online && rendererRecovery.allow(now)) {
                progressAt=now
                val entry=current.entryKey;val position=state.position
                handler.post {if(OdifyYoutubeRuntime.session.value.entryKey==entry && !pausedByCommand)try {
                    load(position,autoStart=true,recovering=true)
                } catch(error:Exception){publish(state.copy(playing=false,buffering=false,error="재생 연결을 다시 확인해 주세요."))}}
            }
        }
    }
    private fun notification():Notification=card!!.notification()
    fun previous() {
        val current=OdifyYoutubeRuntime.session.value
        val previous=current.previous.lastOrNull()
        if(current.playback.position>3000 || previous==null) {command("odifySeek(0)");return}
        if(!previous.isYoutubeStream) {
            if(!handingOff){handingOff=true;handoffNative(current,true)}
            return
        }
        val present=current.presentation ?: current.video?.let {OdifyStreamingLibrary(this).find("youtube:${it.videoId}")}
        command("odifyPause()")
        OdifyYoutubeRuntime.play(this,OdifyStreamingLibrary(this).video(previous),listOfNotNull(present)+current.following,
            presentation=previous,previous=current.previous.dropLast(1),shuffle=current.shuffle,repeat=current.repeat)
    }
    fun next()=handoff()
    private fun handoff() {
        if(handingOff) return
        val current=OdifyYoutubeRuntime.session.value
        val queue=current.following
        if(queue.isEmpty()) {
            prepareFollowing()
            if(autoplayJob?.isActive==true) {
                waitingForAutoplay=current.entryKey
                publish(current.playback.copy(playing=false,buffering=true,ended=true))
            } else command("odifyPause()")
            return
        }
        handingOff=true
        if(queue.first().isYoutubeStream) {
            command("odifyPause()")
            OdifyYoutubeRuntime.play(this,OdifyStreamingLibrary(this).video(queue.first()),queue.drop(1),
                presentation=queue.first(),followingKeys=current.followingKeys.drop(1),shuffle=current.shuffle,repeat=current.repeat,
                previous=(current.previous+listOfNotNull(current.presentation)).takeLast(100))
            return
        }
        handoffNative(current,false)
    }
    private fun handoffNative(current:YoutubeSessionState,back:Boolean) {
        val future=MediaController.Builder(this,SessionToken(this,ComponentName(this,OdifyPlaybackService::class.java))).buildAsync()
        future.addListener({runCatching {
            val player=future.get()
            command("odifyPause()")
            // Resolve the current queue after the asynchronous controller connects.
            val latest=OdifyYoutubeRuntime.session.value
            if(latest.entryKey!=current.entryKey){player.release();return@runCatching}
            nativeController?.release();nativeController=player
            val present=listOfNotNull(latest.presentation ?: latest.video?.let {OdifyStreamingLibrary(this).find("youtube:${it.videoId}")})
            val queue=latest.previous+present+latest.following
            val index=if(back)latest.previous.lastIndex else latest.previous.size+present.size
            if(index !in queue.indices){handingOff=false;return@runCatching}
            player.shuffleModeEnabled=latest.shuffle
            player.repeatMode=if(latest.repeat)androidx.media3.common.Player.REPEAT_MODE_ONE else androidx.media3.common.Player.REPEAT_MODE_OFF
            player.setMediaItems(queue.map {it.mediaItem()},index,0L);player.prepare();player.play()
            card?.dismiss();stopForeground(STOP_FOREGROUND_REMOVE);foreground=false
            OdifyYoutubeRuntime.session.value=YoutubeSessionState()
            handler.postDelayed({if(OdifyYoutubeRuntime.session.value.video==null)stopSelf()},2000)
        }.onFailure {handingOff=false;publish(OdifyYoutubeRuntime.session.value.playback.copy(error="Drive 다음 곡을 연결하지 못했습니다."))}},ContextCompat.getMainExecutor(this))
    }
    override fun onDestroy() {
        if(OdifyYoutubeRuntime.session.value.video==null || pausedByCommand || OdifyYoutubeRuntime.session.value.playback.error.isNotBlank())checkpoint.inactive()
        generation++;handler.removeCallbacksAndMessages(null)
        OdifyYoutubeRuntime.view.value=null
        browser?.let {(it.parent as? ViewGroup)?.removeView(it);it.stopLoading();it.loadUrl("about:blank");it.destroy()};browser=null
        nativeController?.release();nativeController=null
        metadataScope.cancel();card?.close();card=null
        OdifyWidgetPlayback.unbind(this,"youtube")
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(8002)
        if(wake.isHeld) wake.release()
        runCatching {unregisterReceiver(noisy)}
        OdifyYoutubeRuntime.service=null
        if(OdifyYoutubeRuntime.session.value.video?.videoId==activeId) OdifyYoutubeRuntime.session.value=YoutubeSessionState()
        super.onDestroy()
    }
}
