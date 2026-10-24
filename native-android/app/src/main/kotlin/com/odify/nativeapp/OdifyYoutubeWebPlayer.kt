package com.odify.nativeapp

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import org.json.JSONObject

/** Visible IFrame player. No stream extraction or sharing another app's cookies. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun OdifyYoutubeWebPlayer(queue: List<YoutubeVideo>, initialIndex: Int,
    onDismiss: () -> Unit, onPlaybackStarted: () -> Unit) {
    if (queue.isEmpty()) return
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var index by remember(queue) { mutableIntStateOf(initialIndex.coerceIn(queue.indices)) }
    val video = queue[index]
    var web by remember { mutableStateOf<WebView?>(null) }
    var ready by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableFloatStateOf(0f) }
    var duration by remember { mutableFloatStateOf(0f) }
    var seeking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var ended by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    var focusGranted by remember { mutableStateOf(false) }
    val startedCallback by rememberUpdatedState(onPlaybackStarted)
    fun command(js: String) { web?.evaluateJavascript(js, null) }
    fun next() {
        index = if (shuffle && queue.size > 1) queue.indices.filter { it != index }.random()
            else (index + 1) % queue.size
    }
    DisposableEffect(reload) {
        val audio=context.getSystemService(AudioManager::class.java)
        val request=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { focus ->
                if(focus<0) web?.post { command("odifyPause()");playing=false }
            }.build()
        focusGranted=audio.requestAudioFocus(request)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if(!focusGranted) error="다른 오디오가 사용 중입니다. 잠시 후 다시 시도해 주세요."
        onDispose {audio.abandonAudioFocusRequest(request)}
    }
    LaunchedEffect(video.videoId, reload, web, focusGranted) {
        if(!focusGranted) return@LaunchedEffect
        val view = web ?: return@LaunchedEffect
        generation++
        val requestGeneration=generation
        val began=System.currentTimeMillis()
        ready=false;playing=false;error="";position=0f;duration=0f;ended=false
        view.loadDataWithBaseURL("https://${context.packageName}/", OdifyYoutubeWebPage.html(video.videoId,
            "https://${context.packageName}"), "text/html", "UTF-8", null)
        while (true) {
            delay(500)
            if(!ready && System.currentTimeMillis()-began>20_000)
                error="플레이어 연결이 지연되고 있습니다. 네트워크를 확인하고 다시 시도해 주세요."
            view.evaluateJavascript("window.odifySnapshot ? JSON.stringify(window.odifySnapshot()) : null") { encoded ->
                if(requestGeneration!=generation) return@evaluateJavascript
                runCatching {
                    if (encoded == "null") return@runCatching
                    val data = JSONObject(org.json.JSONTokener(encoded).nextValue() as String)
                    ready=data.optBoolean("ready")
                    val state=data.optInt("state",-1)
                    if(state==1 && !playing) startedCallback()
                    playing=state==1
                    if (!seeking) position=data.optDouble("position",0.0).toFloat().coerceAtLeast(0f)
                    duration=data.optDouble("duration",0.0).toFloat().coerceAtLeast(0f)
                    val code=data.optInt("error",0)
                    error=when(code) {
                        0 -> if(!ready && System.currentTimeMillis()-began>20_000) "플레이어 연결이 지연되고 있습니다. 네트워크를 확인하고 다시 시도해 주세요." else ""
                        100 -> "삭제되었거나 비공개인 영상입니다."
                        101,150 -> "이 영상은 앱 내 재생을 허용하지 않습니다. 다른 검색 결과를 선택해 주세요."
                        153 -> "YouTube 플레이어의 앱 식별을 확인하지 못했습니다."
                        else -> "YouTube 재생 오류 ($code) · 다른 영상이나 다시 시도를 선택해 주세요."
                    }
                    if (state==0 && !ended) { ended=true; if (repeat) command("odifyPlay()") else if(index<queue.lastIndex || shuffle) next() }
                    if (state!=0) ended=false
                }
            }
        }
    }
    DisposableEffect(owner, web) {
        val observer=LifecycleEventObserver { _, event ->
            if(event==Lifecycle.Event.ON_PAUSE) { command("odifyPause()");web?.onPause();playing=false }
            if(event==Lifecycle.Event.ON_RESUME) web?.onResume()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Dialog(onDismissRequest=onDismiss, properties=DialogProperties(usePlatformDefaultWidth=false,
        decorFitsSystemWindows=false)) {
        Surface(Modifier.fillMaxSize(),color=OdifyPalette.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal=20.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick=onDismiss) { Icon(Icons.Default.Close,"플레이어 닫기") }
                    Text("YouTube",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                    IconButton(onClick={ command("odifyPause()");OdifyExternalYoutube.openVideo(context,video.videoId) }) {
                        Icon(Icons.Default.OpenInNew,"YouTube 앱에서 열기")
                    }
                }
                Spacer(Modifier.height(24.dp))
                AndroidView(factory={ ctx -> WebView(ctx).apply {
                    setBackgroundColor(android.graphics.Color.BLACK)
                    settings.javaScriptEnabled=true
                    settings.domStorageEnabled=true
                    settings.allowFileAccess=false;settings.allowContentAccess=false
                    settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    // The user selected the result; native controls remain available if autoplay is blocked.
                    settings.mediaPlaybackRequiresUserGesture=false
                    webViewClient=object:WebViewClient() {
                        override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean =
                            request.isForMainFrame
                    }
                    web=this
                } },onRelease={ it.stopLoading();it.loadUrl("about:blank");it.destroy();web=null },
                    modifier=Modifier.fillMaxWidth().aspectRatio(16f/9f).heightIn(min=200.dp)
                        .background(OdifyPalette.surface))
                Spacer(Modifier.height(28.dp))
                Text(video.title,style=MaterialTheme.typography.headlineSmall,color=OdifyPalette.text)
                Spacer(Modifier.height(8.dp))
                Text(video.channel,style=MaterialTheme.typography.bodyMedium,color=OdifyPalette.muted)
                Spacer(Modifier.height(20.dp))
                if(error.isNotBlank()) {
                    Text(error,color=OdifyPalette.muted)
                    TextButton(onClick={reload++}) { Text("다시 시도") }
                } else if(!ready) { LinearProgressIndicator(Modifier.fillMaxWidth());Text("플레이어 연결 중",color=OdifyPalette.muted) }
                Slider(value=position.coerceIn(0f,duration.coerceAtLeast(1f)),
                    onValueChange={seeking=true;position=it},onValueChangeFinished={command("odifySeek($position)");seeking=false},
                    valueRange=0f..duration.coerceAtLeast(1f),enabled=ready && duration>0)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text(OdifyYoutubeWebPage.time(position),color=OdifyPalette.muted)
                    Text(OdifyYoutubeWebPage.time(duration),color=OdifyPalette.muted)
                }
                Row(Modifier.fillMaxWidth().padding(vertical=20.dp),horizontalArrangement=Arrangement.SpaceEvenly,
                    verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick={shuffle=!shuffle}) {Icon(Icons.Default.Shuffle,"랜덤 재생",tint=if(shuffle) OdifyPalette.text else OdifyPalette.muted)}
                    IconButton(onClick={if(position>3) command("odifySeek(0)") else index=(index-1).coerceAtLeast(0)}) {Icon(Icons.Default.SkipPrevious,"이전 곡")}
                    FilledIconButton(onClick={command(if(playing) "odifyPause()" else "odifyPlay()")},
                        enabled=ready,modifier=Modifier.size(64.dp)) {
                        Icon(if(playing) Icons.Default.Pause else Icons.Default.PlayArrow,if(playing) "일시정지" else "재생",Modifier.size(32.dp))
                    }
                    IconButton(onClick={next()},enabled=queue.size>1) {Icon(Icons.Default.SkipNext,"다음 곡")}
                    IconButton(onClick={repeat=!repeat}) {Icon(Icons.Default.RepeatOne,"한 곡 반복",tint=if(repeat) OdifyPalette.text else OdifyPalette.muted)}
                }
                Text("${index+1} / ${queue.size}",color=OdifyPalette.muted)
            }
        }
    }
}

internal object OdifyYoutubeWebPage {
    fun time(value:Float):String {
        val seconds=if(value.isFinite()) value.toInt().coerceAtLeast(0) else 0
        return "${seconds/60}:${(seconds%60).toString().padStart(2,'0')}"
    }
    fun html(id:String,origin:String):String {
        require(Regex("[A-Za-z0-9_-]{11}").matches(id))
        require(Regex("https://[a-zA-Z0-9.]+").matches(origin))
        return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <meta name="referrer" content="strict-origin-when-cross-origin"><style>html,body{margin:0;width:100%;height:100%;background:#000}#player{width:100%;height:100%}</style></head>
        <body><div id="player"></div><script>
        var player,ready=false,errorCode=0;
        function onYouTubeIframeAPIReady(){player=new YT.Player('player',{videoId:'$id',width:'100%',height:'100%',playerVars:{playsinline:1,autoplay:1,controls:1,fs:0,origin:'$origin'},events:{onReady:function(){ready=true;player.playVideo()},onError:function(e){errorCode=e.data}}})}
        function odifyPlay(){if(ready)player.playVideo()} function odifyPause(){if(ready)player.pauseVideo()} function odifySeek(t){if(ready)player.seekTo(t,true)}
        window.odifySnapshot=function(){return{ready:ready,error:errorCode,state:ready?player.getPlayerState():-1,position:ready?player.getCurrentTime():0,duration:ready?player.getDuration():0}};
        </script><script src="https://www.youtube.com/iframe_api"></script></body></html>""".trimIndent()
    }
}
