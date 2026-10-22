package com.odify.nativeapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.consume
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Reorders the actual Media3 playlist, not merely this dialog's visual list.
 * A long press on the handle drags a track by one row at a time.
 */
@Composable
fun OdifyQueueDialog(
    items: List<OdifySong>, currentIndex: Int,
    onMove: (Int, Int) -> Unit, onSelect: (Int) -> Unit,
    onClose: () -> Unit
) {
    var ordered by remember { mutableStateOf(items) }
    val ids = items.map { it.id }
    LaunchedEffect(ids) {
        if (ordered.map { it.id } != ids) ordered = items
    }
    var dragging by remember { mutableStateOf<String?>(null) }
    var displacement by remember { mutableFloatStateOf(0f) }
    val rowHeight = with(LocalDensity.current) { 70.dp.toPx() }
    val activeId = items.getOrNull(currentIndex)?.id
    Dialog(onDismissRequest=onClose,
        properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(
            Modifier.fillMaxWidth().fillMaxHeight(0.86f).padding(horizontal=12.dp),
            shape=RoundedCornerShape(26.dp),color=Color(0xFF191B20)
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal=15.dp, vertical=10.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("다음 재생목록",color=Color.White,fontWeight=FontWeight.Bold,
                            fontSize=20.sp)
                        Text("${ordered.size}곡 · 오른쪽 손잡이를 꾹 눌러 순서 변경",
                            color=Color(0xFFADB0B8),fontSize=11.sp)
                    }
                    IconButton(onClick=onClose) {
                        Icon(Icons.Default.Close,"닫기",tint=Color.White)
                    }
                }
                Spacer(Modifier.height(12.dp))
                LazyColumn(
                    verticalArrangement=Arrangement.spacedBy(3.dp),
                    contentPadding=PaddingValues(bottom=20.dp)
                ) {
                    items(ordered,key={it.id}) { song ->
                        val selected = song.id == activeId
                        val index = ordered.indexOfFirst { it.id == song.id }
                        Row(
                            Modifier.fillMaxWidth()
                                .background(
                                    if (selected) Color(0xFF303D42)
                                    else if (dragging==song.id) Color(0xFF3F434A)
                                    else Color(0xFF25272D),
                                    RoundedCornerShape(14.dp)
                                )
                                .height(68.dp)
                                .clickable { if(dragging==null) onSelect(index) }
                                .padding(start=11.dp,end=7.dp),
                            verticalAlignment=Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.MusicNote,null,
                                tint=if(selected) Color(0xFF8FE0BC) else Color.LightGray,
                                modifier=Modifier.size(24.dp))
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                Text(song.title,
                                    color=if(selected) Color(0xFF9BEFC4) else Color.White,
                                    fontSize=13.sp, fontWeight=FontWeight.SemiBold,
                                    maxLines=1,overflow=TextOverflow.Ellipsis)
                                Text(song.artist.ifBlank { "아티스트 정보 없음" },
                                    color=Color(0xFFAEB1B7),fontSize=11.sp,
                                    maxLines=1,overflow=TextOverflow.Ellipsis)
                            }
                            Icon(
                                Icons.Default.DragHandle,"길게 눌러 이동",
                                tint=Color(0xFFCFD1D6),
                                modifier=Modifier.size(42.dp)
                                    .padding(8.dp)
                                    .pointerInput(song.id, rowHeight) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart={
                                                dragging=song.id
                                                displacement=0f
                                            },
                                            onDragEnd={ dragging=null;displacement=0f },
                                            onDragCancel={ dragging=null;displacement=0f },
                                            onDrag={ change, movement ->
                                                change.consume()
                                                if(dragging != song.id) return@detectDragGesturesAfterLongPress
                                                displacement+=movement.y
                                                var from=ordered.indexOfFirst { it.id==song.id }
                                                while(displacement>=rowHeight && from < ordered.lastIndex) {
                                                    val to=from+1
                                                    val modified=ordered.toMutableList()
                                                    val moved=modified.removeAt(from)
                                                    modified.add(to,moved)
                                                    ordered=modified
                                                    onMove(from,to)
                                                    from=to
                                                    displacement-=rowHeight
                                                }
                                                while(displacement<=-rowHeight && from>0) {
                                                    val to=from-1
                                                    val modified=ordered.toMutableList()
                                                    val moved=modified.removeAt(from)
                                                    modified.add(to,moved)
                                                    ordered=modified
                                                    onMove(from,to)
                                                    from=to
                                                    displacement+=rowHeight
                                                }
                                            }
                                        )
                                    }
                            )
                        }
                    }
                }
            }
        }
    }
}
