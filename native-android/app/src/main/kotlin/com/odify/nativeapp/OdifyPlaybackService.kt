package com.odify.nativeapp

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.io.File
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.*

/**
 * An isolated Android Media3 session. DocumentsUI content:// entries are opened
 * through ContentResolver/DefaultDataSource; no automatic file copying.
 * Cached bytes are bounded by a 192 MiB LRU cache. Provider-specific file
 * handling MAY still stage a full file outside Odify's control.
 */
class OdifyPlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var cache: SimpleCache? = null
    private var cacheDb: StandaloneDatabaseProvider? = null
    private val listeningScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listeningJob: Job? = null
    private var autoplayJob:Job?=null
    private var plannedEntry=""

    override fun onCreate() {
        super.onCreate()
        // Authentication belongs to the upstream, independently of disk cache.
        // A full/corrupt cache must never turn Drive requests into anonymous HTTP.
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Odify-Native/12 Android")
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(false)
        val sources = DefaultDataSource.Factory(this, httpFactory)
        val authenticatedUpstream = DataSource.Factory {
            OdifyDriveDataSource(sources.createDataSource(),
                token = { DriveAuth.tokenBlocking(applicationContext) },
                invalidate = { DriveAuth.invalidateBlocking(applicationContext, it) })
        }
        val player = try {
            val provider = StandaloneDatabaseProvider(this)
            cacheDb = provider
            val diskCache = SimpleCache(
                File(cacheDir, "odify_media3_stream_cache"),
                LeastRecentlyUsedCacheEvictor(192L * 1024L * 1024L),
                provider
            )
            cache = diskCache
            val cacheDataSource = CacheDataSource.Factory()
                .setCache(diskCache)
                .setUpstreamDataSourceFactory(authenticatedUpstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            ExoPlayer.Builder(this)
                .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSource))
                .build()
        } catch (e: Exception) {
            // Cache failure must not prevent otherwise valid offline playback.
            runCatching { cache?.release() }
            cache = null
            ExoPlayer.Builder(this)
                .setMediaSourceFactory(DefaultMediaSourceFactory(authenticatedUpstream))
                .build()
        }
        player.apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(), true
            )
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
        }
        val open=android.app.PendingIntent.getActivity(this,8001,
            Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, player).setSessionActivity(open).build()
        OdifyWidgetPlayback.bind(this,"native",available={player.currentMediaItem!=null}) {action ->
            when(action) {
                "toggle" -> if(player.isPlaying)player.pause() else if(player.currentMediaItem!=null) {player.prepare();player.play()}
                "previous" -> player.seekToPreviousMediaItem()
                "next" -> player.seekToNextMediaItem()
            }
        }
        val listeningStore = OdifyListeningStore(applicationContext)
        player.addListener(object : Player.Listener {
            override fun onEvents(player:Player,events:Player.Events) {
                if(OdifyYoutubeRuntime.session.value.video!=null)return
                val item=player.currentMediaItem ?: run {OdifyWidgetPlayback.refresh(applicationContext);return}
                if(item.mediaMetadata.extras?.getBoolean("odify.youtubeStream")==true)return
                if(player.playerError==null && OdifyAutoplayPolicy.shouldExtend(player.playWhenReady,
                    player.repeatMode!=Player.REPEAT_MODE_OFF,player.hasNextMediaItem()) && autoplayJob?.isActive!=true) {
                    val key=item.mediaMetadata.extras?.getString("odify.queueEntry") ?: item.mediaId
                    if(key!=plannedEntry) {
                        plannedEntry=key
                        val count=player.mediaItemCount
                        val queue=(0 until count).map {queueSong(player.getMediaItemAt(it))}
                        autoplayJob=listeningScope.launch {
                            try {
                                val next=OdifyAutoplay.following(applicationContext,queueSong(item),queue)
                                val latest=player.currentMediaItem
                                val latestKey=latest?.mediaMetadata?.extras?.getString("odify.queueEntry") ?: latest?.mediaId
                                if(latestKey==key && player.mediaItemCount==count && !player.hasNextMediaItem() &&
                                    player.repeatMode==Player.REPEAT_MODE_OFF && next.isNotEmpty()) {
                                    player.addMediaItems(next.map {it.mediaItem()})
                                    if(player.playbackState==Player.STATE_ENDED && player.playWhenReady) {
                                        player.seekToNextMediaItem();player.prepare();player.play()
                                    }
                                }
                            } catch(cancel:CancellationException){throw cancel}
                            catch(error:Exception){android.util.Log.w("OdifyAutoplay","Following queue unavailable",error)}
                        }
                    }
                }
                val metadata=item.mediaMetadata
                OdifyWidgetPlayback.publish(applicationContext,OdifyWidgetTrack("native",item.mediaId,
                    metadata.title?.toString() ?: "Odify",metadata.artist?.toString().orEmpty(),
                    metadata.artworkUri?.path.orEmpty(),player.isPlaying,player.hasPreviousMediaItem(),player.hasNextMediaItem()))
            }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                listeningJob?.cancel()
                if (item == null) return
                if(item.mediaMetadata.extras?.getBoolean("odify.youtubeStream")==true) {
                    player.pause()
                    val following=(player.currentMediaItemIndex+1 until player.mediaItemCount).map {queueSong(player.getMediaItemAt(it))}
                    val previous=(0 until player.currentMediaItemIndex).map {queueSong(player.getMediaItemAt(it))}
                    OdifyYoutubeRuntime.play(applicationContext,OdifyStreamingLibrary(applicationContext).video(queueSong(item)),following,
                        presentation=queueSong(item),shuffle=player.shuffleModeEnabled,repeat=player.repeatMode==Player.REPEAT_MODE_ONE,previous=previous)
                    player.clearMediaItems()
                    return
                }
                val entry = java.util.UUID.randomUUID().toString()
                // Count actual playing time; seeking to 30 seconds cannot fabricate a listen.
                listeningJob = listeningScope.launch {
                    var listened = 0L
                    while (isActive && listened < 30_000L) {
                        val started = android.os.SystemClock.elapsedRealtime()
                        val wasPlaying = player.isPlaying
                        delay(1000L)
                        if (wasPlaying && player.isPlaying) listened +=
                            (android.os.SystemClock.elapsedRealtime() - started).coerceAtMost(1500L)
                    }
                    listeningStore.record(entry, item.mediaId)
                }
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // isPlaying is false during buffering and transient audio-focus loss.
        if (!isPlaybackOngoing()) stopSelf()
    }

    override fun onDestroy() {
        OdifyWidgetPlayback.unbind(this,"native")
        listeningScope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        cache?.release()
        cache = null
        cacheDb = null
        super.onDestroy()
    }
}
