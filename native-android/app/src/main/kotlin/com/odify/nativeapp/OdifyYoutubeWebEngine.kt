package com.odify.nativeapp

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

internal data class YoutubeWebState(val playing:Boolean=false,val position:Long=0,
    val duration:Long=0,val ready:Boolean=false,val error:String="",val ended:Boolean=false,val buffering:Boolean=false)

/** Attach the service-owned renderer; visible playback belongs to OdifyRoot. */
@Composable
internal fun OdifyYoutubeWebHost(modifier:Modifier=Modifier) {
    val view by OdifyYoutubeRuntime.view.collectAsState()
    view?.let { renderer -> key(renderer) {
        AndroidView(factory={
            (renderer.parent as? android.view.ViewGroup)?.removeView(renderer)
            renderer.isEnabled=false
            renderer.importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            renderer
        },onRelease={(it.parent as? android.view.ViewGroup)?.removeView(it)},modifier=modifier)
    }}
}

internal suspend fun youtubeArtwork(context:android.content.Context,id:String):String?=withContext(Dispatchers.IO) {
    require(Regex("[A-Za-z0-9_-]{11}").matches(id))
    runCatching {
        val file=File(context.cacheDir,"youtube_search_$id.jpg")
        if(!file.isFile) {
            val connection=URL("https://i.ytimg.com/vi/$id/mqdefault.jpg").openConnection() as HttpURLConnection
            val bytes=try {
                connection.connectTimeout=8000;connection.readTimeout=8000;connection.instanceFollowRedirects=false
                check(connection.responseCode==200)
                connection.inputStream.use {input ->
                    val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(true) {val count=input.read(buffer);if(count<0) break;require(out.size()+count<=400_000);out.write(buffer,0,count)}
                    out.toByteArray()
                }
            } finally {connection.disconnect()}
            file.writeBytes(bytes)
        }
        file.absolutePath
    }.getOrNull()
}

internal object OdifyYoutubeWebPage {
    fun time(value:Float):String {
        val seconds=if(value.isFinite()) value.toInt().coerceAtLeast(0) else 0
        return "${seconds/60}:${(seconds%60).toString().padStart(2,'0')}"
    }
    fun html(id:String,origin:String,resumeMillis:Long=0,autoStart:Boolean=true):String {
        require(Regex("[A-Za-z0-9_-]{11}").matches(id))
        require(Regex("https://[a-zA-Z0-9.]+").matches(origin))
        return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <meta name="referrer" content="strict-origin-when-cross-origin"><style>html,body{margin:0;width:100%;height:100%;background:#000}#player{position:fixed;inset:0;width:100%;height:100%;border:0}</style></head>
        <body><div id="player"></div><script>
        var player,ready=false,errorCode=0,wantsPlayback=$autoStart,pendingCommand=${if(autoStart)1 else 2},commandedAt=Date.now();
        function onYouTubeIframeAPIReady(){player=new YT.Player('player',{videoId:'$id',width:'100%',height:'100%',playerVars:{playsinline:1,autoplay:${if(autoStart)1 else 0},controls:1,fs:0,origin:'$origin'},events:{onReady:function(){ready=true;if(${resumeMillis.coerceAtLeast(0)/1000.0}>0)player.seekTo(${resumeMillis.coerceAtLeast(0)/1000.0},true);if(wantsPlayback)player.playVideo();else player.pauseVideo()},onStateChange:function(e){if(e.data===1){if(pendingCommand===2){player.pauseVideo();return}wantsPlayback=true;pendingCommand=0}if(e.data===2){if(pendingCommand===1&&Date.now()-commandedAt<3000)return;wantsPlayback=false;pendingCommand=0}},onError:function(e){errorCode=e.data}}})}
        function odifyPlay(){errorCode=0;wantsPlayback=true;pendingCommand=1;commandedAt=Date.now();if(ready)player.playVideo()}
        function odifyPause(){wantsPlayback=false;pendingCommand=2;commandedAt=Date.now();if(ready)player.pauseVideo()}
        function odifySeek(t){if(ready)player.seekTo(t,true)}
        window.odifySnapshot=function(){var s=ready?player.getPlayerState():-1;if(s===2&&pendingCommand===1&&Date.now()-commandedAt>=3000){wantsPlayback=false;pendingCommand=0}return{videoId:'$id',ready:ready,error:errorCode,state:s,wantsPlayback:wantsPlayback,playing:wantsPlayback&&s===1,buffering:wantsPlayback&&(s===3||s===-1||s===5||(s===2&&pendingCommand===1)),position:ready?player.getCurrentTime():0,duration:ready?player.getDuration():0}};
        </script><script src="https://www.youtube.com/iframe_api"></script></body></html>""".trimIndent()
    }
}
