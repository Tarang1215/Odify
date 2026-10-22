package com.odify.nativeapp

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

private val ink = Color(0xFF111216)
private val grey = Color(0xFF7B7E85)
private val pale = Color(0xFFF2F3F5)
private val white = Color.White
private val glass = Brush.horizontalGradient(
    listOf(Color(0xF31C1F25), Color(0xE821242A))
)

enum class OdifyTab { Home, Search, Library, Profile }

@Composable
fun OdifySplash() {
    Box(Modifier.fillMaxSize().background(white), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Reuse the precise monochrome Odify icon in the system launcher.
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(R.drawable.odify_mark),
                contentDescription = "Odify",
                modifier = Modifier.size(90.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text("Odify", fontSize = 32.sp, fontWeight = FontWeight.Bold,
                letterSpacing = (-1.3).sp, color = ink)
        }
    }
}

@Composable
fun AlbumArt(
    song: OdifySong?,
    modifier: Modifier = Modifier,
    radius: androidx.compose.ui.graphics.Shape = RoundedCornerShape(14.dp),
    fallbackKey: Int = 0
) {
    val image by produceState<ImageBitmap?>(initialValue = null, key1 = song?.artworkPath) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                song?.artworkPath?.let { path ->
                    val options = BitmapFactory.Options().apply {
                        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                    }
                    BitmapFactory.decodeFile(path, options)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    val neutralPalette = listOf(
        listOf(Color(0xFF393D44), Color(0xFF858C94)),
        listOf(Color(0xFF242E39), Color(0xFF868A88)),
        listOf(Color(0xFF534C50), Color(0xFFBAB1A8)),
        listOf(Color(0xFF4E555D), Color(0xFFAFACA5))
    )
    val swatch = neutralPalette[(fallbackKey and Int.MAX_VALUE) % neutralPalette.size]
    Box(
        modifier.clip(radius).background(Brush.linearGradient(swatch)),
        contentAlignment = Alignment.Center
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = song?.album ?: song?.title,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Default.MusicNote, contentDescription = null,
                tint = Color.White.copy(alpha = 0.72f),
                modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
fun OdifyRoot(
    songs: List<OdifySong>,
    playback: OdifyPlaybackSnapshot,
    importing: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onImport: () -> Unit,
    onPlay: (List<OdifySong>, Int) -> Unit,
    onTogglePlayback: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onRemove: (OdifySong) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("odify_native_ui", 0) }
    var selectedTab by remember { mutableStateOf(OdifyTab.Home) }
    var liked by remember {
        mutableStateOf(prefs.getStringSet("likes", emptySet())?.toSet() ?: emptySet())
    }
    var history by remember {
        mutableStateOf(prefs.getString("history", "")!!.split("|").filter { it.isNotBlank() })
    }
    var navVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var scrollDistance by remember { mutableFloatStateOf(0f) }

    val active = songs.firstOrNull { it.id == playback.mediaId }
    val recent = history.mapNotNull { id -> songs.firstOrNull { it.id == id } }
    val displayed = if (recent.isNotEmpty()) recent else songs.asReversed()

    fun playList(list: List<OdifySong>, index: Int) {
        if (index !in list.indices) return
        val key = list[index].id
        history = (listOf(key) + history.filterNot { it == key }).take(25)
        prefs.edit().putString("history", history.joinToString("|")).apply()
        onPlay(list, index)
    }

    fun toggleLike(id: String) {
        liked = if (id in liked) liked - id else liked + id
        prefs.edit().putStringSet("likes", liked).apply()
    }

    // User gesture drives hiding, not a heavy constantly-running animation.
    val scrollConnection = remember(playback.isPlaying) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    scrollDistance += available.y
                    if (abs(scrollDistance) >= 18f) {
                        interaction++
                        if (playback.isPlaying) {
                            navVisible = scrollDistance > 0f // finger down = content up
                        } else navVisible = true
                        scrollDistance = 0f
                    }
                }
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(playback.isPlaying, interaction, selectedTab) {
        if (playback.isPlaying) {
            delay(4700)
            navVisible = false
        } else navVisible = true
    }
    LaunchedEffect(selectedTab) { navVisible = true }

    Box(Modifier.fillMaxSize().background(white)) {
        Column(Modifier.fillMaxSize().nestedScroll(scrollConnection)) {
            Box(Modifier.weight(1f)) {
                when (selectedTab) {
                    OdifyTab.Home -> OdifyHome(
                        songs, displayed, recent.isNotEmpty(), onImport, importing,
                        onPlay = ::playList
                    )
                    OdifyTab.Search -> OdifySearch(songs, onImport, ::playList)
                    OdifyTab.Library -> OdifyLibraryScreen(
                        songs, liked, ::playList, onImport, onRemove
                    )
                    OdifyTab.Profile -> OdifyProfile(songs.size, onImport, importing)
                }
            }
            if (active != null) {
                OdifyMiniPlayer(active, playback.isPlaying, active.id in liked,
                    onExpand = { onExpandedChange(true) },
                    onToggle = onTogglePlayback,
                    onLike = { toggleLike(active.id) })
            }
            AnimatedVisibility(
                visible = navVisible || !playback.isPlaying,
                enter = slideInVertically(tween(250)) { it } + fadeIn(tween(250)),
                exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(220))
            ) {
                OdifyBottomBar(selectedTab) {
                    selectedTab = it
                    navVisible = true
                }
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = slideInVertically(tween(310)) { it },
            exit = slideOutVertically(tween(270)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            if (active != null) {
                OdifyNowPlaying(
                    song = active,
                    playing = playback.isPlaying,
                    position = playback.position,
                    duration = playback.duration.takeIf { it > 0 } ?: active.durationMs,
                    liked = active.id in liked,
                    onDismiss = { onExpandedChange(false) },
                    onToggle = onTogglePlayback,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onSeek = onSeek,
                    onLike = { toggleLike(active.id) },
                    onShuffle = onShuffle,
                    onRepeat = onRepeat
                )
            }
        }
    }
}

@Composable
private fun OdifyHome(
    songs: List<OdifySong>,
    recent: List<OdifySong>,
    hasHistory: Boolean,
    onImport: () -> Unit,
    importing: Boolean,
    onPlay: (List<OdifySong>, Int) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 0.dp, top = 22.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(end = 20.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Good evening,", color = grey, fontSize = 13.sp)
                    Text("Music feels\nbetter here.", fontSize = 29.sp,
                        lineHeight = 31.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.9).sp, color = ink)
                }
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(50)).background(pale),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PersonOutline, contentDescription = "프로필",
                        tint = ink, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.height(18.dp))
            SearchHint(Modifier.padding(end = 20.dp), onImport)
            Spacer(Modifier.height(14.dp))
            HorizontalChips(listOf("All", "For You", "Chill", "J-Pop", "K-Pop"))
            Spacer(Modifier.height(27.dp))
            SectionTitle("Made for You")
            Spacer(Modifier.height(12.dp))
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(end = 20.dp)) {
                val cards = listOf("Your Mix", "Chill\nAfternoons", "J-Pop Mix", "Late Night")
                items(cards.size) { index ->
                    OdifyMixCard(cards[index], songs.getOrNull(index), index) {
                        if (songs.isNotEmpty()) onPlay(songs, index % songs.size) else onImport()
                    }
                }
            }
            Spacer(Modifier.height(25.dp))
            SectionTitle(if (hasHistory) "Recently Played" else "Recently Added")
            Spacer(Modifier.height(12.dp))
        }
        if (recent.isEmpty()) {
            item { EmptyMusicPrompt(onImport, importing) }
        } else {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(end = 20.dp)) {
                    items(recent.take(12), key = { it.id }) { song ->
                        val index = recent.indexOf(song)
                        Column(Modifier.width(83.dp).clickable { onPlay(recent, index) }) {
                            AlbumArt(song, Modifier.size(83.dp),
                                fallbackKey = song.id.hashCode())
                            Spacer(Modifier.height(6.dp))
                            Text(song.title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, color = ink)
                            Text(song.artist.ifEmpty { "Unknown artist" }, fontSize = 10.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, color = grey)
                        }
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(24.dp))
            SectionTitle("Your Music, Your Way")
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.fillMaxWidth().padding(end = 20.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(Color(0xFFF2F3F5))
                    .clickable(onClick = onImport)
                    .padding(18.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CloudDownload, null, tint = ink,
                        modifier = Modifier.size(25.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Google Drive 음악 가져오기", fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp, color = ink)
                        Text("${songs.size}곡 저장됨 · MP3 / M4A / FLAC",
                            color = grey, fontSize = 11.sp)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = grey)
                }
            }
            Spacer(Modifier.height(35.dp))
        }
    }
}

