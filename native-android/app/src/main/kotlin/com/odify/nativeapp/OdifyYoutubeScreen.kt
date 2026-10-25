package com.odify.nativeapp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Public playlist metadata. Audio uses locally held files or Google Drive only. */
@Composable
fun OdifyYoutubeScreen(
    localSongs: List<OdifySong>, connected: Boolean, onConnect: () -> Unit,
    onPlayLocal: (List<OdifySong>,Int) -> Unit, onClose: () -> Unit, onImported:()->Unit = {}
) {
    val context=LocalContext.current
    val repo=remember {OdifyYoutubeLibrary(context.applicationContext)}
    val scope=rememberCoroutineScope()
    val streaming=remember {OdifyStreamingLibrary(context.applicationContext)}
    val playlistStore=remember {OdifyPlaylists(context.applicationContext)}
    var playlists by remember {mutableStateOf<List<YoutubePlaylist>>(emptyList())}
    var saved by remember {mutableStateOf(repo.savedIds)}
    var selected by remember {mutableStateOf<String?>(null)}
    var videos by remember {mutableStateOf<List<YoutubeVideo>>(emptyList())}
    var entry by remember {mutableStateOf("")}
    var loading by remember {mutableStateOf(false)}
    var requestNumber by remember { mutableIntStateOf(0) }
    var status by remember {mutableStateOf("공개 플레이리스트만 조회합니다.")}
    val masterRepo=remember {OdifyMasterDatabase(context.applicationContext)}
    var master by remember {mutableStateOf<List<MasterTrack>>(emptyList())}
    var exportData by remember {mutableStateOf("")}
    LaunchedEffect(Unit) {
        master=masterRepo.loadCached()
    }
    val exporter=rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if(uri!=null && exportData.isNotBlank()) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(exportData.toByteArray(Charsets.UTF_8))
                    } ?: error("수집 목록을 저장할 수 없습니다.")
                }
            }.onSuccess {
                status="없는 곡의 수집 목록을 저장했습니다. 음악 파일은 별도 수집 후 Drive에 보관합니다."
            }.onFailure { status=it.message ?: "JSON 내보내기 실패" }
            exportData=""
        }
    }

    fun loadOwned() {
        if(!connected) {status="YouTube 계정을 먼저 연결하세요.";return}
        val request = ++requestNumber
        scope.launch {
            loading=true
            runCatching {repo.ownedPublic()}
                .onSuccess { if(request == requestNumber) {
                    playlists=it;status="공개 플레이리스트 ${it.size}개" } }
                .onFailure { if(request == requestNumber) status=it.message ?: "목록을 가져올 수 없습니다." }
            if(request == requestNumber) loading=false
        }
    }
    fun loadPlaylist(id:String) {
        if(!connected) {status="YouTube 계정 연결이 필요합니다.";return}
        val request = ++requestNumber
        selected=id
        videos=emptyList()
        scope.launch {
            loading=true
            runCatching {repo.playlist(id)}
                .onSuccess { if(request == requestNumber) {videos=it;status="${it.size}개 영상 조회"} }
                .onFailure { if(request == requestNumber) status=it.message ?: "플레이리스트 조회 실패" }
            if(request == requestNumber) loading=false
        }
    }
    LaunchedEffect(connected) {
        requestNumber++; selected=null; videos=emptyList(); playlists=emptyList(); loading=false
        if(connected) loadOwned()
    }
    val matches by key(videos, localSongs, master) {
        produceState<List<OdifySong?>>(emptyList()) {
            value = withContext(Dispatchers.Default) {
                videos.map { OdifyYouTubeMatcher.find(it,localSongs,master) }
            }
        }
    }
    val mixedQueue by produceState<List<OdifySong>>(emptyList(),videos,matches) {
        value=if(matches.size!=videos.size) emptyList() else withContext(Dispatchers.IO) {
            streaming.rememberAll(videos)
            videos.mapIndexedNotNull {i,v -> matches.getOrNull(i) ?: streaming.find("youtube:"+v.videoId)}
        }
    }
    fun back() {
        requestNumber++; loading=false
        if (selected != null) { selected=null; videos=emptyList() } else onClose()
    }
    // Back from a playlist returns to the playlist overview before closing
    // the entire YouTube source screen.
    Dialog(onDismissRequest=::back,
        properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding()
            .navigationBarsPadding().padding(10.dp),
            shape=RoundedCornerShape(22.dp),color=OdifyPalette.surface) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick=::back) {Icon(Icons.Default.ArrowBack,"뒤로")}
                    Text(if(selected==null) "YouTube 공개 플레이리스트" else "플레이리스트 곡",
                        modifier=Modifier.weight(1f),fontSize=18.sp,
                        fontWeight=FontWeight.Bold)
                }
                Text("보유한 곡은 내 음원으로, 나머지는 YouTube로 바로 재생해요. 통째로 내 플레이리스트에 저장할 수 있어요.",
                    fontSize=11.sp,color=Color.Gray,lineHeight=16.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(onClick=onConnect) {Text(
                        if(connected) "Google 계정 변경" else "YouTube 계정 연결",
                        fontSize=11.sp)}
                    OutlinedButton(onClick={loadOwned()},enabled=connected&&!loading) {
                        Icon(Icons.Default.Refresh,null,Modifier.size(16.dp))
                        Text("새로고침",fontSize=11.sp)
                    }
                }
                Spacer(Modifier.height(9.dp))
                Row(verticalAlignment=Alignment.CenterVertically) {
                    OutlinedTextField(value=entry,onValueChange={entry=it},
                        singleLine=true,modifier=Modifier.weight(1f),
                        label={Text("공개 플리 URL",fontSize=11.sp)})
                    TextButton(onClick={
                        runCatching {repo.savePublicLink(entry)}
                            .onSuccess {saved=repo.savedIds;loadPlaylist(it);entry=""}
                            .onFailure {status=it.message ?: "주소 오류"}
                    }) {Text("저장")}
                }
                Text(status,color=Color.Gray,fontSize=11.sp)
                Spacer(Modifier.height(9.dp))
                if(selected!=null) {
                    val matchedQueue=mixedQueue
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(enabled=mixedQueue.isNotEmpty(),onClick={onPlayLocal(mixedQueue,0)}) {Text("전체 재생")}
                        TextButton(enabled=mixedQueue.isNotEmpty(),onClick={onPlayLocal(mixedQueue.shuffled(),0)}) {Text("랜덤")}
                        TextButton(enabled=mixedQueue.isNotEmpty(),onClick={scope.launch {
                            runCatching {withContext(Dispatchers.IO) {playlistStore.saveMix(
                                playlists.firstOrNull {it.id==selected}?.title ?: "YouTube · ${selected.orEmpty().takeLast(6)}",mixedQueue.map {it.id})}}
                                .onSuccess {onImported();status="보관함에 ${it.songIds.size}곡을 저장했어요."}
                                .onFailure {status=it.message ?: "저장 실패"}
                        }}) {Text("플리로 저장")}
                    }
                    val unmatched=videos.filterIndexed { index, _ -> matches.getOrNull(index)==null }
                    OutlinedButton(
                        enabled=videos.isNotEmpty() && matches.size==videos.size,
                        onClick={
                            val items=JSONArray()
                            unmatched.forEach { item ->
                                items.put(JSONObject().apply {
                                    put("youtube_video_id",item.videoId)
                                    put("title",item.title)
                                    put("channel",item.channel)
                                    put("source_url","https://www.youtube.com/watch?v=${item.videoId}")
                                })
                            }
                            exportData=JSONObject().apply {
                                put("format","odify-youtube-collection-v1")
                                put("playlist_id",selected)
                                put("unmatched_count",items.length())
                                put("tracks",items)
                            }.toString(2)
                            exporter.launch("Odify_YouTube_${selected}.json")
                        }
                    ) {
                        Icon(Icons.Default.SaveAlt,null,Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("미보유 ${unmatched.size}곡 수집목록 내보내기",fontSize=11.sp)
                    }
                    Text("내 음원이 없는 곡도 바로 스트리밍할 수 있어요. 20초 이상 들으면 휴대폰 저장 요청이 만들어집니다.",
                        fontSize=10.sp,color=Color.Gray,lineHeight=16.sp)
                    Spacer(Modifier.height(7.dp))
                    LazyColumn {
                        // The same YouTube video can legitimately occur more
                        // than once in a playlist. Its ID alone is not a unique
                        // Compose list key and can crash LazyColumn.
                        itemsIndexed(videos,key={ index,v -> "${index}:${v.videoId}" }) { videoIndex,v ->
                            val held=matches.getOrNull(videoIndex)
                            Column(
                                Modifier.fillMaxWidth()
                                    .clickable(enabled=mixedQueue.size==videos.size) {
                                        onPlayLocal(mixedQueue,videoIndex)
                                    }.padding(vertical=10.dp,horizontal=5.dp)
                            ) {
                                Text(v.title,fontSize=13.sp,fontWeight=FontWeight.SemiBold)
                                Text(if(matches.size!=videos.size) "보유 음원 매칭 중"
                                    else if(held!=null) "Odify 음원 매칭 · 탭하여 재생"
                                    else "YouTube · 탭하여 바로 재생",
                                    fontSize=11.sp,
                                    color=if(held!=null) Color(0xFFBCDEC8) else Color.Gray)
                            }
                            HorizontalDivider()
                        }
                    }
                } else {
                    val list=(playlists+saved.filterNot {id->playlists.any {it.id==id}}
                        .map {YoutubePlaylist(it,"저장한 공개 플레이리스트",0,"직접 등록")})
                        .distinctBy(YoutubePlaylist::id)
                    LazyColumn {
                        items(list,key={it.id}) { p ->
                            Row(Modifier.fillMaxWidth().padding(vertical=7.dp),
                                verticalAlignment=Alignment.CenterVertically) {
                                Column(
                                    Modifier.weight(1f)
                                        .clickable {loadPlaylist(p.id)}.padding(7.dp)
                                ) {
                                    Text(p.title,fontSize=14.sp,fontWeight=FontWeight.SemiBold)
                                    Text("${p.channel} · ${p.count}개",fontSize=11.sp,color=Color.Gray)
                                }
                                IconButton(onClick={repo.toggleSaved(p.id);saved=repo.savedIds}) {
                                    Icon(if(p.id in saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                        if(p.id in saved) "저장한 목록에서 제거" else "플레이리스트 저장")
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
