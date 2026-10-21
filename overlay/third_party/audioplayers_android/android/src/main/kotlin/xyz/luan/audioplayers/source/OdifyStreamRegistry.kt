package xyz.luan.audioplayers.source

import java.net.URI
import java.security.SecureRandom
import java.util.LinkedHashMap

/**
 * Ephemeral in-process handoff for an extracted audio URL and its yt-dlp
 * request headers. MediaPlayer.setDataSource(url, headers) preserves the
 * original signed source without a loopback HTTP server or extra proxy hop.
 *
 * The registry lives alongside the pinned audioplayers_android source, not in
 * a system provider or an app-global persisted database. Never log URLs.
 */
object OdifyStreamRegistry {
    data class Stream(val url: String, val headers: Map<String, String>, val expiresAtMs: Long)

    private val random = SecureRandom()
    private val sessions = LinkedHashMap<String, Stream>()
    private val allowedHeaders = setOf(
        "user-agent", "referer", "origin", "accept", "accept-language"
    )
    private const val TTL_MS = 15 * 60 * 1000L
    private const val MAX_SESSIONS = 20
    private const val PREFIX = "odify-cdn://audio/"

    @Synchronized
    fun register(url: String, headers: Map<String, String>): String {
        val uri = URI(url)
        val hostname = uri.host?.lowercase() ?: ""
        require(uri.scheme == "https" && uri.userInfo == null &&
            (hostname == "googlevideo.com" || hostname.endsWith(".googlevideo.com"))) {
            "Only HTTPS Googlevideo audio is accepted"
        }
        val safe = headers.mapNotNull { (key, value) ->
            val name = key.lowercase().trim()
            if (name !in allowedHeaders || value.isBlank() ||
                value.contains('\r') || value.contains('\n')) null
            else name to value.trim()
        }.toMap().toMutableMap()
        safe.putIfAbsent("user-agent", "Mozilla/5.0")
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val now = System.currentTimeMillis()
        sessions.entries.removeAll { it.value.expiresAtMs <= now }
        sessions[token] = Stream(url, safe, now + TTL_MS)
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

    fun isOdifySource(source: String): Boolean = source.startsWith(PREFIX)
}
