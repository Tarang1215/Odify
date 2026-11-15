package com.odify.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class OdifyTrackMenu(val song:OdifySong,val recent:Boolean=false,val playlistId:String?=null)

internal fun ExploreTrack.optionSong():OdifySong = OdifySong(
    if(id.startsWith("youtube:"))id else "chart:$id",title,artist,music?.album.orEmpty(),"",
    catalogArtistId=music?.artistId.orEmpty())

@Composable
internal fun OdifyTrackOptionsButton(title:String,onClick:()->Unit) {
    IconButton(onClick=onClick,modifier=Modifier.size(44.dp)) {
        Icon(Icons.Outlined.MoreHoriz,"$title 곡 옵션",Modifier.size(20.dp),tint=OdifyPalette.muted)
    }
}

@Composable
internal fun OdifyTrackOptions(menu:OdifyTrackMenu?,liked:Boolean,onDismiss:()->Unit,
    onFavorite:()->Unit,onPlaylist:()->Unit,onNext:(()->Unit)?,onAlbum:(()->Unit)?,
    onLess:()->Unit,onForget:(()->Unit)?,onRemoveFromPlaylist:(()->Unit)?,onDelete:(()->Unit)?) {
    OdifyRetainedGlassSheet(menu,onDismiss=onDismiss,maxHeightFraction=.68f) {target->
        OdifySheetTrackHeader(target.song)
        Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState())) {
            Action(if(liked)"즐겨찾기에서 제거" else "즐겨찾기",Icons.Outlined.FavoriteBorder,onFavorite)
            Action("플레이리스트에 추가",Icons.Outlined.PlaylistAdd,onPlaylist)
            onNext?.let {Action("바로 다음에 재생",Icons.Outlined.QueueMusic,it)}
            onAlbum?.let {Action("앨범으로 이동",Icons.Outlined.Album,it)}
            Action("제안 줄이기",Icons.Outlined.Block,onLess)
            onForget?.let {Action("최근 기록에서 삭제",Icons.Outlined.History,it)}
            onRemoveFromPlaylist?.let {Action("플레이리스트에서 제거",Icons.Outlined.RemoveCircleOutline,it)}
            onDelete?.let {Action("보관함에서 삭제",Icons.Outlined.DeleteOutline,it)}
        }
    }
}

@Composable
private fun Action(label:String,icon:ImageVector,onClick:()->Unit) {
    DropdownMenuItem(text={Text(label,fontSize=14.sp)},leadingIcon={Icon(icon,null,Modifier.size(20.dp))},onClick=onClick)
}
