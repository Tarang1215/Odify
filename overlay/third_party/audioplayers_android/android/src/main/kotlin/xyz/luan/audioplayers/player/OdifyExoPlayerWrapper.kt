package xyz.luan.audioplayers.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import xyz.luan.audioplayers.AudioContextAndroid
import xyz.luan.audioplayers.source.OdifyStreamRegistry
import xyz.luan.audioplayers.source.Source
import xyz.luan.audioplayers.source.UrlSource

/**
 * Only Odify online streams use Media3. Standard URLs, local MP3/FLAC,
 * SAF and bit-perfect USB continue through their original player path.
 * The existing audioplayers Dart API and audio_service queue are preserved.
 */
@OptIn(UnstableApi::class)
class OdifyExoPlayerWrapper(private val wrappedPlayer: WrappedPlayer) : PlayerWrapper {
    companion object {
        private const val RELAY_PREFIX = "http://127.0.0.1:"
        private val relayPattern = Regex("^/stream/[A-Za-z0-9_-]{20,100}$")

        fun accepts(source: Source?): Boolean {
            val url = (source as? UrlSource)?.url ?: return false
            if (OdifyStreamRegistry.isOdifySource(url)) return true
            if (!url.startsWith(RELAY_PREFIX)) return false
            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
            return uri.host == "127.0.0.1" && uri.port in 1..65535 &&
                uri.query.isNullOrEmpty() && uri.fragment.isNullOrEmpty() &&
                relayPattern.matches(uri.path.orEmpty())
        }
    }

    private val appContext = wrappedPlayer.applicationContext
    // Match the actual successful yt-dlp CDN probe: Android HttpURLConnection,
    // identity encoding, finite 0-1023 Range, then sequential 256 KiB ranges.
    // Media3 still demuxes/buffers/plays the continuous exposed byte stream.
    private val httpFactory = DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(6000)
        .setReadTimeoutMs(12000)
        .setAllowCrossProtocolRedirects(false)
    private val media3DataSource = DataSource.Factory {
        OdifyBoundedRangeDataSource(httpFactory)
    }
    private val player = ExoPlayer.Builder(appContext)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(appContext).setDataSourceFactory(media3DataSource)
        ).build()

    private var released = false
    private var preparedNotified = false
    private var sourceRoute = "CDN"
    private var sourceClient = "unknown"

    init {
        player.setWakeMode(C.WAKE_MODE_NETWORK)
        updateContext(wrappedPlayer.context)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (released) return
                when (state) {
                    Player.STATE_READY -> if (!preparedNotified) {
                        preparedNotified = true
                        wrappedPlayer.onPrepared()
                    }
                    Player.STATE_ENDED -> wrappedPlayer.onCompletion()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (released) return
                val safeCause = buildString {
                    append(sourceRoute)
                    append('_')
                    append(generateSequence(error.cause) { it.cause }
                        .take(4).joinToString(",") { cause ->
                            if (cause is HttpDataSource.InvalidResponseCodeException) {
                                val upstream = cause.headerFields.entries.firstOrNull {
                                    it.key.equals("x-odify-upstream-status", ignoreCase = true)
                                }?.value?.firstOrNull()?.takeIf {
                                    it.matches(Regex("[0-9]{3}"))
                                }
                                val proxyFailure = cause.headerFields.entries.firstOrNull {
                                    it.key.equals("x-odify-proxy-failure", ignoreCase = true)
                                }?.value?.firstOrNull()?.takeIf {
                                    it in setOf("timeout", "network")
                                }
                                "HTTP_" + cause.responseCode +
                                    (if (upstream != null) "_UPSTREAM_" + upstream else "") +
                                    (if (proxyFailure != null) "_" + proxyFailure.uppercase() else "")
                            } else cause.javaClass.simpleName
                        })
                }
                // Never log exception messages, headers, video IDs or signed URLs.
                android.util.Log.w(
                    "OdifyMedia3", "code=" + error.errorCodeName + " cause=" + safeCause
                )
                wrappedPlayer.onExoError(
                    error.errorCodeName,
                    safeCause + (if (sourceRoute == "CDN") "_CLIENT_" + sourceClient else "")
                )
            }
        })
    }

    override fun getDuration(): Int? =
        player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
            ?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()

    override fun getCurrentPosition(): Int =
        player.currentPosition.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    override fun isLiveStream(): Boolean = getDuration() == null

    override fun setVolume(leftVolume: Float, rightVolume: Float) {
        // Odify music ordinarily uses equal L/R gain.
        player.volume = ((leftVolume + rightVolume) / 2f).coerceIn(0f, 1f)
    }

    override fun setRate(rate: Float) {
        require(rate > 0f) { "Invalid playback speed" }
        player.playbackParameters = PlaybackParameters(rate)
    }

    override fun setLooping(looping: Boolean) {
        player.repeatMode = if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    override fun updateContext(context: AudioContextAndroid) {
        // WrappedPlayer's existing FocusManager remains the sole focus owner.
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(context.usageType)
                .setContentType(context.contentType)
                .build(), false
        )
    }

    override fun setSource(source: Source) {
        require(accepts(source)) { "Only Odify online sources use Media3" }
        reset()
        val handle = (source as UrlSource).url
        sourceRoute = if (OdifyStreamRegistry.isOdifySource(handle)) "CDN" else "RELAY"
        val session = if (OdifyStreamRegistry.isOdifySource(handle)) {
            OdifyStreamRegistry.lookup(handle) ?: error("Expired Odify CDN session")
        } else null
        sourceClient = session?.client ?: "unknown"
        httpFactory.setDefaultRequestProperties(
            (session?.headers ?: emptyMap()) + ("accept-encoding" to "identity")
        )
        val item = MediaItem.Builder()
            .setUri(session?.url ?: handle)
            .apply { session?.mimeType?.let { setMimeType(it) } }
            .build()
        player.setMediaItem(item)
    }

    override fun prepare() { player.prepare() }
    override fun start() { player.play() }
    override fun pause() { player.pause() }
    override fun stop() { player.stop() }

    override fun seekTo(position: Int) {
        player.seekTo(position.toLong())
        // audioplayers' Dart layer expects an explicit seek-complete callback.
        wrappedPlayer.onSeekComplete()
    }

    override fun reset() {
        preparedNotified = false
        player.stop()
        player.clearMediaItems()
    }

    override fun release() {
        if (released) return
        released = true
        player.release()
    }
}
