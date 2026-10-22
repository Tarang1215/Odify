package com.odify.nativeapp

import android.content.ComponentName
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.android.gms.auth.api.identity.Identity
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
    val drive = remember { OdifyDriveSync(context.applicationContext) }
    var showDriveFolderPicker by remember { mutableStateOf(false) }
    var driveStatus by remember { mutableStateOf(
        if (drive.folderId.isNotBlank()) "Drive folder: ${drive.folderName}"
        else "Drive account not linked"
    ) }
    var refreshCounter by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    // Prime waveform caches while the user browses the library, rather than
    // starting a multi-minute PCM decode when Now Playing is first opened.
    // Analyze one track at a time off the UI thread; prioritize the current
    // track and cap work to protect large music libraries/battery.
    LaunchedEffect(playback.mediaId) {
        val active = songs.firstOrNull { it.id == playback.mediaId }
        if (active?.id?.startsWith("gdrive-") == true) {
            // Fetch only cover metadata for the playing file, never full audio.
            val cover = OdifyDriveArtwork.warm(context.applicationContext, active)
            if (cover != null) songs = library.songs()
        }
        // Never prefetch the entire remote collection. Only analyze the one
        // currently requested Drive track, and keep that expensive read off UI.
        if (active?.isCloud == true && !active.id.startsWith("gdrive-")) {
            kotlinx.coroutines.delay(1250L)
            OdifyWaveformRepository.load(context.applicationContext, active)
        } else {
            val candidates = (listOfNotNull(active) +
                songs.filterNot { it.isCloud || it.id == active?.id }).take(8)
            for (track in candidates) {
                OdifyWaveformRepository.load(context.applicationContext, track)
                kotlinx.coroutines.yield()
            }
        }
    }

    // Refresh catalog when returning to the app, and after background worker sync.
    LaunchedEffect(Unit) {
        if (drive.folderId.isNotBlank()) {
            runCatching { drive.refresh(library) }.onSuccess { count ->
                songs = library.songs()
                driveStatus = "$count tracks indexed · ${drive.folderName}"
            }.onFailure { driveStatus = "Drive sync: ${it.message}" }
        }
        OdifyDriveScheduler.enable(context.applicationContext)
        while (true) {
            delay(60_000L)
            if (drive.folderId.isNotBlank()) songs = library.songs()
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

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        runCatching {
            require(result.resultCode == android.app.Activity.RESULT_OK)
            OdifyDriveAuth.finish(context, result.data)
        }.onSuccess {
            driveStatus = "Drive permission granted"
            showDriveFolderPicker = true
        }.onFailure { driveStatus = "Google Drive login failed: ${it.message}" }
    }

    fun connectDrive() {
        driveStatus = "Requesting Google Drive read-only permission…"
        OdifyDriveAuth.authorize(activity) { result, error ->
            when {
                error != null -> driveStatus = "Google Drive: ${error.message}"
                result == null -> driveStatus = "Google Drive authorization unavailable"
                result.hasResolution() -> {
                    val pending = result.pendingIntent
                    if (pending != null) {
                        consentLauncher.launch(
                            IntentSenderRequest.Builder(pending.intentSender).build()
                        )
                    } else driveStatus = "Google Drive consent unavailable"
                }
                else -> {
                    driveStatus = "Drive connected. Choose your music folder."
                    showDriveFolderPicker = true
                }
            }
        }
    }

    fun refreshDrive() {
        if (drive.folderId.isBlank()) {
            connectDrive()
            return
        }
        scope.launch {
            importing = true
            driveStatus = "Scanning Google Drive music folder…"
            runCatching { drive.refresh(library) }.onSuccess { count ->
                songs = library.songs()
                driveStatus = "$count tracks indexed · ${drive.folderName}"
            }.onFailure { driveStatus = "Drive sync error: ${it.message}" }
            importing = false
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

    LaunchedEffect(expanded) {
        // Match the window bars to the mid-point of the player morph instead
        // of flipping from white to black on its very first animation frame.
        delay(if (expanded) 210L else 200L)
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

    if (showDriveFolderPicker) {
        OdifyDriveFolderDialog(
            onDismiss = { showDriveFolderPicker = false },
            onSelect = { id, name ->
                drive.setFolder(id, name)
                showDriveFolderPicker = false
                refreshDrive()
            }
        )
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
            onConnectDrive = { connectDrive() },
            onRefreshDrive = { refreshDrive() },
            driveStatus = driveStatus,
            driveFolder = drive.folderName,
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
