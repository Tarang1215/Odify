package com.odify.nativeapp

import android.content.ComponentName
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import android.app.Activity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    val nextMediaId: String? = null,
    val queueIds: List<String> = emptyList(),
    val currentQueueIndex: Int = -1
)

@Composable
private fun NativeOdify() {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val library = remember { OdifyLibrary(context.applicationContext) }
    val phoneFolder = remember { OdifyPhoneFolder(context.applicationContext) }
    var phoneFolderStatus by remember { mutableStateOf(phoneFolder.folderLabel) }
    val driveCatalog = remember { DriveCatalog(context.applicationContext) }
    val masterDb = remember { OdifyMasterDatabase(context.applicationContext) }
    val artworkFallback = remember { OdifyArtworkFallback(context.applicationContext) }
    val uploader = remember { OdifyDriveUploader(context.applicationContext) }
    val drivePrefs = remember {
        context.getSharedPreferences("odify_drive_consent_state", 0)
    }
    var driveConnected by remember { mutableStateOf(false) }
    var driveBusy by remember { mutableStateOf(false) }
    var driveStatus by remember { mutableStateOf("Google Drive 계정을 연결하세요.") }
    var driveFolder by remember { mutableStateOf(driveCatalog.folderId) }
    var songs by remember { mutableStateOf(library.songs()) }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var playback by remember { mutableStateOf(OdifyPlaybackSnapshot()) }
    var expanded by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var uploadingToDrive by remember { mutableStateOf(false) }
    var uploadStatus by remember { mutableStateOf("선택한 음악만 Drive에 저장합니다.") }
    var uploadSelection by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    var importAsOffline by remember { mutableStateOf(false) }
    var pendingCoverTrack by remember { mutableStateOf<OdifySong?>(null) }
    var startup by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    fun refreshCatalog(showProgress: Boolean = true) {
        if (driveBusy || uploadingToDrive || !DriveAuth.connected) return
        scope.launch {
            driveBusy = true
            if (showProgress) driveStatus = "Drive 음악 목록을 업데이트하고 있습니다."
            try {
                val (n, merged) = withContext(Dispatchers.IO) {
                    val count = driveCatalog.sync()
                    // Avoid briefly showing ODF###### placeholders on a new
                    // library. The small free-tier collection is hydrated
                    // before the first visible catalog refresh.
                    driveCatalog.prefetchMetadata(limit = 40)
                    count to (library.songs() + driveCatalog.tracks() + phoneFolder.tracks())
                }
                songs = merged
                driveConnected = true
                driveStatus = "음악 ${n}곡 동기화 완료"
                // MASTER is a Google Sheet; after ordinary Drive indexing,
                // use its previously verified YouTube Art Track links only
                // when the audio itself has no cover image.
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val master = runCatching { masterDb.sync() }
                                .getOrElse { masterDb.loadCached() }
                            if (master.isNotEmpty()) {
                                val renamed = driveCatalog.applyMasterCorrections(master)
                                val syncedSongs = driveCatalog.tracks()
                                val covered = artworkFallback.warm(driveCatalog,syncedSongs,master,14)
                                renamed + covered
                            } else 0
                        }
                    }.onSuccess { refreshed ->
                        if (refreshed > 0) {
                            songs = withContext(Dispatchers.IO) {
                                library.songs() + driveCatalog.tracks() + phoneFolder.tracks()
                            }
                        }
                    }
                }
                // A few covers become visible in Search/Home before first play.
                // This runs after sync, off the UI thread, with daily retry caps.
                scope.launch {
                    // Hydrate incrementally: the first four names/covers become
                    // visible quickly instead of waiting for every remote file.
                    for (batch in 0 until 8) {
                        val result = runCatching {
                            withContext(Dispatchers.IO) {
                                val count = driveCatalog.prefetchMetadata(limit = 4)
                                count to (library.songs() + driveCatalog.tracks() + phoneFolder.tracks())
                            }
                        }.getOrNull() ?: break
                        val (count, refreshed) = result
                        if (count > 0) songs = refreshed
                        if (count < 4) break
                        kotlinx.coroutines.yield()
                    }
                }
            } catch (e: Exception) {
                driveStatus = "Drive 동기화 실패: ${e.message ?: "연결 확인 필요"}"
            } finally {
                driveBusy = false
            }
        }
    }

    val driveAuthorization = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val token = runCatching { DriveAuth.resolve(activity, result.data) }.getOrNull()
            if (!token.isNullOrBlank()) {
                driveConnected = true
                drivePrefs.edit().putBoolean("enabled", true).apply()
                refreshCatalog()
            } else driveStatus = "Google 인증이 완료되지 않았습니다."
        } else driveStatus = "Google Drive 권한 요청이 취소되었습니다."
    }

    fun connectDrive() {
        driveStatus = "Google Drive 권한을 확인 중입니다."
        DriveAuth.authorize(activity,
            onToken = {
                driveConnected = true
                drivePrefs.edit().putBoolean("enabled", true).apply()
                refreshCatalog()
            },
            onResolution = { pending ->
                driveAuthorization.launch(
                    IntentSenderRequest.Builder(pending.intentSender).build()
                )
            },
            onError = { driveStatus = "Google 로그인 실패: $it" }
        )
    }

    fun uploadFiles(selected: List<android.net.Uri>) {
        if (uploadingToDrive || driveBusy || selected.isEmpty()) return
        scope.launch {
            uploadingToDrive = true
            var added = 0
            var skipped = 0
            try {
                // Refresh before checking duplicates: another phone or PC
                // may have already uploaded these exact files.
                withContext(Dispatchers.IO) { driveCatalog.sync() }
                selected.forEachIndexed { index, uri ->
                    uploadStatus = "Drive 저장 ${index+1}/${selected.size} 준비"
                    try {
                        val result = uploader.upload(uri, driveCatalog) {
                            uploadStatus = it
                        }
                        when (result) {
                            is OdifyUploadResult.Added -> added++
                            is OdifyUploadResult.Skipped -> {
                                skipped++
                                uploadStatus = "${result.name}: ${result.reason}"
                            }
                        }
                        if (result is OdifyUploadResult.Added) {
                            withContext(Dispatchers.IO) { driveCatalog.sync() }
                        }
                    } catch (e: Exception) {
                        uploadStatus = "업로드 오류: ${e.message ?: "연결 상태를 확인하세요."}"
                        throw e
                    }
                }
                uploadStatus = "Drive 저장 완료: 신규 ${added}곡 · 중복 제외 ${skipped}곡"
            } catch (e: Exception) {
                if (!uploadStatus.startsWith("업로드 오류"))
                    uploadStatus = "Drive 저장 실패: ${e.message ?: "다시 시도하세요."}"
            } finally {
                songs = withContext(Dispatchers.IO) {
                    library.songs() + driveCatalog.tracks() + phoneFolder.tracks()
                }
                uploadingToDrive = false
                uploadSelection = emptyList()
            }
        }
    }

    val uploadAuthorization = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK &&
            runCatching { DriveAuth.resolveUpload(activity,result.data) }.getOrDefault(false))
            uploadFiles(uploadSelection)
        else uploadStatus = "Drive 쓰기 권한 승인이 취소되었습니다."
    }
    val driveUploadPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { files ->
        if (files.isNotEmpty()) {
            uploadSelection = files
            uploadStatus = "업로드 전 Google Drive 저장 권한 확인"
            DriveAuth.authorizeUpload(activity,
                onToken = { uploadFiles(files) },
                onResolution = { pending ->
                    uploadAuthorization.launch(
                        IntentSenderRequest.Builder(pending.intentSender).build()
                    )
                },
                onError = { uploadStatus = "업로드 인증 실패: $it" }
            )
        }
    }

    LaunchedEffect(Unit) {
        songs = withContext(Dispatchers.IO) {
            library.songs() + driveCatalog.tracks() + phoneFolder.tracks()
        }
        if (drivePrefs.getBoolean("enabled", false)) {
            // Silent renewal only. Never launch OAuth consent without interaction.
            DriveAuth.authorize(activity,
                onToken = {
                    driveConnected = true
                    refreshCatalog(showProgress = false)
                },
                onResolution = { driveStatus = "Drive 연결 버튼을 눌러 로그인하세요." },
                onError = { driveStatus = "Drive 연결 버튼을 눌러 다시 로그인하세요." }
            )
        }
    }
    LaunchedEffect(driveConnected) {
        while (driveConnected && isActive) {
            delay(15L * 60L * 1000L)
            refreshCatalog(showProgress = false)
        }
    }
    LaunchedEffect(playback.mediaId) {
        val track = songs.firstOrNull { it.id == playback.mediaId }
        if (track != null && track.id.startsWith("gdrive:") && driveConnected) {
            // Fast path for a tapped song; metadata is saved in the SQLite
            // catalog, while full audio remains in the Drive account.
            val changed = runCatching {
                driveCatalog.ensureMetadata(track)
            }.getOrDefault(false)
            if (changed) {
                songs = withContext(Dispatchers.IO) {
                    library.songs() + driveCatalog.tracks() + phoneFolder.tracks()
                }
            }
        }
    }

    // Prime waveform caches while the user browses the library, rather than
    // starting a multi-minute PCM decode when Now Playing is first opened.
    // Analyze one track at a time off the UI thread; prioritize the current
    // track and cap work to protect large music libraries/battery.
    LaunchedEffect(songs, playback.mediaId) {
        val active = songs.firstOrNull { it.id == playback.mediaId }
        // Never prefetch the entire remote collection. Only analyze the one
        // currently requested Drive track, and keep that expensive read off UI.
        // Streaming range playback must not cause a second unauthenticated
        // full-track decode. Local files get true pre-analyzed waveforms.
        val candidates = (listOfNotNull(active?.takeIf { it.sourceUri == null }) +
            songs.filterNot { it.sourceUri != null || it.id == active?.id }).take(8)
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
        var lastError: String? = null
        while (isActive) {
            val failure = player.playerError
            val failureCode = failure?.errorCodeName
            if (failureCode != null && lastError != failureCode) {
                lastError = failureCode
                val isDrive = player.currentMediaItem?.mediaId
                    ?.startsWith("gdrive:") == true
                val notice = if (isDrive)
                    "Drive 재생 실패 (${failureCode}). 네트워크·Google 권한을 확인하세요."
                else "재생 오류: ${failureCode}"
                Toast.makeText(context, notice, Toast.LENGTH_LONG).show()
            } else if (failureCode == null) {
                lastError = null
            }
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
                    player.getMediaItemAt(nextIndex).mediaId else null,
                currentQueueIndex = player.currentMediaItemIndex,
                queueIds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
            )
            delay(350L)
        }
    }

    val artworkPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { imageUri ->
        val track = pendingCoverTrack
        pendingCoverTrack = null
        if (track != null && imageUri != null) {
            scope.launch {
                runCatching {
                    driveCatalog.setManualArtwork(track, imageUri)
                }.onSuccess {
                    songs = withContext(Dispatchers.IO) {
                        library.songs() + driveCatalog.tracks() + phoneFolder.tracks()
                    }
                    Toast.makeText(context, "앨범커버가 저장됐습니다.", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(context, it.message ?: "이미지를 열 수 없습니다.",
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val phoneFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { tree ->
        if (tree != null) {
            scope.launch {
                importing = true
                runCatching {
                    phoneFolder.select(tree)
                    val count = phoneFolder.scan()
                    phoneFolderStatus = "휴대폰 폴더 ${phoneFolder.folderLabel} · ${count}곡 연결"
                }.onFailure { phoneFolderStatus = it.message ?: "폴더를 열 수 없습니다." }
                songs = withContext(Dispatchers.IO) {
                    library.songs() + driveCatalog.tracks() + phoneFolder.tracks()
                }
                importing = false
            }
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
                songs = withContext(Dispatchers.IO) {
                    library.songs() + driveCatalog.tracks() + phoneFolder.tracks()
                }
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

    if (startup) {
        OdifySplash()
    } else {
        OdifyRoot(
            songs = songs,
            playback = playback,
            driveConnected = driveConnected,
            driveBusy = driveBusy,
            driveStatus = driveStatus,
            driveFolder = driveFolder,
            onDriveConnect = { connectDrive() },
            onDriveSync = { refreshCatalog() },
            onDriveFolderChange = { newId ->
                runCatching {
                    driveCatalog.setFolder(newId)
                    driveFolder = newId
                    songs = library.songs() + phoneFolder.tracks()
                    if (driveConnected) refreshCatalog()
                }.onFailure { driveStatus = it.message ?: "폴더 ID를 확인하세요." }
            },
            uploadingToDrive = uploadingToDrive,
            uploadStatus = uploadStatus,
            onUploadToDrive = {
                if (!uploadingToDrive && !driveBusy) driveUploadPicker.launch(arrayOf("audio/*"))
            },
            phoneFolderStatus = phoneFolderStatus,
            onChoosePhoneFolder = { phoneFolderPicker.launch(null) },
            onRefreshPhoneFolder = {
                scope.launch {
                    importing = true
                    runCatching { phoneFolder.scan() }
                        .onSuccess { phoneFolderStatus = "휴대폰 폴더 새로고침 · ${it}곡" }
                        .onFailure { phoneFolderStatus = it.message ?: "로컬 폴더 오류" }
                    songs = withContext(Dispatchers.IO) {
                        library.songs() + driveCatalog.tracks() + phoneFolder.tracks()
                    }
                    importing = false
                }
            },
            onMasterUpdated = { master ->
                scope.launch {
                    val count = withContext(Dispatchers.IO) {
                        val renamed = driveCatalog.applyMasterCorrections(master)
                        val art = artworkFallback.warm(
                            driveCatalog, driveCatalog.tracks(), master, 18
                        )
                        renamed + art
                    }
                    if (count > 0) songs = withContext(Dispatchers.IO) {
                        library.songs() + driveCatalog.tracks() + phoneFolder.tracks()
                    }
                }
            },
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
            onMoveQueue = { from, to ->
                controller?.let { player ->
                    if (from in 0 until player.mediaItemCount && to in 0 until player.mediaItemCount && from != to)
                        player.moveMediaItem(from,to)
                }
            },
            onSelectQueue = { index ->
                controller?.let { player ->
                    if (index in 0 until player.mediaItemCount) {
                        player.seekToDefaultPosition(index)
                        player.play()
                    }
                }
            },
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
                    runCatching {
                        if (song.id.startsWith("gdrive:")) {
                            withContext(Dispatchers.IO) {
                                driveCatalog.updateMetadata(song, title, artist, album)
                            }
                        } else library.updateMetadata(song, title, artist, album)
                    }.onSuccess {
                        songs = withContext(Dispatchers.IO) {
                            library.songs() + driveCatalog.tracks() + phoneFolder.tracks()
                        }
                    }
                        .onFailure {
                            Toast.makeText(context, it.message ?: "정보 저장 실패",
                                Toast.LENGTH_LONG).show()
                        }
                }
            },
            onChooseArtwork = { track ->
                pendingCoverTrack = track
                artworkPicker.launch(arrayOf("image/*"))
            },
            onRemove = { track ->
                scope.launch {
                    if (playback.mediaId == track.id) controller?.stop()
                    if (track.id.startsWith("gdrive:")) {
                        withContext(Dispatchers.IO) { driveCatalog.hideTrack(track) }
                    } else library.delete(track)
                    songs = withContext(Dispatchers.IO) {
                        library.songs() + driveCatalog.tracks() + phoneFolder.tracks()
                    }
                }
            }
        )
    }
}
