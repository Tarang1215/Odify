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
internal fun OdifyYoutubeSearch(query: String, connected: Boolean, onConnect: () -> Unit,
    onExternalStarted: () -> Unit, onPlayVideo: (List<YoutubeVideo>,Int) -> Unit) {
    val context = LocalContext.current
    val repo = remember { OdifyYoutubeLibrary(context.applicationContext) }
    var videos by remember { mutableStateOf<List<YoutubeVideo>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var quickJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    fun opened(success: Boolean) {
        if (success) onExternalStarted() else error = "YouTube 링크를 열 수 있는 앱이 없습니다."
    }
    LaunchedEffect(query.trim(), connected, retry) {
        quickJob?.cancel()
        videos = emptyList(); error = ""; loading = false
        if (!connected || query.trim().length < 2) return@LaunchedEffect
        delay(900)
        loading = true
        try { videos = repo.search(query.trim().take(160)) }
        catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { error = failure.message ?: "검색을 불러오지 못했어요. YouTube 앱에서 검색해 주세요." }
        finally { loading = false }
    }
    Column(Modifier.fillMaxSize()) {
        Text("Odify 안에서 YouTube 재생", style = MaterialTheme.typography.bodySmall,
            color = OdifyPalette.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!connected) TextButton(onClick = onConnect) { Text("YouTube 연결") }
            if (connected && query.trim().length >= 2) TextButton(enabled = !loading, onClick = {
                quickJob = scope.launch {
                    loading = true; error = ""
                    try {
                        val results = repo.search(OdifyYoutubeQuickPlay.query("", query.trim()))
                        videos = results
                        val chosen = OdifyYoutubeQuickPlay.first(results)
                        if (chosen != null) onPlayVideo(listOf(chosen),0)
                        else error = "재생할 후보가 없어요. 검색 결과에서 직접 선택해 주세요."
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) { error = failure.message ?: "빠른 재생 검색을 불러오지 못했어요." }
                    finally { loading = false }
                }
            }) { Text("가사 영상 바로 재생") }
            if (query.isNotBlank()) TextButton(onClick = {
                opened(OdifyExternalYoutube.openSearch(context, query.take(160)))
            }) { Text("YouTube 앱에서 검색") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) {
            Text(error, style = MaterialTheme.typography.bodySmall, color = OdifyPalette.muted)
            TextButton(onClick={retry++}) {Text("다시 검색")}
        }
        if (query.trim().length < 2) Text("노래나 아티스트를 입력해 주세요.", color = OdifyPalette.muted)
        else if (!loading && connected && videos.isEmpty() && error.isBlank()) Text("검색 결과가 없습니다.", color = OdifyPalette.muted)
        LazyColumn(contentPadding = PaddingValues(bottom = 155.dp)) {
            items(videos, key = { it.videoId }) { video ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable {
                    onPlayVideo(listOf(video),0)
                }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    YoutubeSearchCover(video.videoId, Modifier.size(64.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(video.title, maxLines = 2, overflow = TextOverflow.Ellipsis, color = OdifyPalette.text)
                        Text(video.channel, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, color = OdifyPalette.muted)
                    }
                    IconButton(onClick={opened(OdifyExternalYoutube.openVideo(context,video.videoId))}) {
                        Icon(Icons.Default.OpenInNew, "YouTube 앱에서 열기", tint = OdifyPalette.muted,modifier=Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun YoutubeSearchCover(id: String, modifier: Modifier) {
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
