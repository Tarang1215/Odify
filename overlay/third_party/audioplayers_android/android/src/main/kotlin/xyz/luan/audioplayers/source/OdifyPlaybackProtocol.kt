package xyz.luan.audioplayers.source

import java.net.URI

/**
 * Original Odify policy: binds each validated stream to one extraction client
 * and the exact allowlisted HTTP request headers used for its CDN probes.
 * No third-party music-client source is copied or embedded here.
 */
object OdifyPlaybackProtocol {
    val clientFallbackOrder = listOf("visionos", "android_vr", "android", "web_embedded")

    private val allowedHeaderNames = setOf(
        "user-agent", "referer", "origin", "accept", "accept-language"
    )
    private const val MAX_SESSION_AGE_MS = 15L * 60L * 1000L
    private const val EXPIRY_SAFETY_MS = 90_000L

    fun canonicalHeaders(rawHeaders: Map<String, String>): Map<String, String> {
        val normalized = linkedMapOf<String, String>()
        for ((name, value) in rawHeaders) {
            val lower = name.lowercase().trim()
            if (lower in allowedHeaderNames && value.isNotBlank() &&
                '\r' !in value && '\n' !in value) {
                normalized[lower] = value.trim()
            }
        }
        // Do not invent a browser User-Agent for an app-client CDN URL.
        // A missing client-bound UA must fail over to a different extractor.
        require(!normalized["user-agent"].isNullOrBlank()) {
            "Client-bound CDN User-Agent unavailable"
        }
        return normalized.toMap()
    }

    fun orderedClients(
        preferred: String?,
        failedAt: Map<String, Long>,
        elapsedRealtimeMs: Long
    ): List<String> {
        val ordered = if (preferred in clientFallbackOrder) {
            listOf(preferred!!) + clientFallbackOrder.filter { it != preferred }
        } else clientFallbackOrder
        val available = ordered.filter { client ->
            val at = failedAt[client]
            at == null || elapsedRealtimeMs - at >= 60_000L ||
                elapsedRealtimeMs < at
        }
        return available.ifEmpty { ordered }
    }

    fun expiryDeadlineMs(url: String, nowMs: Long): Long {
        val defaultDeadline = nowMs + MAX_SESSION_AGE_MS
        val rawQuery = try { URI(url).rawQuery } catch (_: Exception) { null }
        val seconds = rawQuery?.split('&')
            ?.firstOrNull { it.startsWith("expire=") }
            ?.substringAfter("expire=")?.toLongOrNull()
        if (seconds == null || seconds <= 0L || seconds > Long.MAX_VALUE / 1000L) {
            return defaultDeadline
        }
        return minOf(defaultDeadline, seconds * 1000L - EXPIRY_SAFETY_MS)
    }
}
