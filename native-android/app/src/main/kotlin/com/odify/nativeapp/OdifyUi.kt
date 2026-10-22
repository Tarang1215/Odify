package com.odify.nativeapp

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
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
    // produceState(key1=...) restarts its loader but keeps the OLD image value
    // for a frame when the song changes. remember(key) recreates state, so
    // neighbor transitions can never flash the previously selected cover.
    val imageState = remember(coverPath) {
        mutableStateOf(OdifyCoverMemory.peek(coverPath))
    }
    LaunchedEffect(coverPath) {
        if (imageState.value == null) {
            imageState.value = withContext(Dispatchers.IO) {
                runCatching {
                    coverPath?.let { path ->
                        // Covers from external files can be 4K/8K. Decoding
                        // full-resolution bitmaps for tiny list rows causes
                        // avoidable heap spikes and can kill the Home screen.
                        val bounds = BitmapFactory.Options().apply {
                            inJustDecodeBounds = true
                        }
                        BitmapFactory.decodeFile(path, bounds)
                        if (bounds.outWidth !in 1..16000 ||
                            bounds.outHeight !in 1..16000) null
                        else {
                            var sample = 1
                            while (bounds.outWidth / sample > 768 ||
                                bounds.outHeight / sample > 768) sample *= 2
                            val options = BitmapFactory.Options().apply {
                                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                                inSampleSize = sample
                            }
                            BitmapFactory.decodeFile(path, options)
                                ?.asImageBitmap()?.also { art ->
                                    OdifyCoverMemory.put(path, art)
                                }
                        }
                    }
                }.getOrNull()
            }
        }
    }
    val image = imageState.value
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
    driveConnected: Boolean,
    driveBusy: Boolean,
    driveStatus: String,
    driveFolder: String,
    onDriveConnect: () -> Unit,
    onDriveSync: () -> Unit,
    onDriveFolderChange: (String) -> Unit,
    youtubeConnected: Boolean,
    youtubeStatus: String,
    onConnectYoutube: () -> Unit,
    phoneFolderStatus: String,
    onChoosePhoneFolder: () -> Unit,
    onRefreshPhoneFolder: () -> Unit,
    onMasterUpdated: (List<MasterTrack>) -> Unit,
    uploadingToDrive: Boolean,
    uploadStatus: String,
    onUploadToDrive: () -> Unit,
    importing: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onImport: () -> Unit,
    onImportOffline: () -> Unit,
    onPlay: (List<OdifySong>, Int) -> Unit,
    onMoveQueue: (Int, Int) -> Unit,
    onPlayYoutubeMatched: (List<OdifySong>,Int) -> Unit,
    onSelectQueue: (Int) -> Unit,
    onTogglePlayback: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onSaveDevice: (OdifySong) -> Unit,
    onRemove: (OdifySong) -> Unit,
    onEdit: (OdifySong, String, String, String) -> Unit,
    onChooseArtwork: (OdifySong) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("odify_native_ui", 0) }
    val playlistStore = remember { OdifyPlaylists(context.applicationContext) }
    var playlistVersion by remember { mutableIntStateOf(0) }
    val playlists = remember(playlistVersion) { playlistStore.all() }
    var playlistDialogTrack by remember { mutableStateOf<OdifySong?>(null) }
    var openLibraryPlaylists by remember { mutableIntStateOf(0) }
    fun createPlaylist(name: String): String = runCatching {
        playlistStore.create(name)
        playlistVersion++
        ""
    }.getOrElse { it.message ?: "플레이리스트 생성 실패" }
    fun addToPlaylist(playlistId: String, songId: String): String = runCatching {
        val added = playlistStore.addSong(playlistId, songId)
        playlistVersion++
        if (!added) "이미 이 플레이리스트에 있는 곡입니다." else ""
    }.getOrElse { it.message ?: "플레이리스트 저장 실패" }
    var selectedTab by remember { mutableStateOf(OdifyTab.Home) }
    val tabHistory = remember { mutableStateListOf<OdifyTab>() }
    fun navigateTo(tab: OdifyTab) {
        if (tab != selectedTab) {
            tabHistory.add(selectedTab)
            if (tabHistory.size > 20) tabHistory.removeAt(0)
            selectedTab = tab
        }
    }
    var queueOpen by remember { mutableStateOf(false) }
    var youtubeOpen by remember { mutableStateOf(false) }
    BackHandler(enabled = queueOpen || expanded ||
        selectedTab != OdifyTab.Home || tabHistory.isNotEmpty()) {
        when {
            queueOpen -> queueOpen = false
            expanded -> onExpandedChange(false)
            tabHistory.isNotEmpty() ->
                selectedTab = tabHistory.removeAt(tabHistory.lastIndex)
            else -> selectedTab = OdifyTab.Home
        }
    }
    var liked by remember {
        mutableStateOf(prefs.getStringSet("likes", emptySet())?.toSet() ?: emptySet())
    }
    var history by remember {
        mutableStateOf(prefs.getString("history", "")!!.split("|").filter { it.isNotBlank() })
    }
    var navVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    // Navigation state is gesture-driven and the mini-player is a separate surface.

    val songIndex = remember(songs) { songs.associateBy(OdifySong::id) }
    val active = songIndex[playback.mediaId]
    val recent = remember(history, songIndex) { history.mapNotNull(songIndex::get) }
    val displayed = remember(recent, songs) {
        OdifySongPresentation.visible(if (recent.isNotEmpty()) recent else songs.asReversed())
    }

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
                    songs = songs,
                    recent = displayed,
                    hasHistory = recent.isNotEmpty(),
                    importing = importing,
                    onImport = onImport,
                    onSearch = { navigateTo(OdifyTab.Search) },
                    onProfile = { navigateTo(OdifyTab.Profile) },
                    onLibrary = { navigateTo(OdifyTab.Library) },
                    onPlaylists = {
                        openLibraryPlaylists++
                        navigateTo(OdifyTab.Library)
                    },
                    onPlay = ::playList,
                    playlists = playlists,
                    onPlaylistPlay = { list ->
                        val tracks = playlistStore.resolved(list, songs)
                        if (tracks.isNotEmpty()) playList(tracks, 0)
                        else {
                            openLibraryPlaylists++
                            navigateTo(OdifyTab.Library)
                        }
                    }
                )
                OdifyTab.Search -> OdifySearch(songs, onImport, ::playList)
                OdifyTab.Library -> OdifyLibraryScreen(
                    songs = songs, liked = liked, onPlay = ::playList,
                    onImport = onImport, onRemove = onRemove, onEdit = onEdit,
                    onChooseArtwork = onChooseArtwork,
                    playlists = playlists, playlistFocus = openLibraryPlaylists,
                    onCreatePlaylist = ::createPlaylist,
                    onDeletePlaylist = { id ->
                        playlistStore.delete(id); playlistVersion++
                    },
                    onRemoveFromPlaylist = { listId, trackId ->
                        playlistStore.removeSong(listId, trackId); playlistVersion++
                    }
                )
                OdifyTab.Profile -> OdifyProfile(
                    songs, onImport, onImportOffline, importing,
                    driveConnected, driveBusy, driveStatus, driveFolder,
                    onDriveConnect, onDriveSync, onDriveFolderChange,
                    youtubeConnected, youtubeStatus, onConnectYoutube,
                    { youtubeOpen = true },
                    phoneFolderStatus, onChoosePhoneFolder, onRefreshPhoneFolder,
                    onMasterUpdated, uploadingToDrive, uploadStatus, onUploadToDrive
                )
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
                navigateTo(it)
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
        // Full player morph: rounded floating panel grows out of the mini area.
        // Keeping this composed throughout exit removes the rectangular
        // slide-and-snap seen with AnimatedVisibility's raw offset.
        val fullTransition = updateTransition(
            targetState = expanded, label = "odify-expanded-player"
        )
        val fullProgress by fullTransition.animateFloat(
            transitionSpec = {
                tween(durationMillis = 440, easing = FastOutSlowInEasing)
            },
            label = "player-expand-progress"
        ) { open -> if (open) 1f else 0f }
        if ((expanded || fullProgress > 0.001f) && active != null) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = fullProgress * 0.16f))
                    .clickable(onClick = { onExpandedChange(false) })
            )
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        val closed = 1f - fullProgress
                        translationY = size.height * 0.59f * closed
                        scaleX = 1f - closed * 0.045f
                        scaleY = 1f - closed * 0.13f
                        alpha = (fullProgress * 1.8f).coerceIn(0.0f, 1.0f)
                        shape = RoundedCornerShape((30f * closed).dp)
                        clip = true
                    }
            ) {
                OdifyNowPlaying(
                    song = active,
                    previousSong = songIndex[playback.previousMediaId],
                    nextSong = songIndex[playback.nextMediaId],
                    playing = playback.isPlaying,
                    position = playback.position,
                    duration = playback.duration.takeIf { it > 0 } ?: active.durationMs,
                    liked = active.id in liked,
                    shuffleEnabled = playback.shuffleEnabled,
                    repeatOne = playback.repeatOne,
                    onDismiss = { onExpandedChange(false) },
                    onToggle = onTogglePlayback,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onSeek = onSeek,
                    onLike = { toggleLike(active.id) },
                    onShuffle = onShuffle,
                    onRepeat = onRepeat,
                    onShowQueue = { queueOpen = true },
                    onAddToPlaylist = { playlistDialogTrack = active },
                    onSaveDevice = { onSaveDevice(active) }
                )
            }
        }
        if (youtubeOpen) {
            OdifyYoutubeScreen(
                localSongs=songs,connected=youtubeConnected,
                onConnect=onConnectYoutube,
                onPlayLocal={ queue,index -> onPlayYoutubeMatched(queue,index);youtubeOpen=false },
                onClose={youtubeOpen=false}
            )
        }
        if (queueOpen) {
            OdifyQueueDialog(
                items=remember(playback.queueIds, songIndex) { playback.queueIds.map { id ->
                    songIndex[id]
                        ?: OdifySong(id, "라이브러리에서 숨긴 곡", "", "", "")
                } },
                entryKeys=playback.queueKeys,
                currentIndex=playback.currentQueueIndex,
                onMove=onMoveQueue,
                onSelect={ index -> onSelectQueue(index); queueOpen=false },
                onClose={ queueOpen=false }
            )
        }
        playlistDialogTrack?.let { target ->
            OdifyPlaylistPicker(
                song = target, playlists = playlists,
                onCreateAndAdd = { name, songId ->
                    runCatching { playlistStore.create(name) }.fold(
                        onSuccess = { list ->
                            playlistVersion++
                            addToPlaylist(list.id, songId)
                        },
                        onFailure = { it.message ?: "생성 실패" }
                    )
                },
                onAdd = ::addToPlaylist,
                onDismiss = { playlistDialogTrack = null }
            )
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
    onProfile: () -> Unit,
    onLibrary: () -> Unit,
    onPlaylists: () -> Unit,
    onPlay: (List<OdifySong>, Int) -> Unit,
    playlists: List<OdifyPlaylist>,
    onPlaylistPlay: (OdifyPlaylist) -> Unit
) {
    // Lightweight, explainable, on-device discovery. Real genre metadata
    // drives each mix; no remote recommendations, opaque claims or fake tracks.
    val vibeMixes = remember(songs, recent) {
        fun tagged(vararg tags: String): List<OdifySong> =
            songs.filter { track ->
                tags.any { t -> track.genre.contains(t, ignoreCase = true) }
            }.distinctBy { it.id }.take(40)
        listOf(
            "잔잔한 밤" to tagged("발라드", "r&b", "재즈", "jazz", "포크", "acoustic", "lo-fi"),
            "에너지 충전" to tagged("록", "rock", "힙합", "hip-hop", "dance", "edm"),
            "팝 플레이리스트" to tagged("k-pop", "j-pop", "pop")
        ).filter { it.second.size >= 2 } +
            listOfNotNull(
                recent.take(25).takeIf { it.size >= 2 }?.let { "최근 취향 다시 듣기" to it }
            )
    }
    // Home shows only actual songs and user-created playlists. Drive accounts,
    // permissions, diagnostics and syncing belong under Profile / Settings.
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp,
            top = 16.dp, bottom = 158.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("ODIFY", color = ink, fontSize = 24.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = (-0.7).sp)
                    Text("오늘은 어떤 음악을 들을까요?", color = grey, fontSize = 12.sp)
                }
                IconButton(onClick = onProfile,
                    modifier = Modifier.size(48.dp)
                        .clip(RoundedCornerShape(50)).background(pale)) {
                    Icon(Icons.Default.PersonOutline, "프로필 및 설정",
                        tint = ink, modifier = Modifier.size(25.dp))
                }
            }
            Spacer(Modifier.height(21.dp))
            SearchHint(onTap = onSearch)
            Spacer(Modifier.height(23.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp))
                .background(pale).clickable(onClick = onLibrary)
                .padding(horizontal = 17.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LibraryMusic, null, tint = ink,
                    modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("내 음악 라이브러리", color = ink, fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold)
                    Text("${songs.size}곡 · 플레이리스트 ${playlists.size}개",
                        color = grey, fontSize = 11.sp)
                }
                Icon(Icons.Default.ChevronRight, "라이브러리 열기", tint = grey)
            }
            Spacer(Modifier.height(29.dp))
            SectionTitle(if (hasHistory) "최근 들은 음악" else "최근 추가된 음악")
            Spacer(Modifier.height(13.dp))
        }
        if (recent.isEmpty()) {
            item {
                EmptyMusicPrompt(onImport, importing)
                Spacer(Modifier.height(25.dp))
            }
        } else {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                    items(recent.take(12), key = { it.id }) { song ->
                        val index = recent.indexOfFirst { it.id == song.id }
                        Column(Modifier.width(102.dp)
                            .clickable { if (index >= 0) onPlay(recent, index) }) {
                            AlbumArt(song, Modifier.size(102.dp),
                                fallbackKey = song.id.hashCode())
                            Spacer(Modifier.height(8.dp))
                            Text(song.title, fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, color = ink)
                            Text(song.artist.ifBlank { "아티스트 미상" },
                                fontSize = 10.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, color = grey)
                        }
                    }
                }
                Spacer(Modifier.height(29.dp))
            }
        }
        if (vibeMixes.isNotEmpty()) {
            item {
                SectionTitle("Vibe Mix · 라이브러리 탐색")
                Spacer(Modifier.height(4.dp))
                Text("보유곡 장르 태그 기반 · 추천 알고리즘은 준비 중",
                    color = grey, fontSize = 11.sp)
                Spacer(Modifier.height(13.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(vibeMixes, key = { it.first }) { (label, tracks) ->
                        OdifyMixCard(
                            label = label, coverSong = tracks.firstOrNull(),
                            index = label.hashCode(),
                            onTap = { if (tracks.isNotEmpty()) onPlay(tracks, 0) }
                        )
                    }
                }
                Spacer(Modifier.height(26.dp))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionTitle("내 플레이리스트") }
                TextButton(onClick = onPlaylists) {
                    Text("모두 보기", color = grey, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (playlists.isEmpty()) {
            item {
                Text("재생 화면의 ⋮ 메뉴에서 나만의 플레이리스트를 만들 수 있습니다.",
                    fontSize = 12.sp, color = grey,
                    modifier = Modifier.padding(vertical = 12.dp))
            }
        } else {
            items(playlists.take(5), key = { it.id }) { list ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .clickable { onPlaylistPlay(list) }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                        .background(pale), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.QueueMusic, null, tint = ink)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(list.name, color = ink, fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Text("${list.songIds.size}곡", color = grey, fontSize = 11.sp)
                    }
                    Icon(Icons.Default.PlayArrow, "플레이리스트 재생", tint = ink)
                }
            }
        }
        item { Spacer(Modifier.height(22.dp)) }
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
    // Only actionable rows have chevrons; this is a static section heading.
    Text(title, fontSize = 17.sp, color = ink, fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth())
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
            Text("내 라이브러리 · 장르 기반",
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
            Text("내 음악을 추가하세요. Drive 계정·폴더 연결은 프로필에서 관리합니다.",
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
    var genre by remember { mutableStateOf("All") }
    var showDuplicates by remember { mutableStateOf(false) }
    val genres = remember(songs) {
        listOf("All") + songs.map { it.genre.substringBefore(" / ").trim() }
            .filter { it.isNotBlank() }.distinct().sorted().take(60)
    }
    val filtered = remember(songs, query, genre, showDuplicates) {
        val matches = songs.filter {
            (genre == "All" || it.genre.startsWith(genre, ignoreCase = true)) &&
            (it.title.contains(query, true) || it.artist.contains(query, true) ||
             it.album.contains(query, true) || it.genre.contains(query, true) ||
             it.lyrics.contains(query, true))
        }
        OdifySongPresentation.visible(matches, showDuplicates)
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
                        if (query.isEmpty()) Text("노래, 가수, 앨범, 장르, 가사 검색",
                            color = grey, fontSize = 13.sp)
                        inner()
                    }
                }
            )
            if (query.isNotBlank()) Icon(Icons.Default.Close, "지우기",
                modifier = Modifier.size(20.dp).clickable { query = "" }, tint = grey)
        }
        Spacer(Modifier.height(14.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            items(genres) { option ->
                val selected = genre == option
                Box(
                    Modifier.clip(RoundedCornerShape(30.dp))
                        .background(if (selected) ink else pale)
                        .clickable { genre = option }
                        .padding(horizontal = 13.dp, vertical = 8.dp)
                ) {
                    Text(option, fontSize = 11.sp,
                        color = if (selected) white else grey)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (query.isEmpty()) "내 음악" else "검색 결과 · ${filtered.size}",
                fontSize = 16.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            Text(if (showDuplicates) "중복 숨기기" else "중복 포함 보기",
                fontSize = 11.sp, color = grey,
                modifier = Modifier.clickable { showDuplicates = !showDuplicates }
                    .padding(horizontal = 6.dp, vertical = 8.dp))
        }
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
    onEdit: (OdifySong, String, String, String) -> Unit,
    onChooseArtwork: (OdifySong) -> Unit,
    playlists: List<OdifyPlaylist>,
    playlistFocus: Int,
    onCreatePlaylist: (String) -> String,
    onDeletePlaylist: (String) -> Unit,
    onRemoveFromPlaylist: (String, String) -> Unit
) {
    var editingSong by remember { mutableStateOf<OdifySong?>(null) }
    var selection by remember { mutableStateOf("Songs") }
    LaunchedEffect(playlistFocus) {
        if (playlistFocus > 0) selection = "Playlists"
    }
    var showDuplicates by remember { mutableStateOf(false) }
    val modes = listOf("Songs", "Saved", "Playlists", "Albums", "Artists")
    val rawVisible = when (selection) {
        "Saved" -> songs.filter { it.id in liked }
        "Albums" -> songs.distinctBy { it.album.ifEmpty { it.title } }
        "Artists" -> songs.distinctBy { it.artist }
        else -> songs
    }
    val visible = OdifySongPresentation.visible(rawVisible, showDuplicates)
    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp)) {
        Spacer(Modifier.height(23.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Your Library", fontSize = 24.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f), color = ink)
            IconButton(onClick = onImport) { Icon(Icons.Default.Add, "음악 가져오기") }
        }
        Spacer(Modifier.height(17.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
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
        Spacer(Modifier.height(12.dp))
        if (selection == "Playlists") {
            OdifyPlaylistLibrary(
                playlists = playlists, songs = songs, onPlay = onPlay,
                onCreate = onCreatePlaylist, onDelete = onDeletePlaylist,
                onRemoveSong = onRemoveFromPlaylist
            )
        } else {
        Text(
            if (showDuplicates) "중복 파일 숨기기" else "MP3 / M4A 중복 파일 모두 보기",
            color = grey, fontSize = 11.sp,
            modifier = Modifier.clickable { showDuplicates = !showDuplicates }
                .padding(vertical = 7.dp)
        )
        Spacer(Modifier.height(12.dp))
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
                        val queue = visible
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
                                if (song.id.startsWith("gdrive:")) {
                                    DropdownMenuItem(
                                        text = { Text("앨범커버 직접 지정") },
                                        onClick = {
                                            showMenu = false
                                            onChooseArtwork(song)
                                        },
                                        leadingIcon = { Icon(Icons.Default.Image, null) }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(if (song.id.startsWith("gdrive:") || song.isPhoneFolder)
                                        "내 라이브러리에서 숨기기" else "가져온 파일 삭제") },
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
    importing: Boolean,
    driveConnected: Boolean,
    driveBusy: Boolean,
    driveStatus: String,
    driveFolder: String,
    onDriveConnect: () -> Unit,
    onDriveSync: () -> Unit,
    onDriveFolderChange: (String) -> Unit,
    youtubeConnected: Boolean,
    youtubeStatus: String,
    onConnectYoutube: () -> Unit,
    onOpenYoutube: () -> Unit,
    phoneFolderStatus: String,
    onChoosePhoneFolder: () -> Unit,
    onRefreshPhoneFolder: () -> Unit,
    onMasterUpdated: (List<MasterTrack>) -> Unit,
    uploadingToDrive: Boolean,
    uploadStatus: String,
    onUploadToDrive: () -> Unit
) {
    var showMaster by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    val cloudCount = songs.count { it.isCloud }
    val offlineCount = songs.size - cloudCount
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = LocalClipboardManager.current
    val pilotStatus by produceState<OdifyPilotStatus?>(null, showAdvanced) {
        while (showAdvanced) {
            value = OdifyDiagnostics.snapshot(context.applicationContext)
            delay(12_000)
        }
    }
    val lastSync = context.getSharedPreferences("odify_drive_catalog_v1", 0)
        .getLong("lastSync", 0L)
    var showFolderEditor by remember { mutableStateOf(false) }
    var folderInput by remember(driveFolder) { mutableStateOf(driveFolder) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 21.dp, end = 21.dp, bottom = 170.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        Text("설정 및 계정", fontSize = 25.sp, fontWeight = FontWeight.Bold,
            color = ink, letterSpacing = (-0.6).sp)
        Spacer(Modifier.height(4.dp))
        Text("음악 소스, 동기화, 재생 및 저장 공간 관리", fontSize = 12.sp, color = grey)
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
        Text("음악 소스 및 연결", color = ink, fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(13.dp))
        OdifySourceRow(
            icon = Icons.Default.CloudQueue,
            title = if (driveConnected) "Google Drive · 연결됨" else "Google Drive 계정 연결",
            subtitle = if (driveConnected) "Drive API · 음악 폴더 자동 검색"
                else "첫 연결 시 Google 읽기 권한 승인 필요",
            enabled = !driveBusy, onClick = onDriveConnect
        )
        Spacer(Modifier.height(7.dp))
        OdifySourceRow(
            icon = Icons.Default.Sync,
            title = if (driveBusy) "라이브러리 동기화 중…" else "Drive 음악 새로고침",
            subtitle = driveStatus.take(90),
            enabled = driveConnected && !driveBusy,
            onClick = onDriveSync
        )
        Spacer(Modifier.height(7.dp))
        OdifySourceRow(
            icon = Icons.Default.FolderOpen,
            title = "Drive 추가 음악 폴더 지정",
            subtitle = "Test · Auto_Upload · Cloud_Uploads 자동 검색",
            enabled = !driveBusy,
            onClick = { showFolderEditor = true }
        )
        Spacer(Modifier.height(7.dp))
        OdifySourceRow(
            icon = Icons.Default.PlaylistPlay,
            title = "YouTube 공개 플레이리스트",
            subtitle = if(youtubeConnected)
                "YouTube 연결됨 · 공개 플리 조회 · 보유 음원과 매칭"
                else youtubeStatus,
            enabled = true,
            onClick = onOpenYoutube
        )
        Spacer(Modifier.height(7.dp))
        OdifySourceRow(
            icon = Icons.Default.Folder,
            title = "휴대폰 로컬 음악 폴더 선택",
            subtitle = if (phoneFolderStatus.isBlank())
                "Drive 업로드 없이 선택한 폴더의 음악을 직접 재생" else phoneFolderStatus,
            enabled = !importing,
            onClick = onChoosePhoneFolder
        )
        Spacer(Modifier.height(7.dp))
        OdifySourceRow(
            icon = Icons.Default.Refresh,
            title = "휴대폰 음악 폴더 다시 스캔",
            subtitle = "새로 복사한 음악도 로컬 라이브러리에 추가",
            enabled = !importing,
            onClick = onRefreshPhoneFolder
        )
        Spacer(Modifier.height(7.dp))
        OdifySourceRow(
            icon = Icons.Default.TableChart,
            title = "음악 마스터 DB 조회 및 다운로드",
            subtitle = "Google Sheets MASTER · 중복곡 검토 · CSV 백업",
            enabled = true, onClick = { showMaster = true }
        )
        Spacer(Modifier.height(7.dp))
        OdifySourceRow(
            icon = Icons.Default.CloudUpload,
            title = if (uploadingToDrive) "선택한 음원 Drive 업로드 중…" else "음악을 Drive에 저장",
            subtitle = "중복 MD5·Track ID·메타데이터 확인 · 앱 전용 폴더",
            enabled = driveConnected && !driveBusy && !uploadingToDrive,
            onClick = onUploadToDrive
        )
        Text(uploadStatus, color = grey, fontSize = 10.sp, lineHeight = 14.sp)
        Spacer(Modifier.height(7.dp))
        OdifySourceRow(
            icon = Icons.Default.CloudDownload,
            title = "Drive 파일 개별 추가 (기존 방식)",
            subtitle = "파일 선택기를 통한 재생도 지원",
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
        OutlinedButton(
            onClick = { showAdvanced = !showAdvanced },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                null, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (showAdvanced) "고급 정보 접기" else "고급 재생·저장 공간·인증 진단")
        }
        if (showAdvanced) {
        Spacer(Modifier.height(16.dp))
        Text("재생 및 저장 공간", color = ink, fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(15.dp))
        OdifyInfoRow(Icons.Default.Headphones, "Audio engine",
            "Android Media3 · background playback")
        Spacer(Modifier.height(7.dp))
        OdifyInfoRow(Icons.Default.CloudDone, "Drive 스트리밍 캐시",
            "사용: ${OdifyDiagnostics.humanBytes(pilotStatus?.streamCacheBytes ?: 0L)} · 최대 192MB")
        Spacer(Modifier.height(7.dp))
        OdifyInfoRow(Icons.Default.OfflinePin, "기기 내 오프라인 음원",
            OdifyDiagnostics.humanBytes(pilotStatus?.offlineBytes ?: 0L))
        Spacer(Modifier.height(7.dp))
        OdifyInfoRow(Icons.Default.Image, "앨범커버 이미지",
            OdifyDiagnostics.humanBytes(pilotStatus?.artworkBytes ?: 0L))
        Spacer(Modifier.height(7.dp))
        OdifyInfoRow(Icons.Default.Wifi, "네트워크 상태",
            if (pilotStatus?.networkConnected == true) "인터넷 사용 가능"
            else "현재 연결 없음 · 저장된 곡만 재생 가능")
        Spacer(Modifier.height(7.dp))
        OdifyInfoRow(Icons.Default.Refresh, "마지막 Drive 목록 동기화",
            if (lastSync == 0L) "아직 동기화되지 않았습니다."
            else java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.KOREA)
                .format(java.util.Date(lastSync)))
        Spacer(Modifier.height(25.dp))
        Text("15GB 무료 Drive 테스트", fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp, color = ink)
        Spacer(Modifier.height(8.dp))
        Text("Drive 용량은 Google 계정에서 확인합니다. 이 화면은 Odify가 직접 저장한 음원·캐시 용량만 표시하며, Google Drive 앱 자체 캐시는 포함하지 않습니다.",
            fontSize = 11.sp, color = grey, lineHeight = 16.sp)
        Spacer(Modifier.height(25.dp))
        Text("OAuth 연결 진단", fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp, color = ink)
        Spacer(Modifier.height(8.dp))
        Text("패키지: ${pilotStatus?.packageName ?: context.packageName}",
            fontSize = 11.sp, color = grey)
        Spacer(Modifier.height(8.dp))
        Text("서명 SHA-1: ${pilotStatus?.signingSha1?.ifEmpty { "확인 불가" } ?: "확인 중"}",
            fontSize = 11.sp, color = grey, lineHeight = 17.sp)
        TextButton(
            onClick = {
                pilotStatus?.signingSha1?.takeIf { it.isNotBlank() }?.let {
                    clipboard.setText(AnnotatedString(it))
                }
            }
        ) {
            Text("Google Cloud 등록용 SHA-1 복사", color = ink, fontSize = 12.sp)
        }
        Spacer(Modifier.height(7.dp))
        OdifyInfoRow(Icons.Default.Info, "About",
            "Odify Native · Preview 12 · Stable OAuth identity")
        }
    }
    if (showMaster) {
        OdifyMasterScreen(
            songs = songs, connected = driveConnected,
            onClose = { showMaster = false },
            onUpdated = onMasterUpdated
        )
    }
    if (showFolderEditor) {
        AlertDialog(
            onDismissRequest = { showFolderEditor = false },
            title = { Text("음악 폴더 지정") },
            text = {
                Column {
                    Text("특별히 추가할 폴더 ID를 입력하세요. 기본 Test, Auto_Upload, Cloud_Uploads는 계속 자동 검색됩니다.",
                        fontSize = 12.sp, color = grey)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = folderInput, onValueChange = { folderInput = it },
                        label = { Text("Drive folder ID") }, singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onDriveFolderChange(folderInput.trim().substringAfterLast("/"))
                    showFolderEditor = false
                }) { Text("적용", color = ink) }
            },
            dismissButton = {
                TextButton(onClick = { showFolderEditor = false }) {
                    Text("취소")
                }
            }
        )
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
