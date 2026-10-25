package com.odify.nativeapp

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.max

private val playerBg = Color(0xFF101216)
private val playerSecondary = Color(0xFFA3A7AF)
private val playerWhite = Color(0xFFF5F6F7)

/**
 * Fullscreen, dark album-first player. Swipe DOWN anywhere outside the seek
 * gesture to collapse. The cached cover supplies one frosted backdrop layer.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun OdifyNowPlaying(
    song: OdifySong,
    glow:Color,
    previousSong: OdifySong?,
    nextSong: OdifySong?,
    playing: Boolean,
    position: Long,
    duration: Long,
    liked: Boolean,
    shuffleEnabled: Boolean,
    repeatOne: Boolean,
    onDismiss: () -> Unit,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onLike: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onShowQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onSaveDevice: () -> Unit,
    onAudioRequest: (String, String, String) -> Unit,
    onDragProgress: (Float) -> Unit = {},
    externalPlayback: Boolean = false,
    onOpenExternal: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val playerBackdrop=rememberOdifyBackdrop()
    val glassTransparency=LocalOdifyGlassPreferences.current?.player ?: .72f
    val glassShade=(.72f-glassTransparency)*.36f
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { 125.dp.toPx() }
    val scrollState = rememberScrollState()
    val viewportHeightPx = with(LocalDensity.current) {
        androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx()
    }.coerceAtLeast(1f)
    var settling by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun updateDrag(value: Float) {
        dragOffset = value.coerceAtLeast(0f)
        // Publish from input/animation callbacks, never read this offset during
        // composition. A slow drag must only invalidate the parent render layer.
        onDragProgress((dragOffset / viewportHeightPx).coerceIn(0f, .65f))
    }
    fun finishDrag(velocity: Float) {
        settling?.cancel()
        if (dragOffset > threshold || (dragOffset > 0f && velocity > 1500f)) {
            onDismiss()
        } else {
            settling = scope.launch {
                val springBack = Animatable(dragOffset)
                springBack.animateTo(0f, spring(dampingRatio = .9f, stiffness = 420f)) {
                    updateDrag(value)
                }
            }
        }
    }
    val collapseScroll = remember(scrollState, threshold, viewportHeightPx) { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput) return Offset.Zero
            if ((available.y > 0f && scrollState.value == 0) || dragOffset > 0f) {
                val previous = dragOffset
                settling?.cancel()
                updateDrag(dragOffset + available.y)
                return Offset(0f, dragOffset - previous)
            }
            return Offset.Zero
        }
        override suspend fun onPreFling(available: Velocity): Velocity {
            if (dragOffset <= 0f) return Velocity.Zero
            finishDrag(available.y)
            return Velocity(0f, available.y)
        }
    } }
    var showInfo by remember { mutableStateOf(false) }
    var actionsOpen by remember { mutableStateOf(false) }
    var audioRequestOpen by remember(song.id) { mutableStateOf(false) }
    if (audioRequestOpen) OdifyAudioRequestDialog(song, { audioRequestOpen = false }, onAudioRequest)
    val lyricsRepository=remember {OdifyLyricsRepository(context.applicationContext)}
    var availableLyrics by remember(song.id) {mutableStateOf(song.lyrics)}
    LaunchedEffect(song.id,song.lyrics) {availableLyrics=lyricsRepository.read(song)}
    var showLyrics by remember(song.id) { mutableStateOf(false) }
    var lyricsMenu by remember(song.id) {mutableStateOf(false)}
    var lyricsEditor by remember(song.id) {mutableStateOf(false)}
    var albumSlide by remember(song.id) { mutableFloatStateOf(0f) }
    var albumSettling by remember(song.id) { mutableStateOf(false) }
    val swipeThreshold = with(LocalDensity.current) { 68.dp.toPx() }
    LaunchedEffect(song.id) {
        albumSlide = 0f
        albumSettling = false
    }
    // Decorative 12-pattern seek art stays stable during one song view.
    // No decoding/download, and no claim to visualize the audio's real waveform.
    val waveform = remember(song.id) { OdifyWaveformPatterns.samples(kotlin.random.Random.nextInt(OdifyWaveformPatterns.count)) }
    val dragState = rememberDraggableState { delta ->
        // Direct state update follows the finger at display refresh rate.
        settling?.cancel()
        updateDrag(dragOffset + delta)
    }

    Box(
        Modifier.fillMaxSize()
            .background(playerBg)
            .then(if(showLyrics) Modifier else Modifier.nestedScroll(collapseScroll).draggable(
                orientation = Orientation.Vertical,
                state = dragState,
                onDragStopped = { velocity ->
                    finishDrag(velocity)
                }
            ))
    ) {
        // Preserve the actual cover's spatial shapes. Palette gradients and a
        // partial-height reflected layer created visible color bands instead.
        Box(Modifier.fillMaxSize().recordOdifyBackdrop(playerBackdrop)) {
        AlbumArt(song, Modifier.fillMaxSize()
            .graphicsLayer { scaleX = 1.18f; scaleY = 1.18f;alpha=.94f }
            .blur(36.dp), radius = RoundedCornerShape(0.dp),
            fallbackKey = song.id.hashCode(),muted=false,
            contentScale = androidx.compose.ui.layout.ContentScale.FillBounds)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
            Color(0xFF101216).copy(alpha=(.14f+glassShade).coerceIn(.04f,.70f)),
            Color(0xFF101216).copy(alpha=(.27f+glassShade).coerceIn(.08f,.78f)),
            Color(0xFF101216).copy(alpha=(.72f+glassShade).coerceIn(.50f,.94f))))))
        }
        BoxWithConstraints(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
        ) {
            val compact = maxHeight < 620.dp
            val artSize = minOf(maxWidth - 62.dp, maxHeight * 0.39f).coerceAtLeast(80.dp)
            val lyricsHeight=maxHeight*.51f
            Column(
                Modifier.fillMaxWidth()
                    // The scroll container owns the viewport while its child
                    // respects the viewport minimum height; reversed modifier
                    // order created empty bottom space on tall Galaxy screens.
                    .verticalScroll(scrollState)
                    .heightIn(min = maxHeight)
                    .padding(horizontal = 25.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                // Consume tall-device spare height between controls rather
                // than leaving one large empty slab below the final button.
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    Modifier.fillMaxWidth().height(58.dp).then(if(showLyrics) Modifier.draggable(
                        orientation=Orientation.Vertical,state=dragState,
                        onDragStopped={finishDrag(it)}) else Modifier),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.KeyboardArrowDown, "재생 화면 닫기",
                            tint = playerWhite)
                    }
                    if(showLyrics) {
                        AlbumArt(song,Modifier.size(40.dp),RoundedCornerShape(8.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(song.title,color=playerWhite,fontSize=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis,fontWeight=FontWeight.SemiBold)
                            Text(song.artist,color=playerWhite.copy(alpha=.66f),fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                        }
                    } else {
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.width(32.dp).height(4.dp).clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha=.45f)))
                    Spacer(Modifier.weight(1f))
                    }
                    if(showLyrics) IconButton(onClick=onLike,modifier=Modifier.size(36.dp)) {
                        Icon(if(liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,"좋아요",tint=playerWhite,
                            modifier=Modifier.size(21.dp))
                    }
                    Box {
                        IconButton(onClick = { actionsOpen = true },
                            modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Default.MoreVert, "곡 옵션 열기",
                                tint = playerWhite)
                        }
                        DropdownMenu(
                            expanded = actionsOpen,
                            onDismissRequest = { actionsOpen = false }
                        ) {
                            if(externalPlayback) DropdownMenuItem(
                                text={Text("YouTube 앱에서 열기")},leadingIcon={Icon(Icons.Default.OpenInNew,null)},
                                onClick={actionsOpen=false;onOpenExternal()})
                            if(!externalPlayback) {
                            DropdownMenuItem(
                                text = { Text("플레이리스트에 추가") },
                                leadingIcon = { Icon(Icons.Default.PlaylistAdd, null) },
                                onClick = { actionsOpen = false; onAddToPlaylist() }
                            )
                            DropdownMenuItem(
                                text = { Text("디바이스에 저장") },
                                leadingIcon = { Icon(Icons.Default.FileDownload, null) },
                                onClick = { actionsOpen = false; onSaveDevice() }
                            )
                            DropdownMenuItem(
                                text = { Text("오디오 변경 요청") },
                                leadingIcon = { Icon(Icons.Default.ReportProblem, null) },
                                onClick = { actionsOpen = false; audioRequestOpen = true }
                            )
                            }
                            DropdownMenuItem(
                                text = { Text("곡 정보") },
                                leadingIcon = { Icon(Icons.Default.Info, null) },
                                onClick = { actionsOpen = false; showInfo = true }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(if (compact) 8.dp else 24.dp))
                val artWidthPx = with(LocalDensity.current) { artSize.toPx() }
                val artGapPx = with(LocalDensity.current) { 12.dp.toPx() }
                // Three covers share one clipped viewport. As the user swipes,
                // the next cover enters continuously instead of popping in.
                if (showLyrics) {
                    OdifyLyricsPanel(song.copy(lyrics=availableLyrics), position, onSeek,
                        Modifier.fillMaxWidth().height(lyricsHeight),immersive=true,
                        editRequested=lyricsEditor,onEditorClosed={lyricsEditor=false},
                        onLyricsChanged={availableLyrics=it})
                } else Box(
                    Modifier.size(artSize)
                        .clip(RoundedCornerShape(22.dp))
                        .border(.7.dp, Color.White.copy(alpha = .22f), RoundedCornerShape(22.dp))
                        .pointerInput(song.id, previousSong?.id, nextSong?.id) {
                            detectHorizontalDragGestures(
                                onHorizontalDrag = { change, amount ->
                                    if (!albumSettling) {
                                        val allowed = when {
                                            amount < 0f -> nextSong != null
                                            amount > 0f -> previousSong != null
                                            else -> true
                                        }
                                        albumSlide = (albumSlide + if (allowed) amount
                                            else amount * .20f)
                                            .coerceIn(-artWidthPx, artWidthPx)
                                        change.consume()
                                    }
                                },
                                onDragEnd = {
                                    if (albumSettling) return@detectHorizontalDragGestures
                                    val direction = when {
                                        albumSlide < -swipeThreshold && nextSong != null -> -1
                                        albumSlide > swipeThreshold && previousSong != null -> 1
                                        else -> 0
                                    }
                                    albumSettling = true
                                    scope.launch {
                                        val animate = Animatable(albumSlide)
                                        val target = if (direction == 0) 0f
                                            else direction * (artWidthPx + artGapPx)
                                        animate.animateTo(target,
                                            if (direction == 0)
                                                spring(dampingRatio = .82f, stiffness = 520f)
                                            else tween(240, easing = FastOutSlowInEasing)
                                        ) { albumSlide = value }
                                        if (direction < 0) onNext()
                                        else if (direction > 0) onPrevious()
                                        if (direction == 0) albumSettling = false
                                    }
                                },
                                onDragCancel = {
                                    scope.launch {
                                        val animate = Animatable(albumSlide)
                                        animate.animateTo(0f, tween(210,
                                            easing = FastOutSlowInEasing)) {
                                            albumSlide = value
                                        }
                                        albumSettling = false
                                    }
                                }
                            )
                        }
                ) {
                    previousSong?.let { neighbor ->
                        AlbumArt(
                            neighbor,
                            Modifier.fillMaxSize().offset {
                                IntOffset((albumSlide - artWidthPx - artGapPx)
                                    .roundToInt(), 0)
                            },
                            radius = RoundedCornerShape(22.dp),
                            fallbackKey = neighbor.id.hashCode()
                        )
                    }
                    nextSong?.let { neighbor ->
                        AlbumArt(
                            neighbor,
                            Modifier.fillMaxSize().offset {
                                IntOffset((albumSlide + artWidthPx + artGapPx)
                                    .roundToInt(), 0)
                            },
                            radius = RoundedCornerShape(22.dp),
                            fallbackKey = neighbor.id.hashCode()
                        )
                    }
                    AlbumArt(
                        song,
                        Modifier.fillMaxSize().offset {
                            IntOffset(albumSlide.roundToInt(), 0)
                        },
                        radius = RoundedCornerShape(22.dp),
                        fallbackKey = song.id.hashCode()
                    )
                }
                if(!showLyrics) {
                Spacer(Modifier.height(22.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(song.title, color = playerWhite, fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold, lineHeight = 25.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(5.dp))
                        if (song.artist.isNotBlank()) {
                            Text(song.artist, color = playerSecondary, fontSize = 13.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    IconButton(onClick = onLike) {
                        Icon(if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            "좋아요", tint = playerWhite, modifier = Modifier.size(25.dp))
                    }
                }
                }

                Spacer(Modifier.height(19.dp))
                // Waveform scrubber replaces a bulky default slider.
                OdifyWaveformProgress(
                    position = position,
                    duration = duration,
                    waveform = waveform,
                    onSeek = onSeek,
                    modifier = Modifier.fillMaxWidth().height(54.dp)
                )
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(timecode(position), fontSize = 11.sp, color = playerSecondary)
                    Text(timecode(duration), fontSize = 11.sp, color = playerSecondary)
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth().height(68.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onShuffle) {
                        Icon(Icons.Default.Shuffle, if (shuffleEnabled) "셔플 끄기" else "셔플 켜기",
                            tint = if (shuffleEnabled) Color(0xFF9BEFC4) else playerSecondary,
                            modifier = Modifier.size(22.dp))
                    }
                    IconButton(onClick = onPrevious) {
                        Icon(Icons.Default.SkipPrevious, "이전 곡",
                            tint = playerWhite, modifier = Modifier.size(28.dp))
                    }
                    // Restrained, 1dp outlined glass circle. NO outer glow.
                    Box(
                        Modifier.size(65.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFF2F3F6))
                            .clickable(onClick = onToggle),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            "재생·일시정지", tint = playerBg,
                            modifier = Modifier.size(31.dp))
                    }
                    IconButton(onClick = onNext) {
                        Icon(Icons.Default.SkipNext, "다음 곡",
                            tint = playerWhite, modifier = Modifier.size(28.dp))
                    }
                    IconButton(onClick = onRepeat) {
                        Icon(if (repeatOne) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            if (repeatOne) "한 곡 반복 끄기" else "한 곡 반복 켜기",
                            tint = if (repeatOne) Color(0xFF9BEFC4) else playerSecondary,
                            modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.height(if (compact) 12.dp else 28.dp))
                // Precisely center the AUDIO pill on the same x-axis as Play.
                Box(
                    Modifier.fillMaxWidth().height(53.dp)
                        .odifyGlass(playerBackdrop,RoundedCornerShape(24.dp),glow)
                ) {
                    Box(Modifier.align(Alignment.CenterStart).padding(start=9.dp)) {
                    Box(Modifier.size(44.dp).clip(CircleShape).combinedClickable(
                        onClick={if(availableLyrics.isNotBlank()) showLyrics=!showLyrics},
                        onLongClick={lyricsMenu=true}),contentAlignment=Alignment.Center) {
                        Icon(Icons.Default.Subtitles,
                            if (showLyrics) "앨범커버 보기" else "가사 보기",
                            tint = if (showLyrics) Color.White else playerSecondary,
                            modifier = Modifier.size(22.dp))
                    }
                    DropdownMenu(expanded=lyricsMenu,onDismissRequest={lyricsMenu=false},
                        shape=RoundedCornerShape(18.dp),containerColor=Color(0xF022252C)) {
                        DropdownMenuItem(text={Text("가사 수정")},leadingIcon={Icon(Icons.Default.Edit,null)},
                            onClick={lyricsMenu=false;lyricsEditor=true})
                    }
                    }
                    Text(
                        text = if (showLyrics) "가사 보기" else "다음 재생목록",
                        color = playerWhite,
                        fontSize = 11.sp,
                        letterSpacing = 0.4.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.Center)
                            .clickable(onClick = onShowQueue)
                    )
                    IconButton(
                        onClick = onShowQueue,
                        modifier = Modifier.align(Alignment.CenterEnd)
                            .padding(end = 9.dp).size(44.dp)
                    ) {
                        Icon(Icons.Default.QueueMusic, "다음 재생목록",
                            tint = playerWhite, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.height(15.dp))
            }
        }
    }
    if(lyricsEditor && !showLyrics) OdifyLyricsPanel(song.copy(lyrics=availableLyrics),position,onSeek,
        editRequested=true,visible=false,onEditorClosed={lyricsEditor=false},onLyricsChanged={availableLyrics=it})
    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("곡 정보") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("제목: ${song.title}")
                    Text("아티스트: ${song.artist.ifBlank { "정보 없음" }}")
                    Text("앨범: ${song.album.ifBlank { "정보 없음" }}")
                    Text("재생 위치: " + if (externalPlayback) "YouTube" else if (song.isCloud) "Google Drive"
                        else if (song.isPhoneFolder) "휴대폰 폴더" else "오프라인 보관함")
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) { Text("닫기") }
            }
        )
    }
}

private fun timecode(milliseconds: Long): String {
    val seconds = (milliseconds.coerceAtLeast(0L) / 1000L)
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

@Composable
private fun OdifyWaveformProgress(
    position: Long,
    duration: Long,
    waveform: List<Float>,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val actualFraction = if (duration > 0L)
        (position.toDouble() / duration).toFloat().coerceIn(0f, 1f) else 0f
    // Polling position updates at 350ms are interpolated smoothly on-screen.
    val rollingFraction by animateFloatAsState(
        targetValue = actualFraction,
        animationSpec = tween(360, easing = LinearEasing),
        label = "odify-playhead"
    )
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    var pendingSeek by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(pendingSeek) {
        if (pendingSeek != null) {
            delay(750)
            pendingSeek = null
        }
    }
    val fraction = scrubbing ?: pendingSeek ?: rollingFraction
    val reveal by animateFloatAsState(
        targetValue = if (waveform.size == 96) 1f else 0f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "odify-waveform-crossfade"
    )
    Canvas(
        modifier.semantics {
            contentDescription = "재생 위치"
            progressBarRangeInfo = ProgressBarRangeInfo(actualFraction,0f..1f)
            setProgress { target ->
                if(duration > 0) {onSeek((duration*target.coerceIn(0f,1f)).toLong());true} else false
            }
        }.pointerInput(duration) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (duration <= 0L || size.width <= 0) return@awaitEachGesture
                var last = (down.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                scrubbing = last
                var completed = false
                while (!completed) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                        ?: break
                    if (change.pressed) {
                        last = (change.position.x / size.width.toFloat())
                            .coerceIn(0f, 1f)
                        scrubbing = last
                        change.consume()
                    } else {
                        // Exactly ONE Media3 seekTo at release; never seek on each frame.
                        pendingSeek = last
                        scrubbing = null
                        onSeek((duration * last).toLong())
                        completed = true
                    }
                }
                if (!completed) scrubbing = null
            }
        }
    ) {
        val center = size.height / 2f
        val played = Color(0xFFF6F7F8)
        val unplayed = Color.White.copy(alpha = .28f)
        if (reveal < 1f) {
            drawLine(unplayed.copy(alpha = 1f - reveal), Offset(0f, center),
                Offset(size.width, center), strokeWidth = 2.dp.toPx())
            drawLine(played.copy(alpha = 1f - reveal), Offset(0f, center),
                Offset(size.width * fraction, center), strokeWidth = 2.dp.toPx())
        }
        if (waveform.size == 96 && reveal > 0f) {
            waveform.forEachIndexed { index, amplitude ->
                val x = (index + .5f) / waveform.size * size.width
                val height = max(1.dp.toPx(), amplitude * size.height * .46f)
                val barColor = if ((index + .5f) / waveform.size <= fraction) played else unplayed
                drawLine(
                    color = barColor.copy(alpha = barColor.alpha * reveal),
                    start = Offset(x, center - height),
                    end = Offset(x, center + height),
                    strokeWidth = 1.1.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }
        }
        // Keep the silhouette uninterrupted; show the handle only while seeking.
        if (duration > 0L && scrubbing != null) {
            val x = fraction.coerceIn(0f, 1f) * size.width
            drawCircle(
                color = playerWhite,
                radius = 2.7.dp.toPx(),
                center = Offset(x, center)
            )
        }
    }
}

