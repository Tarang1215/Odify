package com.odify.nativeapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** From Now Playing > more > Add to playlist. Creates a playlist if needed. */
@Composable
fun OdifyPlaylistPicker(
    song: OdifySong,
    playlists: List<OdifyPlaylist>,
    onCreateAndAdd: (String, String) -> String,
    onAdd: (String, String) -> String,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    OdifyGlassAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("플레이리스트에 추가") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(song.title, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold)
                if (playlists.isNotEmpty()) {
                    Text("저장할 플레이리스트 선택", style = MaterialTheme.typography.labelMedium)
                    LazyColumn(modifier = Modifier.heightIn(max = 230.dp)) {
                        items(playlists, key = { it.id }) { playlist ->
                            Row(Modifier.fillMaxWidth()
                                .clickable {
                                    val result = onAdd(playlist.id, song.id)
                                    if (result.isBlank()) onDismiss() else message = result
                                }.padding(vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.QueueMusic, null)
                                Spacer(Modifier.width(11.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(playlist.name)
                                    Text("${playlist.songIds.size}곡",
                                        style = MaterialTheme.typography.labelSmall)
                                }
                                Icon(Icons.Default.Add, "이 플레이리스트에 추가")
                            }
                        }
                    }
                }
                HorizontalDivider()
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80) },
                    label = { Text("새 플레이리스트 이름") },
                    placeholder = { Text("예: 출퇴근 음악") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (message.isNotBlank()) Text(message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    val result = onCreateAndAdd(name.trim(), song.id)
                    if (result.isBlank()) onDismiss() else message = result
                }
            ) { Text("새로 만들고 추가") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}

/** Saved collections open as full library destinations, above the regular tab chrome. */
@Composable
fun OdifyPlaylistLibrary(playlists:List<OdifyPlaylist>,songs:List<OdifySong>,
    onCreate:(String)->String,onDelete:(String)->Unit,onOpenPlaylist:(OdifyPlaylist)->Unit) {
    var creating by remember {mutableStateOf(false)}
    var name by remember {mutableStateOf("")}
    var error by remember {mutableStateOf("")}
    var options by remember {mutableStateOf<OdifyPlaylist?>(null)}
    var deletePending by remember {mutableStateOf<OdifyPlaylist?>(null)}
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(top=5.dp,bottom=15.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("내 컬렉션",fontSize=18.sp,fontWeight=FontWeight.SemiBold)
                Text("${playlists.size}개의 플레이리스트",fontSize=11.sp,color=OdifyPalette.muted,modifier=Modifier.padding(top=4.dp))
            }
            FilledTonalIconButton(onClick={creating=true},modifier=Modifier.size(42.dp),
                colors=IconButtonDefaults.filledTonalIconButtonColors(containerColor=Color.White.copy(alpha=.08f),contentColor=OdifyPalette.text)) {
                Icon(Icons.Default.Add,"새 플레이리스트",Modifier.size(22.dp))
            }
        }
        if(playlists.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(vertical=36.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Icon(Icons.Default.LibraryMusic,null,Modifier.size(40.dp),tint=OdifyPalette.muted)
                Text("나만의 컬렉션을 만들어 보세요",fontSize=15.sp,fontWeight=FontWeight.Medium,modifier=Modifier.padding(top=18.dp))
                Text("마음에 드는 곡이나 믹스를 모아 두세요",fontSize=12.sp,color=OdifyPalette.muted,modifier=Modifier.padding(top=7.dp,bottom=12.dp))
                TextButton(onClick={creating=true}) {Text("플레이리스트 만들기")}
            }
        } else LazyVerticalGrid(columns=GridCells.Fixed(2),contentPadding=PaddingValues(bottom=160.dp),
            horizontalArrangement=Arrangement.spacedBy(16.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
            items(playlists,key={it.id}) {playlist ->
                Column(Modifier.clip(RoundedCornerShape(18.dp)).clickable {onOpenPlaylist(playlist)}) {
                    AlbumArt(playlist.songIds.firstNotNullOfOrNull {OdifyChartMatching.storedReference(it,songs)},
                        Modifier.fillMaxWidth().aspectRatio(1f),RoundedCornerShape(18.dp),fallbackKey=playlist.name.hashCode())
                    Row(Modifier.fillMaxWidth().padding(top=7.dp),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(start=2.dp)) {
                            Text(playlist.name,fontSize=13.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text("${playlist.songIds.size}곡",fontSize=11.sp,color=OdifyPalette.muted,modifier=Modifier.padding(top=4.dp))
                        }
                        IconButton(onClick={options=playlist},modifier=Modifier.size(40.dp)) {
                            Icon(Icons.Default.MoreHoriz,"${playlist.name} 관리",Modifier.size(20.dp),tint=OdifyPalette.muted)
                        }
                    }
                }
            }
        }
    }
    options?.let {playlist -> OdifyGlassSheet(onDismiss={options=null},maxHeightFraction=.4f) {
        Text(playlist.name,fontSize=16.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.fillMaxWidth().padding(10.dp))
        DropdownMenuItem(text={Text("플레이리스트 삭제",fontSize=14.sp)},leadingIcon={Icon(Icons.Default.DeleteOutline,null,Modifier.size(20.dp))},
            onClick={options=null;deletePending=playlist})
    }}
    if(creating) OdifyGlassAlertDialog(onDismissRequest={creating=false},
        title={Text("새 플레이리스트",fontSize=18.sp)},text={Column {
            OutlinedTextField(value=name,onValueChange={name=it.take(80)},label={Text("이름")},singleLine=true,modifier=Modifier.fillMaxWidth())
            if(error.isNotBlank())Text(error,fontSize=12.sp,color=MaterialTheme.colorScheme.error)
        }},confirmButton={TextButton(enabled=name.isNotBlank(),onClick={
            val result=onCreate(name.trim());if(result.isBlank()){creating=false;name="";error=""}else error=result
        }){Text("만들기")}},dismissButton={TextButton(onClick={creating=false}){Text("취소")}})
    deletePending?.let {playlist -> OdifyGlassAlertDialog(onDismissRequest={deletePending=null},
        title={Text("플레이리스트를 삭제할까요?",fontSize=18.sp)},text={Text("목록만 삭제되며 음악 파일은 유지됩니다.",fontSize=13.sp)},
        confirmButton={TextButton(onClick={deletePending=null;onDelete(playlist.id)}){Text("삭제")}},
        dismissButton={TextButton(onClick={deletePending=null}){Text("취소")}})}
}
