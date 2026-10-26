package com.odify.nativeapp

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*

/** A library destination; the root keeps its live glass mini-player above this page. */
@Composable internal fun OdifyPlaylistDetail(playlist:OdifyPlaylist,tracks:List<OdifySong>,
    currentSongId:String?,onDismiss:()->Unit,onPlay:(List<OdifySong>,Int)->Unit,
    onDelete:()->Unit,onRemoveSong:(String)->Unit) {
    val cover=tracks.firstOrNull {!it.artworkPath.isNullOrBlank()} ?: tracks.firstOrNull()
    val tint=rememberArtworkPalette(cover?.artworkPath).first()
    var menu by remember(playlist.id) {mutableStateOf(false)}
    var trackMenu by remember(playlist.id) {mutableStateOf<OdifySong?>(null)}
    var confirmDelete by remember(playlist.id) {mutableStateOf(false)}
    BoxWithConstraints(Modifier.fillMaxSize().background(OdifyPalette.background)) {
        AlbumArt(cover,Modifier.fillMaxSize().graphicsLayer {alpha=.43f;scaleX=1.15f;scaleY=1.15f}.blur(40.dp),RoundedCornerShape(0.dp))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha=.28f),Color.Black.copy(alpha=.63f),OdifyPalette.background.copy(alpha=.96f)))))
        val heroSize=minOf(maxWidth*.57f,242.dp)
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=22.dp,end=22.dp,bottom=130.dp)) {
            item {
                Row(Modifier.fillMaxWidth().height(58.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick=onDismiss,modifier=Modifier.size(44.dp)) {Icon(Icons.Default.ArrowBack,"보관함으로 돌아가기",Modifier.size(23.dp))}
                    Text("PLAYLIST",fontSize=10.sp,letterSpacing=1.8.sp,color=Color.White.copy(alpha=.60f),modifier=Modifier.weight(1f),textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                    IconButton(onClick={menu=true},modifier=Modifier.size(44.dp)) {Icon(Icons.Default.MoreHoriz,"플레이리스트 옵션",Modifier.size(23.dp))}
                }
                Box(Modifier.fillMaxWidth().padding(top=12.dp,bottom=26.dp),contentAlignment=Alignment.Center) {
                    AlbumArt(cover,Modifier.size(heroSize),RoundedCornerShape(22.dp),fallbackKey=playlist.name.hashCode())
                }
                Text(playlist.name,fontSize=25.sp,lineHeight=31.sp,fontWeight=FontWeight.Bold,maxLines=3,overflow=TextOverflow.Ellipsis)
                Text("나의 플레이리스트",fontSize=12.sp,color=Color.White.copy(alpha=.72f),modifier=Modifier.padding(top=7.dp))
                val minutes=tracks.sumOf {it.durationMs}/60000
                Text("${playlist.songIds.size}곡"+(if(minutes>0)" · ${minutes}분" else ""),fontSize=11.sp,color=Color.White.copy(alpha=.48f),modifier=Modifier.padding(top=4.dp))
                Row(Modifier.fillMaxWidth().padding(top=18.dp,bottom=20.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick={onPlay(tracks.shuffled(),0)},enabled=tracks.isNotEmpty(),modifier=Modifier.size(44.dp)
                        .background(Color.White.copy(alpha=.07f),CircleShape).border(.5.dp,Color.White.copy(alpha=.13f),CircleShape)) {
                        Icon(Icons.Default.Shuffle,"플레이리스트 셔플 재생",Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    IconButton(onClick={menu=true},modifier=Modifier.size(44.dp).background(Color.White.copy(alpha=.07f),CircleShape)
                        .border(.5.dp,Color.White.copy(alpha=.13f),CircleShape)) {Icon(Icons.Default.MoreHoriz,"플레이리스트 관리",Modifier.size(21.dp))}
                    Spacer(Modifier.weight(1f))
                    FilledIconButton(onClick={onPlay(tracks,0)},enabled=tracks.isNotEmpty(),modifier=Modifier.size(55.dp),
                        colors=IconButtonDefaults.filledIconButtonColors(containerColor=Color.White,contentColor=Color.Black)) {
                        Icon(Icons.Default.PlayArrow,"플레이리스트 전체 재생",Modifier.size(29.dp))
                    }
                }
                if(tracks.size<playlist.songIds.size) Text("${tracks.size}곡 준비됨 · 나머지는 동기화 후 표시됩니다",fontSize=11.sp,
                    color=Color.White.copy(alpha=.56f),modifier=Modifier.padding(bottom=12.dp))
                if(tracks.isEmpty()) Text("아직 곡이 없어요. 곡 메뉴에서 이 플레이리스트에 추가해 주세요.",fontSize=13.sp,
                    color=Color.White.copy(alpha=.62f),modifier=Modifier.padding(vertical=22.dp))
            }
            itemsIndexed(tracks,key={_,song -> song.id}) {index,song ->
                val current=song.id==currentSongId
                Row(Modifier.fillMaxWidth().height(68.dp).background(Color.White.copy(alpha=if(current).08f else 0f),RoundedCornerShape(14.dp))
                    .clickable {onPlay(tracks,index)}.padding(start=4.dp,end=2.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text((index+1).toString(),fontSize=11.sp,color=Color.White.copy(alpha=.42f),modifier=Modifier.width(24.dp))
                    AlbumArt(song,Modifier.size(42.dp),RoundedCornerShape(9.dp))
                    Column(Modifier.weight(1f).padding(start=12.dp,end=4.dp)) {
                        Text(song.title,fontSize=13.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(song.artist+(if(song.durationMs>0)" · ${song.durationMs/60000}:${(song.durationMs/1000%60).toString().padStart(2,'0')}" else ""),
                            fontSize=11.sp,color=Color.White.copy(alpha=.56f),maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=4.dp))
                    }
                    IconButton(onClick={trackMenu=song},modifier=Modifier.size(44.dp)) {Icon(Icons.Default.MoreHoriz,"${song.title} 곡 옵션",Modifier.size(20.dp),tint=Color.White.copy(alpha=.65f))}
                }
            }
        }
    }
    if(menu) OdifyGlassSheet(onDismiss={menu=false},tint=tint,maxHeightFraction=.45f) {
        Text(playlist.name,fontSize=16.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.fillMaxWidth().padding(10.dp))
        DropdownMenuItem(text={Text("플레이리스트 삭제",fontSize=14.sp)},leadingIcon={Icon(Icons.Default.DeleteOutline,null,Modifier.size(20.dp))},
            onClick={menu=false;confirmDelete=true})
    }
    trackMenu?.let {song -> OdifyGlassSheet(onDismiss={trackMenu=null},tint=tint,maxHeightFraction=.45f) {
        OdifySheetTrackHeader(song)
        DropdownMenuItem(text={Text("플레이리스트에서 제거",fontSize=14.sp)},leadingIcon={Icon(Icons.Default.RemoveCircleOutline,null,Modifier.size(20.dp))},
            onClick={trackMenu=null;onRemoveSong(song.id)})
    }}
    if(confirmDelete) OdifyGlassAlertDialog(onDismissRequest={confirmDelete=false},
        title={Text("플레이리스트를 삭제할까요?",fontSize=18.sp)},text={Text("목록만 삭제되며 음악 파일은 유지됩니다.",fontSize=13.sp)},
        confirmButton={TextButton(onClick={confirmDelete=false;onDelete()}){Text("삭제")}},dismissButton={TextButton(onClick={confirmDelete=false}){Text("취소")}})
}
