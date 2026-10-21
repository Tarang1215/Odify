package xyz.luan.audioplayers.source

import android.content.Context
import java.net.URI
import java.security.SecureRandom
import java.util.LinkedHashMap

/**
 * Ephemeral in-process handoff for an extracted audio URL and its yt-dlp
 * request headers and selected MIME format. Media3's OkHttpDataSource
 * handles byte ranges, redirects and demux outside Android MediaPlayer.
 *
 * The registry lives alongside the pinned audioplayers_android source, not in
 * a system provider or an app-global persisted database. Never log URLs.
 */
object OdifyStreamRegistry {
    data class Stream(
        val context: Context,
        val url: String,
        val headers: Map<String, String>,
        val mimeType: String?,
        val client: String,
        val expiresAtMs: Long
    )

    private val random = SecureRandom()
    private val sessions = LinkedHashMap<String, Stream>()
    private const val TTL_MS = 15 * 60 * 1000L
    private const val MAX_SESSIONS = 20
    private const val PREFIX = "odify-cdn://audio/"

    @Synchronized
    fun register(
        context: Context, url: String, headers: Map<String, String>,
        audioContainer: String = "", client: String = "unknown"
    ): String {
        val uri = URI(url)
        val hostname = uri.host?.lowercase() ?: ""
        require(uri.scheme == "https" && uri.userInfo == null &&
            (hostname == "googlevideo.com" || hostname.endsWith(".googlevideo.com"))) {
            "Only HTTPS Googlevideo audio is accepted"
        }
        val safe = OdifyPlaybackProtocol.canonicalHeaders(headers)
        require(client == "unknown" || client in OdifyPlaybackProtocol.clientFallbackOrder) {
            "Unsupported YouTube audio client"
        }
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val now = System.currentTimeMillis()
        sessions.entries.removeAll { it.value.expiresAtMs <= now }
        val mimeType = when (audioContainer.lowercase()) {
            "m4a", "mp4" -> "audio/mp4"
            "webm", "opus" -> "audio/webm"
            else -> null
        }
        val deadlineMs = OdifyPlaybackProtocol.expiryDeadlineMs(url, now)
            .coerceAtMost(now + TTL_MS)
        require(deadlineMs > now) { "Extracted CDN session already expired" }
        sessions[token] = Stream(
            context.applicationContext, url, safe, mimeType, client, deadlineMs
        )
        while (sessions.size > MAX_SESSIONS) {
            sessions.remove(sessions.keys.first())
        }
        return PREFIX + token
    }

    @Synchronized
    fun lookup(source: String): Stream? {
        if (!source.startsWith(PREFIX)) return null
        val token = source.removePrefix(PREFIX)
        if (!token.matches(Regex("[a-f0-9]{48}"))) return null
        val stream = sessions[token] ?: return null
        if (stream.expiresAtMs <= System.currentTimeMillis()) {
            sessions.remove(token)
            return null
        }
        return stream
    }

    @Synchronized
    fun invalidate(source: String) {
        if (!source.startsWith(PREFIX)) return
        val key = source.removePrefix(PREFIX)
        if (key.matches(Regex("[a-f0-9]{48}"))) sessions.remove(key)
    }

    fun isOdifySource(source: String): Boolean = source.startsWith(PREFIX)
}
