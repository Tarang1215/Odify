package com.odify.nativeapp

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private val ink = OdifyPalette.text
private val grey = OdifyPalette.muted
private val pale = OdifyPalette.surface
private val white = OdifyPalette.background
private val glass = Brush.horizontalGradient(
    listOf(Color(0xF22C3036), Color(0xF026292E))
)

enum class OdifyTab { Home, Explore, Search, Library, Profile }

// Profile is a utility destination reached from the screen headers.
val odifyPrimaryTabs = listOf(OdifyTab.Home, OdifyTab.Explore, OdifyTab.Search, OdifyTab.Library)
private val navigationHeight = 64.dp

@Composable
fun OdifySplash() {
    Box(Modifier.fillMaxSize().background(white), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Reuse the precise monochrome Odify icon in the system launcher.
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(R.drawable.odify_symbol_black),
                contentDescription = "Odify",
                colorFilter = ColorFilter.tint(ink),
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
    muted: Boolean = false,
    contentScale: ContentScale = ContentScale.Crop
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
                contentScale = contentScale, modifier = Modifier.fillMaxSize(),
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
    externalSong: OdifySong? = null,
    onOpenExternalVideo: () -> Unit = {},
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
    onExternalYoutubeStarted: () -> Unit,
    onPlayYoutubeVideo: (List<YoutubeVideo>, Int) -> Unit,
    onExternalTrack: (String, String) -> Unit,
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
    onAudioRequest: (OdifySong, String, String, String) -> Unit,
    onRemove: (OdifySong) -> Unit,
    onEdit: (OdifySong, String, String, String) -> Unit,
    onChooseArtwork: (OdifySong) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pageBackdrop = rememberOdifyBackdrop()
    val exploreRepository=remember {OdifyExploreRepository(context.applicationContext)}
    val chartIndex by produceState(OdifyChartMatching.buildIndex(emptyList()),songs) {
        value=withContext(Dispatchers.Default) {OdifyChartMatching.buildIndex(songs)}
    }
    LaunchedEffect(exploreRepository) {
        for(country in listOf("kr","jp","us")) exploreRepository.cached(country)
    }
    val prefs = remember { context.getSharedPreferences("odify_native_ui", 0) }
    val playlistStore = remember { OdifyPlaylists(context.applicationContext) }
    val listeningStore = remember { OdifyListeningStore(context.applicationContext) }
    var listeningSignals by remember { mutableStateOf(listeningStore.signals()) }
    DisposableEffect(listeningStore) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            listeningSignals = listeningStore.signals()
        }
        listeningStore.preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { listeningStore.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var playlistVersion by remember { mutableIntStateOf(0) }
    val playlists = remember(playlistVersion) { playlistStore.all() }
    var playlistDialogTrack by remember { mutableStateOf<OdifySong?>(null) }
    var openLibraryPlaylists by rememberSaveable { mutableIntStateOf(0) }
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
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchGenre by rememberSaveable { mutableStateOf("All") }
    var searchScope by rememberSaveable { mutableStateOf(OdifySearchScope.Music) }
    val pageState = rememberSaveableStateHolder()
    val tabHistory = remember { mutableStateListOf<OdifyTab>() }
    fun navigateTo(tab: OdifyTab) {
        if (tab != selectedTab) {
            tabHistory.add(selectedTab)
            if (tabHistory.size > 20) tabHistory.removeAt(0)
            selectedTab = tab
        }
    }
    fun navigateBack() {
        selectedTab = if (tabHistory.isNotEmpty())
            tabHistory.removeAt(tabHistory.lastIndex) else OdifyTab.Home
    }
    var queueOpen by remember { mutableStateOf(false) }
    var youtubeOpen by remember { mutableStateOf(false) }
    BackHandler(enabled = queueOpen || expanded ||
        selectedTab != OdifyTab.Home || tabHistory.isNotEmpty()) {
        when {
            queueOpen -> queueOpen = false
            expanded -> onExpandedChange(false)
            else -> navigateBack()
        }
    }
    var liked by remember {
        mutableStateOf(prefs.getStringSet("likes", emptySet())?.toSet() ?: emptySet())
    }
    var history by remember {
        mutableStateOf(prefs.getString("history", "")!!.split("|").filter { it.isNotBlank() })
    }
    var navVisible by remember { mutableStateOf(true) }
    var compactPlayer by rememberSaveable { mutableStateOf(false) }
    var expansionOrigin by remember { mutableStateOf<Rect?>(null) }
    var playerDragFraction by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(expanded) {
        if (expanded) {
            playerDragFraction = 0f
        }
    }
    // Navigation state is gesture-driven and the mini-player is a separate surface.

    val songIndex = remember(songs,externalSong) { (songs+listOfNotNull(externalSong)).associateBy(OdifySong::id) }
    val active = songIndex[playback.mediaId]
    val artworkPalette=rememberArtworkPalette(active?.artworkPath)
    val artworkGlow=artworkPalette.first()
    val sharedGlow by animateColorAsState(artworkGlow,tween(700),label="shared-artwork-glow")
    val recent = remember(history, songIndex) { history.mapNotNull(songIndex::get) }
    val displayed = remember(recent, songs) {
        OdifySongPresentation.visible(if (recent.isNotEmpty()) recent else songs.asReversed())
    }
    val mixes = remember(songs, liked, listeningSignals) {
        OdifyRecommendations.mixes(songs, liked, listeningSignals)
    }
    fun saveMix(mix: OdifyMix) {
        runCatching { playlistStore.saveMix(mix.name, mix.songs.map { it.id }) }
            .onSuccess {
                playlistVersion++
                android.widget.Toast.makeText(context,"${it.name} · 보관함에 저장했어요",android.widget.Toast.LENGTH_SHORT).show()
            }.onFailure {
                android.widget.Toast.makeText(context,it.message ?: "저장하지 못했습니다.",android.widget.Toast.LENGTH_SHORT).show()
            }
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
    LaunchedEffect(playback.mediaId) {
        playback.mediaId?.takeIf { it in songIndex }?.let { key ->
            history = (listOf(key) + history.filterNot { it == key }).take(25)
            prefs.edit().putString("history",history.joinToString("|")).apply()
        }
    }


    // Both controls live in a stable overlay. Animating layout height caused
    // the old mini-player to snap down after the navigation animation finished.
    val navShown = navVisible
    val navFraction by animateFloatAsState(
        targetValue = if (navShown) 1f else 0f,
        animationSpec = spring(dampingRatio = .95f, stiffness = 420f),
        label = "odify-nav-and-mini-linked"
    )
    // Four primary destinations stay reachable; the full player covers them.
    LaunchedEffect(selectedTab) { navVisible = true; compactPlayer = false }

    // Extend rounded nav background behind the Android gesture indicator.
    val gestureInset = WindowInsets.navigationBars.asPaddingValues()
        .calculateBottomPadding()
    val barHeight = navigationHeight + gestureInset
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
                .recordOdifyBackdrop(pageBackdrop)
                // Scroll content fills the screen: no fixed opaque 135dp bottom slab.
                // Each scrollable page owns its last-item clearance instead.
                .pointerInput(active?.id, selectedTab) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var previousY = down.position.y
                        var net = 0f
                        var horizontal = 0f
                        var vertical = 0f
                        var childOwnsGesture = false
                        val primary = selectedTab in odifyPrimaryTabs
                        while (true) {
                            // Children get first refusal: horizontal album rows
                            // and sliders must keep ownership of their gestures.
                            val event = awaitPointerEvent(PointerEventPass.Final)
                            val pointer = event.changes.firstOrNull { it.id == down.id }
                                ?: break
                            if (!pointer.pressed) {
                                if (primary && !childOwnsGesture && abs(horizontal) > 78.dp.toPx() &&
                                    abs(horizontal) > abs(vertical)*1.8f) {
                                    OdifyMotion.adjacentTab(selectedTab, horizontal)?.let(::navigateTo)
                                }
                                break
                            }
                            horizontal = pointer.position.x - down.position.x
                            vertical = pointer.position.y - down.position.y
                            if (pointer.isConsumed) childOwnsGesture = true
                            net += pointer.position.y - previousY
                            previousY = pointer.position.y
                            if (abs(net) >= 20.dp.toPx() && abs(vertical) > abs(horizontal)*1.3f) {
                                navVisible = net > 0f
                                compactPlayer = net < 0f
                                net = 0f
                            }
                        }
                    }
                }
        ) {
            AnimatedContent(targetState = selectedTab, modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    val direction = if (odifyPrimaryTabs.indexOf(targetState) >= odifyPrimaryTabs.indexOf(initialState)) 1 else -1
                    (slideInHorizontally(tween(230, easing = FastOutSlowInEasing)) { it*direction/5 } + fadeIn(tween(180)))
                        .togetherWith(slideOutHorizontally(tween(200, easing = FastOutSlowInEasing)) { -it*direction/5 } + fadeOut(tween(150)))
                }, label = "primary-page-change") { pageTab ->
            pageState.SaveableStateProvider(pageTab.name) {
                when (pageTab) {
                OdifyTab.Home -> OdifyPersonalHome(
                    recent = displayed,
                    hasHistory = recent.isNotEmpty(),
                    mixes = mixes,
                    onSearch = { navigateTo(OdifyTab.Search) },
                    onProfile = { navigateTo(OdifyTab.Profile) },
                    onLibrary = { navigateTo(OdifyTab.Library) },
                    onExplore = { navigateTo(OdifyTab.Explore) },
                    onSaveMix = ::saveMix,
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
                OdifyTab.Explore -> OdifyExplore(
                    index=chartIndex,repository=exploreRepository,onPlay=::playList,onSync=onDriveSync,
                    onAddTrack={track,owned -> playlistDialogTrack=owned ?:
                        OdifySong("chart:${track.id}",track.title,track.artist,"","")},
                    onProfile = { navigateTo(OdifyTab.Profile) },
                    onMissingTrack = { track -> onExternalTrack(track.artist, track.title) },
                    onSaveChart = { name,ids ->
                        runCatching {playlistStore.saveMix(name,ids)}.onSuccess {
                            playlistVersion++
                            android.widget.Toast.makeText(context,"${it.songIds.size}곡 목록을 보관함에 저장했어요",android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                )
                OdifyTab.Search -> OdifySearch(songs, onImport, ::playList,
                    onProfile = { navigateTo(OdifyTab.Profile) },
                    query = searchQuery, onQueryChange = { searchQuery = it },
                    genre = searchGenre, onGenreChange = { searchGenre = it },
                    searchScope = searchScope, onScopeChange = { searchScope = it },
                    youtubeConnected = youtubeConnected, onConnectYoutube = onConnectYoutube,
                    onExternalYoutubeStarted = onExternalYoutubeStarted,
                    onPlayYoutubeVideo = onPlayYoutubeVideo, onAddSong = { playlistDialogTrack=it })
                OdifyTab.Library -> OdifyLibraryScreen(
                    songs = songs, liked = liked, onPlay = ::playList,
                    onImport = onImport, onRemove = onRemove, onEdit = onEdit,
                    onChooseArtwork = onChooseArtwork,
                    onProfile = { navigateTo(OdifyTab.Profile) },
                    onOpenYoutube = { youtubeOpen = true },
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
                    onMasterUpdated, uploadingToDrive, uploadStatus, onUploadToDrive,
                    onBack = ::navigateBack
                )
                }
            }
            }
        }

        // Linked glass navigation and mini-player follow the same spring.
        Box(
            Modifier.align(Alignment.BottomCenter)
                .graphicsLayer {
                    translationY = (1f - navFraction) * barHeightPx
                    alpha = navFraction
                }
        ) {
            OdifyBottomBar(selectedTab, gestureInset, pageBackdrop,sharedGlow) {
                navigateTo(it)
                navVisible = true
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
                OdifyMiniPlayer(active, playback.isPlaying, active.id in liked, pageBackdrop,sharedGlow,
                    compact=compactPlayer,onCompactChange={compactPlayer=it},
                    onExpand = { bounds ->
                        expansionOrigin = bounds
                        playerDragFraction = 0f
                        onExpandedChange(true)
                    },
                    onToggle = onTogglePlayback,
                    onLike = { toggleLike(active.id) },onPrevious=onPrevious,onNext=onNext)
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
                spring(dampingRatio = 0.92f, stiffness = 360f)
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
                        val progress = fullProgress.coerceIn(0f, 1f)
                        val drag = playerDragFraction.coerceIn(0f, .65f)
                        val closed = 1f - progress
                        val origin = expansionOrigin ?: Rect(
                            11.dp.toPx(), size.height - gestureInsetPx -
                                navFraction * navigationHeight.toPx() - 61.dp.toPx(),
                            size.width - 11.dp.toPx(), size.height - gestureInsetPx -
                                navFraction * navigationHeight.toPx() - 5.dp.toPx()
                        )
                        // Blend out while approaching the measured mini-player.
                        // Never flatten a portrait render into a 56dp strip:
                        // its final frame would otherwise flash against the mini.
                        transformOrigin = TransformOrigin(0f, 0f)
                        // Follow the finger at a uniform scale; do not squash
                        // portrait content into mini-player proportions mid-drag.
                        val attraction=closed*closed*(3f-2f*closed)
                        val terminalScale=minOf(origin.width/size.width,origin.height/size.height).coerceIn(.025f,.15f)
                        val uniformScale=OdifyMotion.playerScale(progress,drag,terminalScale)
                        val centerX=size.width/2f+(origin.center.x-size.width/2f)*attraction
                        val draggedCenterY=size.height/2f+size.height*drag
                        val centerY=draggedCenterY+(origin.center.y-draggedCenterY)*attraction
                        translationX=centerX-size.width*uniformScale/2f
                        translationY=centerY-size.height*uniformScale/2f
                        scaleX = uniformScale
                        scaleY = uniformScale
                        alpha = OdifyMotion.playerOpacity(progress)
                        shape = RoundedCornerShape((22f * maxOf(closed, drag)/uniformScale).dp)
                        clip = true
                    }
                    .featherPlayerEdges {
                        (1f-fullProgress.coerceIn(0f,1f))*1.3f+
                            playerDragFraction.coerceIn(0f,.65f)*2.4f
                    }
            ) {
                OdifyNowPlaying(
                    song = active,
                    glow=sharedGlow,
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
                    onSaveDevice = { onSaveDevice(active) },
                    onAudioRequest = { kind, link, reason -> onAudioRequest(active, kind, link, reason) },
                    externalPlayback=active.id.startsWith("youtube:"),
                    onOpenExternal=onOpenExternalVideo,
                    onDragProgress = { playerDragFraction = it }
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

/** Restrained glass surface; no opaque rectangular slab behind the rounded bar. */
@Composable
private fun OdifyBottomBar(
    selected: OdifyTab,
    bottomInset: androidx.compose.ui.unit.Dp,
    backdrop: OdifyBackdrop,
    glow:Color,
    onSelected: (OdifyTab) -> Unit
) {
    Box(
        Modifier.fillMaxWidth().height(navigationHeight + bottomInset)
            .padding(start = 12.dp, end = 12.dp, bottom = bottomInset + 4.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Row(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().height(58.dp)
                .odifyGlass(backdrop,RoundedCornerShape(21.dp),glow,navigation=true)
                .selectableGroup().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            odifyPrimaryTabs.forEach { tab ->
                val isSelected = tab == selected
                val label = when (tab) {
                    OdifyTab.Home -> "홈"
                    OdifyTab.Explore -> "탐색"
                    OdifyTab.Library -> "보관함"
                    OdifyTab.Search -> "검색"
                    OdifyTab.Profile -> "프로필 및 설정"
                }
                val icon = when (tab) {
                    OdifyTab.Home -> if (isSelected) Icons.Filled.Home else Icons.Outlined.Home
                    OdifyTab.Explore -> if (isSelected) Icons.Filled.Explore else Icons.Outlined.Explore
                    OdifyTab.Library -> if (isSelected) Icons.Filled.LibraryMusic else Icons.Outlined.LibraryMusic
                    OdifyTab.Search -> Icons.Outlined.Search
                    OdifyTab.Profile -> Icons.Outlined.PersonOutline
                }
                val highlight by animateColorAsState(
                    if (isSelected) Color.White.copy(alpha = .065f) else Color.Transparent,
                    animationSpec = tween(180), label = "navigation-selection"
                )
                Box(
                    Modifier.weight(1f).height(48.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .selectable(selected = isSelected, role = Role.Tab,
                            onClick = { onSelected(tab) }),
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.size(width = 38.dp, height = 32.dp)
                        .clip(RoundedCornerShape(10.dp)).background(highlight),
                        contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = label,
                            tint = if (isSelected) ink else Color(0xFF93969E),
                            modifier = Modifier.size(23.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun OdifyAccountButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(18.dp))
            .background(pale), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.PersonOutline, "프로필 및 설정",
                tint = ink, modifier = Modifier.size(21.dp))
        }
    }
}

@Composable
private fun OdifyPageHeader(
    title: String,
    onProfile: () -> Unit,
    onImport: (() -> Unit)? = null
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = ink, fontSize = 25.sp,
            fontWeight = FontWeight.Bold, letterSpacing = (-0.7).sp,
            modifier = Modifier.weight(1f))
        onImport?.let { action ->
            IconButton(onClick = action, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Add, "음악 가져오기", tint = ink,
                    modifier = Modifier.size(23.dp))
            }
        }
        OdifyAccountButton(onProfile)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun OdifyMiniPlayer(
    song: OdifySong, playing: Boolean, liked: Boolean, backdrop: OdifyBackdrop, glow:Color,
    compact: Boolean, onCompactChange: (Boolean) -> Unit, onExpand: (Rect?) -> Unit,
    onToggle: () -> Unit, onLike: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit
) {
    var coordinates by remember {mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null)}
    val expandAction by rememberUpdatedState(onExpand)
    val wobble=remember {Animatable(0f)}
    val scope=rememberCoroutineScope()
    val titleStyle=LocalTextStyle.current.merge(TextStyle(fontSize=12.sp,fontWeight=FontWeight.SemiBold))
    val artistStyle=LocalTextStyle.current.merge(TextStyle(fontSize=10.sp,lineHeight=14.sp))
    val titleMeasurer=rememberTextMeasurer()
    val measured=remember(song.title,titleMeasurer,titleStyle){titleMeasurer.measure(song.title,style=titleStyle,maxLines=1,softWrap=false)}
    val titleWidth=with(LocalDensity.current){measured.size.width.toDp()}
    val measuredArtist=remember(song.artist,titleMeasurer,artistStyle){titleMeasurer.measure(song.artist,style=artistStyle,maxLines=1,softWrap=false)}
    val artistWidth=with(LocalDensity.current){measuredArtist.size.width.toDp()}
    val surfaceProgress by animateFloatAsState(if(compact)0f else 1f,
        tween(720,easing=CubicBezierEasing(.22f,.12f,.26f,1f)),label="droplet-split")
    val split=surfaceProgress.coerceIn(0f,1f)
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var visible by remember {mutableStateOf(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))}
    DisposableEffect(lifecycle) {
        val observer=androidx.lifecycle.LifecycleEventObserver {_,_ -> visible=lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)}
        lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer)}
    }
    val morph=remember {Animatable(0f)}
    val morphStrength by animateFloatAsState(if(playing && visible && android.animation.ValueAnimator.areAnimatorsEnabled())1f else 0f,tween(300),label="liquid-motion")
    LaunchedEffect(playing,visible) {
        if(playing && visible && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            while(true) {morph.snapTo(0f);morph.animateTo(1f,tween(5200,easing=LinearEasing))}
        }
    }
    val tension=remember {Animatable(0f)}
    // Touch-down is immediate. This short release follows the tap, rather than
    // delaying the whole player behind the finger with a slow spring.
    fun pulseSurface() {
        scope.launch {tension.snapTo(1f);tension.animateTo(0f,keyframes {
            durationMillis=240;1f at 0;-.35f at 85;.12f at 155;0f at 240
        })}
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal=11.dp,vertical=5.dp),contentAlignment=Alignment.Center) {
        val compactWidth=(titleWidth+99.dp).coerceIn(168.dp,216.dp).coerceAtMost(maxWidth)
        val expandedText=maxOf(titleWidth,artistWidth).coerceIn(56.dp,(maxWidth-245.dp).coerceAtLeast(56.dp))
        val wideWidth=(expandedText+233.dp).coerceAtMost(maxWidth-12.dp)
        val growth=fluidStage(split,0f,.64f)
        // Measure the destination before the pinch starts. A second width
        // animation can outlive the separation and resize the detached lobe.
        val measuredWidth=wideWidth
        // Both lobes travel outward while they are still connected. Once the
        // neck breaks, position and size have already reached their targets.
        val separationGap=(maxWidth-measuredWidth).coerceAtLeast(12.dp)*growth
        val width=compactWidth+(measuredWidth-compactWidth)*growth+separationGap
        val height=43.dp+13.dp*growth
        val artSize=29.dp+11.dp*growth
        val controlsWidth=43.dp+77.dp*growth
        val gap=separationGap
        val leftWidth=width-controlsWidth-gap
        val density=LocalDensity.current
        val rightPx=with(density){controlsWidth.toPx()};val gapPx=with(density){gap.toPx()}
        val motionPx=with(density){(1.1.dp*morphStrength*(1f-fluidStage(split,0f,.20f)+fluidStage(split,.84f,1f))).toPx()}
        // At the joined endpoint the outline is a whole capsule, independent
        // of the left-lobe pulse. Avoid recomposing controls for that unused
        // phase; the expanded lobe still morphs while music plays.
        val shape=remember(split,rightPx,gapPx,motionPx){OdifyDropletShape(split,rightPx,gapPx,motion=motionPx,
            phaseProvider=if(split<=0f || motionPx<=0f)null else {{morph.value}})}
        Box(Modifier.width(width).height(height).onGloballyPositioned{coordinates=it}
            .graphicsLayer {scaleX=1f+wobble.value*.006f+tension.value*.008f;scaleY=1f-wobble.value*.010f-tension.value*.012f}
            .graphicsLayer {
                shadowElevation=9.dp.toPx();this.shape=shape.frameShape()
                ambientShadowColor=glow.copy(alpha=.25f);spotShadowColor=glow.copy(alpha=.22f)
            }
            .odifyGlass(backdrop,shape,glow,split,controlsWidth,gap)
            .drawBehind {
                // Press highlight follows the same deforming silhouette. A
                // rectangular Material ripple must never cover the neck/gap.
                drawRect(Color.White.copy(alpha=(wobble.value*.045f).coerceIn(0f,.045f)))
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Initial)
                    scope.launch {wobble.snapTo(1f)}
                    do {val event=awaitPointerEvent(PointerEventPass.Initial)} while(event.changes.any{it.pressed})
                    scope.launch {wobble.animateTo(0f,spring(dampingRatio=.82f,stiffness=1250f))}
                }
            }
            .combinedClickable(interactionSource=remember {MutableInteractionSource()},indication=null,
                onClick={onCompactChange(!compact)},onLongClick={pulseSurface()})
            .pointerInput(Unit) {
                var drag=0f
                detectVerticalDragGestures(onDragStart={drag=0f},onDragCancel={drag=0f},
                    onDragEnd={if(drag< -28.dp.toPx())expandAction(coordinates?.takeIf{it.isAttached}?.boundsInRoot())},
                    onVerticalDrag={change,delta->change.consume();drag+=delta})
            },contentAlignment=Alignment.Center) {
            // Fixed lobe containers prevent the Row's weighted text from pushing
            // the transport controls off the center of the right-hand droplet.
            Row(Modifier.width(leftWidth).fillMaxHeight().align(Alignment.CenterStart).padding(start=8.dp,end=(8.dp*growth)),verticalAlignment=Alignment.CenterVertically) {
                AlbumArt(song,Modifier.size(artSize).clickable(interactionSource=remember {MutableInteractionSource()},indication=null,onClickLabel="전체 재생 화면 열기") {
                    expandAction(coordinates?.takeIf{it.isAttached}?.boundsInRoot())
                }.semantics{contentDescription="전체 재생 화면 열기"},radius=CircleShape,fallbackKey=song.id.hashCode())
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f).clipToBounds()) {
                    Text(song.title,modifier=Modifier.fillMaxWidth().basicMarquee(iterations=Int.MAX_VALUE,
                        initialDelayMillis=2200,repeatDelayMillis=1800,spacing=MarqueeSpacing(24.dp),velocity=24.dp),
                        maxLines=1,fontSize=12.sp,color=ink,fontWeight=FontWeight.SemiBold)
                    if(song.artist.isNotBlank())Text(song.artist,modifier=Modifier.fillMaxWidth().height(with(density){16.sp.toDp()}*growth)
                        .basicMarquee(iterations=Int.MAX_VALUE,initialDelayMillis=2400,repeatDelayMillis=1800,velocity=22.dp).graphicsLayer{alpha=growth},
                        fontSize=10.sp,lineHeight=14.sp,maxLines=1,color=ink.copy(alpha=.69f))
                }
                if(growth>.01f) {
                    Spacer(Modifier.width(6.dp*growth))
                    Box(modifier=Modifier.width(32.dp*growth).height(40.dp).graphicsLayer{alpha=growth}
                        .clickable(interactionSource=remember {MutableInteractionSource()},indication=null,role=Role.Button,onClick=onLike),contentAlignment=Alignment.Center) {
                        Icon(if(liked)Icons.Default.Favorite else Icons.Default.FavoriteBorder,"좋아요",tint=ink,modifier=Modifier.size(18.dp))
                    }
                }
            }
            Row(Modifier.width(controlsWidth).fillMaxHeight().align(Alignment.CenterEnd),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
                if(growth>.01f) Box(modifier=Modifier.width(38.dp*growth).height(40.dp).graphicsLayer{alpha=growth}
                    .clickable(interactionSource=remember {MutableInteractionSource()},indication=null,role=Role.Button,onClick=onPrevious),contentAlignment=Alignment.Center) {
                    Icon(Icons.Default.SkipPrevious,"이전 곡",tint=ink,modifier=Modifier.size(21.dp))
                }
                Box(Modifier.size(artSize).clip(CircleShape).background(ink)
                    .clickable(interactionSource=remember {MutableInteractionSource()},indication=null,role=Role.Button,onClick=onToggle),contentAlignment=Alignment.Center) {
                    Icon(if(playing)Icons.Default.Pause else Icons.Default.PlayArrow,"재생·일시정지",tint=white,modifier=Modifier.size(21.dp+2.dp*growth))
                }
                if(growth>.01f) Box(modifier=Modifier.width(38.dp*growth).height(40.dp).graphicsLayer{alpha=growth}
                    .clickable(interactionSource=remember {MutableInteractionSource()},indication=null,role=Role.Button,onClick=onNext),contentAlignment=Alignment.Center) {
                    Icon(Icons.Default.SkipNext,"다음 곡",tint=ink,modifier=Modifier.size(21.dp))
                }
            }
        }
    }
}

