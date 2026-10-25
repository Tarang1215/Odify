package com.odify.nativeapp

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

@Composable
internal fun OdifyYoutubeSearch(query:String,connected:Boolean,onConnect:()->Unit,
    onExternalStarted:()->Unit,onPlayVideo:(List<YoutubeVideo>,Int)->Unit,
    localSongs:List<OdifySong>,onPlayLocal:(List<OdifySong>,Int)->Unit,onAddSong:(OdifySong)->Unit,
    includeYoutube:Boolean=true) {
    val context=LocalContext.current
    val repo=remember {OdifyYoutubeLibrary(context.applicationContext)}
    val streaming=remember {OdifyStreamingLibrary(context.applicationContext)}
    var videos by remember {mutableStateOf<List<YoutubeVideo>>(emptyList())}
    var loading by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf("")}
    var retry by remember {mutableIntStateOf(0)}
    LaunchedEffect(query.trim(),connected,retry,includeYoutube) {
        videos=emptyList();error="";loading=false
        if(!includeYoutube || !connected || query.trim().length<2) return@LaunchedEffect
        delay(500);loading=true
        try {videos=repo.search(query.trim().take(160))}
        catch(cancel:CancellationException){throw cancel}
        catch(failure:Exception){error=failure.message ?: "YouTube 검색 연결을 확인해 주세요."}
        finally {loading=false}
    }
    LazyColumn(contentPadding=PaddingValues(bottom=155.dp)) {
        items(localSongs,key={it.id}) {song ->
            ListItem(headlineContent={Text(song.title,maxLines=1,overflow=TextOverflow.Ellipsis,color=OdifyPalette.text)},
                supportingContent={Text((if(song.id.startsWith("gdrive:")) "Drive · " else "내 음악 · ")+song.artist,
                    maxLines=1,overflow=TextOverflow.Ellipsis,color=OdifyPalette.muted)},
                leadingContent={AlbumArt(song,Modifier.size(52.dp),radius=RoundedCornerShape(12.dp),fallbackKey=song.id.hashCode())},
                trailingContent={IconButton(onClick={onAddSong(song)}) {Icon(Icons.Default.PlaylistAdd,"플레이리스트에 추가",tint=OdifyPalette.muted)}},
                colors=ListItemDefaults.colors(containerColor=OdifyPalette.background),
                modifier=Modifier.clip(RoundedCornerShape(14.dp)).clickable {onPlayLocal(localSongs,localSongs.indexOf(song))})
        }
        if(includeYoutube && query.trim().length>=2) {
            item {
                Spacer(Modifier.height(12.dp))
                Text("YouTube",style=MaterialTheme.typography.titleSmall,color=OdifyPalette.text)
                if(!connected) TextButton(onClick=onConnect){Text("연결해서 함께 검색")}
                if(loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical=8.dp))
                if(error.isNotBlank()) {
                    Text(error,color=OdifyPalette.muted,style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={retry++}){Text("다시 검색")}
                }
            }
            items(videos,key={"youtube:"+it.videoId}) {video ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable {
                    streaming.remember(video);onPlayVideo(listOf(video),0)
                }.padding(vertical=9.dp),verticalAlignment=Alignment.CenterVertically) {
                    YoutubeSearchCover(video.videoId,Modifier.size(52.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(video.title,maxLines=2,overflow=TextOverflow.Ellipsis,color=OdifyPalette.text)
                        Text(video.channel,maxLines=1,overflow=TextOverflow.Ellipsis,
                            style=MaterialTheme.typography.bodySmall,color=OdifyPalette.muted)
                    }
                    IconButton(onClick={
                        streaming.remember(video)
                        streaming.find("youtube:"+video.videoId)?.let(onAddSong)
                    }) {Icon(Icons.Default.PlaylistAdd,"플레이리스트에 추가",tint=OdifyPalette.muted)}
                }
            }
        }
        if(localSongs.isEmpty() && videos.isEmpty() && !loading && error.isBlank()) item {
            Text(if(query.isBlank()) "노래나 아티스트를 검색해 주세요." else "일치하는 검색 결과가 없습니다.",
                color=OdifyPalette.muted,modifier=Modifier.padding(vertical=24.dp))
        }
    }
}

@Composable
internal fun YoutubeSearchCover(id: String, modifier: Modifier) {
    val context = LocalContext.current
    // key() resets the image immediately when a recycled row changes video.
    key(id) {
        val bitmap by produceState<ImageBitmap?>(null, id) {
            value = withContext(Dispatchers.IO) { runCatching {
                require(Regex("[A-Za-z0-9_-]{11}").matches(id))
                val file = java.io.File(context.cacheDir, "youtube_search_$id.jpg")
                val bytes = if (file.isFile) file.readBytes() else {
                    val conn = URL("https://i.ytimg.com/vi/$id/mqdefault.jpg").openConnection() as HttpURLConnection
                    val data = try {
                        conn.connectTimeout = 8000; conn.readTimeout = 8000; conn.instanceFollowRedirects = false
                        check(conn.responseCode == 200)
                        conn.inputStream.use { input ->
                            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer); if (count < 0) break
                                require(out.size() + count <= 400_000); out.write(buffer, 0, count)
                            }; out.toByteArray()
                        }
                    } finally { conn.disconnect() }
                    file.writeBytes(data); data
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull() }
        }
        Box(modifier.clip(RoundedCornerShape(12.dp)).background(OdifyPalette.surface), contentAlignment = Alignment.Center) {
            bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                ?: Icon(Icons.Default.MusicNote, null, tint = OdifyPalette.muted)
        }
    }
}
