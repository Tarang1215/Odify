package com.odify.nativeapp

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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

internal object OdifySearchRowKeys {
    fun library(id: String) = "library:$id"
    fun youtube(videoId: String) = "result:$videoId"
}

internal data class OdifyVideoSearchState(
    val videos: List<YoutubeVideo> = emptyList(),
    val loading: Boolean = false,
    val error: String = ""
)

@Composable
internal fun rememberVideoSearch(
    query: String,
    enabled: Boolean,
    debounceMillis: Long,
    retry: Int = 0,
    search: (suspend (String) -> List<YoutubeVideo>)? = null
): OdifyVideoSearchState {
    val context = LocalContext.current.applicationContext
    val repo = remember(context) { OdifyYoutubeLibrary(context) }
    val request = query.trim().take(160)
    val state = remember(repo, request, enabled, retry, search) {
        mutableStateOf(OdifyVideoSearchState(loading = enabled))
    }
    LaunchedEffect(state) {
        if (!enabled) return@LaunchedEffect
        delay(debounceMillis)
        state.value = try {
            OdifyVideoSearchState(videos = search?.invoke(request) ?: repo.search(request))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            OdifyVideoSearchState(error = failure.message ?: "YouTube 검색 연결을 확인해 주세요.")
        }
    }
    return state.value
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OdifyYoutubeSearch(query:String,connected:Boolean,onConnect:()->Unit,
    onExternalStarted:()->Unit,onPlayVideo:(List<YoutubeVideo>,Int)->Unit,
    localSongs:List<OdifySong>,onPlayLocal:(List<OdifySong>,Int)->Unit,onAddSong:(OdifySong)->Unit,
    includeYoutube:Boolean=true, search: (suspend (String) -> List<YoutubeVideo>)? = null) {
    val context=LocalContext.current.applicationContext
    val streaming=remember(context) {OdifyStreamingLibrary(context)}
    var retry by remember {mutableIntStateOf(0)}
    val showYoutube = includeYoutube && query.trim().length >= 2
    val (videos, loading, error) = rememberVideoSearch(query, showYoutube && connected, 500, retry, search)
    val libraryResults = remember(localSongs) { localSongs.distinctBy(OdifySong::id) }
    LazyColumn(contentPadding=PaddingValues(bottom=155.dp)) {
        itemsIndexed(libraryResults,key={_, song -> OdifySearchRowKeys.library(song.id)}) {index, song ->
            ListItem(headlineContent={Text(song.title,maxLines=1,overflow=TextOverflow.Ellipsis,color=OdifyPalette.text)},
                supportingContent={Text((when {
                    song.isYoutubeStream -> "보관함 · YouTube · "
                    song.id.startsWith("gdrive:") -> "Drive · "
                    else -> "내 음악 · "
                })+song.artist,
                    maxLines=1,overflow=TextOverflow.Ellipsis,color=OdifyPalette.muted)},
                leadingContent={AlbumArt(song,Modifier.size(52.dp),radius=RoundedCornerShape(12.dp),fallbackKey=song.id.hashCode())},
                trailingContent={OdifyTrackOptionsButton(song.title) {onAddSong(song)}},
                colors=ListItemDefaults.colors(containerColor=OdifyPalette.background),
                modifier=Modifier.clip(RoundedCornerShape(14.dp)).combinedClickable(
                    onClick={onPlayLocal(libraryResults,index)},onLongClick={onAddSong(song)},onLongClickLabel="곡 옵션"))
        }
        if(showYoutube) {
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
            items(videos,key={OdifySearchRowKeys.youtube(it.videoId)}) {video ->
                val credits=remember(video) {OdifyMusicMatching.credits(video)}
                val options={
                    streaming.remember(video)
                    streaming.find("youtube:"+video.videoId)?.let(onAddSong)
                    Unit
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).combinedClickable(
                    onClick={streaming.remember(video);onPlayVideo(listOf(video),0)},
                    onLongClick=options,onLongClickLabel="곡 옵션"
                ).padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    YoutubeSearchCover(video.videoId,Modifier.size(52.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(credits.title,maxLines=2,overflow=TextOverflow.Ellipsis,color=OdifyPalette.text)
                        Text(credits.artist.ifBlank {video.channel},maxLines=1,overflow=TextOverflow.Ellipsis,
                            style=MaterialTheme.typography.bodySmall,color=OdifyPalette.muted)
                    }
                    OdifyTrackOptionsButton(credits.title,options)
                }
            }
        }
        if(libraryResults.isEmpty() && videos.isEmpty() && !loading && error.isBlank()) item {
            Text(if(query.isBlank()) "노래나 아티스트를 검색해 주세요." else "일치하는 검색 결과가 없습니다.",
                color=OdifyPalette.muted,modifier=Modifier.padding(vertical=24.dp))
        }
    }
}

@Composable
internal fun YoutubeSearchCover(id: String, modifier: Modifier) {
    val context = LocalContext.current.applicationContext
    key(id) {
        val bitmap by produceState<ImageBitmap?>(null, id) {
            val path = youtubeArtwork(context, id) ?: return@produceState
            val cacheKey = "search:$path"
            value = OdifyCoverMemory.peek(cacheKey) ?: withContext(Dispatchers.IO) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096) return@withContext null
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 320) inSampleSize *= 2
                }
                BitmapFactory.decodeFile(path, options)?.asImageBitmap()?.also {
                    OdifyCoverMemory.put(cacheKey, it)
                }
            }
        }
        Box(modifier.clip(RoundedCornerShape(12.dp)).background(OdifyPalette.surface), contentAlignment = Alignment.Center) {
            bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                ?: Icon(Icons.Default.MusicNote, null, tint = OdifyPalette.muted)
        }
    }
}
