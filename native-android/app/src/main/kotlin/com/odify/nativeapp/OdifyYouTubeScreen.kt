package com.odify.nativeapp

import android.content.Intent
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/**
 * Read-only public YouTube playlist browser. Playback uses the actual YouTube
 * embed, with its own controls and advertising; not a Media3 audio stream.
 */
@Composable
fun OdifyYouTubeScreen(
    connected:Boolean,
    accountName:String,
    onConnect:()->Unit,
    onClose:()->Unit
) {
    val context=LocalContext.current
    val library=remember { OdifyYouTubeLibrary(context.applicationContext) }
    val scope=rememberCoroutineScope()
    var playlists by remember { mutableStateOf<List<OdifyYouTubePlaylist>>(emptyList()) }
    var saved by remember { mutableStateOf(library.savedPlaylists()) }
    var selected by remember { mutableStateOf<OdifyYouTubePlaylist?>(null) }
    var videos by remember { mutableStateOf<List<OdifyYouTubeVideo>>(emptyList()) }
    var current by remember { mutableStateOf<OdifyYouTubeVideo?>(null) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var showSaved by remember { mutableStateOf(false) }

    fun loadPlaylists() {
        if(!connected || loading)return
        scope.launch {
            loading=true;error=""
            runCatching { library.loadPublicPlaylists() }
                .onSuccess { playlists=it }
                .onFailure { error=it.message ?: "YouTube 플레이리스트 조회 오류" }
            loading=false
        }
    }
    fun openPlaylist(p:OdifyYouTubePlaylist) {
        selected=p;current=null;videos=emptyList()
        if(!connected)return
        scope.launch {
            loading=true;error=""
            runCatching { library.loadVideos(p.id) }
                .onSuccess { videos=it }
                .onFailure { error=it.message ?: "플레이리스트 영상을 조회하지 못했습니다." }
            loading=false
        }
    }
    LaunchedEffect(connected) {
        if(connected) loadPlaylists()
    }
    BackHandler {
        when {
            current!=null -> current=null
            selected!=null -> selected=null
            else -> onClose()
        }
    }

    Dialog(onDismissRequest=onClose,properties=DialogProperties(
        usePlatformDefaultWidth=false
    )) {
        Surface(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .padding(9.dp),
            shape=RoundedCornerShape(22.dp),color=Color.White
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick={
                        when {
                            current!=null -> current=null
                            selected!=null -> selected=null
                            else -> onClose()
                        }
                    }) { Icon(Icons.Default.ArrowBack,"뒤로") }
                    Column(Modifier.weight(1f)) {
                        Text("YouTube 공개 플레이리스트",fontWeight=FontWeight.Bold,
                            fontSize=17.sp)
                        Text(accountName.ifBlank { "Drive와 별도 Google 계정" },
                            fontSize=11.sp,color=Color.Gray)
                    }
                    IconButton(onClick=onClose) { Icon(Icons.Default.Close,"닫기") }
                }
                if(!connected) {
                    Spacer(Modifier.height(18.dp))
                    Text("Google 계정을 별도로 선택하면 해당 YouTube 계정의 공개 플레이리스트를 조회할 수 있습니다.",
                        fontSize=13.sp,lineHeight=21.sp)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick=onConnect) {
                        Icon(Icons.Default.AccountCircle,null)
                        Spacer(Modifier.width(8.dp))
                        Text("YouTube용 Google 계정 선택")
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Google Drive 음악 계정과 동일할 필요는 없습니다. YouTube 조회 권한만 별도로 요청합니다.",
                        color=Color.Gray,fontSize=11.sp)
                } else if(current!=null) {
                    val clip=current!!
                    Text(clip.title,fontWeight=FontWeight.SemiBold,fontSize=15.sp,
                        maxLines=2,overflow=TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    // A YouTube embed with official UI. It never extracts audio.
                    key(clip.id) {
                        val webView=remember(clip.id) {
                            WebView(context).apply {
                                settings.javaScriptEnabled=true
                                settings.domStorageEnabled=true
                                settings.mediaPlaybackRequiresUserGesture=true
                                webChromeClient=WebChromeClient()
                                loadUrl("https://www.youtube.com/embed/${clip.id}?playsinline=1")
                            }
                        }
                        DisposableEffect(webView) {
                            onDispose { webView.stopLoading();webView.destroy() }
                        }
                        AndroidView(factory={webView},
                            modifier=Modifier.fillMaxWidth().height(260.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick={
                        val intent=Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://www.youtube.com/watch?v=${clip.id}"))
                        context.startActivity(intent)
                    }) { Text("YouTube 앱에서 열기") }
                    Spacer(Modifier.height(9.dp))
                    Text("이 영상은 YouTube 공식 플레이어로 재생됩니다. Drive 음악처럼 별도의 백그라운드 음원 스트림으로 전환하지 않습니다.",
                        fontSize=11.sp,color=Color.Gray,lineHeight=16.sp)
                } else if(selected!=null) {
                    val p=selected!!
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.title,fontSize=16.sp,fontWeight=FontWeight.SemiBold)
                            Text("${videos.size}개 영상 조회",fontSize=11.sp,color=Color.Gray)
                        }
                        TextButton(onClick={
                            library.toggleSave(p)
                            saved=library.savedPlaylists()
                        }) {
                            Text(if(saved.any{it.id==p.id})"저장 취소" else "내 목록에 저장")
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    if(loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    LazyColumn(verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        items(videos,key={it.id}) { video ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { current=video }
                                    .padding(vertical=11.dp,horizontal=5.dp),
                                verticalAlignment=Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.PlayCircle,null,
                                    modifier=Modifier.size(32.dp),tint=Color(0xFF202126))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(video.title,fontSize=13.sp,
                                        maxLines=2,overflow=TextOverflow.Ellipsis)
                                    Text(video.channel,fontSize=11.sp,color=Color.Gray,
                                        maxLines=1)
                                }
                                Icon(Icons.Default.ChevronRight,null)
                            }
                            HorizontalDivider()
                        }
                    }
                } else {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        FilterChip(selected=!showSaved,onClick={showSaved=false},
                            label={Text("공개 플레이리스트")})
                        Spacer(Modifier.width(7.dp))
                        FilterChip(selected=showSaved,onClick={showSaved=true},
                            label={Text("저장한 목록 (${saved.size})")})
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick={loadPlaylists()}) {
                            Icon(Icons.Default.Refresh,"다시 조회")
                        }
                    }
                    if(loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    val shown=if(showSaved) saved else playlists
                    LazyColumn {
                        items(shown,key={it.id}) { playlist ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { openPlaylist(playlist) }
                                    .padding(vertical=12.dp,horizontal=3.dp),
                                verticalAlignment=Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.VideoLibrary,null,
                                    tint=Color(0xFF25272B),modifier=Modifier.size(29.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(playlist.title,fontSize=13.sp,
                                        maxLines=2,overflow=TextOverflow.Ellipsis)
                                    Text("${playlist.channel} · ${playlist.count}개",
                                        fontSize=11.sp,color=Color.Gray)
                                }
                                Icon(Icons.Default.ChevronRight,null)
                            }
                            HorizontalDivider()
                        }
                    }
                }
                if(error.isNotBlank()) {
                    Spacer(Modifier.height(7.dp))
                    Text(error,fontSize=11.sp,color=Color(0xFFAF283C),
                        lineHeight=17.sp)
                }
            }
        }
    }
}
