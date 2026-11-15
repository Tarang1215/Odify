package com.odify.nativeapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable internal fun OdifyAudioReplaceDialog(song:OdifySong,onSelect:(YoutubeVideo)->Unit,onClose:()->Unit) {
    var query by remember(song.id) {mutableStateOf(OdifyYoutubeQuickPlay.query(song.artist,song.title))}
    val (videos, busy, error) = rememberVideoSearch(query, query.trim().length >= 3, 650)
    OdifyGlassDialog(onClose) {
        Text("다른 음원으로 교체",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("선택하면 바로 재생하고 휴대폰 저장을 시작해요. 기존 파일은 보존합니다.",style=MaterialTheme.typography.bodySmall,color=OdifyPalette.muted)
        OutlinedTextField(query,{query=it.take(160)},singleLine=true,label={Text("YouTube에서 음원 검색")},modifier=Modifier.fillMaxWidth().padding(vertical=12.dp))
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
        LazyColumn(Modifier.heightIn(max=390.dp)) {
            items(videos,key={it.videoId}) {video ->
                val credits=remember(video) {OdifyMusicMatching.credits(video)}
                Row(Modifier.fillMaxWidth().clickable {onSelect(video)}.padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                    YoutubeSearchCover(video.videoId,Modifier.size(48.dp));Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(credits.title,maxLines=2,style=MaterialTheme.typography.bodyMedium)
                        Text(credits.artist.ifBlank {video.channel},maxLines=1,style=MaterialTheme.typography.labelSmall,color=OdifyPalette.muted)
                    }
                    Icon(Icons.Default.SwapHoriz,"이 음원으로 교체",Modifier.size(22.dp))
                }
            }
        }
        TextButton(onClick=onClose,modifier=Modifier.align(Alignment.End)){Text("취소")}
    }
}
