package com.odify.nativeapp

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun OdifyMasterScreen(
    songs: List<OdifySong>, connected: Boolean, onClose: () -> Unit,
    onUpdated: (List<MasterTrack>) -> Unit
) {
    val context = LocalContext.current
    val repo = remember { OdifyMasterDatabase(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<MasterTrack>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("저장된 마스터를 읽고 있습니다.") }
    var keyword by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("전체") }

    LaunchedEffect(Unit) {
        rows = repo.loadCached()
        onUpdated(rows)
        status = if (rows.isEmpty()) "Drive 마스터 DB를 조회해 주세요."
            else "저장된 마스터 ${rows.size}곡"
    }
    fun refresh() {
        if (busy || !connected) return
        scope.launch {
            busy = true
            status = "Google Sheets MASTER를 읽는 중…"
            try {
                rows = repo.sync()
                onUpdated(rows)
                status = "마스터 ${rows.size}곡 동기화 완료"
            } catch (e: Exception) {
                status = "조회 실패: ${e.message}"
            } finally { busy = false }
        }
    }
    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { dest ->
        if (dest != null) scope.launch {
            val bytes = repo.exportSnapshot()
            if (bytes == null) {
                status = "먼저 DB를 조회해 주세요."
            } else try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(dest)?.use { it.write(bytes) }
                        ?: error("파일 저장 실패")
                }
                status = "CSV를 다운로드했습니다."
            } catch (e: Exception) { status = e.message ?: "다운로드 실패" }
        }
    }
    val counts = remember(rows, songs) {
        rows.map { MasterDisplayRow(it, MasterDuplicateDetector.status(it, songs)) }
    }
    val visible = remember(counts, keyword, filter) {
        counts.filter { x ->
            (filter == "전체" ||
                (filter == "Drive 보유" && x.presence == MasterPresence.ON_DRIVE) ||
                (filter == "중복 의심" && x.presence == MasterPresence.POSSIBLE_DUPLICATE) ||
                (filter == "미보유" && x.presence == MasterPresence.NOT_ON_DRIVE)) &&
            (x.track.title.contains(keyword,true) ||
                x.track.artist.contains(keyword,true) ||
                x.track.trackId.contains(keyword,true) ||
                x.track.album.contains(keyword,true))
        }
    }
    Dialog(onDismissRequest=onClose, properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(
            modifier=Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .padding(10.dp),
            shape=RoundedCornerShape(22.dp), color=OdifyPalette.surface
        ) {
            Column(Modifier.fillMaxSize().padding(18.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick=onClose) { Icon(Icons.Default.ArrowBack,"닫기") }
                    Text("Odify 음악 마스터 DB",fontWeight=FontWeight.Bold,
                        fontSize=19.sp,modifier=Modifier.weight(1f))
                }
                Text("Google Sheets MASTER · 조회/CSV 내보내기 · 원본 수정 없음",
                    fontSize=11.sp,color=Color.Gray)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(onClick={refresh()},enabled=connected && !busy) {
                        Icon(Icons.Default.Refresh,null,modifier=Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (busy) "조회 중" else "Drive DB 조회",fontSize=12.sp)
                    }
                    OutlinedButton(onClick={saver.launch("Odify_MASTER.csv")},
                        enabled=rows.isNotEmpty()) {
                        Icon(Icons.Default.CloudDownload,null,modifier=Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("CSV 저장",fontSize=12.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(status, fontSize=11.sp, color=Color.Gray)
                Spacer(Modifier.height(11.dp))
                OutlinedTextField(value=keyword,onValueChange={keyword=it},
                    placeholder={Text("Track ID, 제목, 가수 검색")},
                    modifier=Modifier.fillMaxWidth(),singleLine=true)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                    listOf("전체","Drive 보유","중복 의심","미보유").forEach { option ->
                        FilterChip(selected=filter==option,onClick={filter=option},
                            label={Text(option,fontSize=10.sp)})
                    }
                }
                Text("${visible.size}곡 · 정확한 파일 ID를 먼저 대조하며, 같은 곡의 다른 버전은 중복 확정하지 않습니다.",
                    fontSize=11.sp,color=Color.Gray)
                Spacer(Modifier.height(6.dp))
                LazyColumn(contentPadding=PaddingValues(bottom=22.dp),
                    verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    items(visible,key={it.track.trackId}) { item ->
                        val color = when(item.presence) {
                            MasterPresence.ON_DRIVE -> Color(0xFF2F8B66)
                            MasterPresence.POSSIBLE_DUPLICATE -> Color(0xFFC18320)
                            MasterPresence.NOT_ON_DRIVE -> Color.Gray
                        }
                        Column(Modifier.fillMaxWidth()
                            .background(OdifyPalette.raised,RoundedCornerShape(12.dp))
                            .padding(11.dp)) {
                            Row {
                                Text(item.track.title, fontWeight=FontWeight.SemiBold,
                                    fontSize=13.sp,modifier=Modifier.weight(1f))
                                Text(when(item.presence) {
                                    MasterPresence.ON_DRIVE -> "Drive 보유"
                                    MasterPresence.POSSIBLE_DUPLICATE -> "중복 의심"
                                    MasterPresence.NOT_ON_DRIVE -> "미보유"
                                },fontSize=10.sp,color=color)
                            }
                            Text("${item.track.artist} · ${item.track.trackId} · ${item.track.album}",
                                fontSize=11.sp,color=Color.Gray)
                            Text("DB 다운로드 상태: ${item.track.downloadStatus}",
                                fontSize=10.sp,color=Color.Gray)
                        }
                    }
                }
            }
        }
    }
}