@Composable
private fun SearchHint(modifier: Modifier = Modifier, onTap: () -> Unit) {
    Row(
        modifier.fillMaxWidth().height(43.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(pale).clickable(onClick = onTap)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, null, tint = grey, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(9.dp))
        Text("Search songs, artists, or moods", fontSize = 12.sp,
            color = grey, modifier = Modifier.weight(1f))
        Icon(Icons.Default.Tune, null, tint = grey, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun HorizontalChips(options: List<String>) {
    var selected by remember { mutableStateOf(options.first()) }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(end = 20.dp)) {
        items(options) { label ->
            val active = label == selected
            Box(Modifier.clip(RoundedCornerShape(50))
                .background(if (active) ink else pale)
                .clickable { selected = label }
                .padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(label, color = if (active) white else Color(0xFF5B5D63),
                    fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Row(Modifier.fillMaxWidth().padding(end = 19.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 17.sp, color = ink, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f))
        Icon(Icons.Default.ChevronRight, null, tint = ink, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun OdifyMixCard(
    label: String,
    coverSong: OdifySong?,
    index: Int,
    onTap: () -> Unit
) {
    Box(
        Modifier.width(126.dp).height(167.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onTap)
    ) {
        AlbumArt(coverSong, Modifier.fillMaxSize(),
            radius = RoundedCornerShape(14.dp), fallbackKey = index + 9)
        Box(Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color.Transparent,
                Color.Transparent, Color.Black.copy(alpha = 0.81f)))
        ))
        Column(Modifier.align(Alignment.BottomStart).padding(11.dp)) {
            Text(label, color = white, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, lineHeight = 16.sp)
            Spacer(Modifier.height(3.dp))
            Text(if (coverSong != null) "Made for you" else "Add your music",
                color = white.copy(alpha = .78f), fontSize = 9.sp)
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(9.dp)
            .size(29.dp).clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = .94f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.PlayArrow, null, tint = ink, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun EmptyMusicPrompt(onImport: () -> Unit, importing: Boolean) {
    Box(Modifier.fillMaxWidth().padding(end = 20.dp)
        .clip(RoundedCornerShape(15.dp)).background(pale)
        .clickable(enabled = !importing, onClick = onImport).padding(20.dp)
    ) {
        Column {
            Text(if (importing) "음악을 가져오는 중…" else "음악을 추가해 보세요",
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            Text("Google Drive에서 음악을 선택하면 이곳에 표시됩니다.",
                color = grey, fontSize = 11.sp)
        }
    }
}

@Composable
private fun OdifyBottomBar(selected: OdifyTab, onSelected: (OdifyTab) -> Unit) {
    Row(Modifier.fillMaxWidth()
        .background(Color(0xFCFAFAFB))
        .height(63.dp).padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val icons = mapOf(
            OdifyTab.Home to Icons.Default.Home,
            OdifyTab.Search to Icons.Default.Search,
            OdifyTab.Library to Icons.Default.LibraryMusic,
            OdifyTab.Profile to Icons.Default.PersonOutline
        )
        val labels = mapOf(
            OdifyTab.Home to "Home", OdifyTab.Search to "Search",
            OdifyTab.Library to "Library", OdifyTab.Profile to "Profile"
        )
        OdifyTab.entries.forEach { tab ->
            Column(Modifier.weight(1f).fillMaxHeight()
                .clickable { onSelected(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(icons.getValue(tab), labels.getValue(tab),
                    tint = if (tab == selected) ink else Color(0xFF9BA0A7),
                    modifier = Modifier.size(21.dp))
                Spacer(Modifier.height(3.dp))
                Text(labels.getValue(tab), fontSize = 10.sp,
                    color = if (tab == selected) ink else grey,
                    fontWeight = if (tab == selected) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun OdifyMiniPlayer(
    song: OdifySong,
    playing: Boolean,
    liked: Boolean,
    onExpand: () -> Unit,
    onToggle: () -> Unit,
    onLike: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp)
            .height(58.dp)
            .shadow(9.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(glass)
            .border(1.dp, white.copy(alpha = .20f), RoundedCornerShape(20.dp))
            .clickable(onClick = onExpand)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AlbumArt(song, Modifier.size(43.dp), radius = RoundedCornerShape(12.dp),
            fallbackKey = song.id.hashCode())
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp, color = white, fontWeight = FontWeight.SemiBold)
            Text(song.artist.ifEmpty { "Unknown artist" }, fontSize = 10.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = white.copy(alpha = .69f))
        }
        IconButton(onClick = onLike, modifier = Modifier.size(37.dp)) {
            Icon(if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                "좋아요", tint = white, modifier = Modifier.size(19.dp))
        }
        Box(Modifier.size(37.dp)
            .clip(RoundedCornerShape(13.dp)).background(white)
            .clickable(onClick = onToggle), contentAlignment = Alignment.Center) {
            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                "재생·일시정지", tint = ink, modifier = Modifier.size(23.dp))
        }
    }
}

@Composable
private fun OdifySearch(
    songs: List<OdifySong>,
    onImport: () -> Unit,
    onPlay: (List<OdifySong>, Int) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(songs, query) {
        songs.filter { it.title.contains(query, true)
            || it.artist.contains(query, true) || it.album.contains(query, true) }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(24.dp))
        Text("Search", fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(19.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(pale).height(48.dp).padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, null, tint = grey, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(9.dp))
            BasicTextField(
                value = query, onValueChange = { query = it },
                textStyle = androidx.compose.ui.text.TextStyle(color = ink, fontSize = 14.sp),
                singleLine = true,
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) Text("노래, 아티스트, 앨범 검색",
                            color = grey, fontSize = 13.sp)
                        inner()
                    }
                }
            )
            if (query.isNotBlank()) Icon(Icons.Default.Close, "지우기",
                modifier = Modifier.size(20.dp).clickable { query = "" }, tint = grey)
        }
        Spacer(Modifier.height(22.dp))
        Text(if (query.isEmpty()) "내 음악" else "검색 결과 · ${filtered.size}",
            fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(11.dp))
        if (filtered.isEmpty()) {
            EmptyMusicPrompt(onImport, false)
        } else {
            LazyColumn {
                items(filtered, key = { it.id }) { song ->
                    OdifySongRow(song, onClick = {
                        onPlay(filtered, filtered.indexOf(song))
                    })
                }
            }
        }
    }
}

@Composable
private fun OdifyLibraryScreen(
    songs: List<OdifySong>,
    liked: Set<String>,
    onPlay: (List<OdifySong>, Int) -> Unit,
    onImport: () -> Unit,
    onRemove: (OdifySong) -> Unit
) {
    var selection by remember { mutableStateOf("Songs") }
    val modes = listOf("Songs", "Saved", "Albums", "Artists")
    val visible = when (selection) {
        "Saved" -> songs.filter { it.id in liked }
        "Albums" -> songs.distinctBy { it.album.ifEmpty { it.title } }
        "Artists" -> songs.distinctBy { it.artist }
        else -> songs
    }
    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp)) {
        Spacer(Modifier.height(23.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Your Library", fontSize = 26.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f), color = ink)
            IconButton(onClick = onImport) { Icon(Icons.Default.Add, "음악 가져오기") }
        }
        Spacer(Modifier.height(17.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            modes.forEach { mode ->
                Box(Modifier.clip(RoundedCornerShape(50))
                    .background(if (selection == mode) ink else pale)
                    .clickable { selection = mode }
                    .padding(horizontal = 13.dp, vertical = 8.dp)) {
                    Text(mode, fontSize = 11.sp,
                        color = if (selection == mode) white else Color(0xFF62646A))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Recently added", fontSize = 12.sp, color = grey,
                modifier = Modifier.weight(1f))
            Text("${visible.size} items", fontSize = 11.sp, color = grey)
        }
        Spacer(Modifier.height(9.dp))
        if (visible.isEmpty()) EmptyMusicPrompt(onImport, false)
        else LazyColumn {
            items(visible, key = { it.id }) { song ->
                OdifySongRow(
                    song = song,
                    onClick = {
                        val queue = if (selection == "Songs" || selection == "Saved")
                            visible else songs
                        onPlay(queue, queue.indexOf(song))
                    },
                    trailing = {
                        var showMenu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Default.MoreHoriz, "더보기", tint = grey)
                            }
                            DropdownMenu(expanded = showMenu,
                                onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("가져온 파일 삭제") },
                                    onClick = { showMenu = false; onRemove(song) },
                                    leadingIcon = { Icon(Icons.Default.DeleteOutline, null) }
                                )
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun OdifySongRow(
    song: OdifySong,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(Modifier.fillMaxWidth().height(67.dp)
        .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically) {
        AlbumArt(song, Modifier.size(49.dp), radius = RoundedCornerShape(10.dp),
            fallbackKey = song.id.hashCode())
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, color = ink, fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(song.artist.ifEmpty { "Unknown artist" },
                fontSize = 11.sp, color = grey,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) trailing() else Icon(Icons.Default.MoreHoriz,
            null, tint = Color(0xFFB7B9BE), modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun OdifyProfile(songCount: Int, onImport: () -> Unit, importing: Boolean) {
    Column(Modifier.fillMaxSize().padding(22.dp)) {
        Spacer(Modifier.height(15.dp))
        Text("Odify", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = ink)
        Text("Music for a calmer you.", fontSize = 13.sp, color = grey)
        Spacer(Modifier.height(27.dp))
        Text("My Music", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text("${songCount}곡이 안전하게 기기 안에 저장되어 있습니다.",
            fontSize = 13.sp, color = grey)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onImport, enabled = !importing,
            colors = ButtonDefaults.buttonColors(containerColor = ink),
            shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Default.CloudDownload, null)
            Spacer(Modifier.width(9.dp))
            Text(if (importing) "가져오는 중…" else "Google Drive에서 음악 선택")
        }
        Spacer(Modifier.height(23.dp))
        Text("로그인·광범위 저장소 권한 없이 Android 파일 선택기만 사용합니다.",
            fontSize = 12.sp, color = grey, lineHeight = 17.sp)
        Text("Mornye 및 YouTube 플레이어와 분리된 독립 테스트 앱입니다.",
            fontSize = 12.sp, color = grey, lineHeight = 17.sp)
    }
}
