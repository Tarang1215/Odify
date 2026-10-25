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

/** Actual saved playlists in the Library; no fabricated sample mixes. */
@Composable
fun OdifyPlaylistLibrary(
    playlists: List<OdifyPlaylist>,
    songs: List<OdifySong>,
    onPlay: (List<OdifySong>, Int) -> Unit,
    onCreate: (String) -> String,
    onDelete: (String) -> Unit,
    onRemoveSong: (String, String) -> Unit, currentSongId:String?=null
) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = selectedId != null) { selectedId = null }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var deletePending by remember { mutableStateOf<String?>(null) }
    var songMenu by remember { mutableStateOf<String?>(null) }
    val selected = playlists.firstOrNull { it.id == selectedId }
    val songIndex = remember(songs) { songs.associateBy(OdifySong::id) }
    val playlistContext=androidx.compose.ui.platform.LocalContext.current
    val repo=remember {OdifyPlaylists(playlistContext.applicationContext)}
    val tracks=remember(selected,songs){selected?.let {repo.resolved(it,songs)}.orEmpty()}

    Box(Modifier.fillMaxSize()) {
        if(selected!=null) {
            AlbumArt(tracks.firstOrNull(),Modifier.fillMaxSize().graphicsLayer{alpha=.28f}.blur(34.dp),radius=RoundedCornerShape(0.dp))
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.52f)))
        }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected != null) {
                IconButton(onClick = { selectedId = null }) {
                    Icon(Icons.Default.ArrowBack, "플레이리스트 목록")
                }
                Text(selected.name, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = { deletePending = selected.id }) {
                    Icon(Icons.Default.DeleteOutline, "플레이리스트 삭제")
                }
            } else {
                Text("내 플레이리스트", modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { creating = true }) {
                    Icon(Icons.Default.Add, null)
                    Text("새로 만들기")
                }
            }
        }
        if (selected == null) {
            if (playlists.isEmpty()) {
                Text("플레이리스트가 없습니다. 곡 재생 화면의 ⋮ 메뉴에서 추가할 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 24.dp))
            } else LazyVerticalGrid(columns=GridCells.Fixed(2),contentPadding = PaddingValues(bottom = 150.dp),
                horizontalArrangement=Arrangement.spacedBy(14.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                items(playlists, key = { it.id }) { playlist ->
                    Column(Modifier.clip(RoundedCornerShape(17.dp)).clickable { selectedId=playlist.id }) {
                        AlbumArt(playlist.songIds.firstNotNullOfOrNull {OdifyChartMatching.storedReference(it,songs)},Modifier.fillMaxWidth().aspectRatio(1f),
                            radius=RoundedCornerShape(17.dp),fallbackKey=playlist.name.hashCode())
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(top=9.dp)) {
                                Text(playlist.name,fontWeight=FontWeight.SemiBold,maxLines=1,
                                    overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text("${playlist.songIds.size}곡",style=MaterialTheme.typography.labelSmall,color=OdifyPalette.muted)
                            }
                            IconButton(onClick={deletePending=playlist.id},modifier=Modifier.size(38.dp)) {
                                Icon(Icons.Default.MoreHoriz,"${playlist.name} 삭제 메뉴",tint=OdifyPalette.muted)
                            }
                        }
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(top=15.dp,bottom=19.dp),verticalAlignment=Alignment.CenterVertically) {
                AlbumArt(tracks.firstOrNull(),Modifier.size(112.dp),radius=RoundedCornerShape(22.dp),fallbackKey=selected.name.hashCode())
                Column(Modifier.weight(1f).padding(start=18.dp)) {
                    Text("MY PLAYLIST",style=MaterialTheme.typography.labelSmall,color=OdifyPalette.muted)
                    Spacer(Modifier.height(6.dp))
                    Text(selected.name,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,maxLines=3)
                    Spacer(Modifier.height(7.dp))
                    Text("${selected.songIds.size}곡 · 나의 컬렉션",style=MaterialTheme.typography.bodySmall,color=OdifyPalette.muted)
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom=17.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Button(onClick={onPlay(tracks,0)},enabled=tracks.isNotEmpty(),modifier=Modifier.weight(1f),
                    colors=ButtonDefaults.buttonColors(containerColor=OdifyPalette.text,contentColor=OdifyPalette.background)) {
                    Icon(Icons.Default.PlayArrow,null);Spacer(Modifier.width(8.dp));Text("전체 재생")
                }
                OutlinedButton(onClick={onPlay(tracks.shuffled(),0)},enabled=tracks.isNotEmpty(),modifier=Modifier.weight(1f)) {
                    Icon(Icons.Default.Shuffle,null);Spacer(Modifier.width(8.dp));Text("셔플")
                }
            }
            if(tracks.size<selected.songIds.size) Text("${selected.songIds.size}곡 중 ${tracks.size}곡 준비됨 · 동기화 후 나머지도 이어집니다",
                style=MaterialTheme.typography.labelSmall,color=OdifyPalette.muted,modifier=Modifier.padding(vertical=8.dp))
            if (tracks.isEmpty()) {
                Text("재생 가능한 곡이 없습니다. 재생 화면에서 음악을 추가하세요.",
                    modifier = Modifier.padding(vertical = 20.dp))
            } else LazyColumn(contentPadding = PaddingValues(bottom = 150.dp)) {
                items(tracks, key = { it.id }) { song ->
                    ListItem(
                        headlineContent = { Text(song.title, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium) },
                        supportingContent = { Text(song.artist, maxLines = 1,
                            color = OdifyPalette.muted, style = MaterialTheme.typography.bodySmall) },
                        leadingContent = { AlbumArt(song, Modifier.size(46.dp),
                            radius = RoundedCornerShape(8.dp), fallbackKey = song.id.hashCode()) },
                        trailingContent = {
                            Box {
                                IconButton(onClick = { songMenu = song.id }) {
                                    Icon(Icons.Default.MoreHoriz, "${song.title} 곡 메뉴", tint = OdifyPalette.muted)
                                }
                                OdifyGlassMenu(expanded = songMenu == song.id, onDismissRequest = { songMenu = null }) {
                                    DropdownMenuItem(text = { Text("플레이리스트에서 제거") }, onClick = {
                                        songMenu = null; onRemoveSong(selected.id, song.id)
                                    })
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clip(RoundedCornerShape(14.dp))
                            .background(if(song.id==currentSongId) Color.White.copy(alpha=.075f) else Color.Transparent)
                            .clickable { onPlay(tracks, tracks.indexOf(song)) }
                    )
                }
            }
        }
    }
    }
    if (creating) {
        OdifyGlassAlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("새 플레이리스트") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newName, onValueChange = { newName = it.take(80) },
                        label = { Text("이름") }, singleLine = true
                    )
                    if (error.isNotBlank())
                        Text(error, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(enabled = newName.isNotBlank(), onClick = {
                    val result = onCreate(newName.trim())
                    if (result.isBlank()) {
                        creating = false; newName = ""; error = ""
                    } else error = result
                }) { Text("만들기") }
            },
            dismissButton = {
                TextButton(onClick = { creating = false }) { Text("취소") }
            }
        )
    }
    if (deletePending != null) {
        OdifyGlassAlertDialog(
            onDismissRequest = { deletePending = null },
            title = { Text("플레이리스트를 삭제할까요?") },
            text = { Text("목록만 삭제되며 원본 음악 파일은 그대로 유지됩니다.") },
            confirmButton = {
                TextButton(onClick = {
                    val id = deletePending
                    if (id != null) onDelete(id)
                    if (selectedId == id) selectedId = null
                    deletePending = null
                }) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { deletePending = null }) { Text("취소") }
            }
        )
    }
}
