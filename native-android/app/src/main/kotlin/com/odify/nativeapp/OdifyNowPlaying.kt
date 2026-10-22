package com.odify.nativeapp

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

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
    playing: Boolean,
    position: Long,
    duration: Long,
    liked: Boolean,
    onDismiss: () -> Unit,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onLike: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { 125.dp.toPx() }
    var showInfo by remember { mutableStateOf(false) }
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
                        onDismiss()
                        dragOffset = 0f
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
            val artSize = minOf(maxWidth - 62.dp, maxHeight * 0.39f)
            Column(
                Modifier.fillMaxSize().padding(horizontal = 25.dp),
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
                        Text("Your Library", color = playerWhite, fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { showInfo = !showInfo },
                        modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.MoreHoriz, "음원 정보",
                            tint = playerWhite)
                    }
                }

                Spacer(Modifier.weight(0.8f))
                AlbumArt(
                    song,
                    Modifier.size(artSize),
                    radius = RoundedCornerShape(22.dp),
                    fallbackKey = song.id.hashCode()
                )
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
                        Icon(Icons.Default.Shuffle, "셔플", tint = playerSecondary,
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
                        Icon(Icons.Default.Repeat, "반복 재생", tint = playerSecondary,
                            modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.weight(1f))
                if (showInfo) {
                    Text(song.album.ifEmpty { "Local audio · no album metadata" },
                        color = playerSecondary, fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(10.dp))
                }
                Row(
                    Modifier.fillMaxWidth().height(53.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0x7040444A), Color(0x7844484E))
                            )
                        )
                        .border(0.7.dp, Color.White.copy(alpha = 0.18f),
                            RoundedCornerShape(24.dp))
                        .padding(horizontal = 17.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Headphones, "출력 기기", tint = playerWhite,
                        modifier = Modifier.size(19.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(22.dp))
                            .background(Color.White.copy(alpha = 0.10f))
                            .padding(horizontal = 21.dp, vertical = 9.dp)
                    ) {
                        Text("AUDIO", color = playerWhite,
                            fontSize = 10.sp, letterSpacing = .8.sp,
                            fontWeight = FontWeight.SemiBold)
                    }
                    IconButton(onClick = { showInfo = !showInfo }) {
                        Icon(Icons.Default.QueueMusic, "재생 정보",
                            tint = playerWhite, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.height(15.dp))
            }
        }
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
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val ratio = if (duration > 0) (position.toDouble() / duration).toFloat().coerceIn(0f, 1f)
                else 0f
    val played = Color(0xFFF5F6F7)
    val pending = Color(0xFF50545B)
    Canvas(
        modifier.pointerInput(duration) {
            detectTapGestures { offset ->
                if (duration > 0L && size.width > 0) {
                    val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    onSeek((duration * fraction).toLong())
                }
            }
        }
    ) {
        val bars = 94
        val center = size.height / 2f
        for (i in 0 until bars) {
            val x = ((i + .5f) / bars) * size.width
            val wave = abs(sin((i + 5) * 1.13f).toFloat()) * .66f +
                abs(sin((i + 3) * .39f).toFloat()) * .34f
            val half = (3f + wave * size.height * .33f)
            drawLine(
                color = if (i.toFloat() / bars <= ratio) played else pending,
                start = Offset(x, center - half),
                end = Offset(x, center + half),
                strokeWidth = 2.dp.toPx()
            )
        }
    }
}
