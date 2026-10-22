package com.odify.nativeapp

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/** Public playlist metadata. Audio uses locally held files or Google Drive only. */
@Composable
fun OdifyYoutubeScreen(
    localSongs: List<OdifySong>, connected: Boolean, onConnect: () -> Unit,
    onPlayLocal: (OdifySong) -> Unit, onClose: () -> Unit
) {
    val context=LocalContext.current
    val repo=remember {OdifyYoutubeLibrary(context.applicationContext)}
    val scope=rememberCoroutineScope()
    var playlists by remember {mutableStateOf<List<YoutubePlaylist>>(emptyList())}
    var saved by remember {mutableStateOf(repo.savedIds)}
    var selected by remember {mutableStateOf<String?>(null)}
    var videos by remember {mutableStateOf<List<YoutubeVideo>>(emptyList())}
    var entry by remember {mutableStateOf("")}
    var loading by remember {mutableStateOf(false)}
    var status by remember {mutableStateOf("공개 플레이리스트만 조회합니다.")}

    fun loadOwned() {
        if(!connected) {status="YouTube 계정을 먼저 연결하세요.";return}
        scope.launch {
            loading=true
            runCatching {repo.ownedPublic()}
                .onSuccess {playlists=it;status="공개 플레이리스트 ${it.size}개"}
                .onFailure {status=it.message ?: "목록을 가져올 수 없습니다."}
            loading=false
        }
    }
    fun loadPlaylist(id:String) {
        if(!connected) {status="YouTube 계정 연결이 필요합니다.";return}
        selected=id
        videos=emptyList()
        scope.launch {
            loading=true
            runCatching {repo.playlist(id)}
                .onSuccess {videos=it;status="${it.size}개 영상 조회"}
                .onFailure {status=it.message ?: "플레이리스트 조회 실패"}
            loading=false
        }
    }
    LaunchedEffect(connected) {if(connected) loadOwned()}
    fun match(song:YoutubeVideo):OdifySong? {
        val simplified=MasterDuplicateDetector.norm(song.title)
        val matches=localSongs.filter {
            val title=MasterDuplicateDetector.norm(it.title)
            val artist=MasterDuplicateDetector.norm(it.artist)
            title.length>=3 && simplified.contains(title) &&
                (artist.isBlank() || simplified.contains(artist))
        }
        // Never guess between a remix and a studio recording.
        return matches.singleOrNull()
    }
    Dialog(onDismissRequest=onClose,
        properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding()
            .navigationBarsPadding().padding(10.dp),
            shape=RoundedCornerShape(22.dp),color=Color.White) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick={
                        if(selected!=null) {selected=null;videos=emptyList()} else onClose()
                    }) {Icon(Icons.Default.ArrowBack,"뒤로")}
                    Text(if(selected==null) "YouTube 공개 플레이리스트" else "플레이리스트 곡",
                        modifier=Modifier.weight(1f),fontSize=18.sp,
                        fontWeight=FontWeight.Bold)
                }
                Text("YouTube 계정은 Drive와 별도입니다. 보유 중인 음원은 Odify에서 재생하고, 없는 음원은 목록에만 표시합니다.",
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
                    LazyColumn {
                        items(videos,key={it.videoId}) { v ->
                            val held=match(v)
                            Column(
                                Modifier.fillMaxWidth()
                                    .clickable(enabled=held!=null) {
                                        held?.let(onPlayLocal)
                                    }.padding(vertical=10.dp,horizontal=5.dp)
                            ) {
                                Text(v.title,fontSize=13.sp,fontWeight=FontWeight.SemiBold)
                                Text(if(held!=null) "Odify 음원 매칭 · 탭하여 재생"
                                    else "미보유 · 음원 추가 후 자동 연결",
                                    fontSize=11.sp,
                                    color=if(held!=null) Color(0xFF237A56) else Color.Gray)
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
                                Icon(Icons.Default.ChevronRight,null)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
