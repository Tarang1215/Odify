package com.odify.nativeapp

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * YouTube music discovery and playlist import for Odify.
 * No WebView / no YouTube embedded video player. A known video plays directly
 * through Media3 when its actual music file already exists in Odify.
 * Unmatched videos can be exported as a collection manifest for desktop ingestion.
 */
@Composable
fun OdifyYouTubeScreen(
    connected:Boolean,
    accountName:String,
    songs:List<OdifySong>,
    onPlaySong:(OdifySong)->Unit,
    onConnect:()->Unit,
    onClose:()->Unit
) {
    val context=LocalContext.current
    val library=remember { OdifyYouTubeLibrary(context.applicationContext) }
    val masterRepo=remember { OdifyMasterDatabase(context.applicationContext) }
    val scope=rememberCoroutineScope()
    var playlists by remember { mutableStateOf<List<OdifyYouTubePlaylist>>(emptyList()) }
    var saved by remember { mutableStateOf(library.savedPlaylists()) }
    var selected by remember { mutableStateOf<OdifyYouTubePlaylist?>(null) }
    var videos by remember { mutableStateOf<List<OdifyYouTubeVideo>>(emptyList()) }
    var master by remember { mutableStateOf<List<MasterTrack>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var showSaved by remember { mutableStateOf(false) }
    var exportQueue by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        master=masterRepo.loadCached()
    }
    val exporter=rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { destination ->
        if(destination != null && exportQueue.isNotBlank()) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(destination)?.use { out ->
                        out.write(exportQueue.toByteArray(Charsets.UTF_8))
                    } ?: error("저장할 JSON 파일을 열 수 없습니다.")
                }
            }.onSuccess { error="수집 목록 JSON을 저장했습니다. 음원 파일 자체는 아직 다운로드되지 않았습니다." }
                .onFailure { error=it.message ?: "목록 저장 실패" }
            exportQueue=""
        }
    }
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
        selected=p;videos=emptyList()
        if(!connected)return
        scope.launch {
            loading=true;error=""
            runCatching { library.loadVideos(p.id) }
                .onSuccess { videos=it }
                .onFailure { error=it.message ?: "플레이리스트 영상을 조회하지 못했습니다." }
            loading=false
        }
    }
    LaunchedEffect(connected) { if(connected)loadPlaylists() }
    BackHandler { if(selected!=null)selected=null else onClose() }

    Dialog(onDismissRequest=onClose,
        properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .padding(9.dp),
            shape=RoundedCornerShape(22.dp),color=Color.White
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick={
                        if(selected!=null)selected=null else onClose()
                    }) { Icon(Icons.Default.ArrowBack,"뒤로") }
                    Column(Modifier.weight(1f)) {
                        Text("YouTube 음악 플레이리스트",
                            fontWeight=FontWeight.Bold,fontSize=17.sp)
                        Text(accountName.ifBlank { "Drive와 별도 Google 계정" },
                            fontSize=11.sp,color=Color.Gray)
                    }
                    IconButton(onClick=onClose) { Icon(Icons.Default.Close,"닫기") }
                }
                if(!connected) {
                    Spacer(Modifier.height(16.dp))
                    Text("연결한 YouTube 계정의 공개 플레이리스트를 조회해 Odify 음악 라이브러리와 연결합니다.",
                        fontSize=13.sp,lineHeight=21.sp)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick=onConnect) {
                        Icon(Icons.Default.AccountCircle,null)
                        Spacer(Modifier.width(7.dp))
                        Text("YouTube용 Google 계정 선택")
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Drive와 다른 계정도 연결할 수 있습니다. YouTube 영상 광고가 있는 플레이어를 열지 않으며, 이미 보유한 음악 파일은 Odify에서 직접 재생합니다.",
                        color=Color.Gray,fontSize=11.sp,lineHeight=17.sp)
                } else if(selected!=null) {
                    val playlist=selected!!
                    val owned=remember(videos,songs,master) {
                        videos.associate { it.id to OdifyYouTubeMatcher.find(it,songs,master) }
                    }
                    val matched=owned.values.count { it != null }
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(playlist.title,fontSize=15.sp,
                                fontWeight=FontWeight.SemiBold)
                            Text("Odify에서 재생 가능: ${matched}/${videos.size}곡",
                                fontSize=11.sp,color=Color.Gray)
                        }
                        TextButton(onClick={
                            library.toggleSave(playlist)
                            saved=library.savedPlaylists()
                        }) {
                            Text(if(saved.any{it.id==playlist.id})
                                "저장 취소" else "목록 저장")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        enabled=videos.isNotEmpty(),
                        onClick={
                            val entries=JSONArray()
                            videos.filter { owned[it.id]==null }.forEach { v ->
                                entries.put(JSONObject().apply {
                                    put("youtube_video_id",v.id)
                                    put("title",v.title)
                                    put("channel",v.channel)
                                    put("source_url","https://www.youtube.com/watch?v=${v.id}")
                                })
                            }
                            exportQueue=JSONObject().apply {
                                put("format","odify-youtube-collection-v1")
                                put("playlist_id",playlist.id)
                                put("playlist_title",playlist.title)
                                put("unmatched_count",entries.length())
                                put("tracks",entries)
                            }.toString(2)
                            exporter.launch("Odify_YouTube_${playlist.id}.json")
                        }
                    ) {
                        Icon(Icons.Default.SaveAlt,null,Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("없는 ${videos.size-matched}곡 수집 목록 저장",
                            fontSize=12.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("이미 있는 음원은 즉시 재생합니다. 없는 곡은 수집 목록으로 내보내 별도 음원 확보·Drive 저장에 사용합니다.",
                        fontSize=11.sp,color=Color.Gray,lineHeight=16.sp)
                    Spacer(Modifier.height(8.dp))
                    if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
                    LazyColumn(verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        items(videos,key={it.id}) { video ->
                            val song=owned[video.id]
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable(enabled=song!=null) {
                                        if(song!=null) {
                                            onPlaySong(song)
                                            onClose()
                                        }
                                    }
                                    .padding(vertical=10.dp,horizontal=5.dp),
                                verticalAlignment=Alignment.CenterVertically
                            ) {
                                Icon(if(song!=null)Icons.Default.PlayCircle
                                    else Icons.Default.CloudDownload,null,
                                    tint=if(song!=null)Color(0xFF217D5B)
                                        else Color(0xFF92959B),
                                    modifier=Modifier.size(28.dp))
                                Spacer(Modifier.width(9.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(video.title,fontSize=13.sp,maxLines=2,
                                        overflow=TextOverflow.Ellipsis)
                                    Text(if(song!=null)
                                        "Odify 음원으로 재생 · ${song.artist}"
                                        else "미보유 · 목록에 저장해 수집",
                                        fontSize=11.sp,
                                        color=if(song!=null)
                                            Color(0xFF217D5B) else Color.Gray)
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                } else {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        FilterChip(selected=!showSaved,onClick={showSaved=false},
                            label={Text("공개 목록")})
                        Spacer(Modifier.width(7.dp))
                        FilterChip(selected=showSaved,onClick={showSaved=true},
                            label={Text("저장한 목록 (${saved.size})")})
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick={loadPlaylists()}) {
                            Icon(Icons.Default.Refresh,"다시 조회")
                        }
                    }
                    if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
                    val shown=if(showSaved)saved else playlists
                    LazyColumn {
                        items(shown,key={it.id}) { p ->
                            Row(Modifier.fillMaxWidth()
                                .clickable { openPlaylist(p) }
                                .padding(vertical=12.dp,horizontal=3.dp),
                                verticalAlignment=Alignment.CenterVertically) {
                                Icon(Icons.Default.VideoLibrary,null,
                                    modifier=Modifier.size(29.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(p.title,fontSize=13.sp,maxLines=2,
                                        overflow=TextOverflow.Ellipsis)
                                    Text("${p.channel} · ${p.count}개",
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
                        lineHeight=16.sp)
                }
            }
        }
    }
}