@Composable
private fun OdifySearch(
    songs: List<OdifySong>,
    onImport: () -> Unit,
    onPlay: (List<OdifySong>, Int) -> Unit,
    onProfile: () -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    genre: String,
    onGenreChange: (String) -> Unit,
    searchScope: OdifySearchScope,
    onScopeChange: (OdifySearchScope) -> Unit,
    youtubeConnected: Boolean, onConnectYoutube: () -> Unit, onExternalYoutubeStarted: () -> Unit,
    onPlayYoutubeVideo: (List<YoutubeVideo>, Int) -> Unit, onAddSong:(OdifySong)->Unit
) {
    var showDuplicates by rememberSaveable { mutableStateOf(false) }
    val genres = remember(songs) {
        listOf("All") + songs.map { it.genre.substringBefore(" / ").trim() }
            .filter { it.isNotBlank() }.distinct().sorted().take(60)
    }
    val filtered = remember(songs, query, genre, showDuplicates, searchScope) {
        val matches = songs.filter {
            (genre == "All" || it.genre.startsWith(genre, ignoreCase = true)) &&
            OdifySearchMatching.matches(it, query, searchScope)
        }
        OdifySongPresentation.visible(matches, showDuplicates).sortedBy { if(it.id.startsWith("gdrive:"))0 else 1 }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(12.dp))
        OdifyPageHeader("검색", onProfile)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(pale).height(48.dp).padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, null, tint = grey, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(9.dp))
            BasicTextField(
                value = query, onValueChange = onQueryChange,
                textStyle = androidx.compose.ui.text.TextStyle(color = ink, fontSize = 14.sp),
                singleLine = true,
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) Text(if (searchScope == OdifySearchScope.Lyrics) "떠오르는 가사로 검색" else "노래, 아티스트, 앨범 검색",
                            color = grey, fontSize = 13.sp)
                        inner()
                    }
                }
            )
            if (query.isNotBlank()) Icon(Icons.Default.Close, "지우기",
                modifier = Modifier.size(20.dp).clickable { onQueryChange("") }, tint = grey)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(OdifySearchScope.Music to "곡·아티스트", OdifySearchScope.Lyrics to "가사").forEach { (scope, label) ->
                val selected = searchScope == scope
                OdifyPill(label,selected) {onScopeChange(scope)}
            }
        }
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            items(genres) { option ->
                val selected = genre == option
                OdifyPill(option,selected) {onGenreChange(option)}
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (query.isEmpty()) "내 음악" else if(searchScope==OdifySearchScope.Music) "통합 검색" else "가사 검색 · ${filtered.size}",
                fontSize = 16.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            Text(if (showDuplicates) "중복 숨기기" else "중복 포함 보기",
                fontSize = 11.sp, color = grey,
                modifier = Modifier.clickable { showDuplicates = !showDuplicates }
                    .padding(horizontal = 6.dp, vertical = 8.dp))
        }
        Spacer(Modifier.height(11.dp))
        OdifyYoutubeSearch(query, youtubeConnected, onConnectYoutube, onExternalYoutubeStarted,onPlayYoutubeVideo,
            localSongs=filtered,onPlayLocal=onPlay,onAddSong=onAddSong,includeYoutube=searchScope==OdifySearchScope.Music)

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
    onProfile: () -> Unit,
    onOpenYoutube: () -> Unit,
    playlists: List<OdifyPlaylist>,
    playlistFocus: Int,
    onCreatePlaylist: (String) -> String,
    onDeletePlaylist: (String) -> Unit,
    onRemoveFromPlaylist: (String, String) -> Unit
) {
    var editingSong by remember { mutableStateOf<OdifySong?>(null) }
    var selection by rememberSaveable { mutableStateOf("Playlists") }
    var lastPlaylistFocus by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(playlistFocus) {
        if (playlistFocus > lastPlaylistFocus) {
            selection = "Playlists"
            lastPlaylistFocus = playlistFocus
        }
    }
    var showDuplicates by rememberSaveable { mutableStateOf(false) }
    val modes = listOf("Playlists" to "플레이리스트", "Offline" to "오프라인", "Saved" to "좋아요")
    val rawVisible = when (selection) {
        "Saved" -> songs.filter { it.id in liked }
        "Offline" -> songs.filter { it.isOfflineSaved }
        else -> emptyList()
    }
    val visible = OdifySongPresentation.visible(rawVisible, showDuplicates)
    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp)) {
        Spacer(Modifier.height(12.dp))
        OdifyPageHeader("보관함", onProfile, onImport)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            modes.forEach { (mode, label) ->
                Box(Modifier.heightIn(min = 48.dp)
                    .wrapContentHeight(Alignment.CenterVertically)
                    .clip(RoundedCornerShape(50))
                    .background(if (selection == mode) ink else pale)
                    .clickable { selection = mode }
                    .padding(horizontal = 13.dp, vertical = 8.dp)) {
                    Text(label, fontSize = 12.sp,
                        color = if (selection == mode) white else grey)
                }
            }
        }
        TextButton(onClick = onOpenYoutube,
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 6.dp)) {
            Icon(Icons.Outlined.PlaylistPlay, null, tint = grey,
                modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(6.dp))
            Text("YouTube 플레이리스트", color = grey, fontSize = 12.sp)
            Spacer(Modifier.width(3.dp))
            Icon(Icons.Default.ChevronRight, null, tint = grey,
                modifier = Modifier.size(16.dp))
        }
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
            Text(if(selection == "Offline") "기기에 저장한 음악" else "좋아요한 음악", fontSize = 12.sp, color = grey,
                modifier = Modifier.weight(1f))
            Text("${visible.size}곡", fontSize = 11.sp, color = grey)
        }
        Spacer(Modifier.height(9.dp))
        if (visible.isEmpty()) Text(if(selection=="Offline") "아직 오프라인 음악이 없어요. 설정에서 휴대폰 폴더를 연결하거나 음악을 오프라인으로 가져오세요." else "마음에 드는 음악에 하트를 눌러 주세요.",
            color=grey,fontSize=13.sp,modifier=Modifier.padding(vertical=24.dp))
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
    onUploadToDrive: () -> Unit,
    onBack: () -> Unit
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
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.ArrowBack, "이전 화면", tint = ink)
            }
            Spacer(Modifier.width(4.dp))
            Text("설정 및 계정", fontSize = 25.sp, fontWeight = FontWeight.Bold,
                color = ink, letterSpacing = (-0.6).sp)
        }
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
        Spacer(Modifier.height(24.dp))
        OdifyGlassSettingsCard()
        Spacer(Modifier.height(16.dp))
        OdifyStreamingRequestsCard()
        Spacer(Modifier.height(28.dp))
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
        HorizontalDivider(color = OdifyPalette.hairline)
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

