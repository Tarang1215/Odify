package com.odify.nativeapp

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*

private val homeInk = OdifyPalette.text
private val homeGrey = OdifyPalette.muted

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OdifyPersonalHome(
    recent: List<OdifySong>, hasHistory: Boolean, mixes: List<OdifyMix>,
    playlists: List<OdifyPlaylist>, onProfile: () -> Unit, onExplore: () -> Unit,
    onSearch: () -> Unit, onLibrary: () -> Unit, onPlay: (List<OdifySong>, Int) -> Unit,
    onSaveMix: (OdifyMix) -> Unit, onPlaylistPlay: (OdifyPlaylist) -> Unit
) {
    var selectedMix by remember { mutableStateOf<OdifyMix?>(null) }
    val hero = mixes.firstOrNull()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(22.dp, 14.dp, 22.dp, 160.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick=onSearch,modifier=Modifier.size(48.dp)) {Icon(Icons.Outlined.Search,"검색",tint=homeInk,modifier=Modifier.size(22.dp))}
                Text("Odify", fontSize = 23.sp, fontWeight = FontWeight.Bold,
                    color = homeInk, letterSpacing = (-.9).sp,modifier=Modifier.weight(1f),textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                IconButton(onClick = onProfile, modifier = Modifier.size(48.dp)) {
                    Box(Modifier.size(36.dp).background(OdifyPalette.surface, RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.PersonOutline, "프로필 및 설정", tint = homeInk, modifier = Modifier.size(21.dp))
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                listOf("For you" to { hero?.let {selectedMix=it}; Unit },"Explore" to onExplore,"Search" to onSearch).forEachIndexed {index,(label,action) ->
                    AssistChip(onClick=action,label={Text(label,fontSize=11.sp)},
                        colors=AssistChipDefaults.assistChipColors(containerColor=if(index==0) OdifyPalette.text else OdifyPalette.surface,
                            labelColor=if(index==0) OdifyPalette.background else OdifyPalette.muted),border=null)
                }
            }
            Spacer(Modifier.height(12.dp))
            if (hero != null) {
                Box(Modifier.fillMaxWidth().height(164.dp).clip(RoundedCornerShape(25.dp))
                    .clickable { selectedMix = hero }) {
                    AlbumArt(hero.coverSong(), Modifier.fillMaxSize(), radius = RoundedCornerShape(25.dp))
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
                        Color.Black.copy(alpha=.08f), Color.Black.copy(alpha=.78f)))))
                    Column(Modifier.align(Alignment.BottomStart).padding(21.dp).fillMaxWidth(.8f)) {
                        Text(when(java.time.LocalTime.now().hour) {in 5..11 -> "GOOD MORNING";in 12..17 -> "GOOD AFTERNOON";else -> "GOOD EVENING"}, fontSize=9.sp, letterSpacing=1.7.sp, color=Color.White.copy(alpha=.8f))
                        Spacer(Modifier.height(7.dp))
                        Text(hero.name, fontSize=27.sp, fontWeight=FontWeight.Bold, color=Color.White)
                        Spacer(Modifier.height(5.dp))
                        Text(hero.reason, fontSize=11.sp, color=Color.White.copy(alpha=.8f), maxLines=2)
                    }
                    FilledIconButton(onClick={onPlay(hero.songs,0)}, Modifier.align(Alignment.BottomEnd).padding(16.dp).size(46.dp),
                        colors=IconButtonDefaults.filledIconButtonColors(containerColor=Color.White,contentColor=OdifyPalette.background)) {
                        Icon(Icons.Outlined.PlayArrow,"${hero.name} 재생")
                    }
                }
            } else {
                Text("음악을 연결하고, 나만의 리듬을 찾아요.",color=homeGrey,fontSize=14.sp)
                TextButton(onClick=onProfile) { Text("음악 소스 연결",color=homeInk) }
            }
            Spacer(Modifier.height(27.dp))
        }
        if (recent.isNotEmpty()) item {
            Text(if(hasHistory) "Your recent moments" else "Ready when you are",fontSize=20.sp,fontWeight=FontWeight.Bold,color=homeInk)
            Text(if(hasHistory) "최근 함께한 음악" else "내 음악에서 골라 들어요",fontSize=11.sp,color=homeGrey,
                modifier=Modifier.padding(top=4.dp,bottom=14.dp))
            LazyRow(horizontalArrangement=Arrangement.spacedBy(13.dp)) {
                items(recent.take(10),key={it.id}) {song ->
                    Column(Modifier.width(106.dp).clickable {onPlay(recent,recent.indexOf(song))}) {
                        AlbumArt(song,Modifier.size(106.dp),radius=RoundedCornerShape(15.dp))
                        Text(song.title,fontSize=12.sp,color=homeInk,maxLines=1,overflow=TextOverflow.Ellipsis,
                            modifier=Modifier.padding(top=8.dp),fontWeight=FontWeight.SemiBold)
                        Text(song.artist,fontSize=10.sp,color=homeGrey,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                Text("Made for you",fontSize=20.sp,fontWeight=FontWeight.Bold,color=homeInk,modifier=Modifier.weight(1f))
                Icon(Icons.Outlined.AutoAwesome,null,tint=homeGrey,modifier=Modifier.size(18.dp))
            }
            Text("들을수록 가까워지는 취향",fontSize=11.sp,color=homeGrey,modifier=Modifier.padding(top=4.dp,bottom=14.dp))
        }
        if (mixes.isNotEmpty()) item {
            LazyRow(horizontalArrangement=Arrangement.spacedBy(13.dp)) {
                items(mixes,key={it.key}) { mix ->
                    Column(Modifier.width(132.dp).clickable {selectedMix=mix}) {
                        Box(Modifier.size(132.dp).clip(RoundedCornerShape(19.dp))) {
                            AlbumArt(mix.coverSong(),Modifier.fillMaxSize(),radius=RoundedCornerShape(19.dp))
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.6f)))))
                            Text(mix.name,modifier=Modifier.align(Alignment.BottomStart).padding(13.dp),fontSize=18.sp,
                                fontWeight=FontWeight.SemiBold,color=Color.White,maxLines=2)
                        }
                        Text("${mix.songs.size}곡 · 나만의 믹스",fontSize=11.sp,color=homeGrey,modifier=Modifier.padding(top=7.dp))
                    }
                }
            }
            Spacer(Modifier.height(29.dp))
        }
        item {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("Saved for later",fontSize=20.sp,color=homeInk,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                TextButton(onClick=onLibrary) {Text("보관함",fontSize=12.sp,color=homeGrey)}
            }
        }
        if(playlists.isEmpty()) item {
            Text("믹스를 열고 저장하면 보관함에서 언제든 다시 만나요.",fontSize=12.sp,color=homeGrey,
                modifier=Modifier.padding(vertical=12.dp))
        } else items(playlists.take(3),key={it.id}) { list ->
            ListItem(headlineContent={Text(list.name,fontSize=14.sp)},supportingContent={Text("${list.songIds.size}곡",fontSize=11.sp)},
                leadingContent={Icon(Icons.Outlined.QueueMusic,null,tint=homeGrey)},
                trailingContent={Icon(Icons.Outlined.PlayArrow,null,tint=homeInk)},
                colors=ListItemDefaults.colors(containerColor=OdifyPalette.background),modifier=Modifier.clickable {onPlaylistPlay(list)})
        }
        item {
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth().background(OdifyPalette.surface,RoundedCornerShape(20.dp)).clickable(onClick=onExplore).padding(19.dp),
                verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Outlined.Explore,null,tint=homeInk)
                Column(Modifier.weight(1f).padding(start=13.dp)) {
                    Text("A world beyond your mix",fontSize=14.sp,fontWeight=FontWeight.SemiBold,color=homeInk)
                    Text("한국 · 일본 · 미국, 지금의 음악",fontSize=11.sp,color=homeGrey)
                }
                Icon(Icons.Outlined.ChevronRight,null,tint=homeGrey)
            }
            TextButton(onClick=onSearch,modifier=Modifier.padding(top=8.dp)) {Text("떠오르는 노래나 가사로 검색",color=homeGrey,fontSize=12.sp)}
        }
    }
    selectedMix?.let { mix ->
        ModalBottomSheet(onDismissRequest={selectedMix=null},containerColor=OdifyPalette.surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal=22.dp)) {
                Text(mix.name,fontSize=26.sp,fontWeight=FontWeight.Bold,color=homeInk)
                Text(mix.reason,fontSize=12.sp,color=homeGrey,modifier=Modifier.padding(top=6.dp,bottom=14.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Button(onClick={onPlay(mix.songs,0);selectedMix=null}) {Icon(Icons.Outlined.PlayArrow,null);Text("모두 재생")}
                    OutlinedButton(onClick={onSaveMix(mix)}) {Icon(Icons.Outlined.PlaylistAdd,null);Text("플레이리스트 저장")}
                }
                LazyColumn(Modifier.heightIn(max=390.dp),contentPadding=PaddingValues(bottom=30.dp)) {
                    items(mix.songs,key={it.id}) {song ->
                        ListItem(headlineContent={Text(song.title,maxLines=1,overflow=TextOverflow.Ellipsis)},
                            supportingContent={Text(song.artist,maxLines=1)},leadingContent={AlbumArt(song,Modifier.size(42.dp))},
                            colors=ListItemDefaults.colors(containerColor=OdifyPalette.surface),modifier=Modifier.clickable {onPlay(mix.songs,mix.songs.indexOf(song));selectedMix=null})
                    }
                }
            }
        }
    }
}

/** Keep a missing first-track image from hiding artwork available within the mix. */
private fun OdifyMix.coverSong(): OdifySong? =
    songs.firstOrNull { !it.artworkPath.isNullOrBlank() } ?: songs.firstOrNull()
