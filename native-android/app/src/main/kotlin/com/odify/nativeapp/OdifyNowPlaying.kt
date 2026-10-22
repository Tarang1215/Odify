package com.odify.nativeapp

import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
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
 * gesture to collapse. No expensive GPU blur or exaggerated button glow.
 */
@Composable
fun OdifyNowPlaying(
    song: OdifySong,
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
    onSaveDevice: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { 125.dp.toPx() }
    var showInfo by remember { mutableStateOf(false) }
    var actionsOpen by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var albumSlide by remember(song.id) { mutableFloatStateOf(0f) }
    var albumSettling by remember(song.id) { mutableStateOf(false) }
    val swipeThreshold = with(LocalDensity.current) { 68.dp.toPx() }
    LaunchedEffect(song.id) {
        albumSlide = 0f
        albumSettling = false
    }
    val waveform by produceState<List<Float>>(
        initialValue = if (song.isCloud) emptyList()
            else OdifyWaveformRepository.peek(song.id), song.id
    ) {
        // Never decode a remote Drive file merely to draw a waveform: that
        // defeats range-streaming and can burn through the free-tier data plan.
        value = if (song.isCloud) emptyList()
            else OdifyWaveformRepository.load(context = context, song = song)
    }
    val dragState = rememberDraggableState { delta ->
        // Direct state update follows the finger at display refresh rate.
        dragOffset = (dragOffset + delta).coerceAtLeast(0f)
    }

    Box(
        Modifier.fillMaxSize()
            .offset { IntOffset(0, dragOffset.roundToInt()) }
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF181B21), playerBg, Color(0xFF15181D))
                )
            )
            .draggable(
                orientation = Orientation.Vertical,
                state = dragState,
                onDragStopped = { velocity ->
                    if (dragOffset > threshold || velocity > 1500f) {
                        // Let the outer player morph finish the dismissal.
                        // Resetting dragOffset here would flash the full-size
                        // rectangular surface before exit.
                        onDismiss()
                    } else {
                        scope.launch {
                            val springBack = Animatable(dragOffset)
                            springBack.animateTo(0f, tween(200)) {
                                dragOffset = value
                            }
                        }
                    }
                }
            )
    ) {
        BoxWithConstraints(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
        ) {
            val compact = maxHeight < 620.dp
            val artSize = minOf(maxWidth - 62.dp, maxHeight * 0.39f).coerceAtLeast(80.dp)
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(min = maxHeight)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 25.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    Modifier.fillMaxWidth().height(58.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.KeyboardArrowDown, "재생 화면 닫기",
                            tint = playerWhite)
                    }
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("PLAYING FROM", color = playerSecondary, fontSize = 9.sp,
                            letterSpacing = 1.5.sp, fontWeight = FontWeight.Medium)
                        Text(when {
                            song.id.startsWith("gdrive:") -> "Google Drive"
                            song.isPhoneFolder -> "휴대폰 음악 폴더"
                            else -> "내 음악"
                        }, color = playerWhite, fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.weight(1f))
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
                    OdifyLyricsPanel(song, position, onSeek, Modifier.size(artSize))
                } else Box(
                    Modifier.size(artSize)
                        .clip(RoundedCornerShape(22.dp))
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

                Spacer(Modifier.height(19.dp))
                // Waveform scrubber replaces a bulky default slider.
                OdifyWaveformProgress(
                    position = position,
                    duration = duration,
                    waveform = waveform,
                    onSeek = onSeek,
                    modifier = Modifier.fillMaxWidth().height(45.dp)
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
                            .background(Brush.linearGradient(listOf(
                                Color(0xFF32363C), Color(0xFF25292E))))
                            .border(0.8.dp, Color.White.copy(alpha = 0.19f), CircleShape)
                            .clickable(onClick = onToggle),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            "재생·일시정지", tint = playerWhite,
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
                        .clip(RoundedCornerShape(24.dp))
                        .background(Brush.horizontalGradient(
                            listOf(Color(0x7040444A), Color(0x7844484E))
                        ))
                        .border(0.7.dp, Color.White.copy(alpha = 0.18f),
                            RoundedCornerShape(24.dp))
                ) {
                    IconButton(
                        onClick = { showLyrics = !showLyrics },
                        modifier = Modifier.align(Alignment.CenterStart)
                            .padding(start = 9.dp).size(44.dp)
                    ) {
                        Icon(Icons.Default.Subtitles,
                            if (showLyrics) "앨범커버 보기" else "가사 보기",
                            tint = if (showLyrics) Color.White else playerSecondary,
                            modifier = Modifier.size(22.dp))
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
    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("곡 정보") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("제목: ${song.title}")
                    Text("아티스트: ${song.artist.ifBlank { "정보 없음" }}")
                    Text("앨범: ${song.album.ifBlank { "정보 없음" }}")
                    Text("재생 위치: " + if (song.isCloud) "Google Drive"
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
        modifier.pointerInput(duration) {
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
        val unplayed = Color(0xFF555961)
        if (reveal < 1f) {
            drawLine(unplayed.copy(alpha = 1f - reveal), Offset(0f, center),
                Offset(size.width, center), strokeWidth = 2.dp.toPx())
            drawLine(played.copy(alpha = 1f - reveal), Offset(0f, center),
                Offset(size.width * fraction, center), strokeWidth = 2.dp.toPx())
        }
        if (waveform.size == 96 && reveal > 0f) {
            waveform.forEachIndexed { index, amplitude ->
                val x = (index + .5f) / waveform.size * size.width
                val height = max(2.dp.toPx(), amplitude * size.height * .42f)
                drawLine(
                    color = (if ((index + .5f) / waveform.size <= fraction)
                        played else unplayed).copy(alpha = reveal),
                    start = Offset(x, center - height),
                    end = Offset(x, center + height),
                    strokeWidth = 2.dp.toPx()
                )
            }
        }
        // Thin position marker gives a continuous indicator between waveform bins.
        if (duration > 0L) {
            val x = fraction.coerceIn(0f, 1f) * size.width
            drawCircle(
                color = playerWhite,
                radius = 2.7.dp.toPx(),
                center = Offset(x, center)
            )
        }
    }
}
