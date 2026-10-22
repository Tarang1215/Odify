package com.odify.nativeapp

import android.content.ComponentName
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Do not let Samsung/Android draw a contrasting grey scrim below the
        // white rounded Odify navigation surface and the system gesture bar.
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.WHITE
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }
        setContent {
            MaterialTheme(
                typography = OdifyTypography,
                colorScheme = lightColorScheme(
                    primary = Color(0xFF111215),
                    onPrimary = Color.White,
                    surface = Color.White,
                    background = Color.White,
                    onSurface = Color(0xFF111215)
                )
            ) {
                NativeOdify()
            }
        }
    }
}

data class OdifyPlaybackSnapshot(
    val mediaId: String? = null,
    val isPlaying: Boolean = false,
    val position: Long = 0L,
    val duration: Long = 0L,
    val previousMediaId: String? = null,
    val nextMediaId: String? = null
)

@Composable
private fun NativeOdify() {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val library = remember { OdifyLibrary(context.applicationContext) }
    var songs by remember { mutableStateOf(library.songs()) }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var playback by remember { mutableStateOf(OdifyPlaybackSnapshot()) }
    var expanded by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importAsOffline by remember { mutableStateOf(false) }
    var startup by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    // Prime waveform caches while the user browses the library, rather than
    // starting a multi-minute PCM decode when Now Playing is first opened.
    // Analyze one track at a time off the UI thread; prioritize the current
    // track and cap work to protect large music libraries/battery.
    LaunchedEffect(songs, playback.mediaId) {
        val active = songs.firstOrNull { it.id == playback.mediaId }
        val candidates = (listOfNotNull(active) +
            songs.filter { it.id != active?.id }).filterNot { it.isCloud }.take(8)
        for (track in candidates) {
            OdifyWaveformRepository.load(context.applicationContext, track)
            kotlinx.coroutines.yield()
        }
    }

    // Connect only to our own foreground Media3 service. No vendor player init.
    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, OdifyPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            runCatching { future.get() }.onSuccess { controller = it }
        }, ContextCompat.getMainExecutor(context))
        onDispose { MediaController.releaseFuture(future) }
    }

    LaunchedEffect(controller) {
        val player = controller ?: return@LaunchedEffect
        while (isActive) {
            val currentDuration = player.duration
            val previousIndex = player.previousMediaItemIndex
            val nextIndex = player.nextMediaItemIndex
            playback = OdifyPlaybackSnapshot(
                mediaId = player.currentMediaItem?.mediaId,
                isPlaying = player.isPlaying,
                position = player.currentPosition.coerceAtLeast(0L),
                duration = if (currentDuration > 0L) currentDuration else 0L,
                previousMediaId = if (previousIndex >= 0)
                    player.getMediaItemAt(previousIndex).mediaId else null,
                nextMediaId = if (nextIndex >= 0)
                    player.getMediaItemAt(nextIndex).mediaId else null
            )
            delay(350L)
        }
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                importing = true
                val result = if (importAsOffline) library.importFiles(uris)
                    else library.linkCloudDocuments(uris)
                songs = library.songs()
                importing = false
                result.onSuccess {
                    Toast.makeText(context,
                        if (importAsOffline) "${it}곡 오프라인 저장 완료"
                        else "${it}곡 클라우드 연결 완료",
                        Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(context, it.message ?: "음악 파일을 열 수 없습니다.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun playFrom(items: List<OdifySong>, index: Int) {
        val player = controller
        if (player == null) {
            Toast.makeText(context, "플레이어를 연결 중입니다.", Toast.LENGTH_SHORT).show()
            return
        }
        if (items.isEmpty() || index !in items.indices) return
        player.setMediaItems(items.map(OdifySong::mediaItem), index, 0L)
        player.prepare()
        player.play()
    }

    fun toggle() {
        val player = controller ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    LaunchedEffect(Unit) {
        delay(430)
        startup = false
    }

    SideEffect {
        @Suppress("DEPRECATION")
        activity.window.statusBarColor =
            if (expanded) android.graphics.Color.rgb(16, 18, 22) else android.graphics.Color.WHITE
        @Suppress("DEPRECATION")
        activity.window.navigationBarColor =
            if (expanded) android.graphics.Color.rgb(16, 18, 22) else android.graphics.Color.WHITE
        val insets = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        insets.isAppearanceLightStatusBars = !expanded
        insets.isAppearanceLightNavigationBars = !expanded
    }

    if (startup) {
        OdifySplash()
    } else {
        OdifyRoot(
            songs = songs,
            playback = playback,
            importing = importing,
            expanded = expanded,
            onExpandedChange = { expanded = it },
            onImport = {
                importAsOffline = false
                picker.launch(arrayOf("audio/*"))
            },
            onImportOffline = {
                importAsOffline = true
                picker.launch(arrayOf("audio/*"))
            },
            onPlay = { list, index -> playFrom(list, index) },
            onTogglePlayback = { toggle() },
            onPrevious = { controller?.seekToPreviousMediaItem() },
            onNext = { controller?.seekToNextMediaItem() },
            onSeek = { progress -> controller?.seekTo(progress) },
            onShuffle = {
                controller?.shuffleModeEnabled = !(controller?.shuffleModeEnabled ?: false)
            },
            onRepeat = {
                controller?.repeatMode =
                    if (controller?.repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE)
                        androidx.media3.common.Player.REPEAT_MODE_OFF
                    else androidx.media3.common.Player.REPEAT_MODE_ONE
            },
            onEdit = { song, title, artist, album ->
                scope.launch {
                    runCatching { library.updateMetadata(song, title, artist, album) }
                        .onSuccess { songs = library.songs() }
                        .onFailure {
                            Toast.makeText(context, it.message ?: "정보 저장 실패",
                                Toast.LENGTH_LONG).show()
                        }
                }
            },
            onRemove = { track ->
                scope.launch {
                    if (playback.mediaId == track.id) controller?.stop()
                    library.delete(track)
                    songs = library.songs()
                }
            }
        )
    }
}
