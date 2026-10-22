package com.odify.nativeapp

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.ResolvingDataSource
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

    override fun onCreate() {
        super.onCreate()
        val player = try {
            val provider = StandaloneDatabaseProvider(this)
            cacheDb = provider
            val diskCache = SimpleCache(
                File(cacheDir, "odify_media3_stream_cache"),
                LeastRecentlyUsedCacheEvictor(192L * 1024L * 1024L),
                provider
            )
            cache = diskCache
            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent("Odify-Native/9 Android")
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(20_000)
                .setAllowCrossProtocolRedirects(false)
            val authenticatedUpstream = ResolvingDataSource.Factory(
                DefaultDataSource.Factory(this, httpFactory),
                ResolvingDataSource.Resolver { spec ->
                    val uri = spec.uri
                    if (uri.scheme == "https" &&
                        uri.host == "www.googleapis.com" &&
                        uri.path?.startsWith("/drive/v3/files/") == true &&
                        uri.getQueryParameter("alt") == "media") {
                        val token = DriveAuth.tokenBlocking(applicationContext)
                        spec.buildUpon()
                            .setHttpRequestHeaders(
                                spec.httpRequestHeaders + mapOf(
                                    "Authorization" to "Bearer $token"
                                )
                            ).build()
                    } else spec
                }
            )
            val cacheDataSource = CacheDataSource.Factory()
                .setCache(diskCache)
                .setUpstreamDataSourceFactory(authenticatedUpstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            ExoPlayer.Builder(this)
                .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSource))
                .build()
        } catch (e: Exception) {
            // Cache failure must not prevent otherwise valid offline playback.
            cache?.release()
            cache = null
            ExoPlayer.Builder(this).build()
        }
        player.apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(), true
            )
            setHandleAudioBecomingNoisy(true)
        }
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (session?.player?.isPlaying != true) stopSelf()
    }

    override fun onDestroy() {
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