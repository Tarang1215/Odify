package com.odify.nativeapp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** An LRC-first lyrics area that replaces the square artwork on demand. */
@Composable
fun OdifyLyricsPanel(song: OdifySong, positionMs: Long,
                     onSeek: (Long) -> Unit, modifier: Modifier = Modifier,
                     editRequested:Boolean=false,visible:Boolean=true,
                     onEditorClosed:()->Unit={},onLyricsChanged:(String)->Unit={},immersive:Boolean=false) {
    val context = LocalContext.current
    val repo = remember { OdifyLyricsRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var text by remember(song.id) { mutableStateOf(song.lyrics) }
    var editing by remember(song.id) { mutableStateOf(editRequested) }
    var artistQuery by remember(song.id) { mutableStateOf(song.artist) }
    var titleQuery by remember(song.id) { mutableStateOf(song.title) }
    var draft by remember(song.id) { mutableStateOf(song.lyrics) }
    var matches by remember { mutableStateOf<List<LyricCandidate>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val parsed = remember(text) { LrcParser.parse(text) }
    LaunchedEffect(song.id, song.lyrics) {
        val saved = repo.read(song)
        text = saved
        onLyricsChanged(saved)
        if (KoreanOriginalLyrics.needsOriginal(song, saved)) {
            status = "한국어 원문 가사 확인 중…"
            val original = runCatching { repo.recoverKoreanOriginal(song, saved) }.getOrNull()
            if (original != null) {
                text = original
                onLyricsChanged(original)
                status = "곡명·가수·길이를 대조한 한국어 원문으로 교체했습니다."
            } else {
                status = "발음 표기 가사일 수 있습니다. 가사 추가에서 한글 원문을 선택하세요."
            }
        }
    }
    LaunchedEffect(editRequested) {if(editRequested) {draft=text;editing=true}}
    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            runCatching { repo.import(song, uri); repo.read(song) }
                .onSuccess { text = it;onLyricsChanged(it); status = "LRC 파일을 불러왔습니다."; editing = false;onEditorClosed() }
                .onFailure { status = it.message ?: "파일 열기 실패" }
        }
    }
    fun searchOnline() {
        if (searching) return
        scope.launch {
            searching = true
            status = "LRCLIB에서 가사 후보 조회 중…"
            matches = try {
                val found = repo.search(titleQuery.trim(),artistQuery.trim())
                status = "${found.size}개 가사 후보를 찾았습니다. 원하는 버전을 선택하세요."
                found.sortedWith(compareByDescending<LyricCandidate> {
                    it.text.count { ch -> ch in '가'..'힣' }
                }.thenByDescending { it.synchronized.isNotBlank() })
            } catch(e: Exception) {
                status = e.message ?: "온라인 가사 조회 실패"
                emptyList()
            } finally { searching = false }
        }
    }
    fun saveLyrics(input: String) {
        scope.launch {
            runCatching { repo.save(song,input); repo.read(song) }
                .onSuccess { text = it;onLyricsChanged(it); editing = false;onEditorClosed(); status = "가사를 저장했습니다." }
                .onFailure { status = it.message ?: "저장 실패" }
        }
    }
    val active = remember(parsed, positionMs) {
        if (!parsed.isTimed) -1
        else parsed.lines.indexOfLast { it.milliseconds <= positionMs }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(active, parsed.isTimed) {
        if (active >= 0 && parsed.isTimed)
            listState.animateScrollToItem(active.coerceAtLeast(0),if(immersive) -(listState.layoutInfo.viewportEndOffset*.32f).toInt() else 0)
    }
    val viewport=if(immersive) modifier.graphicsLayer {compositingStrategy=CompositingStrategy.Offscreen}
        .drawWithContent {
            drawContent()
            drawRect(Brush.verticalGradient(0f to Color.Transparent,.10f to Color.White,
                .82f to Color.White,1f to Color.Transparent),blendMode=BlendMode.DstIn)
        } else modifier
    if(visible) Column(
        viewport.padding(horizontal=if(immersive) 4.dp else 14.dp,vertical=if(immersive) 0.dp else 14.dp)
    ) {
        if(!immersive) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text("LYRICS",fontWeight=FontWeight.Bold,color=Color.White,
                fontSize=13.sp,modifier=Modifier.weight(1f))
            TextButton(onClick={ draft=text; editing=true }) {
                Icon(Icons.Default.Edit,null,tint=Color.White,modifier=Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("가사 추가",color=Color.White,fontSize=11.sp)
            }
        }
        if (KoreanOriginalLyrics.needsOriginal(song, text)) {
            Text("현재 가사는 한글 원문이 아닌 발음 표기일 수 있습니다.",
                color = Color(0xFFFFCD86), fontSize = 10.sp,
                modifier = Modifier.padding(vertical = 4.dp))
        }
        }
        if (text.isBlank()) {
            Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                Text("가사 없음\n가사 추가에서 LRC 파일·온라인 검색·직접 입력을 선택하세요.",
                    color=Color(0xFFBCC4D2),lineHeight=21.sp,fontSize=12.sp)
            }
        } else if (parsed.isTimed) {
            LazyColumn(state=listState,modifier=Modifier.fillMaxSize(),
                verticalArrangement=Arrangement.spacedBy(if(immersive) 23.dp else 15.dp),
                contentPadding=PaddingValues(top=if(immersive) 55.dp else 18.dp,bottom=if(immersive) 120.dp else 24.dp)) {
                itemsIndexed(parsed.lines) { index,line ->
                    Text(line.text.ifBlank { "♪" },
                        color=if(index == active) Color.White else Color.White.copy(alpha=if(immersive) .42f else .64f),
                        fontWeight=if(index==active) FontWeight.Bold else FontWeight.Normal,
                        fontSize=if(immersive) {if(index==active) 20.sp else 17.sp} else {if(index==active) 16.sp else 13.sp},
                        lineHeight=if(immersive) 29.sp else 21.sp,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth()
                            .clickable { onSeek(line.milliseconds) })
                }
            }
        } else {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical=if(immersive) 35.dp else 0.dp)) {
                Text(parsed.text,color=Color.White,fontSize=if(immersive) 17.sp else 13.sp,
                    lineHeight=if(immersive) 30.sp else 22.sp,fontWeight=if(immersive) FontWeight.SemiBold else FontWeight.Normal,textAlign=TextAlign.Center,
                    modifier=Modifier.fillMaxWidth())
            }
        }
    }
    if (editing) OdifyGlassAlertDialog(
        onDismissRequest={editing=false;onEditorClosed()},
        title={Text("가사 수정 · LRC / 직접 입력")},
        text={
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value=titleQuery,onValueChange={titleQuery=it},
                    label={Text("곡명")},singleLine=true)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value=artistQuery,onValueChange={artistQuery=it},
                    label={Text("아티스트 (커버곡은 원곡 가수로 검색 가능)")},
                    singleLine=true)
                Spacer(Modifier.height(9.dp))
                OutlinedButton(onClick={openDocument.launch(arrayOf(
                    "text/plain","application/octet-stream","*/*"
                ))}) { Text(".lrc / .txt 파일 불러오기") }
                Spacer(Modifier.height(9.dp))
                OutlinedButton(enabled=!searching,onClick={searchOnline()}) {
                    Text(if(searching) "검색 중…" else "LRCLIB 온라인 가사 후보 검색")
                }
                if(matches.isNotEmpty()) {
                    Text("동명곡·커버곡은 가사나 싱크가 다를 수 있습니다. 확인 후 적용하세요.",
                        fontSize=11.sp,color=Color.Gray)
                    Spacer(Modifier.height(6.dp))
                    matches.take(8).forEach { item ->
                        Column(Modifier.fillMaxWidth()
                            .clickable { saveLyrics(item.text) }
                            .padding(vertical=7.dp)) {
                            Text("${item.songTitle} — ${item.artist}",fontSize=13.sp,
                                fontWeight=FontWeight.Medium)
                            Text("${item.durationSec}초 · ${if(item.synchronized.isNotBlank()) "LRC 싱크 가사" else "일반 가사"}",
                                fontSize=11.sp,color=Color.Gray)
                        }
                    }
                }
                if(status.isNotBlank()) Text(status,fontSize=11.sp,color=Color.Gray)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value=draft,onValueChange={draft=it},
                    label={Text("가사 직접 입력 / LRC 붙여넣기")},
                    modifier=Modifier.fillMaxWidth(), minLines=4,maxLines=8)
            }
        },
        confirmButton={
            TextButton(onClick={saveLyrics(draft)},enabled=draft.isNotBlank()) {
                Text("입력 내용 저장")
            }
        },
        dismissButton={TextButton(onClick={editing=false;onEditorClosed()}) {Text("닫기")} }
    )
}
