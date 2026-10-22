package com.odify.nativeapp

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
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
private val grey = Color(0xFF83868D)
private val pale = Color(0xFFF4F4F6)
private val white = Color.White
private val glass = Brush.horizontalGradient(
    listOf(Color(0xF22C3036), Color(0xF026292E))
)

enum class OdifyTab { Home, Search, Library, Profile }

@Composable
fun OdifySplash() {
    Box(Modifier.fillMaxSize().background(white), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Reuse the precise monochrome Odify icon in the system launcher.
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(R.drawable.odify_symbol_black),
                contentDescription = "Odify",
                modifier = Modifier.size(96.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text("Odify", fontSize = 29.sp, fontWeight = FontWeight.Bold,
                letterSpacing = (-1.3).sp, color = ink)
        }
    }
}

/** Bound decoded artwork retained across adjacent-track transitions. */
private object OdifyCoverMemory {
    private val images = object : LinkedHashMap<String, ImageBitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, ImageBitmap>?
        ): Boolean = size > 24
    }
    @Synchronized fun peek(path: String?): ImageBitmap? =
        path?.let { images[it] }
    @Synchronized fun put(path: String, art: ImageBitmap) {
        images[path] = art
    }
}

@Composable
fun AlbumArt(
    song: OdifySong?,
    modifier: Modifier = Modifier,
    radius: androidx.compose.ui.graphics.Shape = RoundedCornerShape(14.dp),
    fallbackKey: Int = 0,
    muted: Boolean = false
) {
    val coverPath = song?.artworkPath
    val image by produceState<ImageBitmap?>(
        initialValue = OdifyCoverMemory.peek(coverPath),
        key1 = coverPath
    ) {
        value = OdifyCoverMemory.peek(coverPath) ?: withContext(Dispatchers.IO) {
            runCatching {
                coverPath?.let { path ->
                    val options = BitmapFactory.Options().apply {
                        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                    }
                    BitmapFactory.decodeFile(path, options)?.asImageBitmap()?.also { art ->
                        OdifyCoverMemory.put(path, art)
                    }
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
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                colorFilter = if (muted) ColorFilter.colorMatrix(
                    ColorMatrix().apply { setToSaturation(0.20f) }
                ) else null)
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
    onImportOffline: () -> Unit,
    onPlay: (List<OdifySong>, Int) -> Unit,
    onTogglePlayback: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onRemove: (OdifySong) -> Unit,
    onEdit: (OdifySong, String, String, String) -> Unit
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
    // Navigation state is gesture-driven and the mini-player is a separate surface.

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


    // Both controls live in a stable overlay. Animating layout height caused
    // the old mini-player to snap down after the navigation animation finished.
    val navShown = navVisible || active == null
    val navFraction by animateFloatAsState(
        targetValue = if (navShown) 1f else 0f,
        animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
        label = "odify-nav-and-mini-linked"
    )
    LaunchedEffect(active?.id, interaction, selectedTab, expanded) {
        if (active == null) {
            navVisible = true
        } else if (!expanded) {
            delay(3400)
            navVisible = false
        }
    }
    LaunchedEffect(selectedTab) { navVisible = true }

    // Extend rounded nav background behind the Android gesture indicator.
    val gestureInset = WindowInsets.navigationBars.asPaddingValues()
        .calculateBottomPadding()
    val barHeight = 66.dp + gestureInset
    val barHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        barHeight.toPx()
    }
    val gestureInsetPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        gestureInset.toPx()
    }
    Box(Modifier.fillMaxSize().background(white)) {
        // Only the light screens are inset. Fullscreen player must retain
        // status-bar background and apply its own insets exactly once.
        Box(Modifier.fillMaxSize().statusBarsPadding().clipToBounds()) {
        Box(
            Modifier.fillMaxSize()
                // Scroll content fills the screen: no fixed opaque 135dp bottom slab.
                // Each scrollable page owns its last-item clearance instead.
                .pointerInput(active?.id, selectedTab) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var previousY = down.position.y
                        var net = 0f
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pointer = event.changes.firstOrNull { it.id == down.id }
                                ?: break
                            if (!pointer.pressed) break
                            net += pointer.position.y - previousY
                            previousY = pointer.position.y
                            if (kotlin.math.abs(net) >= 27.dp.toPx()) {
                                if (active != null) {
                                    val shouldShow = net > 0f
                                    if (navVisible != shouldShow) {
                                        navVisible = shouldShow
                                    }
                                    interaction++
                                }
                                net = 0f
                            }
                        }
                    }
                }
        ) {
            when (selectedTab) {
                OdifyTab.Home -> OdifyHome(
                    songs, displayed, recent.isNotEmpty(), onImport, importing,
                    onSearch = { selectedTab = OdifyTab.Search },
                    onPlay = ::playList
                )
                OdifyTab.Search -> OdifySearch(songs, onImport, ::playList)
                OdifyTab.Library -> OdifyLibraryScreen(
                    songs, liked, ::playList, onImport, onRemove, onEdit
                )
                OdifyTab.Profile -> OdifyProfile(songs, onImport, onImportOffline, importing)
            }
        }

        // Two visually separate floating surfaces on ONE animation timeline.
        // Fade the navigation as it exits instead of dragging its solid panel
        // across scrollable content or leaving an opaque rectangular ghost.
        Box(
            Modifier.align(Alignment.BottomCenter)
                .graphicsLayer {
                    translationY = (1f - navFraction) * barHeightPx
                    alpha = navFraction
                }
        ) {
            OdifyBottomBar(selectedTab, gestureInset) {
                selectedTab = it
                navVisible = true
                interaction++
            }
        }
        if (active != null) {
            Box(
                Modifier.align(Alignment.BottomCenter)
                    .graphicsLayer {
                        translationY = -gestureInsetPx -
                            navFraction * (barHeightPx - gestureInsetPx)
                    }
            ) {
                OdifyMiniPlayer(active, playback.isPlaying, active.id in liked,
                    onExpand = { onExpandedChange(true) },
                    onToggle = onTogglePlayback,
                    onLike = { toggleLike(active.id) })
            }
        }

        } // light UI layer; mini and nav share safe-area coordinates
        AnimatedVisibility(
            visible = expanded,
            enter = slideInVertically(tween(340, easing = FastOutSlowInEasing)) { it },
            exit = slideOutVertically(tween(320, easing = FastOutSlowInEasing)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            if (active != null) {
                OdifyNowPlaying(
                    song = active,
                    previousSong = songs.firstOrNull { it.id == playback.previousMediaId },
                    nextSong = songs.firstOrNull { it.id == playback.nextMediaId },
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
    onSearch: () -> Unit,
    onPlay: (List<OdifySong>, Int) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 0.dp, top = 17.dp, bottom = 156.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(end = 20.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Good evening,", color = grey, fontSize = 13.sp)
                    Text("Music feels\nbetter here.", fontSize = 27.sp,
                        lineHeight = 29.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.9).sp, color = ink)
                }
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(50)).background(pale),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PersonOutline, contentDescription = "프로필",
                        tint = ink, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.height(17.dp))
            SearchHint(Modifier.padding(end = 20.dp), onSearch)
            Spacer(Modifier.height(12.dp))
            HorizontalChips(listOf("All", "For You", "Chill", "J-Pop", "K-Pop"))
            Spacer(Modifier.height(25.dp))
            SectionTitle("Made for You")
            Spacer(Modifier.height(12.dp))
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp),
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
                            if (song.artist.isNotBlank()) {
                                Text(song.artist, fontSize = 10.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = grey)
                            }
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
                        Text("${songs.size}곡 · Drive 연결 또는 오프라인 저장",
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
        modifier.fillMaxWidth().height(42.dp)
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
                .padding(horizontal = 13.dp, vertical = 7.dp)) {
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
        Modifier.width(146.dp).height(176.dp)
            .clip(RoundedCornerShape(15.dp))
            .clickable(onClick = onTap)
    ) {
        AlbumArt(coverSong, Modifier.fillMaxSize(),
            radius = RoundedCornerShape(15.dp), fallbackKey = index + 9,
            muted = true)
        Box(Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color.Transparent,
                Color.Transparent, Color.Black.copy(alpha = 0.75f)))
        ))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 12.dp, end = 38.dp)) {
            Text(label, color = white, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, lineHeight = 15.sp)
            Spacer(Modifier.height(3.dp))
            Text(if (coverSong != null) "Made for you" else "Add your music",
                color = white.copy(alpha = .78f), fontSize = 9.sp)
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(10.dp)
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
private fun OdifyBottomBar(
    selected: OdifyTab,
    bottomInset: androidx.compose.ui.unit.Dp,
    onSelected: (OdifyTab) -> Unit
) {
    val navShape = RoundedCornerShape(topStart = 23.dp, topEnd = 23.dp)
    Row(Modifier.fillMaxWidth()
        .height(66.dp + bottomInset)
        .shadow(10.dp, navShape, clip = false)
        .clip(navShape)
        .background(Brush.verticalGradient(listOf(
            Color(0xF9FFFFFF), Color(0xF4F6F6F8)
        )))
        .border(0.7.dp, Color(0xFFE9E9EB), navShape)
        .padding(start = 12.dp, end = 12.dp, bottom = bottomInset),
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
        Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 5.dp)
            .height(56.dp)
            .shadow(8.dp, RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp))
            .background(glass)
            .border(0.8.dp, white.copy(alpha = .30f), RoundedCornerShape(22.dp))
            .clickable(onClick = onExpand)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AlbumArt(song, Modifier.size(41.dp), radius = RoundedCornerShape(11.dp),
            fallbackKey = song.id.hashCode())
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp, color = white, fontWeight = FontWeight.SemiBold)
            if (song.artist.isNotBlank()) Text(song.artist, fontSize = 10.sp,
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
        Text("Search", fontSize = 25.sp, fontWeight = FontWeight.Bold)
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
            LazyColumn(contentPadding = PaddingValues(bottom = 155.dp)) {
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
    onRemove: (OdifySong) -> Unit,
    onEdit: (OdifySong, String, String, String) -> Unit
) {
    var editingSong by remember { mutableStateOf<OdifySong?>(null) }
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
            Text("Your Library", fontSize = 24.sp, fontWeight = FontWeight.Bold,
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
        else LazyColumn(contentPadding = PaddingValues(bottom = 155.dp)) {
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
                                    text = { Text("곡 정보 수정") },
                                    onClick = { showMenu = false; editingSong = song },
                                    leadingIcon = { Icon(Icons.Default.Edit, null) }
                                )
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
    editingSong?.let { track ->
        OdifyTrackEditDialog(track,
            onDismiss = { editingSong = null },
            onSave = { title, artist, album ->
                onEdit(track, title, artist, album)
                editingSong = null
            })
    }
}

@Composable
private fun OdifyTrackEditDialog(
    song: OdifySong,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit
) {
    var title by remember(song.id) { mutableStateOf(song.title) }
    var artist by remember(song.id) { mutableStateOf(song.artist) }
    var album by remember(song.id) { mutableStateOf(song.album) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("곡 정보 수정", fontWeight = FontWeight.SemiBold, color = ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("원본 파일은 변경되지 않고 Odify의 표시 정보만 수정됩니다.",
                    fontSize = 12.sp, color = grey)
                OutlinedTextField(title, { title = it }, label = { Text("곡명") },
                    singleLine = true)
                OutlinedTextField(artist, { artist = it }, label = { Text("아티스트") },
                    singleLine = true)
                OutlinedTextField(album, { album = it }, label = { Text("앨범") },
                    singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(title.trim(), artist.trim(), album.trim()) },
                enabled = title.isNotBlank()
            ) { Text("저장", color = ink) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소", color = grey) } },
        containerColor = white,
        shape = RoundedCornerShape(22.dp)
    )
}

@Composable
private fun OdifySongRow(
    song: OdifySong,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(Modifier.fillMaxWidth().height(64.dp)
        .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically) {
        AlbumArt(song, Modifier.size(49.dp), radius = RoundedCornerShape(10.dp),
            fallbackKey = song.id.hashCode())
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, color = ink, fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            if (song.artist.isNotBlank()) {
                Text(song.artist, fontSize = 11.sp, color = grey,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) trailing() else Icon(Icons.Default.MoreHoriz,
            null, tint = Color(0xFFB7B9BE), modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun OdifyProfile(
    songs: List<OdifySong>,
    onImport: () -> Unit,
    onImportOffline: () -> Unit,
    importing: Boolean
) {
    val cloudCount = songs.count { it.isCloud }
    val offlineCount = songs.size - cloudCount
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 21.dp, end = 21.dp, bottom = 170.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Your Space", fontSize = 25.sp, fontWeight = FontWeight.Bold,
            color = ink, letterSpacing = (-0.6).sp)
        Spacer(Modifier.height(4.dp))
        Text("Music for a calmer you.", fontSize = 12.sp, color = grey)
        Spacer(Modifier.height(27.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp))
                .background(pale).padding(horizontal = 19.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(49.dp).clip(RoundedCornerShape(15.dp))
                    .background(ink), contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.MusicNote, null, tint = white,
                    modifier = Modifier.size(27.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("My Music", color = ink, fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("${songs.size}곡 · Drive ${cloudCount}곡 · 오프라인 ${offlineCount}곡",
                    color = grey, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(33.dp))
        Text("Music sources", color = ink, fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(13.dp))
        OdifySourceRow(
            icon = Icons.Default.CloudQueue,
            title = "Google Drive · 연결",
            subtitle = "음원을 복사하지 않고 파일 공급자에서 열기",
            enabled = !importing, onClick = onImport
        )
        HorizontalDivider(color = Color(0xFFF1F1F3))
        OdifySourceRow(
            icon = Icons.Default.FileDownload,
            title = "오프라인 음악 저장",
            subtitle = "선택한 음원을 휴대폰에 복사하여 보관",
            enabled = !importing, onClick = onImportOffline
        )
        Spacer(Modifier.height(11.dp))
        Text(
            "Drive 앱이 데이터를 내부적으로 임시 다운로드할 수 있습니다. " +
                "인터넷 연결이나 Drive 앱 로그인 상태에 따라 재생이 제한될 수 있습니다.",
            color = grey, fontSize = 11.sp, lineHeight = 16.sp
        )
        Spacer(Modifier.height(28.dp))
        Text("Playback & storage", color = ink, fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(15.dp))
        OdifyInfoRow(Icons.Default.Headphones, "Audio engine",
            "Android Media3 · background playback")
        Spacer(Modifier.height(7.dp))
        OdifyInfoRow(Icons.Default.CloudDone, "Smart stream cache",
            "최근 재생 데이터 자동 캐시 · 최대 192MB")
        Spacer(Modifier.height(7.dp))
        OdifyInfoRow(Icons.Default.Info, "About",
            "Odify Native · UI Preview 6")
    }
}

@Composable
private fun OdifySourceRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(11.dp))
                .background(pale),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = ink, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = ink,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(subtitle, fontSize = 11.sp, color = grey)
        }
        Icon(Icons.Default.ChevronRight, null, tint = grey,
            modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun OdifyInfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(37.dp).clip(RoundedCornerShape(11.dp))
            .background(pale), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = ink, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(13.dp))
        Column {
            Text(title, color = ink, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = grey, fontSize = 11.sp)
        }
    }
}
